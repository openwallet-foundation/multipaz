/*
 * Copyright 2022 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.android.identity.android.mdoc.deviceretrieval

import android.os.ConditionVariable
import androidx.test.platform.app.InstrumentationRegistry
import com.android.identity.android.mdoc.engagement.QrEngagementHelper
import com.android.identity.android.mdoc.transport.DataTransport
import com.android.identity.android.mdoc.transport.DataTransportOptions
import com.android.identity.android.mdoc.transport.DataTransportTcp
import org.multipaz.securearea.AndroidKeystoreSecureArea
import org.multipaz.asn1.ASN1Integer
import org.multipaz.cbor.Bstr
import org.multipaz.cbor.Cbor.encode
import org.multipaz.cbor.CborArray
import org.multipaz.cbor.DataItem
import org.multipaz.cbor.Simple
import org.multipaz.cbor.Tagged
import org.multipaz.cbor.toDataItem
import org.multipaz.context.initializeApplication
import org.multipaz.cose.Cose
import org.multipaz.cose.Cose.coseSign1Sign
import org.multipaz.cose.CoseLabel
import org.multipaz.cose.CoseNumberLabel
import org.multipaz.document.Document
import org.multipaz.document.DocumentStore
import org.multipaz.document.NameSpacedData
import org.multipaz.crypto.Algorithm
import org.multipaz.crypto.Crypto.createEcPrivateKey
import org.multipaz.crypto.EcCurve
import org.multipaz.crypto.EcPrivateKey
import org.multipaz.crypto.EcPublicKey
import org.multipaz.crypto.X500Name
import org.multipaz.crypto.X509Cert
import org.multipaz.crypto.X509CertChain
import org.multipaz.crypto.X509KeyUsage
import org.multipaz.mdoc.credential.MdocCredential
import org.multipaz.mdoc.mso.MobileSecurityObjectGenerator
import org.multipaz.mdoc.mso.StaticAuthDataGenerator
import org.multipaz.mdoc.mso.StaticAuthDataParser
import org.multipaz.mdoc.request.DeviceRequestGenerator
import org.multipaz.mdoc.request.DeviceRequestParser
import org.multipaz.mdoc.request.DeviceRequestParser.DocRequest
import org.multipaz.mdoc.response.DeviceResponseGenerator
import org.multipaz.mdoc.response.DeviceResponseParser
import org.multipaz.mdoc.response.DocumentGenerator
import org.multipaz.mdoc.sessionencryption.SessionEncryption
import org.multipaz.mdoc.util.MdocUtil.calculateDigestsForNameSpace
import org.multipaz.mdoc.util.MdocUtil.generateDocumentRequest
import org.multipaz.mdoc.util.MdocUtil.generateIssuerNameSpaces
import org.multipaz.mdoc.util.MdocUtil.mergeIssuerNamesSpaces
import org.multipaz.mdoc.util.MdocUtil.stripIssuerNameSpaces
import org.multipaz.securearea.KeyLockedException
import org.multipaz.securearea.SecureAreaRepository
import org.multipaz.securearea.software.SoftwareCreateKeySettings
import org.multipaz.storage.Storage
import org.multipaz.storage.android.AndroidStorage
import org.multipaz.util.Constants
import kotlinx.coroutines.runBlocking
import kotlin.time.Clock.System.now
import kotlin.time.Instant
import kotlin.time.Instant.Companion.fromEpochMilliseconds
import org.junit.Assert
import org.junit.Before
import org.junit.Test
import org.multipaz.crypto.AsymmetricKey
import org.multipaz.document.buildDocumentStore
import org.multipaz.mdoc.role.MdocRole
import org.multipaz.securearea.UnlockReason
import java.util.Calendar
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import kotlin.random.Random
import kotlin.time.Duration.Companion.days

class DeviceRetrievalHelperTest {
    companion object {
        private const val CREDENTIAL_DOMAIN = "domain"

        private const val MDL_DOCTYPE = "org.iso.18013.5.1.mDL"
        private const val MDL_NAMESPACE = "org.iso.18013.5.1"
        private const val AAMVA_NAMESPACE = "org.aamva.18013.5.1"
    }

    private lateinit var storage: Storage
    private lateinit var secureAreaRepository: SecureAreaRepository
    private lateinit var document: Document
    private lateinit var nameSpacedData: NameSpacedData
    private lateinit var mdocCredential: MdocCredential
    private lateinit var timeSigned: Instant
    private lateinit var timeValidityBegin: Instant
    private lateinit var timeValidityEnd: Instant
    private lateinit var documentSignerKey: EcPrivateKey
    private lateinit var documentSignerCert: X509Cert
    private lateinit var documentStore: DocumentStore

    @Before
    fun setUp() = runBlocking {
        // Do NOT add BouncyCastle at setup time - we want to run tests against the normal AndroidOpenSSL JCA provider

        initializeApplication(InstrumentationRegistry.getInstrumentation().targetContext)

        storage = AndroidStorage(":memory:")
        secureAreaRepository = SecureAreaRepository.Builder()
            .add(AndroidKeystoreSecureArea.create(storage))
            .build()
        documentStore = buildDocumentStore(
            storage = storage,
            secureAreaRepository = secureAreaRepository
        ) {}
    }

    private suspend fun asyncSetup() {
        // Create the document...
        document = documentStore.createDocument()
        nameSpacedData = NameSpacedData.Builder()
            .putEntryString(MDL_NAMESPACE, "given_name", "Erika")
            .putEntryString(MDL_NAMESPACE, "family_name", "Mustermann")
            .putEntryBoolean(AAMVA_NAMESPACE, "real_id", true)
            .build()

        // Create a credential... make sure the credential used supports both
        // mdoc ECDSA and MAC authentication.
        val nowMillis = Calendar.getInstance().timeInMillis / 1000 * 1000
        timeSigned = fromEpochMilliseconds(nowMillis)
        timeValidityBegin = fromEpochMilliseconds(nowMillis + 3600 * 1000)
        timeValidityEnd = fromEpochMilliseconds(nowMillis + 10 * 86400 * 1000)
        val secureArea =
            secureAreaRepository.getImplementation(AndroidKeystoreSecureArea.IDENTIFIER)
        mdocCredential = MdocCredential.create(
            document,
            null,
            CREDENTIAL_DOMAIN,
            secureArea!!,
            MDL_DOCTYPE,
            SoftwareCreateKeySettings.Builder().build()
        )
        Assert.assertFalse(mdocCredential.isCertified)

        // Generate an MSO and issuer-signed data for this credential.
        val msoGenerator = MobileSecurityObjectGenerator(
            Algorithm.SHA256,
            MDL_DOCTYPE,
            mdocCredential.getAttestation().publicKey
        )
        msoGenerator.setValidityInfo(timeSigned, timeValidityBegin, timeValidityEnd, null)
        val issuerNameSpaces = generateIssuerNameSpaces(
            nameSpacedData,
            Random,
            16,
            null
        )
        for (nameSpaceName in issuerNameSpaces.keys) {
            val digests = calculateDigestsForNameSpace(
                nameSpaceName,
                issuerNameSpaces,
                Algorithm.SHA256
            )
            msoGenerator.addDigestIdsForNamespace(nameSpaceName, digests)
        }
        val validFrom = now()
        val validUntil = validFrom + 5.days
        documentSignerKey = createEcPrivateKey(EcCurve.P256)
        documentSignerCert = X509Cert.Builder(
            publicKey = documentSignerKey.publicKey,
            signingKey = AsymmetricKey.anonymous(documentSignerKey, documentSignerKey.curve.defaultSigningAlgorithm),
            serialNumber = ASN1Integer(1L),
            subject = X500Name.fromName("CN=State of Utopia"),
            issuer = X500Name.fromName("CN=State of Utopia"),
            validFrom = validFrom,
            validUntil = validUntil
        )
            .includeSubjectKeyIdentifier()
            .setKeyUsage(setOf(X509KeyUsage.DIGITAL_SIGNATURE))
            .build()
        val mso = msoGenerator.generate()
        val taggedEncodedMso = encode(Tagged(24, Bstr(mso)))

        // IssuerAuth is a COSE_Sign1 where payload is MobileSecurityObjectBytes
        //
        // MobileSecurityObjectBytes = #6.24(bstr .cbor MobileSecurityObject)
        //
        val protectedHeaders = mapOf<CoseLabel, DataItem>(
            CoseNumberLabel(Cose.COSE_LABEL_ALG) to
                    Algorithm.ES256.coseAlgorithmIdentifier!!.toDataItem()
        )
        val unprotectedHeaders = mapOf<CoseLabel, DataItem>(
            CoseNumberLabel(Cose.COSE_LABEL_X5CHAIN) to
                    X509CertChain(listOf(documentSignerCert)).toDataItem()
        )
        val encodedIssuerAuth = encode(
            coseSign1Sign(
                documentSignerKey,
                taggedEncodedMso,
                true,
                Algorithm.ES256,
                protectedHeaders,
                unprotectedHeaders
            ).toDataItem()
        )
        val issuerProvidedAuthenticationData = StaticAuthDataGenerator(
            stripIssuerNameSpaces(issuerNameSpaces, null),
            encodedIssuerAuth
        ).generate()

        // Now that we have issuer-provided authentication data we certify the credential.
        mdocCredential.certify(
            issuerProvidedAuthenticationData,
            timeValidityBegin,
            timeValidityEnd
        )
    }
    
    @Test
    fun testPresentation() = runBlocking {
        asyncSetup()
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val condVarDeviceConnected = ConditionVariable()
        val condVarDeviceDisconnected = ConditionVariable()

        // TODO: use loopback instead of TCP transport
        val proverTransport = DataTransportTcp(
            context,
            DataTransport.Role.MDOC,
            DataTransportOptions.Builder().build()
        )
        val verifierTransport = DataTransportTcp(
            context,
            DataTransport.Role.MDOC_READER,
            DataTransportOptions.Builder().build()
        )
        val executor: Executor = Executors.newSingleThreadExecutor()
        val qrHelperListener: QrEngagementHelper.Listener = object : QrEngagementHelper.Listener {
            override fun onDeviceConnecting() {}
            override fun onDeviceConnected(transport: DataTransport) {
                condVarDeviceConnected.open()
                Assert.assertEquals(proverTransport, transport)
            }

            override fun onError(error: Throwable) {
                throw AssertionError(error)
            }
        }
        val eDeviceKey = createEcPrivateKey(EcCurve.P256)
        val qrHelper = QrEngagementHelper.Builder(
            context,
            eDeviceKey.publicKey,
            DataTransportOptions.Builder().build(),
            qrHelperListener,
            executor
        )
            .setTransports(java.util.List.of(proverTransport))
            .build()
        val encodedDeviceEngagement = qrHelper.deviceEngagement
        val eReaderKey = createEcPrivateKey(EcCurve.P256)
        val encodedEReaderKeyPub =
            encode(eReaderKey.publicKey.toCoseKey(java.util.Map.of()).toDataItem())
        val encodedSessionTranscript = encode(
            CborArray.builder()
                .addTaggedEncodedCbor(encodedDeviceEngagement)
                .addTaggedEncodedCbor(encodedEReaderKeyPub)
                .add(Simple.NULL)
                .end()
                .build()
        )
        val seReader = SessionEncryption(
            MdocRole.MDOC_READER,
            eReaderKey,
            eDeviceKey.publicKey,
            encodedSessionTranscript
        )
        val mdlItemsToRequest: MutableMap<String, Map<String, Boolean>> = HashMap()
        val mdlNsItems: MutableMap<String, Boolean> = HashMap()
        mdlNsItems["family_name"] = true
        mdlNsItems["given_name"] = false
        mdlItemsToRequest[MDL_NAMESPACE] = mdlNsItems
        val aamvaNsItems: MutableMap<String, Boolean> = HashMap()
        aamvaNsItems["real_id"] = false
        mdlItemsToRequest[AAMVA_NAMESPACE] = aamvaNsItems
        val encodedDeviceRequest = DeviceRequestGenerator(encodedSessionTranscript)
            .addDocumentRequest(
                MDL_DOCTYPE,
                mdlItemsToRequest,
                null,
                null,
                Algorithm.UNSET,
                null
            )
            .generate()
        val sessionEstablishment = seReader.encryptMessage(encodedDeviceRequest, null)
        verifierTransport.setListener(object : DataTransport.Listener {
            override fun onConnecting() {}
            override fun onConnected() {}
            override fun onDisconnected() {}
            override fun onTransportSpecificSessionTermination() {
                Assert.fail()
            }

            override fun onError(error: Throwable) {
                throw AssertionError(error)
            }

            override fun onMessageReceived() {
                val data = verifierTransport.getMessage()
                val (first, second) = seReader.decryptMessage(
                    data!!
                )
                Assert.assertNull(second)
                val dr = runBlocking {
                    DeviceResponseParser(
                        first!!,
                        encodedSessionTranscript
                    )
                    .setEphemeralReaderKey(AsymmetricKey.anonymous(
                        privateKey = eReaderKey,
                        algorithm = eReaderKey.curve.defaultKeyAgreementAlgorithm
                    ))
                    .parse()
                }
                Assert.assertEquals(Constants.DEVICE_RESPONSE_STATUS_OK, dr.status)
                Assert.assertEquals("1.0", dr.version)
                val documents: Collection<DeviceResponseParser.Document> = dr.documents
                Assert.assertEquals(1, documents.size.toLong())
                val d = documents.iterator().next()
                Assert.assertEquals(MDL_DOCTYPE, d.docType)
                Assert.assertEquals(0, d.deviceNamespaces.size.toLong())
                Assert.assertEquals(2, d.issuerNamespaces.size.toLong())
                Assert.assertEquals(2, d.getIssuerEntryNames(MDL_NAMESPACE).size.toLong())
                Assert.assertEquals(
                    "Erika",
                    d.getIssuerEntryString(MDL_NAMESPACE, "given_name")
                )
                Assert.assertEquals(
                    "Mustermann",
                    d.getIssuerEntryString(MDL_NAMESPACE, "family_name")
                )
                Assert.assertEquals(1, d.getIssuerEntryNames(AAMVA_NAMESPACE).size.toLong())
                Assert.assertTrue(d.getIssuerEntryBoolean(AAMVA_NAMESPACE, "real_id"))

                // Send a close message (status 20 is "session termination")
                verifierTransport.sendMessage(
                    seReader.encryptMessage(
                        null,
                        Constants.SESSION_DATA_STATUS_SESSION_TERMINATION
                    )
                )
            }
        }, executor)
        verifierTransport.setHostAndPort(proverTransport.host, proverTransport.port)
        verifierTransport.connect()
        Assert.assertTrue(condVarDeviceConnected.block(5000))
        val presentation = arrayOf<DeviceRetrievalHelper?>(null)
        val listener: DeviceRetrievalHelper.Listener = object : DeviceRetrievalHelper.Listener {
            override fun onEReaderKeyReceived(eReaderKey: EcPublicKey) {}
            override fun onDeviceRequest(deviceRequestBytes: ByteArray) = runBlocking {
                val parser = DeviceRequestParser(
                    deviceRequestBytes,
                    presentation[0]!!.sessionTranscript
                )
                val deviceRequest = parser.parse()
                val docRequests: Collection<DocRequest> = deviceRequest.docRequests
                Assert.assertEquals(1, docRequests.size.toLong())
                val request = docRequests.iterator().next()
                Assert.assertEquals(MDL_DOCTYPE, request.docType)
                Assert.assertEquals(2, request.namespaces.size.toLong())
                Assert.assertTrue(request.namespaces.contains(MDL_NAMESPACE))
                Assert.assertTrue(request.namespaces.contains(AAMVA_NAMESPACE))
                Assert.assertEquals(1, request.getEntryNames(AAMVA_NAMESPACE).size.toLong())
                Assert.assertFalse(request.getIntentToRetain(AAMVA_NAMESPACE, "real_id"))
                Assert.assertEquals(2, request.getEntryNames(MDL_NAMESPACE).size.toLong())
                Assert.assertTrue(request.getIntentToRetain(MDL_NAMESPACE, "family_name"))
                Assert.assertFalse(request.getIntentToRetain(MDL_NAMESPACE, "given_name"))
                try {
                    val generator = DeviceResponseGenerator(
                        Constants.DEVICE_RESPONSE_STATUS_OK
                    )
                    val staticAuthData = StaticAuthDataParser(mdocCredential.issuerProvidedData)
                        .parse()
                    val deviceSignedData = NameSpacedData.Builder().build()
                    val mergedIssuerNamespaces: Map<String, List<ByteArray>> =
                        mergeIssuerNamesSpaces(
                            generateDocumentRequest(request),
                            nameSpacedData,
                            staticAuthData
                        )
                    generator.addDocument(
                        DocumentGenerator(
                            MDL_DOCTYPE,
                            staticAuthData.issuerAuth,
                            encodedSessionTranscript
                        )
                            .setIssuerNamespaces(mergedIssuerNamespaces)
                            .setDeviceNamespacesSignature(
                                deviceSignedData,
                                secureArea = mdocCredential.secureArea,
                                keyAlias = mdocCredential.alias,
                                unlockReason = UnlockReason.Unspecified
                            )
                            .generate()
                    )

                    presentation[0]!!.sendDeviceResponse(generator.generate(), null)
                } catch (e: KeyLockedException) {
                    throw AssertionError(e)
                }
            }

            override fun onDeviceDisconnected(transportSpecificTermination: Boolean) {
                Assert.assertFalse(transportSpecificTermination)
                condVarDeviceDisconnected.open()
            }

            override fun onError(error: Throwable) {
                throw AssertionError(error)
            }
        }
        presentation[0] = DeviceRetrievalHelper.Builder(
            context,
            listener,
            context.mainExecutor,
            eDeviceKey
        )
            .useForwardEngagement(
                proverTransport,
                qrHelper.deviceEngagement,
                qrHelper.handover
            )
            .build()
        verifierTransport.sendMessage(sessionEstablishment)
        Assert.assertTrue(condVarDeviceDisconnected.block(5000))
    }

    @Test
    @Throws(Exception::class)
    fun testPresentationVerifierDisconnects() = runBlocking {
        asyncSetup()
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val executor: Executor = Executors.newSingleThreadExecutor()
        val condVarDeviceConnected = ConditionVariable()
        val condVarDeviceRequestReceived = ConditionVariable()
        val condVarOnError = ConditionVariable()

        // TODO: use loopback transport
        val proverTransport = DataTransportTcp(
            context,
            DataTransport.Role.MDOC,
            DataTransportOptions.Builder().build()
        )
        val verifierTransport = DataTransportTcp(
            context,
            DataTransport.Role.MDOC_READER,
            DataTransportOptions.Builder().build()
        )
        val qrHelperListener: QrEngagementHelper.Listener = object : QrEngagementHelper.Listener {
            override fun onDeviceConnecting() {}
            override fun onDeviceConnected(transport: DataTransport) {
                condVarDeviceConnected.open()
                Assert.assertEquals(proverTransport, transport)
            }

            override fun onError(error: Throwable) {
                throw AssertionError(error)
            }
        }
        val eDeviceKey = createEcPrivateKey(EcCurve.P256)
        val qrHelper = QrEngagementHelper.Builder(
            context,
            eDeviceKey.publicKey,
            DataTransportOptions.Builder().build(),
            qrHelperListener,
            executor
        )
            .setTransports(java.util.List.of(proverTransport))
            .build()
        val encodedDeviceEngagement = qrHelper.deviceEngagement
        val eReaderKey = createEcPrivateKey(EcCurve.P256)
        val encodedEReaderKeyPub =
            encode(eReaderKey.publicKey.toCoseKey(java.util.Map.of()).toDataItem())
        val encodedSessionTranscript = encode(
            CborArray.builder()
                .addTaggedEncodedCbor(encodedDeviceEngagement)
                .addTaggedEncodedCbor(encodedEReaderKeyPub)
                .add(Simple.NULL)
                .end()
                .build()
        )
        val seReader = SessionEncryption(
            MdocRole.MDOC_READER,
            eReaderKey,
            eDeviceKey.publicKey,
            encodedSessionTranscript
        )

        // Just make an empty request since the verifier will disconnect immediately anyway.
        val mdlItemsToRequest: Map<String, Map<String, Boolean>> = HashMap()
        val encodedDeviceRequest = DeviceRequestGenerator(encodedSessionTranscript)
            .addDocumentRequest(
                MDL_DOCTYPE,
                mdlItemsToRequest,
                null,
                null,
                Algorithm.UNSET,
                null
            )
            .generate()
        val sessionEstablishment = seReader.encryptMessage(encodedDeviceRequest, null)
        verifierTransport.setListener(object : DataTransport.Listener {
            override fun onConnecting() {}
            override fun onConnected() {}
            override fun onDisconnected() {}
            override fun onTransportSpecificSessionTermination() {
                Assert.fail()
            }

            override fun onError(error: Throwable) {
                throw AssertionError(error)
            }

            override fun onMessageReceived() {
                Assert.fail()
            }
        }, executor)
        verifierTransport.setHostAndPort(proverTransport.host, proverTransport.port)
        verifierTransport.connect()
        Assert.assertTrue(condVarDeviceConnected.block(5000))
        val listener: DeviceRetrievalHelper.Listener = object : DeviceRetrievalHelper.Listener {
            override fun onEReaderKeyReceived(eReaderKey: EcPublicKey) {}
            override fun onDeviceRequest(deviceRequestBytes: ByteArray) {
                // Don't respond yet.. simulate the holder taking infinity to respond.
                // instead, we'll simply wait for the verifier to disconnect instead.
                condVarDeviceRequestReceived.open()
            }

            override fun onDeviceDisconnected(transportSpecificTermination: Boolean) {
                Assert.fail()
            }

            override fun onError(error: Throwable) {
                condVarOnError.open()
            }
        }
        DeviceRetrievalHelper.Builder(
            context,
            listener,
            context.mainExecutor,
            eDeviceKey
        )
            .useForwardEngagement(
                proverTransport,
                qrHelper.deviceEngagement,
                qrHelper.handover
            )
            .build()
        verifierTransport.sendMessage(sessionEstablishment)
        Assert.assertTrue(condVarDeviceRequestReceived.block(5000))
        verifierTransport.close()
        Assert.assertTrue(condVarOnError.block(5000))
    }
}