package org.multipaz.mdoc.engagement

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atStartOfDayIn
import kotlinx.io.bytestring.ByteString
import org.multipaz.cbor.Bstr
import org.multipaz.cbor.Cbor
import org.multipaz.cbor.DiagnosticOption
import org.multipaz.cbor.Simple
import org.multipaz.cbor.Tagged
import org.multipaz.cbor.Tstr
import org.multipaz.cbor.buildCborArray
import org.multipaz.cbor.buildCborMap
import org.multipaz.cbor.putCborMap
import org.multipaz.cose.CoseSign1
import org.multipaz.credential.Credential
import org.multipaz.crypto.Algorithm
import org.multipaz.crypto.AsymmetricKey
import org.multipaz.crypto.Crypto
import org.multipaz.crypto.EcCurve
import org.multipaz.crypto.EcPrivateKey
import org.multipaz.crypto.EcPrivateKeyDoubleCoordinate
import org.multipaz.crypto.EcPublicKey
import org.multipaz.document.Document
import org.multipaz.document.DocumentBadge
import org.multipaz.document.DocumentStore
import org.multipaz.document.buildDocumentStore
import org.multipaz.documenttype.DocumentTypeRepository
import org.multipaz.documenttype.knowntypes.DrivingLicense
import org.multipaz.mdoc.TestVectors
import org.multipaz.mdoc.connectionmethod.BleAddressType
import org.multipaz.mdoc.connectionmethod.BlePeripheralAddress
import org.multipaz.mdoc.connectionmethod.MdocConnectionMethod
import org.multipaz.mdoc.connectionmethod.MdocConnectionMethodBle
import org.multipaz.mdoc.connectionmethod.MdocEngagementType
import org.multipaz.mdoc.credential.MdocCredential
import org.multipaz.mdoc.devicesigned.DeviceNamespaces
import org.multipaz.mdoc.issuersigned.IssuerNamespaces
import org.multipaz.mdoc.request.DeviceRequest
import org.multipaz.mdoc.request.buildDeviceRequest
import org.multipaz.mdoc.response.DeviceResponse
import org.multipaz.mdoc.response.buildDeviceResponse
import org.multipaz.mdoc.role.MdocRole
import org.multipaz.mdoc.sessionencryption.SessionEncryption
import org.multipaz.mdoc.transport.MdocTransport
import org.multipaz.mdoc.transport.MdocTransportClosedException
import org.multipaz.presentment.EngagementParams
import org.multipaz.presentment.FaceMatchingMode
import org.multipaz.presentment.Iso18013Presentment
import org.multipaz.presentment.PresentmentData
import org.multipaz.presentment.PresentmentSelection
import org.multipaz.presentment.PresentmentSource
import org.multipaz.prompt.promptModelSilentConsent
import org.multipaz.request.RequestedClaim
import org.multipaz.request.Requester
import org.multipaz.request.RequesterIdentity
import org.multipaz.request.TrustedRequesterIdentity
import org.multipaz.securearea.SecureAreaRepository
import org.multipaz.securearea.software.SoftwareCreateKeySettings
import org.multipaz.securearea.software.SoftwareSecureArea
import org.multipaz.storage.ephemeral.EphemeralStorage
import org.multipaz.testUtilSetupCryptoProvider
import org.multipaz.util.Constants
import org.multipaz.util.UUID
import org.multipaz.util.fromBase64Url
import org.multipaz.util.fromHex
import org.multipaz.util.toBase64Url
import org.multipaz.util.toHex
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Deterministic golden transcript tests for ISO/IEC 18013-5 Second Edition Device QR
 * (`T-DQR-1`..`T-DQR-4`) and Reader QR (`T-RQR-1`..`T-RQR-4`) BLE engagement.
 */
class MdocQrBleTranscriptTest {

    @BeforeTest
    fun setup() = testUtilSetupCryptoProvider()

    private fun getAnnexDEDeviceKey(): EcPrivateKey = EcPrivateKeyDoubleCoordinate(
        curve = EcCurve.P256,
        d = TestVectors.ISO_18013_5_ANNEX_D_EPHEMERAL_DEVICE_KEY_D.fromHex(),
        x = TestVectors.ISO_18013_5_ANNEX_D_EPHEMERAL_DEVICE_KEY_X.fromHex(),
        y = TestVectors.ISO_18013_5_ANNEX_D_EPHEMERAL_DEVICE_KEY_Y.fromHex(),
    )

    private fun getAnnexDEReaderKey(): EcPrivateKey = EcPrivateKeyDoubleCoordinate(
        curve = EcCurve.P256,
        d = TestVectors.ISO_18013_5_ANNEX_D_EPHEMERAL_READER_KEY_D.fromHex(),
        x = TestVectors.ISO_18013_5_ANNEX_D_EPHEMERAL_READER_KEY_X.fromHex(),
        y = TestVectors.ISO_18013_5_ANNEX_D_EPHEMERAL_READER_KEY_Y.fromHex(),
    )

    private fun getAnnexDStaticDeviceKey(): EcPrivateKey = EcPrivateKeyDoubleCoordinate(
        curve = EcCurve.P256,
        d = TestVectors.ISO_18013_5_ANNEX_D_STATIC_DEVICE_KEY_D.fromHex(),
        x = TestVectors.ISO_18013_5_ANNEX_D_STATIC_DEVICE_KEY_X.fromHex(),
        y = TestVectors.ISO_18013_5_ANNEX_D_STATIC_DEVICE_KEY_Y.fromHex(),
    )

    private class StaticTestPresentmentSource(
        documentStore: DocumentStore,
        documentTypeRepository: DocumentTypeRepository,
    ) : PresentmentSource(
        documentStore = documentStore,
        documentTypeRepository = documentTypeRepository,
    ) {
        override suspend fun resolveTrust(requester: Requester): TrustedRequesterIdentity? = null

        override suspend fun showConsentPrompt(
            requester: Requester,
            trustedRequesterIdentity: TrustedRequesterIdentity?,
            presentmentData: PresentmentData,
            preselectedDocuments: List<Document>,
            onDocumentsInFocus: (documents: List<Document>) -> Unit
        ): PresentmentSelection? = promptModelSilentConsent(
            requester = requester,
            trustedRequesterIdentity = trustedRequesterIdentity,
            presentmentData = presentmentData,
            preselectedDocuments = preselectedDocuments,
            onDocumentsInFocus = onDocumentsInFocus,
        )

        override suspend fun selectCredential(
            document: Document,
            requestedClaims: List<RequestedClaim>,
            keyAgreementPossible: List<EcCurve>,
            credential: Credential?
        ): Credential? = document.getCertifiedCredentials().firstOrNull()

        override suspend fun getBadges(document: Document): List<DocumentBadge> = emptyList()

        override suspend fun getFaceMatchingMode(
            credential: Credential,
            requesterIdentities: List<RequesterIdentity>
        ): FaceMatchingMode = FaceMatchingMode.NEVER
    }

    private suspend fun createAnnexDCredentialAndSource(): Pair<MdocCredential, PresentmentSource> {
        val storage = EphemeralStorage()
        val secureArea = SoftwareSecureArea.create(storage)
        val secureAreaRepository = SecureAreaRepository.Builder().add(secureArea).build()
        val documentTypeRepository = DocumentTypeRepository().apply {
            addDocumentType(DrivingLicense.getDocumentType())
        }
        val documentStore = buildDocumentStore(
            storage = storage,
            secureAreaRepository = secureAreaRepository
        ) {}
        val document = documentStore.createDocument(
            displayName = "Annex D mDL",
            typeDisplayName = "Driving License"
        )
        val credential = MdocCredential.create(
            document = document,
            asReplacementForIdentifier = null,
            domain = "mdoc_key_agreement",
            secureArea = secureArea,
            docType = DrivingLicense.MDL_DOCTYPE,
            createKeySettings = SoftwareCreateKeySettings.Builder()
                .setAlgorithm(Algorithm.ECDH_P256)
                .setPrivateKey(getAnnexDStaticDeviceKey())
                .build()
        )
        val annexDResponseItem = Cbor.decode(TestVectors.ISO_18013_5_ANNEX_D_DEVICE_RESPONSE.fromHex())
        val annexDIssuerSigned = annexDResponseItem["documents"].asArray.first()["issuerSigned"]
        val annexDMdlItems = annexDIssuerSigned["nameSpaces"][DrivingLicense.MDL_NAMESPACE].asArray
        val mandatoryItems = annexDMdlItems.filter { taggedItem ->
            val elementId = taggedItem.asTaggedEncodedCbor["elementIdentifier"].asTstr
            elementId in setOf("family_name", "issue_date", "expiry_date", "document_number")
        }
        val issuerSigned = buildCborMap {
            putCborMap("nameSpaces") {
                put(DrivingLicense.MDL_NAMESPACE, buildCborArray {
                    mandatoryItems.forEach { add(it) }
                })
            }
            put("issuerAuth", annexDIssuerSigned["issuerAuth"])
        }
        credential.certify(ByteString(Cbor.encode(issuerSigned)))
        return Pair(
            credential,
            StaticTestPresentmentSource(
                documentStore = documentStore,
                documentTypeRepository = documentTypeRepository,
            )
        )
    }

    private class LoopbackQrBlePair(
        private val cm: MdocConnectionMethodBle,
    ) {
        private val readerToMdoc = Channel<ByteArray>(Channel.UNLIMITED)
        private val mdocToReader = Channel<ByteArray>(Channel.UNLIMITED)

        val mdocTransport: MdocTransport = Endpoint(MdocRole.MDOC)
        val readerTransport: MdocTransport = Endpoint(MdocRole.MDOC_READER)

        private inner class Endpoint(
            override val role: MdocRole
        ) : MdocTransport() {
            private val _state = MutableStateFlow(State.IDLE)
            override val state: StateFlow<State> = _state.asStateFlow()
            override val connectionMethod: MdocConnectionMethod = cm
            override val scanningTime: Duration? = null

            override suspend fun advertise() {
                _state.value = State.ADVERTISING
            }

            override suspend fun open(eSenderKey: EcPublicKey) {
                _state.value = State.CONNECTED
            }

            override suspend fun sendMessage(message: ByteArray) {
                if (role == MdocRole.MDOC_READER) {
                    readerToMdoc.trySend(message)
                } else {
                    mdocToReader.trySend(message)
                }
            }

            override suspend fun waitForMessage(): ByteArray {
                val ch = if (role == MdocRole.MDOC_READER) mdocToReader else readerToMdoc
                return try {
                    ch.receive()
                } catch (e: Throwable) {
                    throw MdocTransportClosedException("Closed", e)
                }
            }

            override suspend fun close() {
                _state.value = State.CLOSED
                if (role == MdocRole.MDOC_READER) {
                    mdocToReader.close()
                } else {
                    readerToMdoc.close()
                }
            }
        }
    }

    private suspend fun verifyDeviceQrEndToEndSession(
        qrUri: String,
        selectedMethodSelector: (List<MdocConnectionMethod>) -> MdocConnectionMethodBle = {
            it.first() as MdocConnectionMethodBle
        },
    ) = withContext(Dispatchers.Default) {
        assertTrue(qrUri.startsWith("mdoc:"))
        val encodedDeviceEngagement = qrUri.removePrefix("mdoc:").fromBase64Url()
        val decodedDeviceEngagementItem = Cbor.decode(encodedDeviceEngagement)
        val parsedDeviceEngagement = DeviceEngagement.fromDataItem(decodedDeviceEngagementItem)
        val disambiguated = MdocConnectionMethod.disambiguate(
            parsedDeviceEngagement.connectionMethods,
            MdocRole.MDOC_READER
        )
        val selectedMethod = selectedMethodSelector(disambiguated)
        val blePair = LoopbackQrBlePair(selectedMethod)

        val eDeviceKey = getAnnexDEDeviceKey()
        val eReaderKey = getAnnexDEReaderKey()
        val (_, presentmentSource) = createAnnexDCredentialAndSource()
        val presentmentDone = CompletableDeferred<Unit>()

        val params = EngagementParams(
            eDeviceKey = eDeviceKey,
            deviceEngagement = decodedDeviceEngagementItem,
            handover = Simple.NULL,
            eReaderKey = null,
        )
        val engagementParamsFlow = MutableStateFlow(params)

        launch(start = CoroutineStart.UNDISPATCHED) {
            blePair.mdocTransport.open(eDeviceKey.publicKey)
            Iso18013Presentment(
                transport = blePair.mdocTransport,
                engagementParams = engagementParamsFlow,
                source = presentmentSource,
                keyAgreementPossible = listOf(EcCurve.P256),
                insertSequenceNumbers = false,
                timeout = null,
                timeoutSubsequentRequests = null,
            )
            presentmentDone.complete(Unit)
        }

        blePair.readerTransport.open(parsedDeviceEngagement.eDeviceKey)
        val encodedEReaderKey = Cbor.encode(eReaderKey.publicKey.toCoseKey().toDataItem())
        val sessionTranscript = buildCborArray {
            add(Tagged(Tagged.ENCODED_CBOR, Bstr(encodedDeviceEngagement)))
            add(Tagged(Tagged.ENCODED_CBOR, Bstr(encodedEReaderKey)))
            add(Simple.NULL)
        }
        val readerEncryption = SessionEncryption(
            role = MdocRole.MDOC_READER,
            eSelfKey = eReaderKey,
            remotePublicKey = parsedDeviceEngagement.eDeviceKey,
            encodedSessionTranscript = Cbor.encode(sessionTranscript),
        )
        val deviceRequest = buildDeviceRequest(sessionTranscript = sessionTranscript) {
            addDocRequest(
                docType = DrivingLicense.MDL_DOCTYPE,
                nameSpaces = mapOf(DrivingLicense.MDL_NAMESPACE to mapOf("family_name" to false))
            )
        }
        blePair.readerTransport.sendMessage(
            readerEncryption.encryptMessage(
                messagePlaintext = Cbor.encode(deviceRequest.toDataItem()),
                statusCode = null,
            )
        )
        val (encodedResponse, status) = readerEncryption.decryptMessage(blePair.readerTransport.waitForMessage())
        assertNull(status)
        assertNotNull(encodedResponse)
        val deviceResponse = DeviceResponse.fromDataItem(Cbor.decode(encodedResponse))
        deviceResponse.verify(
            sessionTranscript = sessionTranscript,
            eReaderKey = AsymmetricKey.AnonymousExplicit(eReaderKey, Algorithm.ECDH_P256),
            atTime = LocalDate.parse("2021-01-01").atStartOfDayIn(TimeZone.UTC),
        )
        assertEquals(1, deviceResponse.documents.size)

        blePair.readerTransport.sendMessage(
            readerEncryption.encryptMessage(
                messagePlaintext = null,
                statusCode = Constants.SESSION_DATA_STATUS_SESSION_TERMINATION,
            )
        )
        withTimeout(5.seconds) {
            presentmentDone.await()
        }
        blePair.readerTransport.close()
        blePair.mdocTransport.close()
    }

    /**
     * Verifies ISO/IEC 18013-5 Second Edition Clauses 9.3, 9.7, 12.2.4, and 12.7.1 Reader QR
     * session transcript and `SessionMessage` framing:
     * - Mdoc parses `mdoc:<base64url(ReaderEngagement)>`, disambiguates connection methods for
     *   [MdocRole.MDOC], and sends the first `SessionMessage` containing `"deviceEngagementBytes"`
     *   (where `DeviceEngagement` omits key `2` `DeviceRetrievalMethods`).
     * - Both sides derive session keys from
     *   `SessionTranscript = [24(<< DeviceEngagement >>), 24(<< EReaderKey >>), sha256(24(<< ReaderEngagement >>))]`.
     * - Reader sends `{"data": <SKReader-encrypted DeviceRequest>}` (without `"eReaderKey"` since it
     *   was already conveyed in `ReaderEngagement`), and mdoc replies with
     *   `{"data": <SKDevice-encrypted DeviceResponse>}`.
     */
    private suspend fun verifyReaderQrEndToEndSession(
        readerQrUri: String,
    ): List<MdocConnectionMethod> {
        assertTrue(readerQrUri.startsWith("mdoc:"))
        val encodedReaderEngagement = readerQrUri.removePrefix("mdoc:").fromBase64Url()
        val parsedReaderEngagement = ReaderEngagement.fromDataItem(Cbor.decode(encodedReaderEngagement))
        val eReaderKeyPublic = checkNotNull(parsedReaderEngagement.eReaderKey)
        val disambiguated = MdocConnectionMethod.disambiguate(
            parsedReaderEngagement.connectionMethods,
            MdocRole.MDOC
        )

        val eDeviceKey = getAnnexDEDeviceKey()
        val eReaderKey = getAnnexDEReaderKey()
        assertEquals(eReaderKey.publicKey, eReaderKeyPublic)

        // Mdoc builds DeviceEngagement omitting key 2 (DeviceRetrievalMethods)
        val mdocDeviceEngagementItem = buildDeviceEngagement(eDeviceKey = eDeviceKey.publicKey) {
            addCapability(Capability.READER_AUTH_ALL_SUPPORT, Simple.TRUE)
            addCapability(Capability.EXTENDED_REQUEST_SUPPORT, Simple.TRUE)
        }.toDataItem(engagementType = MdocEngagementType.READER_QR)
        assertFalse(mdocDeviceEngagementItem.hasKey(2))
        val encodedMdocDeviceEngagement = Cbor.encode(mdocDeviceEngagementItem)

        val expectedFirstSessionMessageDiag = """
            {
              "deviceEngagementBytes": 24(<< {
                0: "1.1",
                1: [
                  1,
                  24(<< {
                    1: 2,
                    -1: 1,
                    -2: h'5a88d182bce5f42efa59943f33359d2e8a968ff289d93e5fa444b624343167fe',
                    -3: h'b16e8cf858ddc7690407ba61d4c338237a8cfcf3de6aa672fc60a557aa32fc67'
                  } >>)
                ],
                5: [],
                6: {
                  3: true,
                  4: true
                }
              } >>)
            }
        """.trimIndent()
        val firstSessionMessage = Cbor.encode(
            buildCborMap {
                put("deviceEngagementBytes", Tagged(Tagged.ENCODED_CBOR, Bstr(encodedMdocDeviceEngagement)))
            }
        )
        assertEquals(
            expectedFirstSessionMessageDiag,
            Cbor.toDiagnostics(firstSessionMessage, setOf(DiagnosticOption.PRETTY_PRINT, DiagnosticOption.EMBEDDED_CBOR))
        )

        // Reader receives firstSessionMessage, extracts DeviceEngagement, and computes SessionTranscript
        val receivedFirstMessage = Cbor.decode(firstSessionMessage)
        val receivedDeviceEngagementBytes = receivedFirstMessage["deviceEngagementBytes"].asTagged.asBstr
        val receivedDeviceEngagement = DeviceEngagement.fromDataItem(Cbor.decode(receivedDeviceEngagementBytes))

        val readerEngagementBytesTag = Cbor.encode(Tagged(Tagged.ENCODED_CBOR, Bstr(encodedReaderEngagement)))
        val readerEngagementHash = Crypto.digest(Algorithm.SHA256, readerEngagementBytesTag)
        val encodedEReaderKey = Cbor.encode(eReaderKeyPublic.toCoseKey().toDataItem())

        val sessionTranscript = buildCborArray {
            add(Tagged(Tagged.ENCODED_CBOR, Bstr(receivedDeviceEngagementBytes)))
            add(Tagged(Tagged.ENCODED_CBOR, Bstr(encodedEReaderKey)))
            add(Bstr(readerEngagementHash))
        }
        val encodedSessionTranscript = Cbor.encode(sessionTranscript)

        val readerEncryption = SessionEncryption(
            role = MdocRole.MDOC_READER,
            eSelfKey = eReaderKey,
            remotePublicKey = receivedDeviceEngagement.eDeviceKey,
            encodedSessionTranscript = encodedSessionTranscript,
        ).apply {
            setSendSessionEstablishment(false)
        }
        val mdocEncryption = SessionEncryption(
            role = MdocRole.MDOC,
            eSelfKey = eDeviceKey,
            remotePublicKey = eReaderKeyPublic,
            encodedSessionTranscript = encodedSessionTranscript,
        )

        val deviceRequest = buildDeviceRequest(sessionTranscript = sessionTranscript) {
            addDocRequest(
                docType = DrivingLicense.MDL_DOCTYPE,
                nameSpaces = mapOf(DrivingLicense.MDL_NAMESPACE to mapOf("family_name" to false))
            )
        }
        val secondSessionMessage = readerEncryption.encryptMessage(
            messagePlaintext = Cbor.encode(deviceRequest.toDataItem()),
            statusCode = null,
        )
        val decodedSecondMsg = Cbor.decode(secondSessionMessage)
        assertFalse(decodedSecondMsg.hasKey("eReaderKey"))
        assertTrue(decodedSecondMsg.hasKey(Tstr("data")))

        val (decryptedReqBytes, reqStatus) = mdocEncryption.decryptMessage(secondSessionMessage)
        assertNull(reqStatus)
        assertNotNull(decryptedReqBytes)
        val parsedReq = DeviceRequest.fromDataItem(Cbor.decode(decryptedReqBytes))
        assertEquals(DrivingLicense.MDL_DOCTYPE, parsedReq.docRequests.single().docType)

        val (credential, _) = createAnnexDCredentialAndSource()
        val issuerSigned = Cbor.decode(credential.issuerProvidedData.toByteArray())
        val issuerNamespaces = IssuerNamespaces.fromDataItem(issuerSigned["nameSpaces"])
        val issuerAuth = CoseSign1.fromDataItem(issuerSigned["issuerAuth"])
        val mdocResponse = buildDeviceResponse(
            sessionTranscript = sessionTranscript,
            status = DeviceResponse.STATUS_OK,
            eReaderKey = eReaderKeyPublic,
        ) {
            addDocument(
                docType = DrivingLicense.MDL_DOCTYPE,
                issuerAuth = issuerAuth,
                issuerNamespaces = issuerNamespaces,
                deviceNamespaces = DeviceNamespaces(emptyMap()),
                deviceKey = AsymmetricKey.AnonymousExplicit(getAnnexDStaticDeviceKey(), Algorithm.ECDH_P256),
            )
        }
        val thirdSessionMessage = mdocEncryption.encryptMessage(
            messagePlaintext = Cbor.encode(mdocResponse.toDataItem()),
            statusCode = null,
        )
        val (decryptedRespBytes, respStatus) = readerEncryption.decryptMessage(thirdSessionMessage)
        assertNull(respStatus)
        assertNotNull(decryptedRespBytes)
        val verifiedResp = DeviceResponse.fromDataItem(Cbor.decode(decryptedRespBytes))
        verifiedResp.verify(
            sessionTranscript = sessionTranscript,
            eReaderKey = AsymmetricKey.AnonymousExplicit(eReaderKey, Algorithm.ECDH_P256),
            atTime = LocalDate.parse("2021-01-01").atStartOfDayIn(TimeZone.UTC),
        )
        assertEquals(1, verifiedResp.documents.size)

        return disambiguated
    }

    /**
     * Golden `T-DQR-1`: Device QR offering `GattCentral` only.
     */
    @Test
    fun testGoldenDeviceQrGattCentralOnly() = runTest {
        val eDeviceKey = getAnnexDEDeviceKey()
        val centralUuid = UUID.fromString("00000001-a123-48ce-896b-4c76973373e6")
        val bleMethod = MdocConnectionMethodBle(
            gattPeripheral = false,
            gattCentral = true,
            peripheralModeUuid = null,
            centralModeUuid = centralUuid,
        )
        val deItem = buildDeviceEngagement(eDeviceKey = eDeviceKey.publicKey) {
            addConnectionMethod(bleMethod)
            addCapability(Capability.READER_AUTH_ALL_SUPPORT, Simple.TRUE)
            addCapability(Capability.EXTENDED_REQUEST_SUPPORT, Simple.TRUE)
        }.toDataItem(engagementType = MdocEngagementType.DEVICE_QR)
        val encoded = Cbor.encode(deItem)

        val expectedDiag = """
            {
              0: "1.1",
              1: [
                1,
                24(<< {
                  1: 2,
                  -1: 1,
                  -2: h'5a88d182bce5f42efa59943f33359d2e8a968ff289d93e5fa444b624343167fe',
                  -3: h'b16e8cf858ddc7690407ba61d4c338237a8cfcf3de6aa672fc60a557aa32fc67'
                } >>)
              ],
              2: [
                [
                  2,
                  1,
                  {
                    0: false,
                    1: true,
                    11: h'00000001a12348ce896b4c76973373e6'
                  }
                ]
              ],
              5: [],
              6: {
                3: true,
                4: true
              }
            }
        """.trimIndent()
        assertEquals(
            expectedDiag,
            Cbor.toDiagnostics(encoded, setOf(DiagnosticOption.PRETTY_PRINT, DiagnosticOption.EMBEDDED_CBOR))
        )
        assertEquals(
            "a50063312e31018201d818584ba4010220012158205a88d182bce5f42efa59943f33359d2e8a968ff289d93e5fa444b624343167fe225820b16e8cf858ddc7690407ba61d4c338237a8cfcf3de6aa672fc60a557aa32fc670281830201a300f401f50b5000000001a12348ce896b4c76973373e6058006a203f504f5",
            encoded.toHex()
        )
        verifyDeviceQrEndToEndSession("mdoc:${encoded.toBase64Url()}")
    }

    /**
     * Golden `T-DQR-2`: Device QR offering `GattPeripheral` only with optional 7-byte `PeripheralAddress`.
     */
    @Test
    fun testGoldenDeviceQrGattPeripheralWithAddress() = runTest {
        val eDeviceKey = getAnnexDEDeviceKey()
        val peripheralUuid = UUID.fromString("00000002-a123-48ce-896b-4c76973373e6")
        val mdocAddress = BlePeripheralAddress.fromEncoded("28128b37282801".fromHex())
        assertEquals(BleAddressType.RANDOM, mdocAddress.addressType)
        assertEquals("2828378b1228", mdocAddress.address.toByteArray().toHex())

        val bleMethod = MdocConnectionMethodBle(
            gattPeripheral = true,
            gattCentral = false,
            peripheralModeUuid = peripheralUuid,
            centralModeUuid = null,
            peripheralAddress = mdocAddress,
        )
        val deItem = buildDeviceEngagement(eDeviceKey = eDeviceKey.publicKey) {
            addConnectionMethod(bleMethod)
            addCapability(Capability.READER_AUTH_ALL_SUPPORT, Simple.TRUE)
            addCapability(Capability.EXTENDED_REQUEST_SUPPORT, Simple.TRUE)
        }.toDataItem(engagementType = MdocEngagementType.DEVICE_QR)
        val encoded = Cbor.encode(deItem)

        val expectedDiag = """
            {
              0: "1.1",
              1: [
                1,
                24(<< {
                  1: 2,
                  -1: 1,
                  -2: h'5a88d182bce5f42efa59943f33359d2e8a968ff289d93e5fa444b624343167fe',
                  -3: h'b16e8cf858ddc7690407ba61d4c338237a8cfcf3de6aa672fc60a557aa32fc67'
                } >>)
              ],
              2: [
                [
                  2,
                  1,
                  {
                    0: true,
                    1: false,
                    10: h'00000002a12348ce896b4c76973373e6',
                    20: h'28128b37282801'
                  }
                ]
              ],
              5: [],
              6: {
                3: true,
                4: true
              }
            }
        """.trimIndent()
        assertEquals(
            expectedDiag,
            Cbor.toDiagnostics(encoded, setOf(DiagnosticOption.PRETTY_PRINT, DiagnosticOption.EMBEDDED_CBOR))
        )
        assertEquals(
            "a50063312e31018201d818584ba4010220012158205a88d182bce5f42efa59943f33359d2e8a968ff289d93e5fa444b624343167fe225820b16e8cf858ddc7690407ba61d4c338237a8cfcf3de6aa672fc60a557aa32fc670281830201a400f501f40a5000000002a12348ce896b4c76973373e6144728128b37282801058006a203f504f5",
            encoded.toHex()
        )
        verifyDeviceQrEndToEndSession("mdoc:${encoded.toBase64Url()}")
    }

    /**
     * Golden `T-DQR-3`: Device QR offering `GattPeripheral` + `DirectL2capPeripheral` (`psm = 129`).
     */
    @Test
    fun testGoldenDeviceQrGattAndDirectL2capPeripheral() = runTest {
        val eDeviceKey = getAnnexDEDeviceKey()
        val peripheralUuid = UUID.fromString("00000002-a123-48ce-896b-4c76973373e6")
        val bleMethod = MdocConnectionMethodBle(
            gattPeripheral = true,
            gattCentral = false,
            directL2capPeripheral = true,
            directL2capCentral = false,
            peripheralModeUuid = peripheralUuid,
            centralModeUuid = null,
            directL2capPsm = 129,
        )
        val deItem = buildDeviceEngagement(eDeviceKey = eDeviceKey.publicKey) {
            addConnectionMethod(bleMethod)
            addCapability(Capability.READER_AUTH_ALL_SUPPORT, Simple.TRUE)
            addCapability(Capability.EXTENDED_REQUEST_SUPPORT, Simple.TRUE)
        }.toDataItem(engagementType = MdocEngagementType.DEVICE_QR)
        val encoded = Cbor.encode(deItem)

        val expectedDiag = """
            {
              0: "1.1",
              1: [
                1,
                24(<< {
                  1: 2,
                  -1: 1,
                  -2: h'5a88d182bce5f42efa59943f33359d2e8a968ff289d93e5fa444b624343167fe',
                  -3: h'b16e8cf858ddc7690407ba61d4c338237a8cfcf3de6aa672fc60a557aa32fc67'
                } >>)
              ],
              2: [
                [
                  2,
                  1,
                  {
                    0: true,
                    1: false,
                    2: true,
                    3: false,
                    10: h'00000002a12348ce896b4c76973373e6',
                    21: 129
                  }
                ]
              ],
              5: [],
              6: {
                3: true,
                4: true
              }
            }
        """.trimIndent()
        assertEquals(
            expectedDiag,
            Cbor.toDiagnostics(encoded, setOf(DiagnosticOption.PRETTY_PRINT, DiagnosticOption.EMBEDDED_CBOR))
        )
        assertEquals(
            "a50063312e31018201d818584ba4010220012158205a88d182bce5f42efa59943f33359d2e8a968ff289d93e5fa444b624343167fe225820b16e8cf858ddc7690407ba61d4c338237a8cfcf3de6aa672fc60a557aa32fc670281830201a600f501f402f503f40a5000000002a12348ce896b4c76973373e6151881058006a203f504f5",
            encoded.toHex()
        )
        verifyDeviceQrEndToEndSession("mdoc:${encoded.toBase64Url()}") { methods ->
            assertEquals(2, methods.size)
            val first = methods[0] as MdocConnectionMethodBle
            val second = methods[1] as MdocConnectionMethodBle
            assertTrue(first.directL2capPeripheral)
            assertEquals(129, first.directL2capPsm)
            assertTrue(second.gattPeripheral)
            assertNull(second.directL2capPsm)
            first
        }
    }

    /**
     * Golden `T-DQR-4`: Device QR dual-role (`GattPeripheral` + `GattCentral` + `DirectL2capPeripheral`),
     * verifying [MdocConnectionMethod.combine] produces a single `[2, 1, BleOptions]` entry with `3: false`.
     */
    @Test
    fun testGoldenDeviceQrDualRoleCombined() = runTest {
        val eDeviceKey = getAnnexDEDeviceKey()
        val centralUuid = UUID.fromString("00000001-a123-48ce-896b-4c76973373e6")
        val peripheralUuid = UUID.fromString("00000002-a123-48ce-896b-4c76973373e6")
        val advertisedTransports = listOf(
            MdocConnectionMethodBle(
                gattPeripheral = true,
                gattCentral = false,
                directL2capPeripheral = true,
                directL2capCentral = false,
                peripheralModeUuid = peripheralUuid,
                centralModeUuid = null,
                directL2capPsm = 129,
            ),
            MdocConnectionMethodBle(
                gattPeripheral = false,
                gattCentral = true,
                peripheralModeUuid = null,
                centralModeUuid = centralUuid,
            ),
        )
        val combined = MdocConnectionMethod.combine(advertisedTransports)
        assertEquals(1, combined.size)

        val deItem = buildDeviceEngagement(eDeviceKey = eDeviceKey.publicKey) {
            combined.forEach { addConnectionMethod(it) }
            addCapability(Capability.READER_AUTH_ALL_SUPPORT, Simple.TRUE)
            addCapability(Capability.EXTENDED_REQUEST_SUPPORT, Simple.TRUE)
        }.toDataItem(engagementType = MdocEngagementType.DEVICE_QR)
        val encoded = Cbor.encode(deItem)

        val expectedDiag = """
            {
              0: "1.1",
              1: [
                1,
                24(<< {
                  1: 2,
                  -1: 1,
                  -2: h'5a88d182bce5f42efa59943f33359d2e8a968ff289d93e5fa444b624343167fe',
                  -3: h'b16e8cf858ddc7690407ba61d4c338237a8cfcf3de6aa672fc60a557aa32fc67'
                } >>)
              ],
              2: [
                [
                  2,
                  1,
                  {
                    0: true,
                    1: true,
                    2: true,
                    3: false,
                    10: h'00000002a12348ce896b4c76973373e6',
                    11: h'00000001a12348ce896b4c76973373e6',
                    21: 129
                  }
                ]
              ],
              5: [],
              6: {
                3: true,
                4: true
              }
            }
        """.trimIndent()
        assertEquals(
            expectedDiag,
            Cbor.toDiagnostics(encoded, setOf(DiagnosticOption.PRETTY_PRINT, DiagnosticOption.EMBEDDED_CBOR))
        )
        assertEquals(
            "a50063312e31018201d818584ba4010220012158205a88d182bce5f42efa59943f33359d2e8a968ff289d93e5fa444b624343167fe225820b16e8cf858ddc7690407ba61d4c338237a8cfcf3de6aa672fc60a557aa32fc670281830201a700f501f502f503f40a5000000002a12348ce896b4c76973373e60b5000000001a12348ce896b4c76973373e6151881058006a203f504f5",
            encoded.toHex()
        )
        verifyDeviceQrEndToEndSession("mdoc:${encoded.toBase64Url()}") { methods ->
            assertEquals(3, methods.size)
            val m0 = methods[0] as MdocConnectionMethodBle
            val m1 = methods[1] as MdocConnectionMethodBle
            val m2 = methods[2] as MdocConnectionMethodBle
            assertTrue(m0.directL2capPeripheral)
            assertEquals(129, m0.directL2capPsm)
            assertTrue(m1.gattCentral)
            assertNull(m1.directL2capPsm)
            assertTrue(m2.gattPeripheral)
            assertNull(m2.directL2capPsm)
            m0
        }
    }

    /**
     * Golden `T-RQR-1`: Reader QR offering `GattCentral` only.
     */
    @Test
    fun testGoldenReaderQrGattCentralOnly() = runTest {
        val eReaderKey = getAnnexDEReaderKey()
        val centralUuid = UUID.fromString("00000001-a123-48ce-896b-4c76973373e6")
        val readerMethod = MdocConnectionMethodBle(
            gattPeripheral = false,
            gattCentral = true,
            peripheralModeUuid = null,
            centralModeUuid = centralUuid,
        )
        val reItem = buildReaderEngagement(eReaderKey = eReaderKey.publicKey) {
            addConnectionMethod(readerMethod)
        }.toDataItem(engagementType = MdocEngagementType.READER_QR)
        val encoded = Cbor.encode(reItem)

        val expectedDiag = """
            {
              0: "1.1",
              1: [
                1,
                24(<< {
                  1: 2,
                  -1: 1,
                  -2: h'60e3392385041f51403051f2415531cb56dd3f999c71687013aac6768bc8187e',
                  -3: h'e58deb8fdbe907f7dd5368245551a34796f7d2215c440c339bb0f7b67beccdfa'
                } >>)
              ],
              2: [
                [
                  2,
                  1,
                  {
                    0: false,
                    1: true,
                    11: h'00000001a12348ce896b4c76973373e6'
                  }
                ]
              ]
            }
        """.trimIndent()
        assertEquals(
            expectedDiag,
            Cbor.toDiagnostics(encoded, setOf(DiagnosticOption.PRETTY_PRINT, DiagnosticOption.EMBEDDED_CBOR))
        )
        assertEquals(
            "a30063312e31018201d818584ba40102200121582060e3392385041f51403051f2415531cb56dd3f999c71687013aac6768bc8187e225820e58deb8fdbe907f7dd5368245551a34796f7d2215c440c339bb0f7b67beccdfa0281830201a300f401f50b5000000001a12348ce896b4c76973373e6",
            encoded.toHex()
        )
        val disambiguated = verifyReaderQrEndToEndSession("mdoc:${encoded.toBase64Url()}")
        assertEquals(listOf(readerMethod), disambiguated)
    }

    /**
     * Golden `T-RQR-2`: Reader QR offering `GattPeripheral` only (key `10` IS mandatory in Reader QR!).
     */
    @Test
    fun testGoldenReaderQrGattPeripheralOnly() = runTest {
        val eReaderKey = getAnnexDEReaderKey()
        val peripheralUuid = UUID.fromString("00000002-a123-48ce-896b-4c76973373e6")
        val readerMethod = MdocConnectionMethodBle(
            gattPeripheral = true,
            gattCentral = false,
            peripheralModeUuid = peripheralUuid,
            centralModeUuid = null,
        )
        val reItem = buildReaderEngagement(eReaderKey = eReaderKey.publicKey) {
            addConnectionMethod(readerMethod)
        }.toDataItem(engagementType = MdocEngagementType.READER_QR)
        val encoded = Cbor.encode(reItem)

        val expectedDiag = """
            {
              0: "1.1",
              1: [
                1,
                24(<< {
                  1: 2,
                  -1: 1,
                  -2: h'60e3392385041f51403051f2415531cb56dd3f999c71687013aac6768bc8187e',
                  -3: h'e58deb8fdbe907f7dd5368245551a34796f7d2215c440c339bb0f7b67beccdfa'
                } >>)
              ],
              2: [
                [
                  2,
                  1,
                  {
                    0: true,
                    1: false,
                    10: h'00000002a12348ce896b4c76973373e6'
                  }
                ]
              ]
            }
        """.trimIndent()
        assertEquals(
            expectedDiag,
            Cbor.toDiagnostics(encoded, setOf(DiagnosticOption.PRETTY_PRINT, DiagnosticOption.EMBEDDED_CBOR))
        )
        assertEquals(
            "a30063312e31018201d818584ba40102200121582060e3392385041f51403051f2415531cb56dd3f999c71687013aac6768bc8187e225820e58deb8fdbe907f7dd5368245551a34796f7d2215c440c339bb0f7b67beccdfa0281830201a300f501f40a5000000002a12348ce896b4c76973373e6",
            encoded.toHex()
        )
        val disambiguated = verifyReaderQrEndToEndSession("mdoc:${encoded.toBase64Url()}")
        assertEquals(listOf(readerMethod), disambiguated)
    }

    /**
     * Golden `T-RQR-3`: Reader QR offering `GattCentral` + `DirectL2capCentral` (`psm = 128`).
     */
    @Test
    fun testGoldenReaderQrGattAndDirectL2capCentral() = runTest {
        val eReaderKey = getAnnexDEReaderKey()
        val centralUuid = UUID.fromString("00000001-a123-48ce-896b-4c76973373e6")
        val readerMethod = MdocConnectionMethodBle(
            gattPeripheral = false,
            gattCentral = true,
            directL2capPeripheral = false,
            directL2capCentral = true,
            peripheralModeUuid = null,
            centralModeUuid = centralUuid,
            directL2capPsm = 128,
        )
        val reItem = buildReaderEngagement(eReaderKey = eReaderKey.publicKey) {
            addConnectionMethod(readerMethod)
        }.toDataItem(engagementType = MdocEngagementType.READER_QR)
        val encoded = Cbor.encode(reItem)

        val expectedDiag = """
            {
              0: "1.1",
              1: [
                1,
                24(<< {
                  1: 2,
                  -1: 1,
                  -2: h'60e3392385041f51403051f2415531cb56dd3f999c71687013aac6768bc8187e',
                  -3: h'e58deb8fdbe907f7dd5368245551a34796f7d2215c440c339bb0f7b67beccdfa'
                } >>)
              ],
              2: [
                [
                  2,
                  1,
                  {
                    0: false,
                    1: true,
                    2: false,
                    3: true,
                    11: h'00000001a12348ce896b4c76973373e6',
                    21: 128
                  }
                ]
              ]
            }
        """.trimIndent()
        assertEquals(
            expectedDiag,
            Cbor.toDiagnostics(encoded, setOf(DiagnosticOption.PRETTY_PRINT, DiagnosticOption.EMBEDDED_CBOR))
        )
        assertEquals(
            "a30063312e31018201d818584ba40102200121582060e3392385041f51403051f2415531cb56dd3f999c71687013aac6768bc8187e225820e58deb8fdbe907f7dd5368245551a34796f7d2215c440c339bb0f7b67beccdfa0281830201a600f401f502f403f50b5000000001a12348ce896b4c76973373e6151880",
            encoded.toHex()
        )
        val disambiguated = verifyReaderQrEndToEndSession("mdoc:${encoded.toBase64Url()}")
        assertEquals(2, disambiguated.size)
        val first = disambiguated[0] as MdocConnectionMethodBle
        val second = disambiguated[1] as MdocConnectionMethodBle
        assertTrue(first.directL2capCentral)
        assertEquals(128, first.directL2capPsm)
        assertTrue(second.gattCentral)
        assertNull(second.directL2capPsm)
    }

    /**
     * Golden `T-RQR-4`: Reader QR dual-role (`GattPeripheral` + `GattCentral` + `DirectL2capCentral`),
     * verifying [MdocConnectionMethod.combine] produces a single `[2, 1, BleOptions]` entry with `2: false`.
     */
    @Test
    fun testGoldenReaderQrDualRoleCombined() = runTest {
        val eReaderKey = getAnnexDEReaderKey()
        val centralUuid = UUID.fromString("00000001-a123-48ce-896b-4c76973373e6")
        val peripheralUuid = UUID.fromString("00000002-a123-48ce-896b-4c76973373e6")
        val combined = MdocConnectionMethod.combine(
            listOf(
                MdocConnectionMethodBle(
                    gattPeripheral = true,
                    gattCentral = false,
                    peripheralModeUuid = peripheralUuid,
                    centralModeUuid = null,
                ),
                MdocConnectionMethodBle(
                    gattPeripheral = false,
                    gattCentral = true,
                    directL2capPeripheral = false,
                    directL2capCentral = true,
                    peripheralModeUuid = null,
                    centralModeUuid = centralUuid,
                    directL2capPsm = 128,
                ),
            )
        )
        assertEquals(1, combined.size)

        val reItem = buildReaderEngagement(eReaderKey = eReaderKey.publicKey) {
            combined.forEach { addConnectionMethod(it) }
        }.toDataItem(engagementType = MdocEngagementType.READER_QR)
        val encoded = Cbor.encode(reItem)

        val expectedDiag = """
            {
              0: "1.1",
              1: [
                1,
                24(<< {
                  1: 2,
                  -1: 1,
                  -2: h'60e3392385041f51403051f2415531cb56dd3f999c71687013aac6768bc8187e',
                  -3: h'e58deb8fdbe907f7dd5368245551a34796f7d2215c440c339bb0f7b67beccdfa'
                } >>)
              ],
              2: [
                [
                  2,
                  1,
                  {
                    0: true,
                    1: true,
                    2: false,
                    3: true,
                    10: h'00000002a12348ce896b4c76973373e6',
                    11: h'00000001a12348ce896b4c76973373e6',
                    21: 128
                  }
                ]
              ]
            }
        """.trimIndent()
        assertEquals(
            expectedDiag,
            Cbor.toDiagnostics(encoded, setOf(DiagnosticOption.PRETTY_PRINT, DiagnosticOption.EMBEDDED_CBOR))
        )
        assertEquals(
            "a30063312e31018201d818584ba40102200121582060e3392385041f51403051f2415531cb56dd3f999c71687013aac6768bc8187e225820e58deb8fdbe907f7dd5368245551a34796f7d2215c440c339bb0f7b67beccdfa0281830201a700f501f502f403f50a5000000002a12348ce896b4c76973373e60b5000000001a12348ce896b4c76973373e6151880",
            encoded.toHex()
        )
        val disambiguated = verifyReaderQrEndToEndSession("mdoc:${encoded.toBase64Url()}")
        assertEquals(3, disambiguated.size)
        val m0 = disambiguated[0] as MdocConnectionMethodBle
        val m1 = disambiguated[1] as MdocConnectionMethodBle
        val m2 = disambiguated[2] as MdocConnectionMethodBle
        assertTrue(m0.directL2capCentral)
        assertEquals(128, m0.directL2capPsm)
        assertTrue(m1.gattCentral)
        assertNull(m1.directL2capPsm)
        assertTrue(m2.gattPeripheral)
        assertNull(m2.directL2capPsm)
    }
}
