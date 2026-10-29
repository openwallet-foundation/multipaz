package org.multipaz.presentment

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import kotlinx.io.bytestring.ByteString
import org.multipaz.cbor.Bstr
import org.multipaz.cbor.Cbor
import org.multipaz.cbor.DataItem
import org.multipaz.cbor.Simple
import org.multipaz.cbor.Tagged
import org.multipaz.cbor.buildCborArray
import org.multipaz.cbor.toDataItem
import org.multipaz.crypto.Algorithm
import org.multipaz.crypto.Crypto
import org.multipaz.crypto.EcCurve
import org.multipaz.crypto.EcPrivateKey
import org.multipaz.crypto.EcPublicKey
import org.multipaz.document.Document
import org.multipaz.documenttype.ISO_23220_5_CHV_1_DATA_ELEMENT
import org.multipaz.documenttype.ISO_23220_5_CHV_1_NAMESPACE
import org.multipaz.documenttype.knowntypes.DrivingLicense
import org.multipaz.eventlogger.Event
import org.multipaz.eventlogger.EventLogger
import org.multipaz.eventlogger.EventPresentmentIso18013Proximity
import org.multipaz.facematch.CameraFrame
import org.multipaz.facematch.FaceMatcher
import org.multipaz.facematch.FaceMatcherSession
import org.multipaz.mdoc.connectionmethod.MdocConnectionMethod
import org.multipaz.mdoc.connectionmethod.MdocConnectionMethodNfc
import org.multipaz.mdoc.engagement.buildDeviceEngagement
import org.multipaz.mdoc.nfc.MdocHandoverType
import org.multipaz.mdoc.nfc.MdocNfcCceEngagementHelper
import org.multipaz.mdoc.nfc.MdocReaderNfcHandoverOptions
import org.multipaz.mdoc.nfc.mdocReaderNfcHandover
import org.multipaz.mdoc.request.DeviceRequest
import org.multipaz.mdoc.request.DeviceRequestGenerator
import org.multipaz.mdoc.request.buildDeviceRequest
import org.multipaz.mdoc.response.DeviceResponse
import org.multipaz.mdoc.role.MdocRole
import org.multipaz.mdoc.sessionencryption.SessionEncryption
import org.multipaz.mdoc.transport.MdocTransport
import org.multipaz.mdoc.transport.MdocTransportException
import org.multipaz.mdoc.transport.NfcCceTransportMdoc
import org.multipaz.mdoc.transport.NfcCceTransportMdocReader
import org.multipaz.nfc.CommandApdu
import org.multipaz.nfc.NfcDeactivationReason
import org.multipaz.nfc.NfcIsoTag
import org.multipaz.nfc.ResponseApdu
import org.multipaz.prompt.FaceMatcherPromptDialogModel
import org.multipaz.prompt.PassphrasePromptDialogModel
import org.multipaz.prompt.PromptDialogModel
import org.multipaz.prompt.PromptDismissedException
import org.multipaz.prompt.PromptModel
import org.multipaz.prompt.Reason
import org.multipaz.prompt.ShowConsentPromptFn
import org.multipaz.prompt.promptModelSilentConsent
import org.multipaz.request.Requester
import org.multipaz.securearea.KeyLockedException
import org.multipaz.securearea.KeyUnlockData
import org.multipaz.securearea.KeyUnlockDataProvider
import org.multipaz.securearea.PassphraseConstraints
import org.multipaz.securearea.SecureArea
import org.multipaz.securearea.software.SoftwareCreateKeySettings
import org.multipaz.util.Constants
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

class Iso18013PresentmentTest {

    private class LoopbackIsoTag(var engagementHelper: MdocNfcCceEngagementHelper): NfcIsoTag() {
        override val maxTransceiveLength: Int get() = 65536
        override suspend fun close() {}
        override suspend fun updateDialogMessage(message: String) {}
        override suspend fun transceive(command: CommandApdu): ResponseApdu {
            return engagementHelper.processApdu(command)
        }
    }

    private class RecordingEventLogger : EventLogger {
        val events = mutableListOf<Event>()
        override suspend fun addEvent(event: Event): Event {
            events.add(event)
            return event
        }
        override fun addEventAsync(event: Event) {
            events.add(event)
        }
    }

    private suspend fun createTestPresentmentSource(
        eventLogger: EventLogger? = null,
        showConsentPromptFn: ShowConsentPromptFn = ::promptModelSilentConsent,
    ): SimplePresentmentSource {
        val harness = DocumentStoreTestHarness()
        harness.initialize()
        harness.provisionStandardDocuments()
        return SimplePresentmentSource(
            documentStore = harness.documentStore,
            documentTypeRepository = harness.documentTypeRepository,
            eventLogger = eventLogger,
            showConsentPromptFn = showConsentPromptFn,
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
        lateinit var mdocTransport: NfcCceTransportMdoc
        var currentEngagementHelper: MdocNfcCceEngagementHelper? = null

        val engagementHelper = MdocNfcCceEngagementHelper(
            eDeviceKey = eDeviceKey.publicKey,
            onHandoverComplete = { _, encodedDeviceEngagement, handover, receivedEReaderKey ->
                val params = EngagementParams(
                    eDeviceKey = eDeviceKey,
                    deviceEngagement = Cbor.decode(encodedDeviceEngagement.toByteArray()),
                    handover = handover,
                    eReaderKey = receivedEReaderKey
                )
                activeEngagementParamsFlow = MutableStateFlow(params)
            },
            onMessageReceived = { message ->
                mdocTransport.onMessageReceivedViaNfc(message)
            },
            onError = { error ->
                fail("onError: $error")
            },
            handoverPicker = { null }
        )
        currentEngagementHelper = engagementHelper

        val tag = LoopbackIsoTag(engagementHelper)
        val handoverResult = mdocReaderNfcHandover(
            tag = tag,
            negotiatedHandoverConnectionMethods = emptyList(),
            options = MdocReaderNfcHandoverOptions(useCce = true, eReaderKey = eReaderKey)
        )
        assertNotNull(handoverResult)
        assertEquals(MdocHandoverType.CCE_HANDOVER, handoverResult.type)

        val mdocReaderTransport = NfcCceTransportMdocReader(
            nfcTag = tag,
            negotiatedTransport = null
        )
        mdocReaderTransport.open(eReaderKey.publicKey)

        mdocTransport = NfcCceTransportMdoc(
            sendMessageViaNfc = { message ->
                currentEngagementHelper?.sendMessage(message)
                true
            }
        )
        mdocTransport.open(eDeviceKey.publicKey)

        var receivedTelemetry: PresentmentTelemetry? = null
        val presentmentJob = launch {
            Iso18013Presentment(
                transport = mdocTransport,
                engagementParams = activeEngagementParamsFlow,
                source = presentmentSource,
                keyAgreementPossible = listOf(EcCurve.P256),
                insertSequenceNumbers = true,
                timeout = null,
                timeoutSubsequentRequests = null,
                onTelemetry = { receivedTelemetry = it }
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
        ).apply {
            setSendSessionEstablishment(false)
        }

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
        assertNotNull(receivedTelemetry)
        assertEquals(PresentmentOutcome.SUCCESS, receivedTelemetry!!.outcome)
        assertEquals(PresentmentProtocol.ISO_18013_5_PROXIMITY, receivedTelemetry!!.protocol)
        assertEquals(1, receivedTelemetry!!.numRequestsServed)
        assertEquals(1, receivedTelemetry!!.numDocumentsPresented)
        assertEquals(0, receivedTelemetry!!.numNfcReTaps)
        assertNotNull(receivedTelemetry!!.requestSizeBytes)
        assertNotNull(receivedTelemetry!!.responseSizeBytes)
        assertNotNull(receivedTelemetry!!.transportStats)
    }

    /**
     * Verifies ISO/IEC 18013-5 Second Edition Clause 12.2.4:
     * If the curve of `EReaderKey` in `ReaderEngagement` (e.g. P-384) does not match the curve of
     * `EDeviceKey` in `DeviceEngagement` (P-256), the reader generates a new `EReaderKey` on P-256
     * and includes it in `SessionEstablishment`, and [Iso18013Presentment] falls back to the
     * `EReaderKey` from `SessionEstablishment`.
     */
    @Test
    fun testCceEReaderKeyCurveMismatchFallbackToSessionEstablishment() = runTest {
        val presentmentSource = createTestPresentmentSource()
        val eDeviceKey = Crypto.createEcPrivateKey(EcCurve.P256)
        val eReaderKeyEngagement = Crypto.createEcPrivateKey(EcCurve.P384)
        val eReaderKeySession = Crypto.createEcPrivateKey(EcCurve.P256)

        lateinit var activeEngagementParamsFlow: MutableStateFlow<EngagementParams>
        lateinit var mdocTransport: NfcCceTransportMdoc
        var currentEngagementHelper: MdocNfcCceEngagementHelper? = null

        val engagementHelper = MdocNfcCceEngagementHelper(
            eDeviceKey = eDeviceKey.publicKey,
            onHandoverComplete = { _, encodedDeviceEngagement, handover, receivedEReaderKey ->
                assertEquals(EcCurve.P384, receivedEReaderKey.curve)
                val params = EngagementParams(
                    eDeviceKey = eDeviceKey,
                    deviceEngagement = Cbor.decode(encodedDeviceEngagement.toByteArray()),
                    handover = handover,
                    eReaderKey = receivedEReaderKey
                )
                activeEngagementParamsFlow = MutableStateFlow(params)
            },
            onMessageReceived = { message ->
                mdocTransport.onMessageReceivedViaNfc(message)
            },
            onError = { error ->
                fail("onError: $error")
            },
            handoverPicker = { null }
        )
        currentEngagementHelper = engagementHelper

        val tag = LoopbackIsoTag(engagementHelper)
        val handoverResult = mdocReaderNfcHandover(
            tag = tag,
            negotiatedHandoverConnectionMethods = emptyList(),
            options = MdocReaderNfcHandoverOptions(useCce = true, eReaderKey = eReaderKeyEngagement)
        )
        assertNotNull(handoverResult)

        val mdocReaderTransport = NfcCceTransportMdocReader(
            nfcTag = tag,
            negotiatedTransport = null
        )
        mdocReaderTransport.open(eReaderKeySession.publicKey)

        mdocTransport = NfcCceTransportMdoc(
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
        val encodedCoseKey = Cbor.encode(eReaderKeySession.publicKey.toCoseKey().toDataItem())
        val sessionTranscript = buildCborArray {
            add(Tagged(Tagged.ENCODED_CBOR, Bstr(Cbor.encode(params.deviceEngagement))))
            add(Tagged(Tagged.ENCODED_CBOR, Bstr(encodedCoseKey)))
            add(params.handover)
        }
        val encodedSessionTranscript = Cbor.encode(sessionTranscript)
        val readerEncryption = SessionEncryption(
            role = MdocRole.MDOC_READER,
            eSelfKey = eReaderKeySession,
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
        val (responseBytes, _) = readerEncryption.decryptMessage(encryptedResponse)
        assertNotNull(responseBytes)

        val deviceResponse = DeviceResponse.fromDataItem(Cbor.decode(responseBytes))
        deviceResponse.verify(sessionTranscript = sessionTranscript)
        assertEquals(1, deviceResponse.documents.size)

        mdocReaderTransport.sendMessage(
            SessionEncryption.encodeStatus(
                statusCode = Constants.SESSION_DATA_STATUS_SESSION_TERMINATION,
                sequenceNumber = readerEncryption.nextSequenceNumber
            )
        )
        presentmentJob.join()
    }

    @Test
    fun testTwoTapPresentmentSuccess() = runTest {
        val presentmentSource = createTestPresentmentSource()
        val eDeviceKey = Crypto.createEcPrivateKey(EcCurve.P256)
        val eReaderKey1 = Crypto.createEcPrivateKey(EcCurve.P256)
        val eReaderKey2 = Crypto.createEcPrivateKey(EcCurve.P256)

        lateinit var activeEngagementParamsFlow: MutableStateFlow<EngagementParams>
        lateinit var mdocTransport: NfcCceTransportMdoc
        var currentEngagementHelper: MdocNfcCceEngagementHelper? = null

        val engagementHelper1 = MdocNfcCceEngagementHelper(
            eDeviceKey = eDeviceKey.publicKey,
            onHandoverComplete = { _, encodedDeviceEngagement, handover, receivedEReaderKey ->
                val params = EngagementParams(
                    eDeviceKey = eDeviceKey,
                    deviceEngagement = Cbor.decode(encodedDeviceEngagement.toByteArray()),
                    handover = handover,
                    eReaderKey = receivedEReaderKey
                )
                activeEngagementParamsFlow = MutableStateFlow(params)
            },
            onMessageReceived = { message ->
                mdocTransport.onMessageReceivedViaNfc(message)
            },
            onError = { error -> fail("Engagement 1 error: $error") },
            handoverPicker = { null }
        )
        currentEngagementHelper = engagementHelper1

        val tag1 = LoopbackIsoTag(engagementHelper1)
        mdocReaderNfcHandover(tag1, emptyList(), MdocReaderNfcHandoverOptions(useCce = true, eReaderKey = eReaderKey1))

        mdocTransport = NfcCceTransportMdoc(
            sendMessageViaNfc = { message ->
                currentEngagementHelper?.sendMessage(message)
                true
            }
        )
        mdocTransport.open(eDeviceKey.publicKey)

        var receivedTelemetry: PresentmentTelemetry? = null
        val presentmentJob = launch {
            Iso18013Presentment(
                transport = mdocTransport,
                engagementParams = activeEngagementParamsFlow,
                source = presentmentSource,
                keyAgreementPossible = listOf(EcCurve.P256),
                insertSequenceNumbers = true,
                timeout = null,
                timeoutSubsequentRequests = null,
                onTelemetry = { receivedTelemetry = it }
            )
        }

        // Tap 1 disconnect
        mdocTransport.onNfcDeactivated(NfcDeactivationReason.LINK_LOSS)

        // Tap 2 re-tap setup
        val engagementHelper2 = MdocNfcCceEngagementHelper(
            eDeviceKey = eDeviceKey.publicKey,
            onHandoverComplete = { _, encodedDeviceEngagement, handover, receivedEReaderKey ->
                activeEngagementParamsFlow.value = EngagementParams(
                    eDeviceKey = eDeviceKey,
                    deviceEngagement = Cbor.decode(encodedDeviceEngagement.toByteArray()),
                    handover = handover,
                    eReaderKey = receivedEReaderKey
                )
            },
            onMessageReceived = { message ->
                mdocTransport.onMessageReceivedViaNfc(message)
            },
            onError = { error -> fail("Engagement 2 error: $error") },
            handoverPicker = { null }
        )
        currentEngagementHelper = engagementHelper2

        val tag2 = LoopbackIsoTag(engagementHelper2)
        mdocReaderNfcHandover(tag2, emptyList(), MdocReaderNfcHandoverOptions(useCce = true, eReaderKey = eReaderKey2))

        val mdocReaderTransport2 = NfcCceTransportMdocReader(tag2, null)
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
        assertNotNull(receivedTelemetry)
        assertEquals(PresentmentOutcome.SUCCESS, receivedTelemetry!!.outcome)
        assertEquals(1, receivedTelemetry!!.numNfcReTaps)
    }

    @Test
    fun testTwoTapPresentmentWalletRemovedTooFast() = runTest {
        val presentmentSource = createTestPresentmentSource()
        val eDeviceKey = Crypto.createEcPrivateKey(EcCurve.P256)
        val eReaderKey1 = Crypto.createEcPrivateKey(EcCurve.P256)
        val eReaderKey2 = Crypto.createEcPrivateKey(EcCurve.P256)

        lateinit var activeEngagementParamsFlow: MutableStateFlow<EngagementParams>
        lateinit var mdocTransport: NfcCceTransportMdoc
        var currentEngagementHelper: MdocNfcCceEngagementHelper? = null

        val engagementHelper1 = MdocNfcCceEngagementHelper(
            eDeviceKey = eDeviceKey.publicKey,
            onHandoverComplete = { _, encodedDeviceEngagement, handover, receivedEReaderKey ->
                val params = EngagementParams(
                    eDeviceKey = eDeviceKey,
                    deviceEngagement = Cbor.decode(encodedDeviceEngagement.toByteArray()),
                    handover = handover,
                    eReaderKey = receivedEReaderKey
                )
                activeEngagementParamsFlow = MutableStateFlow(params)
            },
            onMessageReceived = { message ->
                mdocTransport.onMessageReceivedViaNfc(message)
            },
            onError = { error -> fail("Engagement 1 error: $error") },
            handoverPicker = { null }
        )
        currentEngagementHelper = engagementHelper1

        val tag1 = LoopbackIsoTag(engagementHelper1)
        mdocReaderNfcHandover(tag1, emptyList(), MdocReaderNfcHandoverOptions(useCce = true, eReaderKey = eReaderKey1))

        mdocTransport = NfcCceTransportMdoc(
            sendMessageViaNfc = { message ->
                currentEngagementHelper?.sendMessage(message)
                true
            }
        )
        mdocTransport.open(eDeviceKey.publicKey)

        var presentmentException: Throwable? = null
        var callbackTelemetry: PresentmentTelemetry? = null
        val presentmentJob = launch {
            try {
                Iso18013Presentment(
                    transport = mdocTransport,
                    engagementParams = activeEngagementParamsFlow,
                    source = presentmentSource,
                    keyAgreementPossible = listOf(EcCurve.P256),
                    insertSequenceNumbers = true,
                    timeout = null,
                    timeoutSubsequentRequests = null,
                    onTelemetry = { callbackTelemetry = it }
                )
            } catch (e: Throwable) {
                presentmentException = e
            }
        }

        // Tap 1 disconnect
        mdocTransport.onNfcDeactivated(NfcDeactivationReason.LINK_LOSS)

        // Tap 2 re-tap
        val engagementHelper2 = MdocNfcCceEngagementHelper(
            eDeviceKey = eDeviceKey.publicKey,
            onHandoverComplete = { _, encodedDeviceEngagement, handover, receivedEReaderKey ->
                activeEngagementParamsFlow.value = EngagementParams(
                    eDeviceKey = eDeviceKey,
                    deviceEngagement = Cbor.decode(encodedDeviceEngagement.toByteArray()),
                    handover = handover,
                    eReaderKey = receivedEReaderKey
                )
            },
            onMessageReceived = { message ->
                mdocTransport.onMessageReceivedViaNfc(message)
            },
            onError = { error -> fail("Engagement 2 error: $error") },
            handoverPicker = { null }
        )
        currentEngagementHelper = engagementHelper2

        val tag2 = LoopbackIsoTag(engagementHelper2)
        mdocReaderNfcHandover(tag2, emptyList(), MdocReaderNfcHandoverOptions(useCce = true, eReaderKey = eReaderKey2))

        val mdocReaderTransport2 = NfcCceTransportMdocReader(tag2, null)
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
        mdocTransport.onNfcDeactivated(NfcDeactivationReason.LINK_LOSS)

        presentmentJob.join()
        assertTrue(
            presentmentException is Iso18013PresentmentNfcDisconnectedException,
            "Expected Iso18013PresentmentNfcDisconnectedException but got $presentmentException"
        )
        assertNotNull(callbackTelemetry)
        assertEquals(PresentmentOutcome.TRANSPORT_ERROR, callbackTelemetry!!.outcome)
        assertEquals(1, callbackTelemetry!!.numNfcReTaps)
    }

    @Test
    fun testCancelledPresentmentClosesTransportWithoutSendingSessionTermination() = runTest {
        val presentmentSource = createTestPresentmentSource()
        val eDeviceKey = Crypto.createEcPrivateKey(EcCurve.P256)
        val deviceEngagement = buildDeviceEngagement(eDeviceKey = eDeviceKey.publicKey) {}.toDataItem()
        val waitingForRequest = CompletableDeferred<Unit>()
        var sendMessageCalls = 0
        var closeCalls = 0

        val transport = object : MdocTransport() {
            private val _state = MutableStateFlow(State.CONNECTED)
            override val state: StateFlow<State> = _state
            override val role: MdocRole = MdocRole.MDOC
            override val connectionMethod: MdocConnectionMethod = MdocConnectionMethodNfc(0xffff, 0x10000)
            override val scanningTime: Duration? = null
            private val hangForever = CompletableDeferred<ByteArray>()

            override suspend fun advertise() {}
            override suspend fun open(eSenderKey: EcPublicKey) {}

            override suspend fun sendMessage(message: ByteArray) {
                sendMessageCalls += 1
            }

            override suspend fun waitForMessage(): ByteArray {
                return hangForever.await()
            }

            override suspend fun close() {
                // Suspend inside close() to verify NonCancellable context in finally block
                yield()
                closeCalls += 1
                _state.value = State.CLOSED
            }
        }

        var callbackTelemetry: PresentmentTelemetry? = null
        val eventLogger = RecordingEventLogger()
        val presentmentSourceWithLogger = createTestPresentmentSource(eventLogger = eventLogger)

        val job = launch {
            Iso18013Presentment(
                transport = transport,
                eDeviceKey = eDeviceKey,
                deviceEngagement = deviceEngagement,
                handover = Simple.NULL,
                source = presentmentSourceWithLogger,
                keyAgreementPossible = listOf(EcCurve.P256),
                onWaitingForRequest = { waitingForRequest.complete(Unit) },
                onTelemetry = { callbackTelemetry = it },
            )
        }

        waitingForRequest.await()
        job.cancel()
        job.join()

        assertEquals(0, sendMessageCalls)
        assertEquals(1, closeCalls)
        assertEquals(MdocTransport.State.CLOSED, transport.state.value)
        assertNotNull(callbackTelemetry)
        assertEquals(PresentmentOutcome.CANCELED_BY_USER, callbackTelemetry!!.outcome)
        val loggedEvent = assertIs<EventPresentmentIso18013Proximity>(eventLogger.events.single())
        assertEquals(PresentmentOutcome.CANCELED_BY_USER, loggedEvent.telemetry?.outcome)
    }

    private fun buildSingleMessageTransport(
        incomingMessage: suspend () -> ByteArray,
        initialState: MdocTransport.State = MdocTransport.State.CONNECTED,
    ): MdocTransport = object : MdocTransport() {
        private val _state = MutableStateFlow(initialState)
        override val state: StateFlow<State> = _state
        override val role: MdocRole = MdocRole.MDOC
        override val connectionMethod: MdocConnectionMethod = MdocConnectionMethodNfc(0xffff, 0x10000)
        override val scanningTime: Duration? = null

        override suspend fun advertise() {}
        override suspend fun open(eSenderKey: EcPublicKey) {}
        override suspend fun sendMessage(message: ByteArray) {}
        override suspend fun waitForMessage(): ByteArray = incomingMessage()
        override suspend fun close() {
            _state.value = State.CLOSED
        }
    }

    private suspend fun buildEncryptedReaderSessionData(
        eDeviceKey: EcPrivateKey,
        deviceEngagement: DataItem,
        deviceRequestBuilder: suspend (encodedSessionTranscript: ByteArray) -> DeviceRequest,
    ): ByteArray {
        val eReaderKey = Crypto.createEcPrivateKey(EcCurve.P256)
        val encodedCoseKey = Cbor.encode(eReaderKey.publicKey.toCoseKey().toDataItem())
        val sessionTranscript = buildCborArray {
            add(Tagged(Tagged.ENCODED_CBOR, Bstr(Cbor.encode(deviceEngagement))))
            add(Tagged(Tagged.ENCODED_CBOR, Bstr(encodedCoseKey)))
            add(Simple.NULL)
        }
        val encodedSessionTranscript = Cbor.encode(sessionTranscript)
        val readerEncryption = SessionEncryption(
            role = MdocRole.MDOC_READER,
            eSelfKey = eReaderKey,
            remotePublicKey = eDeviceKey.publicKey,
            encodedSessionTranscript = encodedSessionTranscript,
        )
        val deviceRequest = deviceRequestBuilder(encodedSessionTranscript)
        return readerEncryption.encryptMessage(
            messagePlaintext = Cbor.encode(deviceRequest.toDataItem()),
            statusCode = null,
        )
    }

    @Test
    fun testOutcomeCanceledByUserWhenConsentDeclined() = runTest {
        val eventLogger = RecordingEventLogger()
        val presentmentSource = createTestPresentmentSource(
            eventLogger = eventLogger,
            showConsentPromptFn = { _, _, _, _, _ -> null },
        )
        val eDeviceKey = Crypto.createEcPrivateKey(EcCurve.P256)
        val deviceEngagement = buildDeviceEngagement(eDeviceKey = eDeviceKey.publicKey) {}.toDataItem()
        val encryptedRequest = buildEncryptedReaderSessionData(eDeviceKey, deviceEngagement) {
            buildTestDeviceRequest(it)
        }
        val transport = buildSingleMessageTransport(incomingMessage = { encryptedRequest })

        var callbackTelemetry: PresentmentTelemetry? = null
        assertFailsWith<PresentmentCanceledException> {
            Iso18013Presentment(
                transport = transport,
                eDeviceKey = eDeviceKey,
                deviceEngagement = deviceEngagement,
                handover = Simple.NULL,
                source = presentmentSource,
                keyAgreementPossible = listOf(EcCurve.P256),
                onTelemetry = { callbackTelemetry = it },
            )
        }

        assertNotNull(callbackTelemetry)
        assertEquals(PresentmentOutcome.CANCELED_BY_USER, callbackTelemetry!!.outcome)
        val loggedEvent = assertIs<EventPresentmentIso18013Proximity>(eventLogger.events.single())
        assertNull(loggedEvent.presentmentData)
        assertEquals(PresentmentOutcome.CANCELED_BY_USER, loggedEvent.telemetry?.outcome)
    }

    @Test
    fun testOutcomeCannotSatisfyRequest() = runTest {
        val eventLogger = RecordingEventLogger()
        val presentmentSource = createTestPresentmentSource(eventLogger = eventLogger)
        val eDeviceKey = Crypto.createEcPrivateKey(EcCurve.P256)
        val deviceEngagement = buildDeviceEngagement(eDeviceKey = eDeviceKey.publicKey) {}.toDataItem()
        val encryptedRequest = buildEncryptedReaderSessionData(eDeviceKey, deviceEngagement) { encodedTranscript ->
            val generator = DeviceRequestGenerator(encodedTranscript)
            generator.addDocumentRequest(
                docType = "org.example.nonexistent.doctype",
                itemsToRequest = mapOf("org.example.ns" to mapOf("foo" to false)),
                requestInfo = null,
                readerKey = null,
                signatureAlgorithm = Algorithm.ESP256,
                readerKeyCertificateChain = null,
            )
            DeviceRequest.fromDataItem(Cbor.decode(generator.generate()))
        }
        val transport = buildSingleMessageTransport(incomingMessage = { encryptedRequest })

        var callbackTelemetry: PresentmentTelemetry? = null
        assertFailsWith<PresentmentCannotSatisfyRequestException> {
            Iso18013Presentment(
                transport = transport,
                eDeviceKey = eDeviceKey,
                deviceEngagement = deviceEngagement,
                handover = Simple.NULL,
                source = presentmentSource,
                keyAgreementPossible = listOf(EcCurve.P256),
                onTelemetry = { callbackTelemetry = it },
            )
        }

        assertNotNull(callbackTelemetry)
        assertEquals(PresentmentOutcome.CANNOT_SATISFY_REQUEST, callbackTelemetry!!.outcome)
        val loggedEvent = assertIs<EventPresentmentIso18013Proximity>(eventLogger.events.single())
        assertEquals(PresentmentOutcome.CANNOT_SATISFY_REQUEST, loggedEvent.telemetry?.outcome)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun testOutcomeFaceNotMatched() = runTest {
        val eventLogger = RecordingEventLogger()
        val harness = DocumentStoreTestHarness()
        harness.initialize()
        harness.provisionMdoc(
            displayName = "mDL",
            docType = DrivingLicense.MDL_DOCTYPE,
            data = mapOf(
                DrivingLicense.MDL_NAMESPACE to listOf(
                    "given_name" to "Erika".toDataItem(),
                    "portrait" to byteArrayOf(1, 2, 3, 4).toDataItem(),
                )
            ),
            keyAuthorizedDataElements = mapOf(
                ISO_23220_5_CHV_1_NAMESPACE to listOf(ISO_23220_5_CHV_1_DATA_ELEMENT)
            ),
        )
        val fakeFaceMatcher = object : FaceMatcher {
            override val name = "test"
            override fun createSession(referencePortrait: ByteString): FaceMatcherSession =
                object : FaceMatcherSession(referencePortrait) {
                    override suspend fun feedFrame(frame: CameraFrame) {}
                }
        }
        val promptModel = object : PromptModel(
            object : PromptModel.Builder(toHumanReadable = { _, _ -> throw IllegalStateException() }) {
                init { addCommonDialogs() }
                override fun build(): PromptModel = throw IllegalStateException()
            }
        ) {
            override val promptModelScope = CoroutineScope(Dispatchers.Default + SupervisorJob() + this)
        }
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            val dialogModel = promptModel.getDialogModel(FaceMatcherPromptDialogModel.DialogType)
            dialogModel.dialogState.collect { state ->
                if (state is PromptDialogModel.DialogShownState) {
                    state.resultChannel.send(false)
                }
            }
        }

        val presentmentSource = SimplePresentmentSource(
            documentStore = harness.documentStore,
            documentTypeRepository = harness.documentTypeRepository,
            eventLogger = eventLogger,
            showConsentPromptFn = ::promptModelSilentConsent,
            getFaceMatcherFn = { fakeFaceMatcher },
            getFaceMatchingModeFn = { _, _ -> FaceMatchingMode.ALWAYS },
            domainsMdocSignature = listOf("mdoc"),
        )
        val eDeviceKey = Crypto.createEcPrivateKey(EcCurve.P256)
        val deviceEngagement = buildDeviceEngagement(eDeviceKey = eDeviceKey.publicKey) {}.toDataItem()
        val encryptedRequest = buildEncryptedReaderSessionData(eDeviceKey, deviceEngagement) { encodedTranscript ->
            buildDeviceRequest(sessionTranscript = Cbor.decode(encodedTranscript)) {
                addDocRequest(
                    docType = DrivingLicense.MDL_DOCTYPE,
                    nameSpaces = mapOf(
                        DrivingLicense.MDL_NAMESPACE to mapOf("given_name" to false),
                    ),
                )
            }
        }
        val transport = buildSingleMessageTransport(incomingMessage = { encryptedRequest })

        var callbackTelemetry: PresentmentTelemetry? = null
        assertFailsWith<FaceNotMatchedException> {
            withContext(promptModel) {
                Iso18013Presentment(
                    transport = transport,
                    eDeviceKey = eDeviceKey,
                    deviceEngagement = deviceEngagement,
                    handover = Simple.NULL,
                    source = presentmentSource,
                    keyAgreementPossible = listOf(EcCurve.P256),
                    onTelemetry = { callbackTelemetry = it },
                )
            }
        }

        assertNotNull(callbackTelemetry)
        assertEquals(PresentmentOutcome.FACE_NOT_MATCHED, callbackTelemetry!!.outcome)
        val loggedEvent = assertIs<EventPresentmentIso18013Proximity>(eventLogger.events.single())
        assertEquals(PresentmentOutcome.FACE_NOT_MATCHED, loggedEvent.telemetry?.outcome)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun testOutcomeAuthenticationFailed() = runTest {
        val harness = DocumentStoreTestHarness()
        harness.initialize()
        harness.provisionMdoc(
            displayName = "mDL",
            docType = DrivingLicense.MDL_DOCTYPE,
            data = mapOf(
                DrivingLicense.MDL_NAMESPACE to listOf(
                    "given_name" to "Erika".toDataItem(),
                )
            ),
            createKeySettings = SoftwareCreateKeySettings.Builder()
                .setPassphraseRequired(true, "1234", PassphraseConstraints.PIN_FOUR_DIGITS)
                .build(),
        )

        val eventLogger = RecordingEventLogger()
        val promptModel = object : PromptModel(
            object : PromptModel.Builder(
                toHumanReadable = { _, _ ->
                    Reason.HumanReadable(
                        title = "Unlock credential",
                        subtitle = "Enter PIN",
                        requireConfirmation = false,
                    )
                }
            ) {
                init {
                    addCommonDialogs()
                }
                override fun build(): PromptModel = throw IllegalStateException()
            }
        ) {
            override val promptModelScope = CoroutineScope(Dispatchers.Default + SupervisorJob() + this)
        }
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            val dialogModel = promptModel.getDialogModel(PassphrasePromptDialogModel.DialogType)
            dialogModel.dialogState.collect { state ->
                if (state is PromptDialogModel.DialogShownState) {
                    state.resultChannel.close(PromptDismissedException())
                }
            }
        }

        val presentmentSource = SimplePresentmentSource(
            documentStore = harness.documentStore,
            documentTypeRepository = harness.documentTypeRepository,
            eventLogger = eventLogger,
            showConsentPromptFn = ::promptModelSilentConsent,
            domainsMdocSignature = listOf("mdoc"),
        )
        val eDeviceKey = Crypto.createEcPrivateKey(EcCurve.P256)
        val deviceEngagement = buildDeviceEngagement(eDeviceKey = eDeviceKey.publicKey) {}.toDataItem()
        val encryptedRequest = buildEncryptedReaderSessionData(eDeviceKey, deviceEngagement) { encodedTranscript ->
            buildDeviceRequest(sessionTranscript = Cbor.decode(encodedTranscript)) {
                addDocRequest(
                    docType = DrivingLicense.MDL_DOCTYPE,
                    nameSpaces = mapOf(
                        DrivingLicense.MDL_NAMESPACE to mapOf("given_name" to false),
                    ),
                )
            }
        }
        val transport = buildSingleMessageTransport(incomingMessage = { encryptedRequest })

        var callbackTelemetry: PresentmentTelemetry? = null
        val error = assertFailsWith<AuthenticationFailedException> {
            withContext(promptModel) {
                Iso18013Presentment(
                    transport = transport,
                    eDeviceKey = eDeviceKey,
                    deviceEngagement = deviceEngagement,
                    handover = Simple.NULL,
                    source = presentmentSource,
                    keyAgreementPossible = listOf(EcCurve.P256),
                    onTelemetry = { callbackTelemetry = it },
                )
            }
        }
        assertTrue(generateSequence<Throwable>(error) { it.cause }.any { it is KeyLockedException })

        assertNotNull(callbackTelemetry)
        assertEquals(PresentmentOutcome.AUTHENTICATION_FAILED, callbackTelemetry!!.outcome)
        val loggedEvent = assertIs<EventPresentmentIso18013Proximity>(eventLogger.events.single())
        assertEquals(PresentmentOutcome.AUTHENTICATION_FAILED, loggedEvent.telemetry?.outcome)
        assertNull(loggedEvent.presentmentData)
    }

    @Test
    fun testMdocPresentmentGenerateResponseAuthenticationFailed() = runTest {
        val harness = DocumentStoreTestHarness()
        harness.initialize()
        harness.provisionMdoc(
            displayName = "mDL",
            docType = DrivingLicense.MDL_DOCTYPE,
            data = mapOf(
                DrivingLicense.MDL_NAMESPACE to listOf(
                    "given_name" to "Erika".toDataItem(),
                )
            ),
            createKeySettings = SoftwareCreateKeySettings.Builder()
                .setPassphraseRequired(true, "1234", PassphraseConstraints.PIN_FOUR_DIGITS)
                .build(),
        )

        val presentmentSource = SimplePresentmentSource(
            documentStore = harness.documentStore,
            documentTypeRepository = harness.documentTypeRepository,
            showConsentPromptFn = ::promptModelSilentConsent,
            domainsMdocSignature = listOf("mdoc"),
        )
        val sessionTranscript = buildCborArray {
            add(Simple.NULL)
            add(Simple.NULL)
            add(byteArrayOf(1, 2, 3))
        }
        val deviceRequest = buildDeviceRequest(sessionTranscript = sessionTranscript) {
            addDocRequest(
                docType = DrivingLicense.MDL_DOCTYPE,
                nameSpaces = mapOf(
                    DrivingLicense.MDL_NAMESPACE to mapOf("given_name" to false),
                ),
            )
        }
        val selection = mdocPresentmentObtainConsent(
            deviceRequest = deviceRequest,
            source = presentmentSource,
        )

        val failingUnlockProvider = object : KeyUnlockDataProvider {
            override suspend fun getKeyUnlockData(
                secureArea: SecureArea,
                alias: String,
                algorithm: Algorithm,
                unlockReason: Reason,
            ): KeyUnlockData {
                throw KeyLockedException("Deferred key unlock failed")
            }
        }

        val error = assertFailsWith<AuthenticationFailedException> {
            withContext(failingUnlockProvider) {
                mdocPresentmentGenerateResponse(
                    selection = selection,
                    deviceRequest = deviceRequest,
                    eReaderKey = null,
                    sessionTranscript = sessionTranscript,
                    source = presentmentSource,
                )
            }
        }
        assertTrue(generateSequence<Throwable>(error) { it.cause }.any { it is KeyLockedException })
    }

    @Test
    fun testOutcomeConnectionTimeout() = runTest {
        val eventLogger = RecordingEventLogger()
        val presentmentSource = createTestPresentmentSource(eventLogger = eventLogger)
        val eDeviceKey = Crypto.createEcPrivateKey(EcCurve.P256)
        val deviceEngagement = buildDeviceEngagement(eDeviceKey = eDeviceKey.publicKey) {}.toDataItem()
        val transport = buildSingleMessageTransport(
            incomingMessage = { byteArrayOf() },
            initialState = MdocTransport.State.ADVERTISING,
        )

        var callbackTelemetry: PresentmentTelemetry? = null
        assertFailsWith<TimeoutCancellationException> {
            withTimeout(50.milliseconds) {
                Iso18013Presentment(
                    transport = transport,
                    eDeviceKey = eDeviceKey,
                    deviceEngagement = deviceEngagement,
                    handover = Simple.NULL,
                    source = presentmentSource,
                    keyAgreementPossible = listOf(EcCurve.P256),
                    onTelemetry = { callbackTelemetry = it },
                )
            }
        }

        assertNotNull(callbackTelemetry)
        assertEquals(PresentmentOutcome.CONNECTION_TIMEOUT, callbackTelemetry!!.outcome)
        val loggedEvent = assertIs<EventPresentmentIso18013Proximity>(eventLogger.events.single())
        assertEquals(PresentmentOutcome.CONNECTION_TIMEOUT, loggedEvent.telemetry?.outcome)
    }

    @Test
    fun testOutcomeRequestTimeout() = runTest {
        val eventLogger = RecordingEventLogger()
        val presentmentSource = createTestPresentmentSource(eventLogger = eventLogger)
        val eDeviceKey = Crypto.createEcPrivateKey(EcCurve.P256)
        val deviceEngagement = buildDeviceEngagement(eDeviceKey = eDeviceKey.publicKey) {}.toDataItem()
        val hangForever = CompletableDeferred<ByteArray>()
        val transport = buildSingleMessageTransport(incomingMessage = { hangForever.await() })

        var callbackTelemetry: PresentmentTelemetry? = null
        assertFailsWith<Iso18013PresentmentTimeoutException> {
            Iso18013Presentment(
                transport = transport,
                eDeviceKey = eDeviceKey,
                deviceEngagement = deviceEngagement,
                handover = Simple.NULL,
                source = presentmentSource,
                keyAgreementPossible = listOf(EcCurve.P256),
                timeout = 50.milliseconds,
                onTelemetry = { callbackTelemetry = it },
            )
        }

        assertNotNull(callbackTelemetry)
        assertEquals(PresentmentOutcome.REQUEST_TIMEOUT, callbackTelemetry!!.outcome)
        val loggedEvent = assertIs<EventPresentmentIso18013Proximity>(eventLogger.events.single())
        assertEquals(PresentmentOutcome.REQUEST_TIMEOUT, loggedEvent.telemetry?.outcome)
    }

    @Test
    fun testOutcomeTransportError() = runTest {
        val eventLogger = RecordingEventLogger()
        val presentmentSource = createTestPresentmentSource(eventLogger = eventLogger)
        val eDeviceKey = Crypto.createEcPrivateKey(EcCurve.P256)
        val deviceEngagement = buildDeviceEngagement(eDeviceKey = eDeviceKey.publicKey) {}.toDataItem()

        // Case 1: waitForMessage() throws MdocTransportException
        val transport1 = buildSingleMessageTransport(
            incomingMessage = { throw MdocTransportException("BLE link dropped") }
        )
        var callbackTelemetry1: PresentmentTelemetry? = null
        assertFailsWith<MdocTransportException> {
            Iso18013Presentment(
                transport = transport1,
                eDeviceKey = eDeviceKey,
                deviceEngagement = deviceEngagement,
                handover = Simple.NULL,
                source = presentmentSource,
                keyAgreementPossible = listOf(EcCurve.P256),
                onTelemetry = { callbackTelemetry1 = it },
            )
        }
        assertNotNull(callbackTelemetry1)
        assertEquals(PresentmentOutcome.TRANSPORT_ERROR, callbackTelemetry1!!.outcome)

        // Case 2: transport transitions to State.FAILED instead of State.CONNECTED
        val eDeviceKey2 = Crypto.createEcPrivateKey(EcCurve.P256)
        val transport2 = buildSingleMessageTransport(
            incomingMessage = { byteArrayOf() },
            initialState = MdocTransport.State.FAILED,
        )
        var callbackTelemetry2: PresentmentTelemetry? = null
        assertFailsWith<MdocTransportException> {
            Iso18013Presentment(
                transport = transport2,
                eDeviceKey = eDeviceKey2,
                deviceEngagement = deviceEngagement,
                handover = Simple.NULL,
                source = presentmentSource,
                keyAgreementPossible = listOf(EcCurve.P256),
                onTelemetry = { callbackTelemetry2 = it },
            )
        }
        assertNotNull(callbackTelemetry2)
        assertEquals(PresentmentOutcome.TRANSPORT_ERROR, callbackTelemetry2!!.outcome)
    }

    @Test
    fun testOutcomeOtherErrorOnMalformedRequest() = runTest {
        val eventLogger = RecordingEventLogger()
        val presentmentSource = createTestPresentmentSource(eventLogger = eventLogger)
        val eDeviceKey = Crypto.createEcPrivateKey(EcCurve.P256)
        val deviceEngagement = buildDeviceEngagement(eDeviceKey = eDeviceKey.publicKey) {}.toDataItem()
        val transport = buildSingleMessageTransport(
            incomingMessage = { byteArrayOf(0x01, 0x02, 0x03) }
        )

        var callbackTelemetry: PresentmentTelemetry? = null
        assertFailsWith<Exception> {
            Iso18013Presentment(
                transport = transport,
                eDeviceKey = eDeviceKey,
                deviceEngagement = deviceEngagement,
                handover = Simple.NULL,
                source = presentmentSource,
                keyAgreementPossible = listOf(EcCurve.P256),
                onTelemetry = { callbackTelemetry = it },
            )
        }

        assertNotNull(callbackTelemetry)
        assertEquals(PresentmentOutcome.OTHER_ERROR, callbackTelemetry!!.outcome)
        val loggedEvent = assertIs<EventPresentmentIso18013Proximity>(eventLogger.events.single())
        assertEquals(PresentmentOutcome.OTHER_ERROR, loggedEvent.telemetry?.outcome)
    }
}
