package org.multipaz.presentment

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import org.multipaz.asn1.ASN1Integer
import org.multipaz.cbor.Cbor
import org.multipaz.cbor.DataItem
import org.multipaz.cbor.DiagnosticOption
import org.multipaz.cbor.Simple
import org.multipaz.cbor.Uint
import org.multipaz.cbor.addCborArray
import org.multipaz.cbor.buildCborArray
import org.multipaz.credential.Credential
import org.multipaz.crypto.Algorithm
import org.multipaz.crypto.AsymmetricKey
import org.multipaz.crypto.Crypto
import org.multipaz.crypto.EcCurve
import org.multipaz.crypto.EcPrivateKey
import org.multipaz.crypto.JsonWebEncryption
import org.multipaz.crypto.X500Name
import org.multipaz.crypto.X509CertChain
import org.multipaz.document.Document
import org.multipaz.documenttype.DocumentAttribute
import org.multipaz.documenttype.DocumentAttributeType
import org.multipaz.documenttype.MdocDataElement
import org.multipaz.documenttype.TransactionType
import org.multipaz.mdoc.response.DeviceResponse
import org.multipaz.mdoc.util.MdocUtil
import org.multipaz.openid.OpenID4VP
import org.multipaz.prompt.promptModelSilentConsent
import org.multipaz.request.Requester
import org.multipaz.sdjwt.SdJwtKb
import org.multipaz.trustmanagement.TrustPoint
import org.multipaz.util.Logger
import org.multipaz.util.fromBase64Url
import org.multipaz.util.toBase64Url
import kotlin.collections.iterator
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class DigitalCredentialsPresentmentTest {
    private object FooTransactionType: TransactionType(
        displayName = "Foo",
        identifier = "foo",
        attributes = transactionDataElements,
        kbJwtResponseClaimName = "kb_foo",
        mdocResponseNamespace = "FooNS"
    ) {
        override suspend fun isApplicable(
            transactionData: TransactionData,
            credential: Credential
        ): Boolean {
            return transactionData.attributes.getBoolean("succeed")!!
        }

        override suspend fun applyCbor(
            transactionData: TransactionData,
            credential: Credential
        ): Map<String, DataItem> = buildMap {
            put("result", Uint(42UL))
        }
    }

    private object BarTransactionType: TransactionType(
        displayName = "Bar",
        identifier = "bar",
        attributes = transactionDataElements
    ) {
        override suspend fun isApplicable(
            transactionData: TransactionData,
            credential: Credential
        ): Boolean {
            return transactionData.attributes.getBoolean("succeed")!!
        }

        override suspend fun applyCbor(
            transactionData: TransactionData,
            credential: Credential
        ): Map<String, DataItem> = buildMap {
            put("result", Uint(57UL))
        }
    }

    // Unregistered transaction type, will cause an error
    private object BuzTransactionType: TransactionType(
        displayName = "Buz",
        identifier = "buz",
        attributes = transactionDataElements
    ) {
        override suspend fun isApplicable(
            transactionData: TransactionData,
            credential: Credential
        ): Boolean = true
    }

    companion object {
        private const val TAG = "DigitalCredentialsPresentmentTest"

        private const val CLIENT_ID = "x509_san_dns:verifier.multipaz.org"
        private const val DNS_NAME = "verifier.multipaz.org"
        private const val ORIGIN = "https://verifier.multipaz.org"
        private const val APP_ID = "org.multipaz.testApp"

        private val transactionDataElements = listOf(MdocDataElement(
            attribute = DocumentAttribute(
                type = DocumentAttributeType.Boolean,
                identifier = "succeed",
                displayName = "succeed",
                description = "if false, transaction will be rejected"
            ),
            mandatory = true
        ))
    }

    val documentStoreTestHarness = DocumentStoreTestHarness()

    // On Kotlin/JS, @BeforeTest using runTest is broken. Work around.
    private fun runTestWithSetup(block: suspend TestScope.() -> Unit) = runTest { setup(); block() }

    private suspend fun setup() {
        documentStoreTestHarness.initialize()
        documentStoreTestHarness.provisionStandardDocuments()
        documentStoreTestHarness.documentTypeRepository.addTransactionType(FooTransactionType)
        documentStoreTestHarness.documentTypeRepository.addTransactionType(BarTransactionType)
    }

    private data class ShownConsentPrompt(
        val credentialPresentmentData: CredentialPresentmentData,
        val preselectedDocuments: List<Document>,
        val requester: Requester,
        val trustPoint: TrustPoint?
    )

    private data class TestOpenID4VPResponse(
        val shownConsentPrompts: List<ShownConsentPrompt>,
        val vpToken: Map<String, List<String>>,
        val nonce: String,
        val origin: String,
        val clientId: String,
    )

    private suspend fun testOpenID4VP(
        version: OpenID4VP.Version,
        signRequest: Boolean,
        encryptionKey: EcPrivateKey?,
        dcql: JsonObject,
        transactionData: List<String>
    ): TestOpenID4VPResponse {
        val presentmentSource = SimplePresentmentSource(
            documentStore = documentStoreTestHarness.documentStore,
            documentTypeRepository = documentStoreTestHarness.documentTypeRepository,
            showConsentPromptFn = ::promptModelSilentConsent,
            preferSignatureToKeyAgreement = true,
            domainsMdocSignature = listOf("mdoc"),
            domainsKeyBoundSdJwt = listOf("sdjwt"),
        )

        val nonce = Random.nextBytes(16).toBase64Url()

        val readerAuthKey = if (signRequest) {
            val key = Crypto.createEcPrivateKey(EcCurve.P256)
            val readerRootCerts = documentStoreTestHarness.readerRootKey.certChain.certificates
            val cert = MdocUtil.generateReaderCertificate(
                readerRootKey = documentStoreTestHarness.readerRootKey,
                readerKey = key.publicKey,
                subject = X500Name.fromName("CN=Multipaz Reader Cert Single-Use key"),
                dnsName = DNS_NAME,
                serial = ASN1Integer.fromRandom(128),
                validFrom = readerRootCerts.first().validityNotBefore,
                validUntil = readerRootCerts.first().validityNotAfter
            )
            AsymmetricKey.X509CertifiedExplicit(
                privateKey = key,
                certChain = X509CertChain(listOf(cert) + readerRootCerts)
            )
        } else {
            null
        }

        val request = OpenID4VP.generateRequest(
            version = version,
            origin = ORIGIN,
            clientId = CLIENT_ID,
            nonce = nonce,
            responseEncryptionKey = encryptionKey?.publicKey,
            requestSigningKey = readerAuthKey,
            responseMode = OpenID4VP.ResponseMode.DC_API,
            responseUri = null,
            dclqQuery = dcql,
            jsonTransactionData = transactionData
        )

        val protocol = when (version) {
            OpenID4VP.Version.DRAFT_24 -> "openid4vp"
            OpenID4VP.Version.DRAFT_29 -> {
                if (signRequest) {
                    "openid4vp-v1-signed"
                } else {
                    "openid4vp-v1-unsigned"
                }
            }
        }

        val shownConsentPrompts = mutableListOf<ShownConsentPrompt>()

        val dismissable = MutableStateFlow<Boolean>(true)
        val dcResponseObject = digitalCredentialsPresentment(
            protocol = protocol,
            data = request,
            appId = APP_ID,
            origin = ORIGIN,
            preselectedDocuments = emptyList(),
            source = presentmentSource,
        )
        val decryptedDcResponse = if (encryptionKey != null) {
            val jweCompactSerialization = dcResponseObject["data"]!!.jsonObject["response"]!!.jsonPrimitive.content
            if (version == OpenID4VP.Version.DRAFT_29) {
                // From Section 8.3: If the selected public key contains a kid parameter, the JWE MUST
                // include the same value in the kid JWE Header Parameter (as defined in Section 4.1.6)
                // of the encrypted response.
                val protectedHeader = Json.decodeFromString(
                    JsonObject.serializer(),
                    jweCompactSerialization.split('.')[0].fromBase64Url().decodeToString()
                )
                assertEquals(
                    "response-encryption-key",
                    protectedHeader["kid"]!!.jsonPrimitive.content
                )
            }

            JsonWebEncryption.decrypt(
                jweCompactSerialization,
                AsymmetricKey.anonymous(
                    privateKey = encryptionKey,
                    algorithm = encryptionKey.curve.defaultKeyAgreementAlgorithm
                )
            )
        } else {
            dcResponseObject["data"]!!.jsonObject
        }

        // In OpenID4VP 1.0 this is a response of the form.
        //
        //  {
        //    "vp_token": {
        //      "<cred1>": ["<cred1response1>", "<cred1response2>", ...],
        //      "<cred2>": ["<cred2response1>", "<cred2response2">, ...],
        //      [...]
        //    }
        // }
        //
        // and in OpenID4VP Draft 24 it's of the form
        //
        //  {
        //    "vp_token": {
        //      "<cred1>": "<cred1response>",
        //      "<cred2>": "<cred2response>",
        //      [...]
        //    }
        //  }
        //
        val vpToken = mutableMapOf<String, List<String>>()
        for ((credId, result) in decryptedDcResponse["vp_token"]!!.jsonObject) {
            vpToken[credId] = when (version) {
                OpenID4VP.Version.DRAFT_24 -> listOf(result.jsonPrimitive.content)
                OpenID4VP.Version.DRAFT_29 -> result.jsonArray.toList().map { it.jsonPrimitive.content }
            }
        }

        return TestOpenID4VPResponse(
            shownConsentPrompts = shownConsentPrompts,
            vpToken = vpToken,
            nonce = nonce,
            origin = ORIGIN,
            clientId = if (version == OpenID4VP.Version.DRAFT_29) {
                if (signRequest) {
                    CLIENT_ID
                } else {
                    "web-origin:$ORIGIN"
                }
            } else {
                CLIENT_ID
            }
        )
    }

    suspend fun test_OpenID4VP_mdoc(
        version: OpenID4VP.Version,
        signRequest: Boolean,
        encryptionKey: EcPrivateKey?,
        dcql: String,
        transactionData: List<String>,
        expectedMdocResponse: String
    ) {
        val response = testOpenID4VP(
            version = version,
            signRequest = signRequest,
            encryptionKey = encryptionKey,
            dcql = Json.decodeFromString(JsonObject.serializer(), dcql),
            transactionData = transactionData
        )
        assertEquals(1, response.vpToken.keys.size)
        val credId = response.vpToken.keys.first()
        val encodedDeviceResponse = response.vpToken[credId]!![0].fromBase64Url()

        val encryptionKeyJwkThumbprint = encryptionKey?.publicKey?.toJwkThumbprint(Algorithm.SHA256)?.toByteArray()
        val handoverInfo = if (version == OpenID4VP.Version.DRAFT_29) {
            Cbor.encode(
                buildCborArray {
                    add(response.origin)
                    add(response.nonce)
                    if (encryptionKeyJwkThumbprint != null) {
                        add(encryptionKeyJwkThumbprint)
                    } else {
                        add(Simple.NULL)
                    }
                }
            )
        } else {
            Cbor.encode(
                buildCborArray {
                    add(response.origin)
                    if (signRequest) {
                        add(response.clientId)
                    } else {
                        add("web-origin:${response.origin}")
                    }
                    add(response.nonce)
                }
            )
        }
        Logger.iCbor(TAG, "handoverInfo", handoverInfo)
        val handoverInfoDigest = Crypto.digest(Algorithm.SHA256, handoverInfo)
        val sessionTranscript = buildCborArray {
            add(Simple.NULL) // DeviceEngagementBytes
            add(Simple.NULL) // EReaderKeyBytes
            addCborArray {
                add("OpenID4VPDCAPIHandover")
                add(handoverInfoDigest)
            }
        }

        val deviceResponse = DeviceResponse.fromDataItem(Cbor.decode(encodedDeviceResponse))
        deviceResponse.verify(
            sessionTranscript = sessionTranscript,
            transactionDataList = if (transactionData.isEmpty()) {
                emptyList()
            } else {
                TransactionDataJson.parse(
                    base64UrlEncodedJson = transactionData.map {
                        it.encodeToByteArray().toBase64Url()
                    },
                    documentTypeRepository = documentStoreTestHarness.documentTypeRepository
                ).values.toList()
            }
        )
        assertEquals(DeviceResponse.STATUS_OK, deviceResponse.status)
        assertEquals(1, deviceResponse.documents.size)
        val doc = deviceResponse.documents[0]
        assertEquals(
            expectedMdocResponse,
            deviceResponse.prettyPrint().trim()
        )
    }

    @OptIn(ExperimentalSerializationApi::class)
    suspend fun test_OpenID4VP_sdJwt(
        version: OpenID4VP.Version,
        signRequest: Boolean,
        encryptionKey: EcPrivateKey?,
        dcql: String,
        transactionData: List<String>,
        expectedSdJwtResponse: String,
        expectedKbJwtResponse: String?
    ) {
        val response = testOpenID4VP(
            version = version,
            signRequest = signRequest,
            encryptionKey = encryptionKey,
            dcql = Json.decodeFromString(JsonObject.serializer(), dcql),
            transactionData = transactionData
        )
        assertEquals(1, response.vpToken.keys.size)
        val credId = response.vpToken.keys.first()
        val compactSerialization = response.vpToken[credId]!![0]
        val sdJwtKb = SdJwtKb.fromCompactSerialization(compactSerialization)
        val expectedAudience = if (version == OpenID4VP.Version.DRAFT_29) {
            "origin:$ORIGIN"
        } else {
            if (signRequest) CLIENT_ID else "web-origin:$ORIGIN"
        }
        val processedJwt = sdJwtKb.verify(
            issuerKey = documentStoreTestHarness.dsKey.publicKey,
            checkNonce = { nonce -> nonce == response.nonce },
            checkAudience = { audience ->
                    expectedAudience == audience
            },
            checkCreationTime = { creationTime -> true },
            transactionData = if (transactionData.isEmpty()) {
                emptyList()
            } else {
                TransactionDataJson.parse(
                    base64UrlEncodedJson = transactionData.map {
                        it.encodeToByteArray().toBase64Url()
                    },
                    documentTypeRepository = documentStoreTestHarness.documentTypeRepository
                ).values.first()
            }
        ).filterKeys { key -> !setOf("iat", "nbf", "exp", "cnf").contains(key) }  // filter out variable claims
        assertEquals(
            expectedSdJwtResponse,
            Json {
                prettyPrint = true
                prettyPrintIndent = "  "
            }.encodeToString(processedJwt)
        )
        if (expectedKbJwtResponse != null) {
            val filterOut = setOf("iat", "nonce", "aud", "sd_hash")
            val kbJwt = sdJwtKb.jwtBody.filterKeys { key -> !filterOut.contains(key) }
            assertEquals(
                expectedKbJwtResponse,
                Json {
                    prettyPrint = true
                    prettyPrintIndent = "  "
                }.encodeToString(kbJwt)
            )
        }
    }

    suspend fun test_OID4VP_mDL(
        versionDraftNumber: Int,
        signRequest: Boolean,
        encryptResponse: Boolean,
    ) {
        val version = when (versionDraftNumber) {
            24 -> OpenID4VP.Version.DRAFT_24
            29 -> OpenID4VP.Version.DRAFT_29
            else -> throw IllegalArgumentException("Unknown draft number")
        }
        val encryptionKey = if (encryptResponse) Crypto.createEcPrivateKey(EcCurve.P256) else null
        test_OpenID4VP_mdoc(
            version = version,
            signRequest = signRequest,
            encryptionKey = encryptionKey,
            dcql =
                """
                    {
                      "credentials": [{
                          "id": "mDL",
                          "format": "mso_mdoc",
                          "meta": { "doctype_value": "org.iso.18013.5.1.mDL" },
                          "claims": [
                            { "path": ["org.iso.18013.5.1", "age_over_21"] },
                            { "path": ["org.iso.18013.5.1", "portrait"] }
                    ]}]}
                """.trimIndent().trim(),
            transactionData = listOf(),
            expectedMdocResponse =
                """
                    Document 0:
                      DocType: org.iso.18013.5.1.mDL
                      IssuerSigned:
                        org.iso.18013.5.1:
                          age_over_21: true
                          portrait: 5318 bytes
                """.trimIndent().trim(),
        )
    }

    suspend fun test_OID4VP_mDL_withTransaction(
        versionDraftNumber: Int,
        signRequest: Boolean,
        encryptResponse: Boolean,
    ) {
        val version = when (versionDraftNumber) {
            24 -> OpenID4VP.Version.DRAFT_24
            29 -> OpenID4VP.Version.DRAFT_29
            else -> throw IllegalArgumentException("Unknown draft number")
        }
        val encryptionKey = if (encryptResponse) Crypto.createEcPrivateKey(EcCurve.P256) else null
        test_OpenID4VP_mdoc(
            version = version,
            signRequest = signRequest,
            encryptionKey = encryptionKey,
            dcql =
                """
                    {
                      "credentials": [{
                          "id": "mDL",
                          "format": "mso_mdoc",
                          "meta": { "doctype_value": "org.iso.18013.5.1.mDL" },
                          "claims": [
                            { "path": ["org.iso.18013.5.1", "age_over_21"] },
                            { "path": ["org.iso.18013.5.1", "portrait"] }
                    ]}]}
                """.trimIndent().trim(),
            transactionData = listOf(
                makeTransactionData(FooTransactionType, "mDL"),
                makeTransactionData(BarTransactionType, "mDL",
                    algorithms = listOf(Algorithm.SHA384, Algorithm.SHA512))
            ),
            expectedMdocResponse =
                """
                    Document 0:
                      DocType: org.iso.18013.5.1.mDL
                      IssuerSigned:
                        org.iso.18013.5.1:
                          age_over_21: true
                          portrait: 5318 bytes
                      DeviceNamespaces:
                        FooNS:
                          transaction_data_hash: 32 bytes
                          result: 42
                        bar:
                          transaction_data_hash_alg: -43
                          transaction_data_hash: 48 bytes
                          result: 57
                """.trimIndent().trim(),
        )
    }

    suspend fun test_OID4VP_SDJWT(
        versionDraftNumber: Int,
        signRequest: Boolean,
        encryptResponse: Boolean,
    ) {
        val version = when (versionDraftNumber) {
            24 -> OpenID4VP.Version.DRAFT_24
            29 -> OpenID4VP.Version.DRAFT_29
            else -> throw IllegalArgumentException("Unknown draft number")
        }
        val encryptionKey = if (encryptResponse) Crypto.createEcPrivateKey(EcCurve.P256) else null
        test_OpenID4VP_sdJwt(
            version = version,
            signRequest = signRequest,
            encryptionKey = encryptionKey,
            dcql =
                """
                    {
                      "credentials": [{
                          "id": "pid",
                          "format": "dc+sd-jwt",
                          "meta": { "vct_values": [ "urn:eudi:pid:1" ] },
                          "claims": [
                            { "path": ["age_equal_or_over", "18"] },
                            { "path": ["given_name"] },
                            { "path": ["family_name"] }
                    ]}]}                
                """.trimIndent().trim(),
            transactionData = listOf(),
            expectedSdJwtResponse =
                """
                    {
                      "iss": "https://example-issuer.com",
                      "vct": "urn:eudi:pid:1",
                      "family_name": "Mustermann",
                      "given_name": "Erika",
                      "age_equal_or_over": {
                        "18": true
                      }
                    }
                """.trimIndent().trim(),
            expectedKbJwtResponse = null
        )
    }

    suspend fun test_OID4VP_SDJWT_withTransaction(
        versionDraftNumber: Int,
        signRequest: Boolean,
        encryptResponse: Boolean,
    ) {
        val version = when (versionDraftNumber) {
            24 -> OpenID4VP.Version.DRAFT_24
            29 -> OpenID4VP.Version.DRAFT_29
            else -> throw IllegalArgumentException("Unknown draft number")
        }
        val encryptionKey = if (encryptResponse) Crypto.createEcPrivateKey(EcCurve.P256) else null
        test_OpenID4VP_sdJwt(
            version = version,
            signRequest = signRequest,
            encryptionKey = encryptionKey,
            dcql =
                """
                    {
                      "credentials": [{
                          "id": "pid",
                          "format": "dc+sd-jwt",
                          "meta": { "vct_values": [ "urn:eudi:pid:1" ] },
                          "claims": [
                            { "path": ["age_equal_or_over", "18"] },
                            { "path": ["given_name"] },
                            { "path": ["family_name"] }
                    ]}]}                
                """.trimIndent().trim(),
            transactionData = listOf(
                makeTransactionData(FooTransactionType, "pid",
                    algorithms = listOf(Algorithm.SHA384, Algorithm.SHA512)),
                makeTransactionData(BarTransactionType, "pid",
                    algorithms = listOf(Algorithm.SHA384, Algorithm.SHA512))
            ),
            expectedSdJwtResponse =
                """
                    {
                      "iss": "https://example-issuer.com",
                      "vct": "urn:eudi:pid:1",
                      "family_name": "Mustermann",
                      "given_name": "Erika",
                      "age_equal_or_over": {
                        "18": true
                      }
                    }
                """.trimIndent().trim(),
            expectedKbJwtResponse = """
                {
                  "kb_foo": {
                    "result": 42
                  },
                  "bar": {
                    "result": 57
                  },
                  "transaction_data_hashes_alg": "sha-384",
                  "transaction_data_hashes": [
                    "u7YpNRo4AwUtcGu5pvec-utbtMgp-9igj_mDLK2mm5juySa9b6ORQIQco1Jowz77",
                    "qY4IVRKWfa4jaq8MEJNc3a-Zsf6hXmo5cEPZ_GzN5ytDtHvg94nwPjN_rg7SlRuE"
                  ]
                }
            """.trimIndent().trim()
        )
    }

    suspend fun test_OID4VP_SDJWT_unknownTransaction(
        versionDraftNumber: Int,
        signRequest: Boolean,
        encryptResponse: Boolean,
    ) {
        val version = when (versionDraftNumber) {
            24 -> OpenID4VP.Version.DRAFT_24
            29 -> OpenID4VP.Version.DRAFT_29
            else -> throw IllegalArgumentException("Unknown draft number")
        }
        val encryptionKey = if (encryptResponse) Crypto.createEcPrivateKey(EcCurve.P256) else null
        assertFailsWith(IllegalStateException::class) {
            test_OpenID4VP_sdJwt(
                version = version,
                signRequest = signRequest,
                encryptionKey = encryptionKey,
                dcql =
                    """
                    {
                      "credentials": [{
                          "id": "pid",
                          "format": "dc+sd-jwt",
                          "meta": { "vct_values": [ "urn:eudi:pid:1" ] },
                          "claims": [
                            { "path": ["age_equal_or_over", "18"] },
                            { "path": ["given_name"] },
                            { "path": ["family_name"] }
                    ]}]}                
                """.trimIndent().trim(),
                transactionData = listOf(makeTransactionData(BuzTransactionType, "pid")),
                expectedSdJwtResponse = "",
                expectedKbJwtResponse = null
            )
        }
    }

    suspend fun test_OID4VP_SDJWT_failingTransaction(
        versionDraftNumber: Int,
        signRequest: Boolean,
        encryptResponse: Boolean,
    ) {
        val version = when (versionDraftNumber) {
            24 -> OpenID4VP.Version.DRAFT_24
            29 -> OpenID4VP.Version.DRAFT_29
            else -> throw IllegalArgumentException("Unknown draft number")
        }
        val encryptionKey = if (encryptResponse) Crypto.createEcPrivateKey(EcCurve.P256) else null
        assertFailsWith(PresentmentCannotSatisfyRequestException::class) {
            test_OpenID4VP_sdJwt(
                version = version,
                signRequest = signRequest,
                encryptionKey = encryptionKey,
                dcql =
                    """
                    {
                      "credentials": [{
                          "id": "pid",
                          "format": "dc+sd-jwt",
                          "meta": { "vct_values": [ "urn:eudi:pid:1" ] },
                          "claims": [
                            { "path": ["age_equal_or_over", "18"] },
                            { "path": ["given_name"] },
                            { "path": ["family_name"] }
                    ]}]}                
                """.trimIndent().trim(),
                transactionData = listOf(
                    makeTransactionData(FooTransactionType, "pid", succeed = false)
                ),
                expectedSdJwtResponse = "",
                expectedKbJwtResponse = null
            )
        }
    }

    private fun makeTransactionData(
        transactionType: TransactionType,
        credentialId: String,
        succeed: Boolean = true,
        algorithms: List<Algorithm>? = null
    ) = buildJsonObject {
            put("type", transactionType.identifier)
            putJsonArray("credential_ids") {
                add(credentialId)
            }
            put("succeed", succeed)
            algorithms?.let {
                putJsonArray("transaction_data_hashes_alg") {
                    for (alg in it) {
                        add(alg.hashAlgorithmName)
                    }
                }
            }
        }.toString()

    @Test fun OID4VP_24_NoSignedRequest_NoEncryptedResponse_mDL() = runTestWithSetup { test_OID4VP_mDL(24, false, false) }
    @Test fun OID4VP_24_NoSignedRequest_EncryptedResponse_mDL() = runTestWithSetup { test_OID4VP_mDL(24, false, true) }
    @Test fun OID4VP_24_SignedRequest_NoEncryptedResponse_mDL() = runTestWithSetup { test_OID4VP_mDL(24, true, false) }
    @Test fun OID4VP_24_SignedRequest_EncryptedResponse_mDL() = runTestWithSetup { test_OID4VP_mDL(24, true, true) }

    @Test fun OID4VP_24_NoSignedRequest_NoEncryptedResponse_SDJWT() = runTestWithSetup { test_OID4VP_SDJWT(24, false, false) }
    @Test fun OID4VP_24_NoSignedRequest_EncryptedResponse_SDJWT() = runTestWithSetup { test_OID4VP_SDJWT(24, false, true) }
    @Test fun OID4VP_24_SignedRequest_NoEncryptedResponse_SDJWT() = runTestWithSetup { test_OID4VP_SDJWT(24, true, false) }
    @Test fun OID4VP_24_SignedRequest_EncryptedResponse_SDJWT() = runTestWithSetup { test_OID4VP_SDJWT(24, true, true) }

    @Test fun OID4VP_29_NoSignedRequest_NoEncryptedResponse_mDL() = runTestWithSetup { test_OID4VP_mDL(29, false, false) }
    @Test fun OID4VP_29_NoSignedRequest_EncryptedResponse_mDL() = runTestWithSetup { test_OID4VP_mDL(29, false, true) }
    @Test fun OID4VP_29_SignedRequest_NoEncryptedResponse_mDL() = runTestWithSetup { test_OID4VP_mDL(29, true, false) }
    @Test fun OID4VP_29_SignedRequest_EncryptedResponse_mDL() = runTestWithSetup { test_OID4VP_mDL(29, true, true) }

    @Test fun OID4VP_29_NoSignedRequest_NoEncryptedResponse_mDL_withTransaction() = runTestWithSetup { test_OID4VP_mDL_withTransaction(29, false, false) }
    @Test fun OID4VP_29_NoSignedRequest_EncryptedResponse_mDL_withTransaction() = runTestWithSetup { test_OID4VP_mDL_withTransaction(29, false, true ) }
    @Test fun OID4VP_29_SignedRequest_NoEncryptedResponse_mDL_withTransaction() = runTestWithSetup { test_OID4VP_mDL_withTransaction(29, true, false) }
    @Test fun OID4VP_29_SignedRequest_EncryptedResponse_mDL_withTransaction() = runTestWithSetup { test_OID4VP_mDL_withTransaction(29, true, true) }

    @Test fun OID4VP_29_NoSignedRequest_NoEncryptedResponse_SDJWT() = runTestWithSetup { test_OID4VP_SDJWT(29, false, false) }
    @Test fun OID4VP_29_NoSignedRequest_EncryptedResponse_SDJWT() = runTestWithSetup { test_OID4VP_SDJWT(29, false, true) }
    @Test fun OID4VP_29_SignedRequest_NoEncryptedResponse_SDJWT() = runTestWithSetup { test_OID4VP_SDJWT(29, true, false) }
    @Test fun OID4VP_29_SignedRequest_EncryptedResponse_SDJWT() = runTestWithSetup { test_OID4VP_SDJWT(29, true, true) }

    @Test fun OID4VP_29_NoSignedRequest_NoEncryptedResponse_SDJWT_withTransaction() = runTestWithSetup { test_OID4VP_SDJWT_withTransaction(29, false, false) }
    @Test fun OID4VP_29_NoSignedRequest_EncryptedResponse_SDJWT_withTransaction() = runTestWithSetup { test_OID4VP_SDJWT_withTransaction(29, false, true) }
    @Test fun OID4VP_29_SignedRequest_NoEncryptedResponse_SDJWT_withTransaction() = runTestWithSetup { test_OID4VP_SDJWT_withTransaction(29, true, false) }
    @Test fun OID4VP_29_SignedRequest_EncryptedResponse_SDJWT_withTransaction() = runTestWithSetup { test_OID4VP_SDJWT_withTransaction(29, true, true) }

    @Test fun OID4VP_29_SignedRequest_EncryptedResponse_SDJWT_unknownTransaction() = runTestWithSetup { test_OID4VP_SDJWT_unknownTransaction(29, true, true) }

    @Test fun OID4VP_29_SignedRequest_EncryptedResponse_SDJWT_failingTransaction() = runTestWithSetup { test_OID4VP_SDJWT_failingTransaction(29, true, true) }
}

private fun DeviceResponse.prettyPrint(): String {
    val diagOptions = setOf(DiagnosticOption.BSTR_PRINT_LENGTH)
    val sb = StringBuilder()
    for (n in documents.indices) {
        val doc = documents[n]
        sb.appendLine("Document $n:")
        sb.appendLine("  DocType: ${doc.docType}")
        sb.appendLine("  IssuerSigned:")
        doc.issuerNamespaces.data.forEach { (namespaceName, issuerSignedItemsMap) ->
            sb.appendLine("    $namespaceName:")
            issuerSignedItemsMap.forEach { (dataElementName, issuerSignedItem) ->
                sb.appendLine("      $dataElementName: ${Cbor.toDiagnostics(issuerSignedItem.dataElementValue, diagOptions)}")
            }
        }
        if (doc.deviceNamespaces.data.isNotEmpty()) {
            sb.appendLine("  DeviceNamespaces:")
            doc.deviceNamespaces.data.forEach { (namespaceName, itemsMap) ->
                sb.appendLine("    $namespaceName:")
                itemsMap.forEach { (name, item) ->
                    sb.appendLine("      $name: ${Cbor.toDiagnostics(item, diagOptions)}")
                }
            }
        }
    }
    return sb.toString()
}
