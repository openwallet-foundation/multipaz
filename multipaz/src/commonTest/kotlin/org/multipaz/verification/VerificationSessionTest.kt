package org.multipaz.verification

import kotlinx.coroutines.test.runTest
import kotlinx.io.bytestring.ByteString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import org.multipaz.asn1.ASN1Integer
import org.multipaz.cbor.Bstr
import org.multipaz.cbor.Cbor
import org.multipaz.cbor.Simple
import org.multipaz.cbor.Tagged
import org.multipaz.cbor.Tstr
import org.multipaz.cbor.buildCborArray
import org.multipaz.cbor.buildCborMap
import org.multipaz.crypto.Algorithm
import org.multipaz.crypto.AsymmetricKey
import org.multipaz.crypto.Crypto
import org.multipaz.crypto.EcCurve
import org.multipaz.crypto.JsonWebSignature
import org.multipaz.crypto.X500Name
import org.multipaz.crypto.X509CertChain
import org.multipaz.document.Document
import org.multipaz.documenttype.ISO_18013_TRANSACTION_DATA_NAMESPACE
import org.multipaz.documenttype.MultiDocumentCannedRequest
import org.multipaz.documenttype.knowntypes.DrivingLicense
import org.multipaz.documenttype.knowntypes.EUPersonalID
import org.multipaz.documenttype.knowntypes.PhotoID
import org.multipaz.mdoc.credential.KeyBoundMdocCredential
import org.multipaz.mdoc.credential.KeylessMdocCredential
import org.multipaz.mdoc.engagement.buildDeviceEngagement
import org.multipaz.mdoc.request.DeviceRequest
import org.multipaz.mdoc.request.DeviceRequestInfo
import org.multipaz.mdoc.request.DocRequestInfo
import org.multipaz.mdoc.request.DocumentSet
import org.multipaz.mdoc.request.UseCase
import org.multipaz.mdoc.request.buildDeviceRequest
import org.multipaz.mdoc.response.DeviceResponse
import org.multipaz.mdoc.response.MdocDocument
import org.multipaz.mdoc.response.OtherDocument
import org.multipaz.mdoc.response.buildDeviceResponse
import org.multipaz.mdoc.util.MdocUtil
import org.multipaz.openid.OpenID4VP
import org.multipaz.presentment.DocumentStoreTestHarness
import org.multipaz.presentment.digitalCredentialsPresentment
import org.multipaz.presentment.mdocPresentment
import org.multipaz.request.MdocRequestedClaim
import org.multipaz.request.OpenID4VPRequesterIdentity
import org.multipaz.sdjwt.SdJwt
import org.multipaz.sdjwt.credential.KeylessSdJwtVcCredential
import org.multipaz.securearea.CreateKeySettings
import org.multipaz.trustmanagement.TrustMetadata
import org.multipaz.util.toBase64Url
import org.multipaz.util.zlibDeflate
import org.multipaz.utopia.knowntypes.PingTransaction
import org.multipaz.utopia.knowntypes.UtopiaMovieTicket
import org.multipaz.utopia.knowntypes.wellKnownMultipleDocumentRequests
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.minutes

class VerificationSessionTest {
    private val harness = DocumentStoreTestHarness()
    private lateinit var readerIdentity: VerifierIdentity
    private lateinit var secondaryIdentity: VerifierIdentity
    private val mdlPingTransactionData: List<String> by lazy {
        listOf(PingTransaction.sampleData.getSerializedJson(credentialIds = listOf("mDL")))
    }
    private val euPidPingTransactionData: List<String> by lazy {
        listOf(PingTransaction.sampleData.getSerializedJson(credentialIds = listOf("pid")))
    }

    /**
     * Canned multi-credential request offering mDL ("mdl") OR PhotoID ("pid") as alternatives,
     * each carrying its own `org.multipaz.transaction.ping` transaction with a per-credential
     * `string` payload ("mdl text" / "pid text"). Sourced from `multipaz-utopia` so the test
     * tracks any updates to the well-known request.
     */
    private val ageOver18MultiRequest: MultiDocumentCannedRequest by lazy {
        wellKnownMultipleDocumentRequests.single { it.id == "transaction-and-age-over-18" }
    }
    private val ageOver18Dcql: String get() = ageOver18MultiRequest.dcqlString
    private val ageOver18TransactionData: List<String> get() =
        Json.parseToJsonElement(ageOver18MultiRequest.transactionData!!).jsonArray.map { it.toString() }

    private suspend fun setup() {
        harness.initialize()
        harness.documentTypeRepository.addDocumentType(UtopiaMovieTicket.getDocumentType())
        harness.provisionStandardDocuments(
            keyAuthorizedNamespaces = listOf(
                ISO_18013_TRANSACTION_DATA_NAMESPACE,
                PingTransaction.openId4VpMdocResponseNamespace,
            )
        )
        harness.provisionKeylessMdoc(
            displayName = "Movie Ticket Mdoc",
            docType = UtopiaMovieTicket.MOVIE_TICKET_DOCTYPE,
            data = mapOf(
                UtopiaMovieTicket.MOVIE_TICKET_NAMESPACE to listOf(
                    "ticket_id" to Tstr("987654321"),
                    "movie" to Tstr("The Last Utopian"),
                    "cinema" to Tstr("Utopia Cinema"),
                )
            )
        )
        harness.provisionKeylessSdJwtVc(
            displayName = "Movie Ticket SD-JWT",
            vct = UtopiaMovieTicket.MOVIE_TICKET_VCT,
            data = listOf(
                "ticket_id" to JsonPrimitive("987654321"),
                "movie" to JsonPrimitive("The Last Utopian"),
                "cinema" to JsonPrimitive("Utopia Cinema"),
            )
        )

        val readerPrivateKey = Crypto.createEcPrivateKey(EcCurve.P256)
        val readerCert = MdocUtil.generateReaderCertificate(
            readerRootKey = harness.readerRootKey,
            readerKey = readerPrivateKey.publicKey,
            subject = X500Name.fromName("CN=Multipaz TEST Reader"),
            dnsName = DNS_NAME,
            serial = ASN1Integer.fromRandom(128),
            validFrom = harness.validFrom,
            validUntil = harness.validUntil,
            extensions = emptyList()
        )
        readerIdentity = VerifierIdentity(
            key = AsymmetricKey.X509CertifiedExplicit(
                certChain = X509CertChain(
                    listOf(readerCert) + harness.readerRootKey.certChain.certificates
                ),
                privateKey = readerPrivateKey
            ),
            clientId = CLIENT_ID
        )
        val secondaryRootKey = Crypto.createEcPrivateKey(EcCurve.P256)
        val secondaryRootCert = MdocUtil.generateReaderRootCertificate(
            readerRootKey = AsymmetricKey.anonymous(secondaryRootKey),
            subject = X500Name.fromName("CN=Secondary Root"),
            serial = ASN1Integer.fromRandom(128),
            validFrom = harness.validFrom,
            validUntil = harness.validUntil,
            crlUrl = "https://example.com/crl"
        )
        val secondaryPrivateKey = Crypto.createEcPrivateKey(EcCurve.P256)
        val secondaryCert = MdocUtil.generateReaderCertificate(
            readerRootKey = AsymmetricKey.X509CertifiedExplicit(
                certChain = X509CertChain(listOf(secondaryRootCert)),
                privateKey = secondaryRootKey
            ),
            readerKey = secondaryPrivateKey.publicKey,
            subject = X500Name.fromName("CN=Seconary"),
            dnsName = null,
            serial = ASN1Integer.fromRandom(128),
            validFrom = harness.validFrom,
            validUntil = harness.validUntil,
            extensions = emptyList()
        )
        val certHash = Crypto.digest(Algorithm.SHA256, secondaryCert.encoded.toByteArray())
        secondaryIdentity = VerifierIdentity(
            key = AsymmetricKey.X509CertifiedExplicit(
                certChain = X509CertChain(listOf(secondaryCert, secondaryRootCert)),
                privateKey = secondaryPrivateKey
            ),
            clientId = "x509_hash:${certHash.toBase64Url()}"
        )
    }

    private suspend fun assertVerifiedMdl(
        record: PresentmentRecord,
        expectPingTransaction: Boolean = true,
    ) {
        val presentations = record.verify(
            documentTypeRepository = harness.documentTypeRepository
        )
        assertEquals(1, presentations.size)
        val mdoc = assertIs<MdocVerifiedPresentation>(presentations[0])
        assertEquals(DrivingLicense.MDL_DOCTYPE, mdoc.docType)

        val claims = mdoc.issuerSignedClaims.associate { it.dataElementName to it.value }
        assertEquals("Erika", claims["given_name"]!!.asTstr)
        assertEquals("Mustermann", claims["family_name"]!!.asTstr)
        assertEquals(true, claims["age_over_21"]!!.asBoolean)

        // Trust check: presented DS cert must match the one the harness signed the credential with.
        assertEquals(
            harness.dsKey.publicKey,
            mdoc.documentSignerCertChain.certificates.first().ecPublicKey
        )

        if (expectPingTransaction) {
            // Ping transaction round-trip: the holder echoes the request "string" attribute.
            val pingResponse = assertNotNull(
                mdoc.transactionResponses?.get(PingTransaction.identifier),
                "expected a Ping transaction response"
            )
            assertEquals("string data", pingResponse["string"]!!.asTstr)
            if (record is OpenID4VPPresentmentRecord) {
                assertEquals(32, pingResponse["transactionDataHash"]!!.asBstr.size)
            } else {
                assertEquals(null, pingResponse["transactionDataHash"])
            }
        }
    }

    private suspend fun assertVerifiedEuPidSdJwt(record: PresentmentRecord) {
        val presentations = record.verify(
            documentTypeRepository = harness.documentTypeRepository
        )
        assertEquals(1, presentations.size)
        val pid = assertIs<JsonVerifiedPresentation>(presentations[0])
        assertEquals(EUPersonalID.EUPID_VCT, pid.vct)

        val claims = pid.issuerSignedClaims.associate {
            it.claimPath.last().jsonPrimitive.content to it.value
        }
        assertEquals("Erika", claims["given_name"]!!.jsonPrimitive.content)
        assertEquals("Mustermann", claims["family_name"]!!.jsonPrimitive.content)
        // Nested place_of_birth claim arrives as the top-level object containing the "country" key.
        assertEquals("ZZ", claims["place_of_birth"]!!.jsonObject["country"]!!.jsonPrimitive.content)

        // Trust check: presented DS cert must match the one the harness signed the credential with.
        assertEquals(
            harness.dsKey.publicKey,
            pid.documentSignerCertChain.certificates.first().ecPublicKey
        )

        // Ping transaction round-trip: generateSdJwtResponseClaims is echoed in the KB-JWT body under
        // kbJwtResponseClaimName and surfaced under the transaction type's identifier.
        val pingResponse = assertNotNull(
            pid.transactionResponses?.get(PingTransaction.identifier),
            "expected a Ping transaction response"
        )
        assertEquals("string data", pingResponse.jsonObject["string"]!!.jsonPrimitive.content)
    }

    /**
     * Asserts a multi-credential `transaction-and-age-over-18` response where the holder selected
     * [expectedDocType] and the per-credential ping transaction echoed [expectedPingString].
     */
    private suspend fun assertVerifiedAgeOver18(
        record: PresentmentRecord,
        expectedDocType: String,
        expectedPingString: String,
        expectedDocRequestId: Long?
    ) {
        val presentations = record.verify(
            documentTypeRepository = harness.documentTypeRepository
        )
        assertEquals(1, presentations.size)
        val mdoc = assertIs<MdocVerifiedPresentation>(presentations[0])
        assertEquals(expectedDocType, mdoc.docType)

        val claims = mdoc.issuerSignedClaims.associate {
            it.dataElementName to it.value
        }
        assertEquals(true, claims["age_over_18"]!!.asBoolean)

        assertEquals(
            harness.dsKey.publicKey,
            mdoc.documentSignerCertChain.certificates.first().ecPublicKey
        )

        val pingResponse = assertNotNull(
            mdoc.transactionResponses?.get(PingTransaction.identifier),
            "expected a Ping transaction response"
        )
        assertEquals(expectedPingString, pingResponse["string"]!!.asTstr)
        if (record is OpenID4VPPresentmentRecord) {
            assertEquals(32, pingResponse["transactionDataHash"]!!.asBstr.size)
        } else {
            assertEquals(null, pingResponse["transactionDataHash"])
        }
        assertEquals(expectedDocRequestId, pingResponse["docRequestId"]?.asNumber)
    }

    @Test
    fun testOpenID4VPUriScheme() = runTest {
        setup()
        val nonce = ByteString(Random.nextBytes(18))
        val record = openID4VPUriSchemeRoundTrip(
            dcql = MDL_DCQL,
            transactionData = mdlPingTransactionData,
            nonce = nonce,
        )
        assertVerifiedMdl(record)
    }

    @Test
    fun testOpenID4VPUriSchemeSdJwt() = runTest {
        setup()
        val nonce = ByteString(Random.nextBytes(18))
        val record = openID4VPUriSchemeRoundTrip(
            dcql = EU_PID_SDJWT_DCQL,
            transactionData = euPidPingTransactionData,
            nonce = nonce,
        )
        assertVerifiedEuPidSdJwt(record)
    }

    /**
     * Runs the signed + encrypted OpenID4VP URI-scheme round-trip end-to-end and returns the
     * verifier-side [PresentmentRecord] after a successful [PresentmentRecord.verifyNonce].
     */
    private suspend fun openID4VPUriSchemeRoundTrip(
        dcql: String,
        transactionData: List<String>,
        nonce: ByteString,
    ): PresentmentRecord {
        val responseUri = "$ORIGIN/direct_post/test-session"
        val session = VerificationUtil.generateVerificationSessionForDcql(
            requestTypes = setOf(VerificationSession.RequestType.OPENID4VP_URI_SCHEME),
            dcql = dcql,
            transactionData = transactionData,
            verifierIdentities = listOf(readerIdentity),
            origin = ORIGIN,
            nonce = nonce,
            encryptResponse = true,
            responseUri = responseUri,
            documentTypeRepository = harness.documentTypeRepository,
        )

        // Signed URI-scheme request: openID4VPRequest is { "request": "<JWT compact serialization>" }.
        val uriRequest = session.find<VerificationSession.OpenID4VPUriSchemeRequest>()
        val signedJwt = Json.parseToJsonElement(uriRequest.openID4VPRequest)
            .jsonObject["request"]!!.jsonPrimitive.content
        val jwsInfo = JsonWebSignature.getInfo(signedJwt)
        val requesterCertChain = assertNotNull(jwsInfo.x5c, "expected x5c in signed request")
        JsonWebSignature.verify(signedJwt, requesterCertChain.certificates.first().publicKey)

        // Wallet-side: generate the response from the harness's test credential.
        val responseObject = OpenID4VP.generateResponse(
            version = OpenID4VP.Version.DRAFT_29,
            preselectedDocuments = emptyList(),
            source = harness.presentmentSource,
            appId = null,
            origin = ORIGIN,
            request = jwsInfo.claimsSet,
            requesterIdentities = listOf(OpenID4VPRequesterIdentity(requesterCertChain, CLIENT_ID)),
        )
        // Encrypted direct_post.jwt: response is { "response": "<JWE compact serialization>" }.
        val jwe = responseObject.response["response"]!!.jsonPrimitive.content

        // Verifier-side: process the form parameters that would have been POSTed to responseUri.
        val record = session.processOpenID4VPUriSchemeResponse(
            postedData = mapOf("response" to jwe)
        )
        record.verifyNonce(nonce)
        return record
    }

    @Test
    fun testOpenID4VPDcApi() = runTest {
        setup()

        val nonce = ByteString(Random.nextBytes(18))
        val session = VerificationUtil.generateVerificationSessionForDcql(
            requestTypes = setOf(VerificationSession.RequestType.DC_OPENID4VP),
            dcql = MDL_DCQL,
            transactionData = mdlPingTransactionData,
            verifierIdentities = listOf(readerIdentity),
            origin = ORIGIN,
            nonce = nonce,
            encryptResponse = true,
            documentTypeRepository = harness.documentTypeRepository,
        )

        val dcResponse = walletDcApiResponse(session, protocolName = "openid4vp-v1-signed")
        val record = session.processDcResponse(dcResponse)
        record.verifyNonce(nonce)
        assertVerifiedMdl(record)
    }

    /**
     * OpenID4VP 1.0 Appendix A.3.2.2 does not say where in `data` the JWS JSON Serialization of a multisigned
     * request goes: [VerificationUtil] makes it `data` itself, and e.g. the OpenID Foundation conformance suite
     * sends it as the value of `request`. The wallet must answer both.
     */
    @Test
    fun testOpenID4VPDcApiMultisignedInRequest() = runTest {
        setup()

        val nonce = ByteString(Random.nextBytes(18))
        val session = VerificationUtil.generateVerificationSessionForDcql(
            requestTypes = setOf(VerificationSession.RequestType.DC_OPENID4VP),
            dcql = MDL_DCQL,
            transactionData = mdlPingTransactionData,
            verifierIdentities = listOf(secondaryIdentity, readerIdentity),
            origin = ORIGIN,
            nonce = nonce,
            encryptResponse = true,
            documentTypeRepository = harness.documentTypeRepository,
        )

        val dcResponse = walletDcApiResponse(
            session = session,
            protocolName = "openid4vp-v1-multisigned",
            transformData = { data -> buildJsonObject { put("request", data) } },
        )
        val record = session.processDcResponse(dcResponse)
        record.verifyNonce(nonce)
        assertVerifiedMdl(record)
    }

    @Test
    fun testOpenID4VPDcApiDraft24() = runTest {
        setup()

        val nonce = ByteString(Random.nextBytes(18))
        // OpenID4VP DRAFT_24 does not support `transaction_data`, so this request omits it.
        val session = VerificationUtil.generateVerificationSessionForDcql(
            requestTypes = setOf(VerificationSession.RequestType.DC_OPENID4VP_DRAFT_24),
            dcql = MDL_DCQL,
            verifierIdentities = listOf(readerIdentity),
            origin = ORIGIN,
            nonce = nonce,
            encryptResponse = true,
            documentTypeRepository = harness.documentTypeRepository,
        )

        val dcResponse = walletDcApiResponse(session, protocolName = "openid4vp")
        val record = session.processDcResponse(dcResponse)
        record.verifyNonce(nonce)
        assertVerifiedMdl(record, expectPingTransaction = false)
    }

    @Test
    fun testDcIso18013() = runTest {
        setup()

        val nonce = ByteString(Random.nextBytes(18))
        val session = VerificationUtil.generateVerificationSessionForDcql(
            requestTypes = setOf(VerificationSession.RequestType.DC_ISO_18013),
            dcql = MDL_DCQL,
            transactionData = mdlPingTransactionData,
            verifierIdentities = listOf(readerIdentity),
            origin = ORIGIN,
            nonce = nonce,
            encryptResponse = true,
            documentTypeRepository = harness.documentTypeRepository,
        )

        val dcResponse = walletDcApiResponse(session, protocolName = "org-iso-mdoc")
        val record = session.processDcResponse(dcResponse)
        record.verifyNonce(nonce)
        assertVerifiedMdl(record)
    }

    @Test
    fun testDcIso18013SdJwt() = runTest {
        setup()

        val nonce = ByteString(Random.nextBytes(18))
        val session = VerificationUtil.generateVerificationSessionForDcql(
            requestTypes = setOf(VerificationSession.RequestType.DC_ISO_18013),
            dcql = EU_PID_SDJWT_DCQL,
            transactionData = euPidPingTransactionData,
            verifierIdentities = listOf(readerIdentity),
            origin = ORIGIN,
            nonce = nonce,
            encryptResponse = true,
            documentTypeRepository = harness.documentTypeRepository,
        )

        val dcResponse = walletDcApiResponse(session, protocolName = "org-iso-mdoc")
        val record = session.processDcResponse(dcResponse)
        record.verifyNonce(nonce)
        assertVerifiedEuPidSdJwt(record)
    }

    @Test
    fun testIso18013Proximity() = runTest {
        setup()

        // Holder's session key + a QR-style engagement; reader picks its own ephemeral key.
        val eDeviceKey = Crypto.createEcPrivateKey(EcCurve.P256)
        val eReaderKey = Crypto.createEcPrivateKey(EcCurve.P256)
        val deviceEngagement = ByteString(
            Cbor.encode(buildDeviceEngagement(eDeviceKey = eDeviceKey.publicKey) {}.toDataItem())
        )
        val handover = Simple.NULL

        val session = VerificationUtil.generateVerificationSessionForDcql(
            requestTypes = setOf(VerificationSession.RequestType.ISO_18013_PROXIMITY),
            dcql = MDL_DCQL,
            transactionData = mdlPingTransactionData,
            verifierIdentities = listOf(readerIdentity),
            documentTypeRepository = harness.documentTypeRepository,
            deviceEngagement = deviceEngagement,
            handover = handover,
            eReaderKey = eReaderKey,
            // encryptResponse defaults to true; ISO_18013_PROXIMITY requires it.
        )

        val proximityRequest = session.find<VerificationSession.Iso18013ProximityRequest>()
        val parsedRequest = DeviceRequest.fromDataItem(proximityRequest.deviceRequest)
        parsedRequest.verifyReaderAuthentication(proximityRequest.sessionTranscript)
        val mdocResponse = mdocPresentment(
            deviceRequest = parsedRequest,
            eReaderKey = eReaderKey.publicKey,
            sessionTranscript = proximityRequest.sessionTranscript,
            source = harness.presentmentSource,
            keyAgreementPossible = emptyList(),
            requesterAppId = null,
            requesterOrigin = null,
            preselectedDocuments = emptyList(),
            onWaitingForUserInput = {},
            onDocumentsInFocus = {},
        )

        // ISO_18013_PROXIMITY records don't carry DC-API encryptionInfo/origin, so verifyNonce is N/A.
        val record = session.processIso18013ProximityResponse(
            deviceResponse = mdocResponse.deviceResponse.toDataItem()
        )
        assertVerifiedMdl(record)
    }

    @Test
    fun testMultiDcOpenID4VPMdl() = runTest {
        setup()
        runMultiAgeOver18(
            requestType = VerificationSession.RequestType.DC_OPENID4VP,
            preselected = harness.docMdl,
            expectedDocType = DrivingLicense.MDL_DOCTYPE,
            expectedPingString = "mdl text",
            expectedDocRequestId = null,
            multisign = false
        )
    }

    @Test
    fun testMultiDcOpenID4VPMdlMultisign() = runTest {
        setup()
        runMultiAgeOver18(
            requestType = VerificationSession.RequestType.DC_OPENID4VP,
            preselected = harness.docMdl,
            expectedDocType = DrivingLicense.MDL_DOCTYPE,
            expectedPingString = "mdl text",
            expectedDocRequestId = null,
            multisign = true
        )
    }

    @Test
    fun testMultiDcOpenID4VPPid() = runTest {
        setup()
        runMultiAgeOver18(
            requestType = VerificationSession.RequestType.DC_OPENID4VP,
            preselected = harness.docPhotoId,
            expectedDocType = PhotoID.PHOTO_ID_DOCTYPE,
            expectedPingString = "pid text",
            expectedDocRequestId = null,
            multisign = false
        )
    }

    @Test
    fun testMultiDcIso18013Mdl() = runTest {
        setup()
        runMultiAgeOver18(
            requestType = VerificationSession.RequestType.DC_ISO_18013,
            preselected = harness.docMdl,
            expectedDocType = DrivingLicense.MDL_DOCTYPE,
            expectedPingString = "mdl text",
            expectedDocRequestId = 0L,
            multisign = false
        )
    }

    @Test
    fun testMultiDcIso18013MdlMultisign() = runTest {
        setup()
        runMultiAgeOver18(
            requestType = VerificationSession.RequestType.DC_ISO_18013,
            preselected = harness.docMdl,
            expectedDocType = DrivingLicense.MDL_DOCTYPE,
            expectedPingString = "mdl text",
            expectedDocRequestId = 0L,
            multisign = true
        )
    }

    @Test
    fun testMultiDcIso18013Pid() = runTest {
        setup()
        runMultiAgeOver18(
            requestType = VerificationSession.RequestType.DC_ISO_18013,
            preselected = harness.docPhotoId,
            expectedDocType = PhotoID.PHOTO_ID_DOCTYPE,
            expectedPingString = "pid text",
            expectedDocRequestId = 1L,
            multisign = false
        )
    }

    /**
     * Drives the `transaction-and-age-over-18` multi-credential request end-to-end through DC API,
     * steering the wallet's credential choice via [preselected].
     */
    private suspend fun runMultiAgeOver18(
        requestType: VerificationSession.RequestType,
        preselected: Document,
        expectedDocType: String,
        expectedPingString: String,
        expectedDocRequestId: Long?,
        multisign: Boolean
    ) {
        val nonce = ByteString(Random.nextBytes(18))
        val session = VerificationUtil.generateVerificationSessionForDcql(
            requestTypes = setOf(requestType),
            dcql = ageOver18Dcql,
            transactionData = ageOver18TransactionData,
            verifierIdentities = buildList {
                if (multisign) {
                    add(secondaryIdentity)
                }
                add(readerIdentity)
            },
            origin = ORIGIN,
            nonce = nonce,
            encryptResponse = true,
            documentTypeRepository = harness.documentTypeRepository,
        )
        val protocolName = when (requestType) {
            VerificationSession.RequestType.DC_ISO_18013 -> "org-iso-mdoc"
            VerificationSession.RequestType.DC_OPENID4VP ->
                if (multisign) {
                    "openid4vp-v1-multisigned"
                } else {
                    "openid4vp-v1-signed"
                }
            else -> throw IllegalArgumentException("Unexpected requestType: $requestType")
        }

        val dcResponse = walletDcApiResponse(
            session = session,
            protocolName = protocolName,
            preselectedDocuments = listOf(preselected),
        )
        val record = session.processDcResponse(dcResponse)
        record.verifyNonce(nonce)
        assertVerifiedAgeOver18(
            record = record,
            expectedDocType = expectedDocType,
            expectedPingString = expectedPingString,
            expectedDocRequestId = expectedDocRequestId
        )
    }

    /**
     * Wallet-side dispatch for both DC API protocols. Pulls the entry for [protocolName] from
     * `getDcRequest()`, runs it through [digitalCredentialsPresentment], and returns the
     * `{ "protocol", "data" }` envelope that [VerificationSession.processDcResponse] expects.
     * [transformData] changes the entry's `data` before the wallet reads it.
     */
    private suspend fun walletDcApiResponse(
        session: VerificationSession,
        protocolName: String,
        preselectedDocuments: List<Document> = emptyList(),
        transformData: (data: JsonObject) -> JsonObject = { it },
    ): JsonObject {
        val dcRequest = session.getDcRequest()
        val protocolEntry = dcRequest["requests"]!!.jsonArray
            .single { it.jsonObject["protocol"]!!.jsonPrimitive.content == protocolName }
            .jsonObject
        return digitalCredentialsPresentment(
            protocol = protocolEntry["protocol"]!!.jsonPrimitive.content,
            data = transformData(protocolEntry["data"]!!.jsonObject),
            appId = APP_ID,
            origin = ORIGIN,
            preselectedDocuments = preselectedDocuments,
            source = harness.presentmentSource,
        )
    }

    @Test
    fun testSdJwtHolderBindingValidityAndAudienceChecks() = runTest {
        setup()
        val now = Clock.System.now()
        val holderPrivateKey = Crypto.createEcPrivateKey(EcCurve.P256)
        val holderKey = AsymmetricKey.anonymous(holderPrivateKey, Algorithm.ESP256)
        val credential = SdJwt.create(
            issuerKey = harness.dsKey,
            kbKey = holderPrivateKey.publicKey,
            claims = buildJsonObject { put("given_name", "Erika") },
            nonSdClaims = buildJsonObject {
                put("iss", "https://issuer.example")
                put("vct", EUPersonalID.EUPID_VCT)
                put("nbf", (now - 1.days).epochSeconds)
            },
            creationTime = now - 1.days,
            expiresIn = 11.days,
        )
        val nonce = "test-nonce-1"
        val validPresentation = credential.present(
            signingKey = holderKey,
            nonce = nonce,
            audience = CLIENT_ID,
            creationTime = now,
        ).compactSerialization

        fun buildRequest(extraQueryFields: String = ""): String = """
            {
              "client_id": "$CLIENT_ID",
              "nonce": "$nonce",
              "response_uri": "https://$DNS_NAME/post",
              "dcql_query": {
                "credentials": [{
                  "id": "pid",
                  "format": "dc+sd-jwt",
                  "meta": { "vct_values": ["${EUPersonalID.EUPID_VCT}"] },
                  "claims": [{ "path": ["given_name"] }]
                  $extraQueryFields
                }]
              }
            }
        """.trimIndent()

        fun buildRecord(presentation: String, request: String = buildRequest()): OpenID4VPPresentmentRecord {
            val vpToken = buildJsonObject {
                putJsonArray("pid") { add(presentation) }
            }.toString()
            return OpenID4VPPresentmentRecord(vpToken, request, null)
        }

        // Control: valid presentation passes
        val verified = buildRecord(validPresentation).verify(atTime = now)
        assertEquals(1, verified.size)

        // 1. Stripped KB-JWT must be rejected (both default and explicit require_cryptographic_holder_binding)
        val kbStripped = validPresentation.substring(0, validPresentation.lastIndexOf('~') + 1)
        assertFailsWith<IllegalStateException> {
            buildRecord(kbStripped, buildRequest()).verify(atTime = now)
        }
        assertFailsWith<IllegalStateException> {
            buildRecord(kbStripped, buildRequest(""", "require_cryptographic_holder_binding": true""")).verify(atTime = now)
        }
        // Even with require_cryptographic_holder_binding: false, a credential containing `cnf` must reject stripped KB-JWT
        assertFailsWith<IllegalStateException> {
            buildRecord(kbStripped, buildRequest(""", "require_cryptographic_holder_binding": false""")).verify(atTime = now)
        }

        // 2. Expired or not-yet-valid credential must be rejected
        val futureTime = now + 20.days
        val futurePresentation = credential.present(
            signingKey = holderKey,
            nonce = nonce,
            audience = CLIENT_ID,
            creationTime = futureTime,
        ).compactSerialization
        assertFailsWith<IllegalStateException> {
            buildRecord(futurePresentation).verify(atTime = futureTime)
        }
        val pastTime = now - 5.days
        val pastPresentation = credential.present(
            signingKey = holderKey,
            nonce = nonce,
            audience = CLIENT_ID,
            creationTime = pastTime,
        ).compactSerialization
        assertFailsWith<IllegalStateException> {
            buildRecord(pastPresentation).verify(atTime = pastTime)
        }

        // 3. Wrong audience or stale KB-JWT creationTime must be rejected
        val wrongAudiencePresentation = credential.present(
            signingKey = holderKey,
            nonce = nonce,
            audience = "x509_san_dns:other-verifier.example",
            creationTime = now,
        ).compactSerialization
        assertFailsWith<IllegalStateException> {
            buildRecord(wrongAudiencePresentation).verify(atTime = now)
        }
        val staleKbPresentation = credential.present(
            signingKey = holderKey,
            nonce = nonce,
            audience = CLIENT_ID,
            creationTime = now - 10.minutes,
        ).compactSerialization
        assertFailsWith<IllegalStateException> {
            buildRecord(staleKbPresentation).verify(atTime = now)
        }
    }

    @Test
    fun testOtherDocumentSdJwtAudienceCheckInDeviceResponse() = runTest {
        setup()
        val now = Clock.System.now()
        val sessionTranscript = buildCborArray { add(Simple.NULL); add(Simple.NULL); add(byteArrayOf(1, 2, 3)) }
        val sessionTranscriptBytes = Tagged(
            tagNumber = Tagged.ENCODED_CBOR,
            taggedItem = Bstr(Cbor.encode(sessionTranscript))
        )
        val expectedNonce = Crypto.digest(Algorithm.SHA256, Cbor.encode(sessionTranscriptBytes)).toBase64Url()

        val holderPrivateKey = Crypto.createEcPrivateKey(EcCurve.P256)
        val holderKey = AsymmetricKey.anonymous(holderPrivateKey, Algorithm.ESP256)
        val credential = SdJwt.create(
            issuerKey = harness.dsKey,
            kbKey = holderPrivateKey.publicKey,
            claims = buildJsonObject { put("given_name", "Erika") },
            nonSdClaims = buildJsonObject {
                put("iss", "https://issuer.example")
                put("vct", EUPersonalID.EUPID_VCT)
                put("nbf", (now - 1.days).epochSeconds)
            },
            creationTime = now - 1.days,
            expiresIn = 11.days,
        )
        // Presentation with legacy aud = "none" must be rejected per DIS Annex H.2
        val legacyAudPresentation = credential.present(
            signingKey = holderKey,
            nonce = expectedNonce,
            audience = "none",
            creationTime = now,
        ).compactSerialization

        val deviceResponse = buildDeviceResponse(
            sessionTranscript = sessionTranscript,
            status = DeviceResponse.STATUS_OK,
        ) {
            addOtherDocument(
                OtherDocument(
                    docFormat = "dc+sd-jwt",
                    data = ByteString(legacyAudPresentation.encodeToByteArray().zlibDeflate())
                )
            )
        }
        val signedDeviceRequest = buildDeviceRequest(
            sessionTranscript = sessionTranscript,
            deviceRequestInfo = DeviceRequestInfo.fromValues(
                useCases = listOf(
                    UseCase(
                        mandatory = true,
                        documentSets = listOf(DocumentSet(listOf(0))),
                        purposeHints = emptyMap()
                    )
                )
            )
        ) {
            addDocRequest(
                docType = EUPersonalID.EUPID_VCT,
                nameSpaces = mapOf("_" to mapOf("given_name" to false)),
                docRequestInfo = DocRequestInfo(
                    docFormat = "dc+sd-jwt",
                    dataElementIdentifierMapping = mapOf(
                        "given_name" to buildJsonArray { add(JsonPrimitive("given_name")) }
                    )
                )
            )
            addReaderAuthAll(readerIdentity.key)
        }

        // An aud="none" OtherDocument must fail verification against DIS Annex H.2 audience check
        val drParsed = DeviceResponse.fromDataItem(deviceResponse.toDataItem())
        assertFailsWith<IllegalStateException> {
            drParsed.verify(
                sessionTranscript = sessionTranscript,
                deviceRequest = signedDeviceRequest,
                documentTypeRepository = harness.documentTypeRepository,
                atTime = now,
            )
        }
    }

    @Test
    fun testDcqlQueryConstraintsEnforced() = runTest {
        setup()
        val now = Clock.System.now()
        val nonce = "test-nonce-dcql"
        val holderPrivateKey = Crypto.createEcPrivateKey(EcCurve.P256)
        val holderKey = AsymmetricKey.anonymous(holderPrivateKey, Algorithm.ESP256)
        val basicUserPresentation = SdJwt.create(
            issuerKey = harness.dsKey,
            kbKey = holderPrivateKey.publicKey,
            claims = buildJsonObject { put("given_name", "Erika") },
            nonSdClaims = buildJsonObject {
                put("iss", "https://issuer.example")
                put("vct", "urn:example:basic-user")
                put("nbf", (now - 1.days).epochSeconds)
            },
            creationTime = now - 1.days,
            expiresIn = 11.days,
        ).present(
            signingKey = holderKey,
            nonce = nonce,
            audience = CLIENT_ID,
            creationTime = now,
        ).compactSerialization

        val adminDcql = """
            {
              "credentials": [{
                "id": "administrator",
                "format": "dc+sd-jwt",
                "meta": { "vct_values": ["urn:example:administrator"] },
                "claims": [{ "path": ["admin_status"] }]
              }]
            }
        """.trimIndent()
        val adminRequest = """
            {
              "client_id": "$CLIENT_ID",
              "nonce": "$nonce",
              "response_uri": "https://$DNS_NAME/post",
              "dcql_query": $adminDcql
            }
        """.trimIndent()

        // 1. Type (vct) substitution under a privileged query ID is rejected
        val substitutedRecord = OpenID4VPPresentmentRecord(
            vpToken = buildJsonObject { putJsonArray("administrator") { add(basicUserPresentation) } }.toString(),
            vpRequest = adminRequest,
            mdocSessionTranscript = null,
        )
        assertFailsWith<IllegalStateException> {
            substitutedRecord.verify(atTime = now)
        }

        // 2. Correct VCT but missing requested claim ("admin_status") is rejected
        val missingClaimPresentation = SdJwt.create(
            issuerKey = harness.dsKey,
            kbKey = holderPrivateKey.publicKey,
            claims = buildJsonObject { put("given_name", "Erika") },
            nonSdClaims = buildJsonObject {
                put("iss", "https://issuer.example")
                put("vct", "urn:example:administrator")
                put("nbf", (now - 1.days).epochSeconds)
            },
            creationTime = now - 1.days,
            expiresIn = 11.days,
        ).present(
            signingKey = holderKey,
            nonce = nonce,
            audience = CLIENT_ID,
            creationTime = now,
        ).compactSerialization
        assertFailsWith<IllegalStateException> {
            OpenID4VPPresentmentRecord(
                vpToken = buildJsonObject { putJsonArray("administrator") { add(missingClaimPresentation) } }.toString(),
                vpRequest = adminRequest,
                mdocSessionTranscript = null,
            ).verify(atTime = now)
        }

        // 3. Empty vp_token ("{}") or unknown query ID is rejected
        assertFailsWith<IllegalStateException> {
            OpenID4VPPresentmentRecord(
                vpToken = "{}",
                vpRequest = adminRequest,
                mdocSessionTranscript = null,
            ).verify(atTime = now)
        }
        assertFailsWith<IllegalStateException> {
            OpenID4VPPresentmentRecord(
                vpToken = buildJsonObject { putJsonArray("unknown_id") { add(basicUserPresentation) } }.toString(),
                vpRequest = adminRequest,
                mdocSessionTranscript = null,
            ).verify(atTime = now)
        }

        // 4. Multiple presentations when multiple=false is rejected, while multiple=true succeeds
        val validAdminPresentation = SdJwt.create(
            issuerKey = harness.dsKey,
            kbKey = holderPrivateKey.publicKey,
            claims = buildJsonObject { put("admin_status", "super") },
            nonSdClaims = buildJsonObject {
                put("iss", "https://issuer.example")
                put("vct", "urn:example:administrator")
                put("nbf", (now - 1.days).epochSeconds)
            },
            creationTime = now - 1.days,
            expiresIn = 11.days,
        ).present(
            signingKey = holderKey,
            nonce = nonce,
            audience = CLIENT_ID,
            creationTime = now,
        ).compactSerialization
        assertFailsWith<IllegalStateException> {
            OpenID4VPPresentmentRecord(
                vpToken = buildJsonObject {
                    putJsonArray("administrator") {
                        add(validAdminPresentation)
                        add(validAdminPresentation)
                    }
                }.toString(),
                vpRequest = adminRequest,
                mdocSessionTranscript = null,
            ).verify(atTime = now)
        }

        val multiAdminDcql = """
            {
              "credentials": [{
                "id": "administrator",
                "format": "dc+sd-jwt",
                "multiple": true,
                "meta": { "vct_values": ["urn:example:administrator"] },
                "claims": [{ "path": ["admin_status"] }]
              }]
            }
        """.trimIndent()
        val multiVerified = OpenID4VPPresentmentRecord(
            vpToken = buildJsonObject {
                putJsonArray("administrator") {
                    add(validAdminPresentation)
                    add(validAdminPresentation)
                }
            }.toString(),
            vpRequest = """
                {
                  "client_id": "$CLIENT_ID",
                  "nonce": "$nonce",
                  "response_uri": "https://$DNS_NAME/post",
                  "dcql_query": $multiAdminDcql
                }
            """.trimIndent(),
            mdocSessionTranscript = null,
        ).verify(atTime = now)
        assertEquals(2, multiVerified.size)
        val processedMulti = DcqlProcessedResponse(multiAdminDcql, multiVerified)
        assertEquals(setOf("administrator"), processedMulti.multipleQueryIds)
        assertEquals(2, processedMulti.credentialLists["administrator"]?.size)
    }

    @Test
    fun testSdJwtKbClaimsSeparatedFromIssuerSignedClaimsAndCannotSatisfyDcql() = runTest {
        setup()
        val now = Clock.System.now()
        val nonce = "test-nonce-kb-separation"
        val holderPrivateKey = Crypto.createEcPrivateKey(EcCurve.P256)
        val holderKey = AsymmetricKey.anonymous(holderPrivateKey, Algorithm.ESP256)
        val credential = SdJwt.create(
            issuerKey = harness.dsKey,
            kbKey = holderPrivateKey.publicKey,
            claims = buildJsonObject {
                put("given_name", "Erika")
                put("age_over_21", false)
            },
            nonSdClaims = buildJsonObject {
                put("iss", "https://issuer.example")
                put("vct", EUPersonalID.EUPID_VCT)
                put("nbf", (now - 1.days).epochSeconds)
            },
            creationTime = now - 1.days,
            expiresIn = 11.days,
        )

        // 1. Colliding and unissued claims in KB-JWT must go to deviceSignedClaims, never issuerSignedClaims,
        //    and must not overwrite issuer-signed values in DcqlProcessedResponse even when an explicit claim id is used.
        val collidingPresentation = credential.present(
            signingKey = holderKey,
            nonce = nonce,
            audience = CLIENT_ID,
            creationTime = now,
        ) {
            put("given_name", "Mallory")
            put("is_admin", true)
        }.compactSerialization

        val dcqlWithId = """
            {
              "credentials": [{
                "id": "pid",
                "format": "dc+sd-jwt",
                "meta": { "vct_values": ["${EUPersonalID.EUPID_VCT}"] },
                "claims": [{ "id": "claim_given_name", "path": ["given_name"] }]
              }]
            }
        """.trimIndent()
        val requestWithId = """
            {
              "client_id": "$CLIENT_ID",
              "nonce": "$nonce",
              "response_uri": "https://$DNS_NAME/post",
              "dcql_query": $dcqlWithId
            }
        """.trimIndent()

        val verifiedList = OpenID4VPPresentmentRecord(
            vpToken = buildJsonObject { putJsonArray("pid") { add(collidingPresentation) } }.toString(),
            vpRequest = requestWithId,
            mdocSessionTranscript = null,
        ).verify(
            atTime = now,
            documentTypeRepository = harness.documentTypeRepository,
        )
        val vp = assertIs<JsonVerifiedPresentation>(verifiedList.single())
        val issuerClaims = vp.issuerSignedClaims.associate {
            it.claimPath.last().jsonPrimitive.content to it.value
        }
        val deviceClaims = vp.deviceSignedClaims.associate {
            it.claimPath.last().jsonPrimitive.content to it.value
        }
        assertEquals("Erika", issuerClaims["given_name"]!!.jsonPrimitive.content)
        assertEquals(null, issuerClaims["is_admin"])
        assertEquals(null, issuerClaims["nonce"])
        assertEquals("Mallory", deviceClaims["given_name"]!!.jsonPrimitive.content)
        assertEquals(true, deviceClaims["is_admin"]!!.jsonPrimitive.boolean)
        assertEquals(nonce, deviceClaims["nonce"]!!.jsonPrimitive.content)

        val processed = DcqlProcessedResponse(dcqlWithId, verifiedList)
        val processedCred = processed.credentials["pid"]!!
        assertEquals("Erika", processedCred.issuerSignedClaims["claim_given_name"]!!.render())
        assertEquals(null, processedCred.issuerSignedClaims["is_admin"])
        assertEquals("Mallory", processedCred.deviceSignedClaims["given_name"]!!.render())
        assertEquals(null, processedCred.deviceSignedClaims["claim_given_name"])

        // 2. A KB-JWT claim must NOT satisfy a missing requested claim in DCQL for dc+sd-jwt.
        val dcqlMissingClaim = """
            {
              "credentials": [{
                "id": "pid",
                "format": "dc+sd-jwt",
                "meta": { "vct_values": ["${EUPersonalID.EUPID_VCT}"] },
                "claims": [{ "path": ["is_admin"] }]
              }]
            }
        """.trimIndent()
        assertFailsWith<IllegalStateException> {
            OpenID4VPPresentmentRecord(
                vpToken = buildJsonObject { putJsonArray("pid") { add(collidingPresentation) } }.toString(),
                vpRequest = """
                    {
                      "client_id": "$CLIENT_ID",
                      "nonce": "$nonce",
                      "response_uri": "https://$DNS_NAME/post",
                      "dcql_query": $dcqlMissingClaim
                    }
                """.trimIndent(),
                mdocSessionTranscript = null,
            ).verify(atTime = now)
        }

        // 3. A KB-JWT claim must NOT satisfy a DCQL values constraint when the issuer-signed claim was omitted.
        val filteredWithoutAge = credential.filter { path, _ ->
            path.first().jsonPrimitive.content != "age_over_21"
        }.present(
            signingKey = holderKey,
            nonce = nonce,
            audience = CLIENT_ID,
            creationTime = now,
        ) {
            put("age_over_21", true)
        }.compactSerialization

        val dcqlAgeGate = """
            {
              "credentials": [{
                "id": "pid",
                "format": "dc+sd-jwt",
                "meta": { "vct_values": ["${EUPersonalID.EUPID_VCT}"] },
                "claims": [{ "path": ["age_over_21"], "values": [true] }]
              }]
            }
        """.trimIndent()
        assertFailsWith<IllegalStateException> {
            OpenID4VPPresentmentRecord(
                vpToken = buildJsonObject { putJsonArray("pid") { add(filteredWithoutAge) } }.toString(),
                vpRequest = """
                    {
                      "client_id": "$CLIENT_ID",
                      "nonce": "$nonce",
                      "response_uri": "https://$DNS_NAME/post",
                      "dcql_query": $dcqlAgeGate
                    }
                """.trimIndent(),
                mdocSessionTranscript = null,
            ).verify(atTime = now)
        }
    }

    private suspend fun assertVerifiedKeylessMovieTicketMdoc(record: PresentmentRecord) {
        val presentations = record.verify(
            documentTypeRepository = harness.documentTypeRepository
        )
        assertEquals(1, presentations.size)
        val mdoc = assertIs<MdocVerifiedPresentation>(presentations[0])
        assertFalse(mdoc.holderBound)
        assertEquals(UtopiaMovieTicket.MOVIE_TICKET_DOCTYPE, mdoc.docType)
        assertTrue(mdoc.deviceSignedClaims.isEmpty())

        val claims = mdoc.issuerSignedClaims.associate { it.dataElementName to it.value }
        assertEquals("987654321", claims["ticket_id"]!!.asTstr)
        assertEquals("The Last Utopian", claims["movie"]!!.asTstr)

        assertEquals(
            harness.dsKey.publicKey,
            mdoc.documentSignerCertChain.certificates.first().ecPublicKey
        )

        val processed = DcqlProcessedResponse(MOVIE_TICKET_MDOC_KEYLESS_DCQL, presentations)
        val ticketCred = assertNotNull(processed.credentials["ticket"])
        assertEquals("987654321", ticketCred.issuerSignedClaims["ticket_id"]!!.render())
        assertEquals("The Last Utopian", ticketCred.issuerSignedClaims["movie"]!!.render())
    }

    private suspend fun assertVerifiedKeylessMovieTicketSdJwt(record: PresentmentRecord) {
        val presentations = record.verify(
            documentTypeRepository = harness.documentTypeRepository
        )
        assertEquals(1, presentations.size)
        val ticket = assertIs<JsonVerifiedPresentation>(presentations[0])
        assertFalse(ticket.holderBound)
        assertEquals(UtopiaMovieTicket.MOVIE_TICKET_VCT, ticket.vct)
        assertTrue(ticket.deviceSignedClaims.isEmpty())

        val claims = ticket.issuerSignedClaims.associate {
            it.claimPath.last().jsonPrimitive.content to it.value
        }
        assertEquals("987654321", claims["ticket_id"]!!.jsonPrimitive.content)
        assertEquals("The Last Utopian", claims["movie"]!!.jsonPrimitive.content)

        assertEquals(
            harness.dsKey.publicKey,
            ticket.documentSignerCertChain.certificates.first().ecPublicKey
        )

        val processed = DcqlProcessedResponse(MOVIE_TICKET_SDJWT_KEYLESS_DCQL, presentations)
        val ticketCred = assertNotNull(processed.credentials["ticket"])
        assertEquals("987654321", ticketCred.issuerSignedClaims["ticket_id"]!!.render())
        assertEquals("The Last Utopian", ticketCred.issuerSignedClaims["movie"]!!.render())
    }

    @Test
    fun testKeylessMdocAcrossAllTransports() = runTest {
        setup()

        // 1. OpenID4VP URI scheme
        val uriNonce = ByteString(Random.nextBytes(18))
        val uriRecord = openID4VPUriSchemeRoundTrip(
            dcql = MOVIE_TICKET_MDOC_KEYLESS_DCQL,
            transactionData = emptyList(),
            nonce = uriNonce,
        )
        assertVerifiedKeylessMovieTicketMdoc(uriRecord)

        // 2. DC API OpenID4VP (openid4vp-v1-signed)
        val dcOpenIdNonce = ByteString(Random.nextBytes(18))
        val dcOpenIdSession = VerificationUtil.generateVerificationSessionForDcql(
            requestTypes = setOf(VerificationSession.RequestType.DC_OPENID4VP),
            dcql = MOVIE_TICKET_MDOC_KEYLESS_DCQL,
            verifierIdentities = listOf(readerIdentity),
            origin = ORIGIN,
            nonce = dcOpenIdNonce,
            encryptResponse = true,
            documentTypeRepository = harness.documentTypeRepository,
        )
        val dcOpenIdResponse = walletDcApiResponse(dcOpenIdSession, protocolName = "openid4vp-v1-signed")
        val dcOpenIdRecord = dcOpenIdSession.processDcResponse(dcOpenIdResponse)
        dcOpenIdRecord.verifyNonce(dcOpenIdNonce)
        assertVerifiedKeylessMovieTicketMdoc(dcOpenIdRecord)

        // 3. DC API ISO 18013-7 Annex C (org-iso-mdoc)
        val dcIsoNonce = ByteString(Random.nextBytes(18))
        val dcIsoSession = VerificationUtil.generateVerificationSessionForDcql(
            requestTypes = setOf(VerificationSession.RequestType.DC_ISO_18013),
            dcql = MOVIE_TICKET_MDOC_KEYLESS_DCQL,
            verifierIdentities = listOf(readerIdentity),
            origin = ORIGIN,
            nonce = dcIsoNonce,
            encryptResponse = true,
            documentTypeRepository = harness.documentTypeRepository,
        )
        val dcIsoResponse = walletDcApiResponse(dcIsoSession, protocolName = "org-iso-mdoc")
        val dcIsoRecord = dcIsoSession.processDcResponse(dcIsoResponse)
        dcIsoRecord.verifyNonce(dcIsoNonce)
        assertVerifiedKeylessMovieTicketMdoc(dcIsoRecord)

        // 4. ISO 18013-5 Proximity
        val eDeviceKey = Crypto.createEcPrivateKey(EcCurve.P256)
        val eReaderKey = Crypto.createEcPrivateKey(EcCurve.P256)
        val deviceEngagement = ByteString(
            Cbor.encode(buildDeviceEngagement(eDeviceKey = eDeviceKey.publicKey) {}.toDataItem())
        )
        val proximitySession = VerificationUtil.generateVerificationSessionForDcql(
            requestTypes = setOf(VerificationSession.RequestType.ISO_18013_PROXIMITY),
            dcql = MOVIE_TICKET_MDOC_KEYLESS_DCQL,
            verifierIdentities = listOf(readerIdentity),
            documentTypeRepository = harness.documentTypeRepository,
            deviceEngagement = deviceEngagement,
            handover = Simple.NULL,
            eReaderKey = eReaderKey,
        )
        val proximityRequest = proximitySession.find<VerificationSession.Iso18013ProximityRequest>()
        val parsedRequest = DeviceRequest.fromDataItem(proximityRequest.deviceRequest)
        parsedRequest.verifyReaderAuthentication(proximityRequest.sessionTranscript)
        val mdocResponse = mdocPresentment(
            deviceRequest = parsedRequest,
            eReaderKey = eReaderKey.publicKey,
            sessionTranscript = proximityRequest.sessionTranscript,
            source = harness.presentmentSource,
            keyAgreementPossible = emptyList(),
            requesterAppId = null,
            requesterOrigin = null,
            preselectedDocuments = emptyList(),
            onWaitingForUserInput = {},
            onDocumentsInFocus = {},
        )
        val proximityRecord = proximitySession.processIso18013ProximityResponse(
            deviceResponse = mdocResponse.deviceResponse.toDataItem()
        )
        assertVerifiedKeylessMovieTicketMdoc(proximityRecord)
    }

    @Test
    fun testKeylessSdJwtAcrossAllTransports() = runTest {
        setup()

        // 1. OpenID4VP URI scheme
        val uriNonce = ByteString(Random.nextBytes(18))
        val uriRecord = openID4VPUriSchemeRoundTrip(
            dcql = MOVIE_TICKET_SDJWT_KEYLESS_DCQL,
            transactionData = emptyList(),
            nonce = uriNonce,
        )
        assertVerifiedKeylessMovieTicketSdJwt(uriRecord)

        // 2. DC API OpenID4VP (openid4vp-v1-signed)
        val dcOpenIdNonce = ByteString(Random.nextBytes(18))
        val dcOpenIdSession = VerificationUtil.generateVerificationSessionForDcql(
            requestTypes = setOf(VerificationSession.RequestType.DC_OPENID4VP),
            dcql = MOVIE_TICKET_SDJWT_KEYLESS_DCQL,
            verifierIdentities = listOf(readerIdentity),
            origin = ORIGIN,
            nonce = dcOpenIdNonce,
            encryptResponse = true,
            documentTypeRepository = harness.documentTypeRepository,
        )
        val dcOpenIdResponse = walletDcApiResponse(dcOpenIdSession, protocolName = "openid4vp-v1-signed")
        val dcOpenIdRecord = dcOpenIdSession.processDcResponse(dcOpenIdResponse)
        dcOpenIdRecord.verifyNonce(dcOpenIdNonce)
        assertVerifiedKeylessMovieTicketSdJwt(dcOpenIdRecord)

        // 3. DC API ISO 18013-7 Annex C (org-iso-mdoc)
        val dcIsoNonce = ByteString(Random.nextBytes(18))
        val dcIsoSession = VerificationUtil.generateVerificationSessionForDcql(
            requestTypes = setOf(VerificationSession.RequestType.DC_ISO_18013),
            dcql = MOVIE_TICKET_SDJWT_KEYLESS_DCQL,
            verifierIdentities = listOf(readerIdentity),
            origin = ORIGIN,
            nonce = dcIsoNonce,
            encryptResponse = true,
            documentTypeRepository = harness.documentTypeRepository,
        )
        val dcIsoResponse = walletDcApiResponse(dcIsoSession, protocolName = "org-iso-mdoc")
        val dcIsoRecord = dcIsoSession.processDcResponse(dcIsoResponse)
        dcIsoRecord.verifyNonce(dcIsoNonce)
        assertVerifiedKeylessMovieTicketSdJwt(dcIsoRecord)

        // 4. ISO 18013-5 Proximity
        val eDeviceKey = Crypto.createEcPrivateKey(EcCurve.P256)
        val eReaderKey = Crypto.createEcPrivateKey(EcCurve.P256)
        val deviceEngagement = ByteString(
            Cbor.encode(buildDeviceEngagement(eDeviceKey = eDeviceKey.publicKey) {}.toDataItem())
        )
        val proximitySession = VerificationUtil.generateVerificationSessionForDcql(
            requestTypes = setOf(VerificationSession.RequestType.ISO_18013_PROXIMITY),
            dcql = MOVIE_TICKET_SDJWT_KEYLESS_DCQL,
            verifierIdentities = listOf(readerIdentity),
            documentTypeRepository = harness.documentTypeRepository,
            deviceEngagement = deviceEngagement,
            handover = Simple.NULL,
            eReaderKey = eReaderKey,
        )
        val proximityRequest = proximitySession.find<VerificationSession.Iso18013ProximityRequest>()
        val parsedRequest = DeviceRequest.fromDataItem(proximityRequest.deviceRequest)
        parsedRequest.verifyReaderAuthentication(proximityRequest.sessionTranscript)
        val mdocResponse = mdocPresentment(
            deviceRequest = parsedRequest,
            eReaderKey = eReaderKey.publicKey,
            sessionTranscript = proximityRequest.sessionTranscript,
            source = harness.presentmentSource,
            keyAgreementPossible = emptyList(),
            requesterAppId = null,
            requesterOrigin = null,
            preselectedDocuments = emptyList(),
            onWaitingForUserInput = {},
            onDocumentsInFocus = {},
        )
        val proximityRecord = proximitySession.processIso18013ProximityResponse(
            deviceResponse = mdocResponse.deviceResponse.toDataItem()
        )
        assertVerifiedKeylessMovieTicketSdJwt(proximityRecord)
    }

    @Test
    fun testKeylessHolderBindingEnforcementAndMdocAntiStripping() = runTest {
        setup()
        val now = Clock.System.now()
        val nonce = "test-nonce-keyless-enforcement"
        val sessionTranscript = buildCborArray {
            add(Simple.NULL)
            add(Simple.NULL)
            add(byteArrayOf(7, 8, 9))
        }

        // 1. Keyless SD-JWT must be rejected when require_cryptographic_holder_binding is omitted or true
        val keylessSdJwt = SdJwt.create(
            issuerKey = harness.dsKey,
            kbKey = null,
            claims = buildJsonObject { put("ticket_id", "987654321") },
            nonSdClaims = buildJsonObject {
                put("iss", "https://issuer.example")
                put("vct", UtopiaMovieTicket.MOVIE_TICKET_VCT)
                put("nbf", (now - 1.days).epochSeconds)
            },
            creationTime = now - 1.days,
            expiresIn = 11.days,
        ).compactSerialization

        fun buildSdJwtRequest(extraFields: String = ""): String = """
            {
              "client_id": "$CLIENT_ID",
              "nonce": "$nonce",
              "response_uri": "https://$DNS_NAME/post",
              "dcql_query": {
                "credentials": [{
                  "id": "ticket",
                  "format": "dc+sd-jwt",
                  "meta": { "vct_values": ["${UtopiaMovieTicket.MOVIE_TICKET_VCT}"] },
                  "claims": [{ "path": ["ticket_id"] }]
                  $extraFields
                }]
              }
            }
        """.trimIndent()

        // Default (omitted -> true) and explicit true reject keyless SD-JWT
        assertFailsWith<IllegalStateException> {
            OpenID4VPPresentmentRecord(
                vpToken = buildJsonObject { putJsonArray("ticket") { add(keylessSdJwt) } }.toString(),
                vpRequest = buildSdJwtRequest(),
                mdocSessionTranscript = null,
            ).verify(atTime = now)
        }
        assertFailsWith<IllegalStateException> {
            OpenID4VPPresentmentRecord(
                vpToken = buildJsonObject { putJsonArray("ticket") { add(keylessSdJwt) } }.toString(),
                vpRequest = buildSdJwtRequest(""", "require_cryptographic_holder_binding": true"""),
                mdocSessionTranscript = null,
            ).verify(atTime = now)
        }
        // Explicit false accepts keyless SD-JWT
        val verifiedKeylessSdJwt = OpenID4VPPresentmentRecord(
            vpToken = buildJsonObject { putJsonArray("ticket") { add(keylessSdJwt) } }.toString(),
            vpRequest = buildSdJwtRequest(""", "require_cryptographic_holder_binding": false"""),
            mdocSessionTranscript = null,
        ).verify(atTime = now)
        assertFalse(verifiedKeylessSdJwt.single().holderBound)

        // 2. Keyless mdoc must be rejected when require_cryptographic_holder_binding is omitted or true
        val keylessMdocDoc = harness.documentStore.listDocumentIds()
            .map { harness.documentStore.lookupDocument(it)!! }
            .first { it.displayName == "Movie Ticket Mdoc" }
        val keylessMdocCred = keylessMdocDoc.getCertifiedCredentials().single() as KeylessMdocCredential
        val keylessMdocResponseB64 = Cbor.encode(
            buildDeviceResponse(
                sessionTranscript = sessionTranscript,
                status = DeviceResponse.STATUS_OK,
            ) {
                addDocument(
                    credential = keylessMdocCred,
                    requestedClaims = listOf(
                        MdocRequestedClaim(
                            docType = UtopiaMovieTicket.MOVIE_TICKET_DOCTYPE,
                            namespaceName = UtopiaMovieTicket.MOVIE_TICKET_NAMESPACE,
                            dataElementName = "ticket_id",
                            intentToRetain = false
                        )
                    )
                )
            }.toDataItem()
        ).toBase64Url()

        fun buildMdocRequest(docType: String, namespace: String, claim: String, extraFields: String = ""): String = """
            {
              "client_id": "$CLIENT_ID",
              "nonce": "$nonce",
              "response_uri": "https://$DNS_NAME/post",
              "dcql_query": {
                "credentials": [{
                  "id": "doc",
                  "format": "mso_mdoc",
                  "meta": { "doctype_value": "$docType" },
                  "claims": [{ "path": ["$namespace", "$claim"] }]
                  $extraFields
                }]
              }
            }
        """.trimIndent()

        assertFailsWith<IllegalStateException> {
            OpenID4VPPresentmentRecord(
                vpToken = buildJsonObject { putJsonArray("doc") { add(keylessMdocResponseB64) } }.toString(),
                vpRequest = buildMdocRequest(
                    UtopiaMovieTicket.MOVIE_TICKET_DOCTYPE,
                    UtopiaMovieTicket.MOVIE_TICKET_NAMESPACE,
                    "ticket_id"
                ),
                mdocSessionTranscript = sessionTranscript,
            ).verify(atTime = now)
        }
        assertFailsWith<IllegalStateException> {
            OpenID4VPPresentmentRecord(
                vpToken = buildJsonObject { putJsonArray("doc") { add(keylessMdocResponseB64) } }.toString(),
                vpRequest = buildMdocRequest(
                    UtopiaMovieTicket.MOVIE_TICKET_DOCTYPE,
                    UtopiaMovieTicket.MOVIE_TICKET_NAMESPACE,
                    "ticket_id",
                    """, "require_cryptographic_holder_binding": true"""
                ),
                mdocSessionTranscript = sessionTranscript,
            ).verify(atTime = now)
        }
        val verifiedKeylessMdoc = OpenID4VPPresentmentRecord(
            vpToken = buildJsonObject { putJsonArray("doc") { add(keylessMdocResponseB64) } }.toString(),
            vpRequest = buildMdocRequest(
                UtopiaMovieTicket.MOVIE_TICKET_DOCTYPE,
                UtopiaMovieTicket.MOVIE_TICKET_NAMESPACE,
                "ticket_id",
                """, "require_cryptographic_holder_binding": false"""
            ),
            mdocSessionTranscript = sessionTranscript,
        ).verify(atTime = now)
        assertFalse(verifiedKeylessMdoc.single().holderBound)

        // 3. Anti-stripping: a key-bound mdoc (MSO contains deviceKeyInfo) with deviceSigned stripped
        //    must be rejected EVEN when require_cryptographic_holder_binding is false!
        val keyBoundPhotoIdCred = harness.docPhotoId.getCertifiedCredentials()
            .filterIsInstance<KeyBoundMdocCredential>()
            .first()
        val strippedDocumentItem = buildCborMap {
            put("docType", PhotoID.PHOTO_ID_DOCTYPE)
            put("issuerSigned", buildCborMap {
                put(
                    "nameSpaces",
                    keyBoundPhotoIdCred.issuerNamespaces
                        .filter(
                            listOf(
                                MdocRequestedClaim(
                                    docType = PhotoID.PHOTO_ID_DOCTYPE,
                                    namespaceName = PhotoID.PHOTO_ID_NAMESPACE,
                                    dataElementName = "given_name",
                                    intentToRetain = false
                                )
                            )
                        )
                        .toDataItem()
                )
                put("issuerAuth", keyBoundPhotoIdCred.issuerAuth.toDataItem())
            })
            // Intentionally omit "deviceSigned" on a key-bound MSO
        }
        val strippedDeviceResponseB64 = Cbor.encode(
            buildCborMap {
                put("version", "1.0")
                put("documents", buildCborArray { add(strippedDocumentItem) })
                put("status", 0L)
            }
        ).toBase64Url()

        assertFailsWith<IllegalStateException> {
            OpenID4VPPresentmentRecord(
                vpToken = buildJsonObject { putJsonArray("doc") { add(strippedDeviceResponseB64) } }.toString(),
                vpRequest = buildMdocRequest(
                    PhotoID.PHOTO_ID_DOCTYPE,
                    PhotoID.PHOTO_ID_NAMESPACE,
                    "given_name",
                    """, "require_cryptographic_holder_binding": false"""
                ),
                mdocSessionTranscript = sessionTranscript,
            ).verify(atTime = now)
        }
    }

    @Test
    fun testNonEcKeysAcrossProtocols() = runTest {
        setup()

        for (alg in listOf(Algorithm.ML_DSA_44, Algorithm.RS256_2048)) {
            val iacaRoot = AsymmetricKey.ephemeral(alg) as AsymmetricKey.AnonymousExplicit
            val iacaCert = MdocUtil.generateIacaCertificate(
                iacaKey = iacaRoot,
                subject = X500Name.fromName("CN=Non-EC IACA ($alg),C=US"),
                serial = ASN1Integer.fromRandom(128),
                validFrom = harness.validFrom,
                validUntil = harness.validUntil,
                issuerAltNameUrl = "https://issuer.example.com",
                crlUrl = "https://issuer.example.com/crl"
            )
            val dsKeyRaw = AsymmetricKey.ephemeral(alg) as AsymmetricKey.AnonymousExplicit
            val dsCert = MdocUtil.generateDsCertificate(
                iacaKey = AsymmetricKey.X509CertifiedExplicit(
                    certChain = X509CertChain(listOf(iacaCert)),
                    privateKey = iacaRoot.privateKey,
                    algorithm = alg
                ),
                dsKey = dsKeyRaw.publicKey,
                subject = X500Name.fromName("CN=Non-EC DS ($alg),C=US"),
                serial = ASN1Integer.fromRandom(128),
                validFrom = harness.validFrom,
                validUntil = harness.validUntil
            )
            val nonEcDsKey = AsymmetricKey.X509CertifiedExplicit(
                certChain = X509CertChain(listOf(dsCert, iacaCert)),
                privateKey = dsKeyRaw.privateKey,
                algorithm = alg
            )

            val readerRootRaw = AsymmetricKey.ephemeral(alg) as AsymmetricKey.AnonymousExplicit
            val readerRootCert = MdocUtil.generateReaderRootCertificate(
                readerRootKey = readerRootRaw,
                subject = X500Name.fromName("CN=Non-EC Reader Root ($alg),C=US"),
                serial = ASN1Integer.fromRandom(128),
                validFrom = harness.validFrom,
                validUntil = harness.validUntil,
                crlUrl = "https://verifier.multipaz.org/crl"
            )
            harness.readerTrustManager.addX509Cert(readerRootCert, TrustMetadata())
            val readerLeafRaw = AsymmetricKey.ephemeral(alg) as AsymmetricKey.AnonymousExplicit
            val readerLeafCert = MdocUtil.generateReaderCertificate(
                readerRootKey = AsymmetricKey.X509CertifiedExplicit(
                    certChain = X509CertChain(listOf(readerRootCert)),
                    privateKey = readerRootRaw.privateKey,
                    algorithm = alg
                ),
                readerKey = readerLeafRaw.publicKey,
                subject = X500Name.fromName("CN=Non-EC Reader ($alg),C=US"),
                dnsName = DNS_NAME,
                serial = ASN1Integer.fromRandom(128),
                validFrom = harness.validFrom,
                validUntil = harness.validUntil
            )
            val nonEcReaderIdentity = VerifierIdentity(
                key = AsymmetricKey.X509CertifiedExplicit(
                    certChain = X509CertChain(listOf(readerLeafCert, readerRootCert)),
                    privateKey = readerLeafRaw.privateKey,
                    algorithm = alg
                ),
                clientId = CLIENT_ID
            )

            val nonEcMdlDoc = harness.documentStore.createDocument(displayName = "Non-EC mDL ($alg)")
            DrivingLicense.getDocumentType().createMdocCredentialWithSampleData(
                document = nonEcMdlDoc,
                secureArea = harness.softwareSecureArea,
                createKeySettings = CreateKeySettings(algorithm = alg),
                dsKey = nonEcDsKey,
                signedAt = harness.signedAt,
                validFrom = harness.validFrom,
                validUntil = harness.validUntil,
                expectedUpdate = null,
                domain = "mdoc",
                deviceKeyAuthorizedNamespaces = listOf(
                    ISO_18013_TRANSACTION_DATA_NAMESPACE,
                    PingTransaction.openId4VpMdocResponseNamespace,
                )
            )
            nonEcMdlDoc.edit { provisioned = true }

            val nonEcPidDoc = harness.documentStore.createDocument(displayName = "Non-EC PID ($alg)")
            EUPersonalID.getDocumentType().createKeyBoundSdJwtVcCredentialWithSampleData(
                document = nonEcPidDoc,
                secureArea = harness.softwareSecureArea,
                createKeySettings = CreateKeySettings(algorithm = alg),
                dsKey = nonEcDsKey,
                signedAt = harness.signedAt,
                validFrom = harness.validFrom,
                validUntil = harness.validUntil,
                domain = "sdjwt"
            )
            nonEcPidDoc.edit { provisioned = true }

            // 1. DC API OpenID4VP (mso_mdoc + dc+sd-jwt) with non-EC reader JWS, issuerAuth, and deviceAuth
            for ((dcql, preselected, expectedFormatMdoc) in listOf(
                Triple(MDL_DCQL, nonEcMdlDoc, true),
                Triple(EU_PID_SDJWT_DCQL, nonEcPidDoc, false),
            )) {
                val nonce = ByteString(Random.nextBytes(18))
                val session = VerificationUtil.generateVerificationSessionForDcql(
                    requestTypes = setOf(VerificationSession.RequestType.DC_OPENID4VP),
                    dcql = dcql,
                    verifierIdentities = listOf(nonEcReaderIdentity),
                    origin = ORIGIN,
                    nonce = nonce,
                    encryptResponse = true,
                    documentTypeRepository = harness.documentTypeRepository,
                )
                val dcResponse = walletDcApiResponse(
                    session = session,
                    protocolName = "openid4vp-v1-signed",
                    preselectedDocuments = listOf(preselected)
                )
                val record = session.processDcResponse(dcResponse)
                record.verifyNonce(nonce)
                val verified = record.verify(documentTypeRepository = harness.documentTypeRepository)
                assertEquals(1, verified.size)
                assertTrue(verified.single().holderBound)
                assertEquals(nonEcDsKey.publicKey, verified.single().documentSignerCertChain!!.certificates.first().publicKey)
                if (expectedFormatMdoc) {
                    assertIs<MdocVerifiedPresentation>(verified.single())
                } else {
                    assertIs<JsonVerifiedPresentation>(verified.single())
                }
            }

            // 2. DC API ISO 18013-7 Annex C (mso_mdoc + dc+sd-jwt) with non-EC COSE readerAuth, issuerAuth, and deviceAuth
            for ((dcql, preselected, expectedFormatMdoc) in listOf(
                Triple(MDL_DCQL, nonEcMdlDoc, true),
                Triple(EU_PID_SDJWT_DCQL, nonEcPidDoc, false),
            )) {
                val nonce = ByteString(Random.nextBytes(18))
                val session = VerificationUtil.generateVerificationSessionForDcql(
                    requestTypes = setOf(VerificationSession.RequestType.DC_ISO_18013),
                    dcql = dcql,
                    verifierIdentities = listOf(nonEcReaderIdentity),
                    origin = ORIGIN,
                    nonce = nonce,
                    encryptResponse = true,
                    documentTypeRepository = harness.documentTypeRepository,
                )
                val dcResponse = walletDcApiResponse(
                    session = session,
                    protocolName = "org-iso-mdoc",
                    preselectedDocuments = listOf(preselected)
                )
                val record = session.processDcResponse(dcResponse)
                record.verifyNonce(nonce)
                val verified = record.verify(documentTypeRepository = harness.documentTypeRepository)
                assertEquals(1, verified.size)
                assertTrue(verified.single().holderBound)
                assertEquals(nonEcDsKey.publicKey, verified.single().documentSignerCertChain!!.certificates.first().publicKey)
                if (expectedFormatMdoc) {
                    assertIs<MdocVerifiedPresentation>(verified.single())
                } else {
                    assertIs<JsonVerifiedPresentation>(verified.single())
                }
            }

            // 3. ISO 18013-5 Proximity with EC eDeviceKey/eReaderKey + non-EC readerAuth, issuerAuth, and deviceAuth
            val eDeviceKey = Crypto.createEcPrivateKey(EcCurve.P256)
            val eReaderKey = Crypto.createEcPrivateKey(EcCurve.P256)
            val deviceEngagement = ByteString(
                Cbor.encode(buildDeviceEngagement(eDeviceKey = eDeviceKey.publicKey) {}.toDataItem())
            )
            val proximitySession = VerificationUtil.generateVerificationSessionForDcql(
                requestTypes = setOf(VerificationSession.RequestType.ISO_18013_PROXIMITY),
                dcql = MDL_DCQL,
                verifierIdentities = listOf(nonEcReaderIdentity),
                documentTypeRepository = harness.documentTypeRepository,
                deviceEngagement = deviceEngagement,
                handover = Simple.NULL,
                eReaderKey = eReaderKey,
            )
            val proximityRequest = proximitySession.find<VerificationSession.Iso18013ProximityRequest>()
            val parsedRequest = DeviceRequest.fromDataItem(proximityRequest.deviceRequest)
            parsedRequest.verifyReaderAuthentication(proximityRequest.sessionTranscript)
            val mdocResponse = mdocPresentment(
                deviceRequest = parsedRequest,
                eReaderKey = eReaderKey.publicKey,
                sessionTranscript = proximityRequest.sessionTranscript,
                source = harness.presentmentSource,
                keyAgreementPossible = listOf(EcCurve.P256),
                requesterAppId = null,
                requesterOrigin = null,
                preselectedDocuments = listOf(nonEcMdlDoc),
                onWaitingForUserInput = {},
                onDocumentsInFocus = {},
            )
            val proximityRecord = proximitySession.processIso18013ProximityResponse(
                deviceResponse = mdocResponse.deviceResponse.toDataItem()
            )
            val proximityVerified = proximityRecord.verify(documentTypeRepository = harness.documentTypeRepository)
            val mdocVerified = assertIs<MdocVerifiedPresentation>(proximityVerified.single())
            assertTrue(mdocVerified.holderBound)
            assertEquals(nonEcDsKey.publicKey, mdocVerified.documentSignerCertChain.certificates.first().publicKey)
        }
    }

    private companion object {
        const val ORIGIN = "https://verifier.multipaz.org"
        const val DNS_NAME = "verifier.multipaz.org"
        const val CLIENT_ID = "x509_san_dns:$DNS_NAME"
        const val APP_ID = "org.multipaz.testApp"

        val MDL_DCQL = """
            {
              "credentials": [{
                  "id": "mDL",
                  "format": "mso_mdoc",
                  "meta": { "doctype_value": "${DrivingLicense.MDL_DOCTYPE}" },
                  "claims": [
                    { "path": ["${DrivingLicense.MDL_NAMESPACE}", "given_name"] },
                    { "path": ["${DrivingLicense.MDL_NAMESPACE}", "family_name"] },
                    { "path": ["${DrivingLicense.MDL_NAMESPACE}", "age_over_21"] }
                  ]
              }]
            }
        """.trimIndent()

        val EU_PID_SDJWT_DCQL = """
            {
              "credentials": [{
                  "id": "pid",
                  "format": "dc+sd-jwt",
                  "meta": { "vct_values": [ "${EUPersonalID.EUPID_VCT}" ] },
                  "claims": [
                    { "path": ["given_name"] },
                    { "path": ["family_name"] },
                    { "path": ["place_of_birth", "country"] }
                  ]
              }]
            }
        """.trimIndent()

        val MOVIE_TICKET_MDOC_KEYLESS_DCQL = """
            {
              "credentials": [{
                  "id": "ticket",
                  "format": "mso_mdoc",
                  "require_cryptographic_holder_binding": false,
                  "meta": { "doctype_value": "${UtopiaMovieTicket.MOVIE_TICKET_DOCTYPE}" },
                  "claims": [
                    { "path": ["${UtopiaMovieTicket.MOVIE_TICKET_NAMESPACE}", "ticket_id"] },
                    { "path": ["${UtopiaMovieTicket.MOVIE_TICKET_NAMESPACE}", "movie"] }
                  ]
              }]
            }
        """.trimIndent()

        val MOVIE_TICKET_SDJWT_KEYLESS_DCQL = """
            {
              "credentials": [{
                  "id": "ticket",
                  "format": "dc+sd-jwt",
                  "require_cryptographic_holder_binding": false,
                  "meta": { "vct_values": [ "${UtopiaMovieTicket.MOVIE_TICKET_VCT}" ] },
                  "claims": [
                    { "path": ["ticket_id"] },
                    { "path": ["movie"] }
                  ]
              }]
            }
        """.trimIndent()
    }
}
