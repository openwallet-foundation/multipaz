package org.multipaz.presentment

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.multipaz.cbor.Bstr
import org.multipaz.cbor.Cbor
import org.multipaz.cbor.Tagged
import org.multipaz.cbor.buildCborArray
import org.multipaz.crypto.Algorithm
import org.multipaz.crypto.Crypto
import org.multipaz.crypto.EcCurve
import org.multipaz.documenttype.knowntypes.DrivingLicense
import org.multipaz.mdoc.connectionmethod.MdocConnectionMethodNfcV2
import org.multipaz.mdoc.nfc.MdocHandoverType
import org.multipaz.mdoc.nfc.MdocNfcV2EngagementHelper
import org.multipaz.mdoc.nfc.MdocReaderNfcHandoverOptions
import org.multipaz.mdoc.nfc.mdocReaderNfcHandover
import org.multipaz.mdoc.request.DeviceRequest
import org.multipaz.mdoc.request.DeviceRequestGenerator
import org.multipaz.mdoc.response.DeviceResponse
import org.multipaz.mdoc.role.MdocRole
import org.multipaz.mdoc.sessionencryption.SessionEncryption
import org.multipaz.mdoc.transport.NfcHybridTransportMdoc
import org.multipaz.mdoc.transport.NfcHybridTransportMdocReader
import org.multipaz.nfc.CommandApdu
import org.multipaz.nfc.NfcIsoTag
import org.multipaz.nfc.ResponseApdu
import org.multipaz.prompt.promptModelSilentConsent
import org.multipaz.util.Constants
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.test.fail

class Iso18013PresentmentTest {

    private class LoopbackIsoTag(var engagementHelper: MdocNfcV2EngagementHelper): NfcIsoTag() {
        override val maxTransceiveLength: Int get() = 65536
        override suspend fun close() {}
        override suspend fun updateDialogMessage(message: String) {}
        override suspend fun transceive(command: CommandApdu): ResponseApdu {
            return engagementHelper.processApdu(command)
        }
    }

    private suspend fun createTestPresentmentSource(): SimplePresentmentSource {
        val harness = DocumentStoreTestHarness()
        harness.initialize()
        harness.provisionStandardDocuments()
        return SimplePresentmentSource(
            documentStore = harness.documentStore,
            documentTypeRepository = harness.documentTypeRepository,
            showConsentPromptFn = ::promptModelSilentConsent,
            preferSignatureToKeyAgreement = true,
            domainsMdocSignature = listOf("mdoc"),
            domainsKeyBoundSdJwt = listOf("sdjwt"),
        )
    }

    private suspend fun buildTestDeviceRequest(encodedSessionTranscript: ByteArray): DeviceRequest {
        val generator = DeviceRequestGenerator(encodedSessionTranscript)
        generator.addDocumentRequest(
            docType = DrivingLicense.MDL_DOCTYPE,
            itemsToRequest = mapOf(DrivingLicense.MDL_NAMESPACE to mapOf("given_name" to false)),
            requestInfo = null,
            readerKey = null,
            signatureAlgorithm = Algorithm.ESP256,
            readerKeyCertificateChain = null
        )
        return DeviceRequest.fromDataItem(Cbor.decode(generator.generate()))
    }

    @Test
    fun testSingleTapPresentment() = runTest {
        val presentmentSource = createTestPresentmentSource()
        val eDeviceKey = Crypto.createEcPrivateKey(EcCurve.P256)
        val eReaderKey = Crypto.createEcPrivateKey(EcCurve.P256)

        lateinit var activeEngagementParamsFlow: MutableStateFlow<EngagementParams>
        lateinit var mdocTransport: NfcHybridTransportMdoc
        var currentEngagementHelper: MdocNfcV2EngagementHelper? = null

        val engagementHelper = MdocNfcV2EngagementHelper(
            eDeviceKey = eDeviceKey.publicKey,
            onHandoverComplete = { connectionMethods, encodedDeviceEngagement, handover ->
                val params = EngagementParams(
                    eDeviceKey = eDeviceKey,
                    deviceEngagement = Cbor.decode(encodedDeviceEngagement.toByteArray()),
                    handover = handover
                )
                activeEngagementParamsFlow = MutableStateFlow(params)
            },
            onMessageReceived = { message ->
                mdocTransport.onMessageReceivedViaNfc(message)
            },
            onError = { error ->
                fail("onError: $error")
            },
            negotiatedHandoverPicker = { connectionMethods -> connectionMethods.first() }
        )
        currentEngagementHelper = engagementHelper

        val tag = LoopbackIsoTag(engagementHelper)
        val handoverResult = mdocReaderNfcHandover(
            tag = tag,
            negotiatedHandoverConnectionMethods = emptyList(),
            options = MdocReaderNfcHandoverOptions(useNfcV2 = true)
        )
        assertNotNull(handoverResult)
        assertEquals(MdocHandoverType.V2_HANDOVER, handoverResult.type)

        val mdocReaderTransport = NfcHybridTransportMdocReader(
            nfcTag = tag,
            negotiatedTransport = null
        )
        mdocReaderTransport.open(eReaderKey.publicKey)

        mdocTransport = NfcHybridTransportMdoc(
            sendMessageViaNfc = { message ->
                currentEngagementHelper?.sendMessage(message)
                true
            }
        )
        mdocTransport.open(eDeviceKey.publicKey)

        val presentmentJob = launch {
            Iso18013Presentment(
                transport = mdocTransport,
                engagementParams = activeEngagementParamsFlow,
                source = presentmentSource,
                keyAgreementPossible = listOf(EcCurve.P256),
                insertSequenceNumbers = true,
                timeout = null,
                timeoutSubsequentRequests = null
            )
        }

        val params = activeEngagementParamsFlow.value
        val encodedCoseKey = Cbor.encode(eReaderKey.publicKey.toCoseKey().toDataItem())
        val sessionTranscript = buildCborArray {
            add(Tagged(Tagged.ENCODED_CBOR, Bstr(Cbor.encode(params.deviceEngagement))))
            add(Tagged(Tagged.ENCODED_CBOR, Bstr(encodedCoseKey)))
            add(params.handover)
        }
        val encodedSessionTranscript = Cbor.encode(sessionTranscript)
        val readerEncryption = SessionEncryption(
            role = MdocRole.MDOC_READER,
            eSelfKey = eReaderKey,
            remotePublicKey = eDeviceKey.publicKey,
            encodedSessionTranscript = encodedSessionTranscript,
            insertSequenceNumbers = true
        )

        val deviceRequest = buildTestDeviceRequest(encodedSessionTranscript)

        val encryptedRequest = readerEncryption.encryptMessage(
            messagePlaintext = Cbor.encode(deviceRequest.toDataItem()),
            statusCode = null
        )
        mdocReaderTransport.sendMessage(encryptedRequest)

        val encryptedResponse = mdocReaderTransport.waitForMessage()
        val (responseBytes, status) = readerEncryption.decryptMessage(encryptedResponse)
        assertNotNull(responseBytes)

        val deviceResponse = DeviceResponse.fromDataItem(Cbor.decode(responseBytes))
        deviceResponse.verify(sessionTranscript = sessionTranscript)
        assertEquals(1, deviceResponse.documents.size)
        assertEquals(DrivingLicense.MDL_DOCTYPE, deviceResponse.documents.first().docType)

        // Reader terminates session
        val terminationMessage = SessionEncryption.encodeStatus(
            statusCode = Constants.SESSION_DATA_STATUS_SESSION_TERMINATION,
            sequenceNumber = readerEncryption.nextSequenceNumber
        )
        mdocReaderTransport.sendMessage(terminationMessage)

        presentmentJob.join()
    }

    @Test
    fun testTwoTapPresentmentSuccess() = runTest {
        val presentmentSource = createTestPresentmentSource()
        val eDeviceKey = Crypto.createEcPrivateKey(EcCurve.P256)
        val eReaderKey1 = Crypto.createEcPrivateKey(EcCurve.P256)
        val eReaderKey2 = Crypto.createEcPrivateKey(EcCurve.P256)

        lateinit var activeEngagementParamsFlow: MutableStateFlow<EngagementParams>
        lateinit var mdocTransport: NfcHybridTransportMdoc
        var currentEngagementHelper: MdocNfcV2EngagementHelper? = null

        val engagementHelper1 = MdocNfcV2EngagementHelper(
            eDeviceKey = eDeviceKey.publicKey,
            onHandoverComplete = { _, encodedDeviceEngagement, handover ->
                val params = EngagementParams(
                    eDeviceKey = eDeviceKey,
                    deviceEngagement = Cbor.decode(encodedDeviceEngagement.toByteArray()),
                    handover = handover
                )
                activeEngagementParamsFlow = MutableStateFlow(params)
            },
            onMessageReceived = { message ->
                mdocTransport.onMessageReceivedViaNfc(message)
            },
            onError = { error -> fail("Engagement 1 error: $error") },
            negotiatedHandoverPicker = { connectionMethods -> connectionMethods.first() }
        )
        currentEngagementHelper = engagementHelper1

        val tag1 = LoopbackIsoTag(engagementHelper1)
        mdocReaderNfcHandover(tag1, emptyList(), MdocReaderNfcHandoverOptions(useNfcV2 = true))

        mdocTransport = NfcHybridTransportMdoc(
            sendMessageViaNfc = { message ->
                currentEngagementHelper?.sendMessage(message)
                true
            }
        )
        mdocTransport.open(eDeviceKey.publicKey)

        val presentmentJob = launch {
            Iso18013Presentment(
                transport = mdocTransport,
                engagementParams = activeEngagementParamsFlow,
                source = presentmentSource,
                keyAgreementPossible = listOf(EcCurve.P256),
                insertSequenceNumbers = true,
                timeout = null,
                timeoutSubsequentRequests = null
            )
        }

        // Tap 1 disconnect
        mdocTransport.onNfcDeactivated(0)

        // Tap 2 re-tap setup
        val engagementHelper2 = MdocNfcV2EngagementHelper(
            eDeviceKey = eDeviceKey.publicKey,
            onHandoverComplete = { _, encodedDeviceEngagement, handover ->
                activeEngagementParamsFlow.value = EngagementParams(
                    eDeviceKey = eDeviceKey,
                    deviceEngagement = Cbor.decode(encodedDeviceEngagement.toByteArray()),
                    handover = handover,
                    eReaderKey = eReaderKey2.publicKey
                )
            },
            onMessageReceived = { message ->
                mdocTransport.onMessageReceivedViaNfc(message)
            },
            onError = { error -> fail("Engagement 2 error: $error") },
            negotiatedHandoverPicker = { connectionMethods -> connectionMethods.first() }
        )
        currentEngagementHelper = engagementHelper2

        val tag2 = LoopbackIsoTag(engagementHelper2)
        mdocReaderNfcHandover(tag2, emptyList(), MdocReaderNfcHandoverOptions(useNfcV2 = true))

        val mdocReaderTransport2 = NfcHybridTransportMdocReader(tag2, null)
        mdocReaderTransport2.open(eReaderKey2.publicKey)

        val params2 = activeEngagementParamsFlow.value
        val encodedCoseKey2 = Cbor.encode(eReaderKey2.publicKey.toCoseKey().toDataItem())
        val sessionTranscript2 = buildCborArray {
            add(Tagged(Tagged.ENCODED_CBOR, Bstr(Cbor.encode(params2.deviceEngagement))))
            add(Tagged(Tagged.ENCODED_CBOR, Bstr(encodedCoseKey2)))
            add(params2.handover)
        }
        val encodedSessionTranscript2 = Cbor.encode(sessionTranscript2)
        val readerEncryption2 = SessionEncryption(
            role = MdocRole.MDOC_READER,
            eSelfKey = eReaderKey2,
            remotePublicKey = eDeviceKey.publicKey,
            encodedSessionTranscript = encodedSessionTranscript2,
            insertSequenceNumbers = true
        )

        val deviceRequest = buildTestDeviceRequest(encodedSessionTranscript2)

        val encryptedRequest = readerEncryption2.encryptMessage(
            messagePlaintext = Cbor.encode(deviceRequest.toDataItem()),
            statusCode = null
        )
        mdocReaderTransport2.sendMessage(encryptedRequest)

        val encryptedResponse = mdocReaderTransport2.waitForMessage()
        val (responseBytes, status) = readerEncryption2.decryptMessage(encryptedResponse)
        assertNotNull(responseBytes)

        val deviceResponse = DeviceResponse.fromDataItem(Cbor.decode(responseBytes))
        deviceResponse.verify(sessionTranscript = sessionTranscript2)
        assertEquals(1, deviceResponse.documents.size)

        val terminationMessage = SessionEncryption.encodeStatus(
            statusCode = Constants.SESSION_DATA_STATUS_SESSION_TERMINATION,
            sequenceNumber = readerEncryption2.nextSequenceNumber
        )
        mdocReaderTransport2.sendMessage(terminationMessage)

        presentmentJob.join()
    }

    @Test
    fun testTwoTapPresentmentWalletRemovedTooFast() = runTest {
        val presentmentSource = createTestPresentmentSource()
        val eDeviceKey = Crypto.createEcPrivateKey(EcCurve.P256)
        val eReaderKey1 = Crypto.createEcPrivateKey(EcCurve.P256)
        val eReaderKey2 = Crypto.createEcPrivateKey(EcCurve.P256)

        lateinit var activeEngagementParamsFlow: MutableStateFlow<EngagementParams>
        lateinit var mdocTransport: NfcHybridTransportMdoc
        var currentEngagementHelper: MdocNfcV2EngagementHelper? = null

        val engagementHelper1 = MdocNfcV2EngagementHelper(
            eDeviceKey = eDeviceKey.publicKey,
            onHandoverComplete = { _, encodedDeviceEngagement, handover ->
                val params = EngagementParams(
                    eDeviceKey = eDeviceKey,
                    deviceEngagement = Cbor.decode(encodedDeviceEngagement.toByteArray()),
                    handover = handover
                )
                activeEngagementParamsFlow = MutableStateFlow(params)
            },
            onMessageReceived = { message ->
                mdocTransport.onMessageReceivedViaNfc(message)
            },
            onError = { error -> fail("Engagement 1 error: $error") },
            negotiatedHandoverPicker = { connectionMethods -> connectionMethods.first() }
        )
        currentEngagementHelper = engagementHelper1

        val tag1 = LoopbackIsoTag(engagementHelper1)
        mdocReaderNfcHandover(tag1, emptyList(), MdocReaderNfcHandoverOptions(useNfcV2 = true))

        mdocTransport = NfcHybridTransportMdoc(
            sendMessageViaNfc = { message ->
                currentEngagementHelper?.sendMessage(message)
                true
            }
        )
        mdocTransport.open(eDeviceKey.publicKey)

        var presentmentException: Throwable? = null
        val presentmentJob = launch {
            try {
                Iso18013Presentment(
                    transport = mdocTransport,
                    engagementParams = activeEngagementParamsFlow,
                    source = presentmentSource,
                    keyAgreementPossible = listOf(EcCurve.P256),
                    insertSequenceNumbers = true,
                    timeout = null,
                    timeoutSubsequentRequests = null
                )
            } catch (e: Throwable) {
                presentmentException = e
            }
        }

        // Tap 1 disconnect
        mdocTransport.onNfcDeactivated(0)

        // Tap 2 re-tap
        val engagementHelper2 = MdocNfcV2EngagementHelper(
            eDeviceKey = eDeviceKey.publicKey,
            onHandoverComplete = { _, encodedDeviceEngagement, handover ->
                activeEngagementParamsFlow.value = EngagementParams(
                    eDeviceKey = eDeviceKey,
                    deviceEngagement = Cbor.decode(encodedDeviceEngagement.toByteArray()),
                    handover = handover,
                    eReaderKey = eReaderKey2.publicKey
                )
            },
            onMessageReceived = { message ->
                mdocTransport.onMessageReceivedViaNfc(message)
            },
            onError = { error -> fail("Engagement 2 error: $error") },
            negotiatedHandoverPicker = { connectionMethods -> connectionMethods.first() }
        )
        currentEngagementHelper = engagementHelper2

        val tag2 = LoopbackIsoTag(engagementHelper2)
        mdocReaderNfcHandover(tag2, emptyList(), MdocReaderNfcHandoverOptions(useNfcV2 = true))

        val mdocReaderTransport2 = NfcHybridTransportMdocReader(tag2, null)
        mdocReaderTransport2.open(eReaderKey2.publicKey)

        val params2 = activeEngagementParamsFlow.value
        val encodedCoseKey2 = Cbor.encode(eReaderKey2.publicKey.toCoseKey().toDataItem())
        val sessionTranscript2 = buildCborArray {
            add(Tagged(Tagged.ENCODED_CBOR, Bstr(Cbor.encode(params2.deviceEngagement))))
            add(Tagged(Tagged.ENCODED_CBOR, Bstr(encodedCoseKey2)))
            add(params2.handover)
        }
        val encodedSessionTranscript2 = Cbor.encode(sessionTranscript2)
        val readerEncryption2 = SessionEncryption(
            role = MdocRole.MDOC_READER,
            eSelfKey = eReaderKey2,
            remotePublicKey = eDeviceKey.publicKey,
            encodedSessionTranscript = encodedSessionTranscript2,
            insertSequenceNumbers = true
        )

        val deviceRequest = buildTestDeviceRequest(encodedSessionTranscript2)

        val encryptedRequest = readerEncryption2.encryptMessage(
            messagePlaintext = Cbor.encode(deviceRequest.toDataItem()),
            statusCode = null
        )
        mdocReaderTransport2.sendMessage(encryptedRequest)

        val encryptedResponse = mdocReaderTransport2.waitForMessage()
        assertNotNull(encryptedResponse)

        // Wallet is removed too fast before reader sends session termination
        mdocTransport.onNfcDeactivated(0)

        presentmentJob.join()
        assertTrue(
            presentmentException is Iso18013PresentmentNfcDisconnectedException,
            "Expected Iso18013PresentmentNfcDisconnectedException but got $presentmentException"
        )
    }
}
