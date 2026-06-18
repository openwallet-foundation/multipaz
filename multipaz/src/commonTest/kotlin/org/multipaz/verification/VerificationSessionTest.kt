package org.multipaz.verification

import kotlinx.coroutines.test.runTest
import kotlinx.io.bytestring.ByteString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.multipaz.asn1.ASN1Integer
import org.multipaz.cbor.Cbor
import org.multipaz.cbor.Simple
import org.multipaz.crypto.Algorithm
import org.multipaz.crypto.AsymmetricKey
import org.multipaz.crypto.Crypto
import org.multipaz.crypto.EcCurve
import org.multipaz.crypto.JsonWebSignature
import org.multipaz.crypto.X500Name
import org.multipaz.crypto.X509CertChain
import org.multipaz.document.Document
import org.multipaz.documenttype.MultiDocumentCannedRequest
import org.multipaz.documenttype.knowntypes.DrivingLicense
import org.multipaz.documenttype.knowntypes.EUPersonalID
import org.multipaz.documenttype.knowntypes.PhotoID
import org.multipaz.mdoc.engagement.buildDeviceEngagement
import org.multipaz.mdoc.request.DeviceRequest
import org.multipaz.mdoc.util.MdocUtil
import org.multipaz.openid.OpenID4VP
import org.multipaz.presentment.DocumentStoreTestHarness
import org.multipaz.presentment.digitalCredentialsPresentment
import org.multipaz.presentment.mdocPresentment
import org.multipaz.request.OpenID4VPRequesterIdentity
import org.multipaz.util.toBase64Url
import org.multipaz.utopia.knowntypes.PingTransaction
import org.multipaz.utopia.knowntypes.wellKnownMultipleDocumentRequests
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull

class VerificationSessionTest {
    private val harness = DocumentStoreTestHarness()
    private lateinit var readerIdentity: VerifierIdentity
    private lateinit var secondaryIdentity: VerifierIdentity
    private val mdlPingTransactionData: List<String> by lazy {
        listOf(PingTransaction.sampleData.toJsonText(credentialId = "mDL"))
    }
    private val euPidPingTransactionData: List<String> by lazy {
        listOf(PingTransaction.sampleData.toJsonText(credentialId = "pid"))
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
        harness.provisionStandardDocuments()

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
            // Ping transaction round-trip: the holder echoes the request "string" attribute and
            // includes a transaction_data_hash binding the response to the request.
            val pingResponse = assertNotNull(
                mdoc.transactionResponses?.get(PingTransaction.identifier),
                "expected a Ping transaction response"
            )
            assertEquals("string data", pingResponse["string"]!!.asTstr)
            assertEquals(32, pingResponse["transaction_data_hash"]!!.asBstr.size)
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
        // Nested age_equal_or_over claim arrives as the top-level object containing the "18" key.
        assertEquals(true, claims["age_equal_or_over"]!!.jsonObject["18"]!!.jsonPrimitive.boolean)

        // Trust check: presented DS cert must match the one the harness signed the credential with.
        assertEquals(
            harness.dsKey.publicKey,
            pid.documentSignerCertChain.certificates.first().ecPublicKey
        )

        // Ping transaction round-trip: applyCbor("string") is echoed in the KB-JWT body under
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
        assertEquals(32, pingResponse["transaction_data_hash"]!!.asBstr.size)
        assertEquals(expectedDocRequestId, pingResponse["doc_request_id"]?.asNumber)
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
        JsonWebSignature.verify(signedJwt, requesterCertChain.certificates.first().ecPublicKey)

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
     */
    private suspend fun walletDcApiResponse(
        session: VerificationSession,
        protocolName: String,
        preselectedDocuments: List<Document> = emptyList(),
    ): JsonObject {
        val dcRequest = session.getDcRequest()
        val protocolEntry = dcRequest["requests"]!!.jsonArray
            .single { it.jsonObject["protocol"]!!.jsonPrimitive.content == protocolName }
            .jsonObject
        return digitalCredentialsPresentment(
            protocol = protocolEntry["protocol"]!!.jsonPrimitive.content,
            data = protocolEntry["data"]!!.jsonObject,
            appId = APP_ID,
            origin = ORIGIN,
            preselectedDocuments = preselectedDocuments,
            source = harness.presentmentSource,
        )
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
                    { "path": ["age_equal_or_over", "18"] }
                  ]
              }]
            }
        """.trimIndent()
    }
}
