package org.multipaz.presentment

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.io.bytestring.ByteString
import kotlinx.io.bytestring.decodeToString
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNamingStrategy
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.buildJsonArray
import org.multipaz.asn1.ASN1Integer
import org.multipaz.cbor.ByteStringFormat
import org.multipaz.cbor.Cbor
import org.multipaz.cbor.Cdn
import org.multipaz.cbor.CdnGeneratorOptions
import org.multipaz.cbor.DataItem
import org.multipaz.cbor.DiagnosticOption
import org.multipaz.cbor.Simple
import org.multipaz.cbor.Tagged
import org.multipaz.cbor.Tstr
import org.multipaz.cbor.Uint
import org.multipaz.cbor.addCborArray
import org.multipaz.cbor.buildCborArray
import org.multipaz.cbor.buildCborMap
import org.multipaz.cbor.putCborArray
import org.multipaz.cbor.toDataItem
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
import org.multipaz.document.DocumentBadge
import org.multipaz.document.DocumentStore
import org.multipaz.documenttype.DocumentTypeRepository
import org.multipaz.documenttype.ISO_18013_TRANSACTION_DATA_NAMESPACE
import org.multipaz.documenttype.TransactionType
import org.multipaz.documenttype.TransactionUserInput
import org.multipaz.documenttype.knowntypes.PaymentTransaction
import org.multipaz.mdoc.devicesigned.DeviceAuth
import org.multipaz.mdoc.request.DocRequestInfo
import org.multipaz.mdoc.request.TransactionsInfo
import org.multipaz.mdoc.request.buildDeviceRequest
import org.multipaz.mdoc.response.DeviceResponse
import org.multipaz.mdoc.util.MdocUtil
import org.multipaz.openid.OpenID4VP
import org.multipaz.prompt.promptModelSilentConsent
import org.multipaz.request.RequestedClaim
import org.multipaz.request.Requester
import org.multipaz.request.RequesterIdentity
import org.multipaz.request.TrustedRequesterIdentity
import org.multipaz.sdjwt.SdJwtKb
import org.multipaz.trustmanagement.TrustPoint
import org.multipaz.util.Logger
import org.multipaz.util.fromBase64Url
import org.multipaz.util.toBase64Url
import org.multipaz.util.toHex
import org.multipaz.util.zlibInflate
import org.multipaz.verification.VerifierIdentity
import kotlin.collections.iterator
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.time.Clock

class DigitalCredentialsPresentmentTest {
    internal abstract class BooleanTransaction(
        displayName: String,
        identifier: String,
        kbJwtResponseClaimName: String = identifier,
        openId4VpMdocResponseNamespace: String = identifier,
    ): TransactionType<Boolean>(
        displayName = displayName,
        identifier = identifier,
        kbJwtResponseClaimName = kbJwtResponseClaimName,
        openId4VpMdocResponseNamespace = openId4VpMdocResponseNamespace,
    ) {
        @Serializable
        data class JsonData(
            val type: String,
            val credentialIds: List<String>,
            val transactionDataHashesAlg: List<String>?,
            val succeed: Boolean
        )

        override fun serializeIso18013Request(payload: Boolean): DataItem = buildCborMap {
            put("succeed", payload)
        }

        override fun parseIso18013Request(dataItem: DataItem): Boolean =
            dataItem["succeed"].asBoolean

        override fun serializeOpenId4VpRequest(
            payload: Boolean,
            credentialIds: List<String>,
            hashAlgorithms: List<Algorithm>?
        ): String = jsonFormat.encodeToString(
            value = JsonData(
                type = identifier,
                transactionDataHashesAlg = joseHashAlgorithms(hashAlgorithms),
                credentialIds = credentialIds,
                succeed = payload
            )
        )

        override fun parseOpenId4VpRequest(jsonString: String): Boolean =
            jsonFormat.decodeFromString<JsonData>(jsonString).succeed


        override fun parseJson(serialized: ByteString): TransactionData<Boolean> {
            val jsonString = serialized.decodeToString().fromBase64Url().decodeToString()
            val data = jsonFormat.decodeFromString<JsonData>(jsonString)
            return TransactionData(
                type = this,
                payload = data.succeed,
                protocol = TransactionProtocol.OPENID4VP,
                rawBytes = serialized,
                hashAlgorithms = parseJoseHashAlgorithms(data.transactionDataHashesAlg),
            )
        }


        override suspend fun isApplicable(
            transactionData: TransactionData<Boolean>,
            credential: Credential
        ): Boolean {
            return transactionData.payload && super.isApplicable(transactionData, credential)
        }

        companion object {
            @OptIn(ExperimentalSerializationApi::class)
            private val jsonFormat = Json {
                explicitNulls = false
                namingStrategy = JsonNamingStrategy.SnakeCase
            }
        }
    }

    private object FooTransactionType: BooleanTransaction(
        displayName = "Foo",
        identifier = "foo",
        kbJwtResponseClaimName = "kb_foo"
    ) {
        override suspend fun isApplicable(
            transactionData: TransactionData<Boolean>,
            credential: Credential
        ): Boolean {
            return transactionData.payload && super.isApplicable(transactionData, credential)
        }

        override suspend fun generateMdocResponseElements(
            transactionData: TransactionData<Boolean>,
            credential: Credential,
            userInput: TransactionUserInput?,
            docRequestId: Int?
        ): Map<String, DataItem> = buildMap {
            check(userInput == null)
            putAll(super.generateMdocResponseElements(transactionData, credential, userInput, docRequestId))
            put("result", Uint(42UL))
        }

        override suspend fun generateSdJwtResponseClaims(
            transactionData: TransactionData<Boolean>,
            credential: Credential,
            userInput: TransactionUserInput?,
            docRequestId: Int?
        ): Map<String, JsonElement> = buildMap {
            check(userInput == null)
            putAll(super.generateSdJwtResponseClaims(transactionData, credential, userInput, docRequestId))
            put("result", JsonPrimitive(42))
        }
    }

    private object BarTransactionType: BooleanTransaction(
        displayName = "Bar",
        identifier = "bar"
    ) {
        override suspend fun generateMdocResponseElements(
            transactionData: TransactionData<Boolean>,
            credential: Credential,
            userInput: TransactionUserInput?,
            docRequestId: Int?
        ): Map<String, DataItem> = buildMap {
            check(userInput == null)
            putAll(super.generateMdocResponseElements(transactionData, credential, userInput, docRequestId))
            put("result", Uint(57UL))
        }

        override suspend fun generateSdJwtResponseClaims(
            transactionData: TransactionData<Boolean>,
            credential: Credential,
            userInput: TransactionUserInput?,
            docRequestId: Int?
        ): Map<String, JsonElement> = buildMap {
            check(userInput == null)
            putAll(super.generateSdJwtResponseClaims(transactionData, credential, userInput, docRequestId))
            put("result", JsonPrimitive(57))
        }
    }

    // Unregistered transaction type, will cause an error
    private object BuzTransactionType: BooleanTransaction(
        displayName = "Buz",
        identifier = "buz",
    )

    companion object {
        private const val TAG = "DigitalCredentialsPresentmentTest"

        private const val CLIENT_ID = "x509_san_dns:verifier.multipaz.org"
        private const val DNS_NAME = "verifier.multipaz.org"
        private const val ORIGIN = "https://verifier.multipaz.org"
        private const val APP_ID = "org.multipaz.testApp"
    }

    val documentStoreTestHarness = DocumentStoreTestHarness()

    // On Kotlin/JS, @BeforeTest using runTest is broken. Work around.
    private fun runTestWithSetup(block: suspend TestScope.() -> Unit) = runTest { setup(); block() }

    private suspend fun setup() {
        documentStoreTestHarness.initialize()
        documentStoreTestHarness.provisionStandardDocuments(
            keyAuthorizedNamespaces = listOf(
                ISO_18013_TRANSACTION_DATA_NAMESPACE,
                "foo",
                "bar"
            )
        )
        documentStoreTestHarness.documentTypeRepository.addTransactionType(FooTransactionType)
        documentStoreTestHarness.documentTypeRepository.addTransactionType(BarTransactionType)
        documentStoreTestHarness.documentTypeRepository.addTransactionType(PaymentTransaction)
        documentStoreTestHarness.provisionMdoc(
            displayName = "Payment Card Mdoc",
            docType = "org.multipaz.payment.sca.1",
            data = mapOf(
                "org.multipaz.payment.sca.1" to listOf(
                    Pair("account_id", Tstr("acc-12345"))
                )
            ),
            keyAuthorizedNamespaces = listOf(
                ISO_18013_TRANSACTION_DATA_NAMESPACE,
                PaymentTransaction.openId4VpMdocResponseNamespace
            )
        )
        documentStoreTestHarness.provisionSdJwtVc(
            displayName = "Payment Card SD-JWT",
            vct = "org.multipaz.payment.sca.1",
            data = listOf(
                Pair("account_id", JsonPrimitive("acc-12345"))
            )
        )
    }

    private class TipPresentmentSource(
        documentStore: DocumentStore,
        documentTypeRepository: DocumentTypeRepository,
    ) : PresentmentSource(
        documentStore = documentStore,
        documentTypeRepository = documentTypeRepository
    ) {
        var tipPercent: Double? = null

        fun insertTip(tipPercent: Double) {
            this.tipPercent = tipPercent
        }

        private val delegate = SimplePresentmentSource(
            documentStore = documentStore,
            documentTypeRepository = documentTypeRepository,
            preferSignatureToKeyAgreement = true,
            domainsMdocSignature = listOf("mdoc"),
            domainsKeyBoundSdJwt = listOf("sdjwt")
        )

        override suspend fun resolveTrust(requester: Requester): TrustedRequesterIdentity? =
            delegate.resolveTrust(requester)

        override suspend fun showConsentPrompt(
            requester: Requester,
            trustedRequesterIdentity: TrustedRequesterIdentity?,
            consentData: ConsentData,
            preselectedDocuments: List<Document>,
            onDocumentsInFocus: (documents: List<Document>) -> Unit
        ): CredentialSelection? {
            val ret = consentData.credentialQueryResult.select(preselectedDocuments)
            onDocumentsInFocus(ret.matches.map { it.credential.document })
            val userInputMap = mutableMapOf<String, TransactionUserInput>()
            tipPercent?.let { tip ->
                userInputMap[PaymentTransaction.identifier] = PaymentTransaction.UserInput(tipPercent = tip)
            }
            return ret.copy(transactionUserInput = userInputMap)
        }

        override suspend fun getBadges(document: Document): List<DocumentBadge> =
            delegate.getBadges(document)

        override suspend fun selectCredential(
            document: Document,
            requestedClaims: List<RequestedClaim>,
            keyAgreementPossible: List<EcCurve>,
            credential: Credential?,
        ): Credential? =
            delegate.selectCredential(document, requestedClaims, keyAgreementPossible, credential)
    }

    private data class ShownConsentPrompt(
        val credentialQueryResult: CredentialQueryResult,
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
            nonce = nonce,
            responseEncryptionKey = encryptionKey?.publicKey,
            verifierIdentities = buildList {
                readerAuthKey?.let {
                    add(VerifierIdentity(it, CLIENT_ID))
                }
            },
            responseMode = OpenID4VP.ResponseMode.DC_API,
            responseUri = null,
            dcqlQuery = dcql,
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
        deviceResponse.verifySingleDoc(
            sessionTranscript = sessionTranscript,
            transactionData = if (transactionData.isEmpty()) {
                emptyList()
            } else {
                documentStoreTestHarness.documentTypeRepository.parseJsonTransactions(
                    base64UrlEncodedJson = transactionData.map {
                        it.encodeToByteArray().toBase64Url()
                    }
                ).values.first()
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
                documentStoreTestHarness.documentTypeRepository.parseJsonTransactions(
                    base64UrlEncodedJson = transactionData.map {
                        it.encodeToByteArray().toBase64Url()
                    }
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

    suspend fun test_OID4VP_mDL_noClaims(
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
                          "meta": { "doctype_value": "org.iso.18013.5.1.mDL" }
                    }]}
                """.trimIndent().trim(),
            transactionData = listOf(),
            expectedMdocResponse =
                """
                    Document 0:
                      DocType: org.iso.18013.5.1.mDL
                      IssuerSigned:
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
                        foo:
                          transactionDataHash: 32 bytes
                          result: 42
                        bar:
                          transactionDataHashAlg: -43
                          transactionDataHash: 48 bytes
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
        transactionType: TransactionType<Boolean>,
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
    @Test fun OID4VP_29_NoSignedRequest_NoEncryptedResponse_mDL_noClaims() = runTestWithSetup { test_OID4VP_mDL_noClaims(29, false, false) }

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

    // -----------------------------------------------------------------------------------------
    // PaymentTransaction End-to-End Tests: (ISO 18013-5 vs OpenID4VP) x (ISO mdoc vs SD-JWT VC)
    // -----------------------------------------------------------------------------------------

    @Test
    fun payment_Iso18013_IsoMdoc() = runTestWithSetup {
        val sessionTranscript = buildCborArray { add(Simple.NULL); add(Simple.NULL); add(byteArrayOf(1, 2, 3)) }
        val source = TipPresentmentSource(
            documentStore = documentStoreTestHarness.documentStore,
            documentTypeRepository = documentStoreTestHarness.documentTypeRepository,
        )
        source.insertTip(20.0)

        // 1. Verifier prepares entire DeviceRequest in ISO 18013-5
        val payload = PaymentTransaction.sampleData.payload
        val requestDataItem = PaymentTransaction.serializeIso18013Request(payload)
        val deviceRequest = buildDeviceRequest(sessionTranscript = sessionTranscript) {
            addDocRequest(
                docType = "org.multipaz.payment.sca.1",
                nameSpaces = mapOf(
                    "org.multipaz.payment.sca.1" to mapOf("account_id" to false)
                ),
                docRequestInfo = DocRequestInfo(
                    transactionData = TransactionsInfo(
                        data = mapOf(PaymentTransaction.identifier to requestDataItem)
                    )
                )
            )
        }

        // Pretty-printed entire DeviceRequest (Concise Diagnostic Notation)
        val requestCdn = Cdn.encode(
            item = deviceRequest.toDataItem(),
            options = CdnGeneratorOptions.Pretty
        )
        val expectedRequestCdn = """
            {
              "version": "1.1",
              "docRequests": [
                {
                  "itemsRequest": 24(<< {
                    "docType": "org.multipaz.payment.sca.1",
                    "nameSpaces": {
                      "org.multipaz.payment.sca.1": {
                        "account_id": false
                      },
                      "org.iso.transactiondata": {
                        "urn:eudi:sca:payment:1": true
                      }
                    },
                    "requestInfo": {
                      "transactionData": {
                        "urn:eudi:sca:payment:1": {
                          "transactionId": "3AD99006-6E0D-4D07-AE75-5DAEF0FE21D9",
                          "currency": "USD",
                          "amount": 123.25,
                          "payee": {
                            "name": "Linux Foundation",
                            "id": "01234"
                          },
                          "tipRequested": true
                        }
                      }
                    }
                  } >>)
                }
              ]
            }
        """.trimIndent()
        assertEquals(expectedRequestCdn, requestCdn.trim())

        // 2. Wallet presentment
        val creationTime = Clock.System.now()
        val isoResponse = mdocPresentment(
            deviceRequest = deviceRequest,
            eReaderKey = null,
            sessionTranscript = sessionTranscript,
            source = source,
            keyAgreementPossible = emptyList(),
            requesterAppId = null,
            requesterOrigin = ORIGIN,
            creationTime = creationTime,
            preselectedDocuments = emptyList(),
            onWaitingForUserInput = {},
            onDocumentsInFocus = {}
        )
        val deviceResponse = isoResponse.deviceResponse

        // 3. Verifier verifies response
        deviceResponse.verify(
            sessionTranscript = sessionTranscript,
            eReaderKey = null,
            deviceRequest = deviceRequest,
            documentTypeRepository = source.documentTypeRepository,
            atTime = creationTime
        )
        assertEquals(DeviceResponse.STATUS_OK, deviceResponse.status)
        assertEquals(1, deviceResponse.documents.size)
        val mdocDoc = deviceResponse.documents[0]
        val txElements = mdocDoc.deviceNamespaces.data[ISO_18013_TRANSACTION_DATA_NAMESPACE]!![PaymentTransaction.identifier]!!
        assertEquals(0L, txElements["docRequestId"].asNumber)
        assertEquals(123.25, txElements["amount"].asDouble)
        assertEquals("USD", txElements["currency"].asTstr)
        assertEquals(24.65, txElements["tipAmount"].asDouble)

        // 4. Pretty-printed entire DeviceResponse (Concise Diagnostic Notation)
        val responseCdn = Cdn.encode(
            item = deviceResponse.toDataItem(),
            options = CdnGeneratorOptions.Pretty
        )
        val expectedResponseCdn = """
            {
              "version": "1.0",
              "status": 0,
              "documents": [
                {
                  "docType": "org.multipaz.payment.sca.1",
                  "issuerSigned": {
                    "issuerAuth": [ # COSE_Sign1
                      /protected/ << {
                        /alg/ 1: -7 # ES256: ECDSA with SHA-256
                      } >>,
                      /unprotected/ {
                        /x5chain/ 33:
                        # Subject DN: C=US,CN=OWF Multipaz TEST DS
                        # Issuer DN: C=US,CN=OWF Multipaz TEST IACA
                        cert'''...'''
                      },
                      /payload/ << 24(<< {
                        "version": "1.0",
                        "digestAlgorithm": "SHA-256",
                        "docType": "org.multipaz.payment.sca.1",
                        "valueDigests": {
                          "org.multipaz.payment.sca.1": {
                            0: h'...'
                          }
                        },
                        "deviceKeyInfo": {
                          "deviceKey": { # COSE_Key
                            /kty/ 1: 2, # EC2
                            /crv/ -1: 1, # P-256
                            /x/ -2: h'...',
                            /y/ -3: h'...'
                          },
                          "keyAuthorizations": {
                            "nameSpaces": [
                              "org.iso.transactiondata",
                              "urn:eudi:sca:payment:1"
                            ]
                          }
                        },
                        "validityInfo": {
                          "signed": dt'...',
                          "validFrom": dt'...',
                          "validUntil": dt'...'
                        }
                      } >>) >>,
                      /signature/ h'...'
                    ],
                    "nameSpaces": {
                      "org.multipaz.payment.sca.1": [
                        24(<< {
                          "digestID": 0,
                          "random": h'...',
                          "elementIdentifier": "account_id",
                          "elementValue": "acc-12345"
                        } >>)
                      ]
                    }
                  },
                  "deviceSigned": {
                    "deviceAuth": {
                      "deviceSignature": [ # COSE_Sign1
                        /protected/ << {
                          /alg/ 1: -7 # ES256: ECDSA with SHA-256
                        } >>,
                        /unprotected/ {},
                        /payload/ null,
                        /signature/ h'...'
                      ]
                    },
                    "nameSpaces": 24(<< {
                      "org.iso.transactiondata": {
                        "urn:eudi:sca:payment:1": {
                          "tipAmount": 24.65,
                          "docRequestId": 0,
                          "amount": 123.25,
                          "currency": "USD"
                        }
                      }
                    } >>)
                  }
                }
              ]
            }
        """.trimIndent()
        assertEquals(expectedResponseCdn, normalizeMdocResponseCdn(responseCdn.trim()))
    }

    @Test
    fun payment_Iso18013_SdJwtVc() = runTestWithSetup {
        val sessionTranscript = buildCborArray { add(Simple.NULL); add(Simple.NULL); add(byteArrayOf(1, 2, 3)) }
        val source = TipPresentmentSource(
            documentStore = documentStoreTestHarness.documentStore,
            documentTypeRepository = documentStoreTestHarness.documentTypeRepository,
        )
        source.insertTip(20.0)

        // 1. Verifier prepares entire DeviceRequest in ISO 18013-5 requesting SD-JWT VC
        val payload = PaymentTransaction.sampleData.payload
        val requestDataItem = PaymentTransaction.serializeIso18013Request(payload)
        val deviceRequest = buildDeviceRequest(sessionTranscript = sessionTranscript) {
            addDocRequest(
                docType = "org.multipaz.payment.sca.1",
                nameSpaces = mapOf(
                    "_" to mapOf("sdjwtvc_account_id" to false)
                ),
                docRequestInfo = DocRequestInfo(
                    docFormat = "dc+sd-jwt",
                    dataElementIdentifierMapping = mapOf(
                        "sdjwtvc_account_id" to buildJsonArray { add("account_id") }
                    ),
                    transactionData = TransactionsInfo(
                        data = mapOf(PaymentTransaction.identifier to requestDataItem)
                    )
                )
            )
        }

        // Pretty-printed entire DeviceRequest (Concise Diagnostic Notation)
        val requestCdn = Cdn.encode(
            item = deviceRequest.toDataItem(),
            options = CdnGeneratorOptions.Pretty
        )
        val expectedRequestCdn = """
            {
              "version": "1.1",
              "docRequests": [
                {
                  "itemsRequest": 24(<< {
                    "docType": "org.multipaz.payment.sca.1",
                    "nameSpaces": {
                      "_": {
                        "sdjwtvc_account_id": false
                      },
                      "org.iso.transactiondata": {
                        "urn:eudi:sca:payment:1": true
                      }
                    },
                    "requestInfo": {
                      "docFormat": "dc+sd-jwt",
                      "dataElementIdentifierMapping": {
                        "sdjwtvc_account_id": [
                          "account_id"
                        ]
                      },
                      "transactionData": {
                        "urn:eudi:sca:payment:1": {
                          "transactionId": "3AD99006-6E0D-4D07-AE75-5DAEF0FE21D9",
                          "currency": "USD",
                          "amount": 123.25,
                          "payee": {
                            "name": "Linux Foundation",
                            "id": "01234"
                          },
                          "tipRequested": true
                        }
                      }
                    }
                  } >>)
                }
              ]
            }
        """.trimIndent()
        assertEquals(expectedRequestCdn, requestCdn.trim())

        // 2. Wallet presentment
        val creationTime = Clock.System.now()
        val isoResponse = mdocPresentment(
            deviceRequest = deviceRequest,
            eReaderKey = null,
            sessionTranscript = sessionTranscript,
            source = source,
            keyAgreementPossible = emptyList(),
            requesterAppId = null,
            requesterOrigin = ORIGIN,
            creationTime = creationTime,
            preselectedDocuments = emptyList(),
            onWaitingForUserInput = {},
            onDocumentsInFocus = {}
        )
        val deviceResponse = isoResponse.deviceResponse

        // 3. Verifier verifies response
        deviceResponse.verify(
            sessionTranscript = sessionTranscript,
            eReaderKey = null,
            deviceRequest = deviceRequest,
            documentTypeRepository = source.documentTypeRepository,
            atTime = creationTime
        )

        assertEquals(0, deviceResponse.documents.size)
        assertEquals(1, deviceResponse.otherDocuments.size)
        val otherDoc = deviceResponse.otherDocuments[0]
        assertEquals("dc+sd-jwt", otherDoc.docFormat)

        // Pretty-printed entire DeviceResponse (Concise Diagnostic Notation) using LENGTH_ONLY
        val responseCdn = Cdn.encode(
            item = deviceResponse.toDataItem(),
            options = CdnGeneratorOptions(prettyPrint = true, byteStringFormat = ByteStringFormat.LENGTH_ONLY)
        )
        val expectedResponseCdn = """
            {
              "version": "1.1",
              "status": 0,
              "otherDocuments": [
                {
                  "docFormat": "dc+sd-jwt",
                  "data": ${otherDoc.data.size} bytes
                }
              ]
            }
        """.trimIndent()
        assertEquals(expectedResponseCdn, responseCdn.trim())

        // Pretty-printed SD-JWT KB-JWT payload (JSON)
        val decompressedData = otherDoc.data.toByteArray().zlibInflate()
        val sdJwtKb = SdJwtKb.fromCompactSerialization(decompressedData.decodeToString())
        val prettyJson = Json { prettyPrint = true }
        val kbJwtJson = prettyJson.encodeToString(sdJwtKb.jwtBody)
        val sdHash = sdJwtKb.jwtBody["sd_hash"]!!.jsonPrimitive.content
        val iat = sdJwtKb.jwtBody["iat"]!!.jsonPrimitive.content
        val nonce = sdJwtKb.jwtBody["nonce"]!!.jsonPrimitive.content
        val expectedKbJwtJson = """
            {
                "iat": $iat,
                "nonce": "$nonce",
                "aud": "none",
                "sd_hash": "$sdHash",
                "urn:eudi:sca:payment:1": {
                    "tip_amount": 24.65,
                    "doc_request_id": 0,
                    "amount": 123.25,
                    "currency": "USD"
                }
            }
        """.trimIndent()
        assertEquals(expectedKbJwtJson, kbJwtJson.trim())
    }

    @Test
    fun payment_OpenID4VP_SdJwtVc() = runTestWithSetup {
        val source = TipPresentmentSource(
            documentStore = documentStoreTestHarness.documentStore,
            documentTypeRepository = documentStoreTestHarness.documentTypeRepository,
        )
        source.insertTip(20.0)

        // 1. Verifier prepares DCQL query and transaction_data for OpenID4VP request
        val dcql = buildJsonObject {
            put("credentials", buildJsonArray {
                add(buildJsonObject {
                    put("id", "payment_credential")
                    put("format", "dc+sd-jwt")
                    put("meta", buildJsonObject {
                        put("vct_values", buildJsonArray { add("org.multipaz.payment.sca.1") })
                    })
                    put("claims", buildJsonArray {
                        add(buildJsonObject { put("path", buildJsonArray { add("account_id") }) })
                    })
                })
            })
        }

        val payload = PaymentTransaction.sampleData.payload
        val requestJsonString = PaymentTransaction.serializeOpenId4VpRequest(
            payload = payload,
            credentialIds = listOf("payment_credential"),
            hashAlgorithms = listOf(Algorithm.SHA256)
        )

        val prettyJson = Json { prettyPrint = true }
        val dcqlJsonString = prettyJson.encodeToString(dcql)
        val expectedDcqlJson = """
            {
                "credentials": [
                    {
                        "id": "payment_credential",
                        "format": "dc+sd-jwt",
                        "meta": {
                            "vct_values": [
                                "org.multipaz.payment.sca.1"
                            ]
                        },
                        "claims": [
                            {
                                "path": [
                                    "account_id"
                                ]
                            }
                        ]
                    }
                ]
            }
        """.trimIndent()
        assertEquals(expectedDcqlJson, dcqlJsonString.trim())

        val transactionDataJson = prettyJson.encodeToString(Json.parseToJsonElement(requestJsonString))
        val expectedTransactionDataJson = """
            {
                "type": "urn:eudi:sca:payment:1",
                "credential_ids": [
                    "payment_credential"
                ],
                "transaction_data_hashes_alg": [
                    "sha-256"
                ],
                "payload": {
                    "transaction_id": "3AD99006-6E0D-4D07-AE75-5DAEF0FE21D9",
                    "currency": "USD",
                    "amount": 123.25,
                    "payee": {
                        "name": "Linux Foundation",
                        "id": "01234"
                    },
                    "tip_requested": true
                }
            }
        """.trimIndent()
        assertEquals(expectedTransactionDataJson, transactionDataJson.trim())

        val nonce = "openid4vp-nonce-67890"
        val request = OpenID4VP.generateRequest(
            version = OpenID4VP.Version.DRAFT_29,
            origin = ORIGIN,
            nonce = nonce,
            responseEncryptionKey = null,
            verifierIdentities = emptyList(),
            responseMode = OpenID4VP.ResponseMode.DC_API,
            responseUri = null,
            dcqlQuery = dcql,
            jsonTransactionData = listOf(requestJsonString)
        )

        // 2. Wallet presentment
        val response = OpenID4VP.generateResponse(
            version = OpenID4VP.Version.DRAFT_29,
            preselectedDocuments = emptyList(),
            source = source,
            appId = null,
            origin = ORIGIN,
            request = request,
            requesterIdentities = emptyList(),
        )

        // 3. Verifier processes response
        val vpTokens = response.response["vp_token"]!!.jsonObject
        val compactSerialization = vpTokens["payment_credential"]!!.jsonArray[0].jsonPrimitive.content
        val sdJwtKb = SdJwtKb.fromCompactSerialization(compactSerialization)

        val transactionData = source.documentTypeRepository.parseJsonTransactions(
            base64UrlEncodedJson = listOf(requestJsonString.encodeToByteArray().toBase64Url())
        ).values.first()

        sdJwtKb.verify(
            issuerKey = documentStoreTestHarness.dsKey.publicKey,
            checkNonce = { it == nonce },
            checkAudience = { it == "origin:$ORIGIN" },
            checkCreationTime = { true },
            transactionData = transactionData
        )

        // 4. Pretty-printed SD-JWT + SD-JWT KB
        val kbJwtJson = prettyJson.encodeToString(sdJwtKb.jwtBody)
        val sdHash = sdJwtKb.jwtBody["sd_hash"]!!.jsonPrimitive.content
        val iat = sdJwtKb.jwtBody["iat"]!!.jsonPrimitive.content
        val expectedHash = transactionData.first().computeHash(Algorithm.SHA256).toByteArray().toBase64Url()
        val expectedKbJwtJson = """
            {
                "iat": $iat,
                "nonce": "openid4vp-nonce-67890",
                "aud": "origin:https://verifier.multipaz.org",
                "sd_hash": "$sdHash",
                "urn:eudi:sca:payment:1": {
                    "tip_amount": 24.65
                },
                "transaction_data_hashes_alg": "sha-256",
                "transaction_data_hashes": [
                    "$expectedHash"
                ]
            }
        """.trimIndent()
        assertEquals(expectedKbJwtJson, kbJwtJson.trim())
    }

    @Test
    fun payment_OpenID4VP_IsoMdoc() = runTestWithSetup {
        val source = TipPresentmentSource(
            documentStore = documentStoreTestHarness.documentStore,
            documentTypeRepository = documentStoreTestHarness.documentTypeRepository,
        )
        source.insertTip(20.0)

        // 1. Verifier prepares DCQL query and transaction_data for OpenID4VP request
        val dcql = buildJsonObject {
            put("credentials", buildJsonArray {
                add(buildJsonObject {
                    put("id", "payment_credential")
                    put("format", "mso_mdoc")
                    put("meta", buildJsonObject {
                        put("doctype_value", "org.multipaz.payment.sca.1")
                    })
                    put("claims", buildJsonArray {
                        add(buildJsonObject {
                            put("path", buildJsonArray {
                                add("org.multipaz.payment.sca.1")
                                add("account_id")
                            })
                        })
                    })
                })
            })
        }

        val payload = PaymentTransaction.sampleData.payload
        val requestJsonString = PaymentTransaction.serializeOpenId4VpRequest(
            payload = payload,
            credentialIds = listOf("payment_credential"),
            hashAlgorithms = listOf(Algorithm.SHA256)
        )

        val prettyJson = Json { prettyPrint = true }
        val dcqlJsonString = prettyJson.encodeToString(dcql)
        val expectedDcqlJson = """
            {
                "credentials": [
                    {
                        "id": "payment_credential",
                        "format": "mso_mdoc",
                        "meta": {
                            "doctype_value": "org.multipaz.payment.sca.1"
                        },
                        "claims": [
                            {
                                "path": [
                                    "org.multipaz.payment.sca.1",
                                    "account_id"
                                ]
                            }
                        ]
                    }
                ]
            }
        """.trimIndent()
        assertEquals(expectedDcqlJson, dcqlJsonString.trim())

        val transactionDataJson = prettyJson.encodeToString(Json.parseToJsonElement(requestJsonString))
        val expectedTransactionDataJson = """
            {
                "type": "urn:eudi:sca:payment:1",
                "credential_ids": [
                    "payment_credential"
                ],
                "transaction_data_hashes_alg": [
                    "sha-256"
                ],
                "payload": {
                    "transaction_id": "3AD99006-6E0D-4D07-AE75-5DAEF0FE21D9",
                    "currency": "USD",
                    "amount": 123.25,
                    "payee": {
                        "name": "Linux Foundation",
                        "id": "01234"
                    },
                    "tip_requested": true
                }
            }
        """.trimIndent()
        assertEquals(expectedTransactionDataJson, transactionDataJson.trim())

        val nonce = "openid4vp-nonce-67890"
        val request = OpenID4VP.generateRequest(
            version = OpenID4VP.Version.DRAFT_29,
            origin = ORIGIN,
            nonce = nonce,
            responseEncryptionKey = null,
            verifierIdentities = emptyList(),
            responseMode = OpenID4VP.ResponseMode.DC_API,
            responseUri = null,
            dcqlQuery = dcql,
            jsonTransactionData = listOf(requestJsonString)
        )

        // 2. Wallet presentment
        val response = OpenID4VP.generateResponse(
            version = OpenID4VP.Version.DRAFT_29,
            preselectedDocuments = emptyList(),
            source = source,
            appId = null,
            origin = ORIGIN,
            request = request,
            requesterIdentities = emptyList(),
        )

        // 3. Verifier processes response
        val vpTokens = response.response["vp_token"]!!.jsonObject
        val encodedDeviceResponse = vpTokens["payment_credential"]!!.jsonArray[0].jsonPrimitive.content.fromBase64Url()
        val deviceResponse = DeviceResponse.fromDataItem(Cbor.decode(encodedDeviceResponse))

        val handoverInfo = Cbor.encode(
            buildCborArray {
                add(ORIGIN)
                add(nonce)
                add(Simple.NULL)
            }
        )
        val handoverInfoDigest = Crypto.digest(Algorithm.SHA256, handoverInfo)
        val sessionTranscript = buildCborArray {
            add(Simple.NULL)
            add(Simple.NULL)
            addCborArray {
                add("OpenID4VPDCAPIHandover")
                add(handoverInfoDigest)
            }
        }

        val transactionData = source.documentTypeRepository.parseJsonTransactions(
            base64UrlEncodedJson = listOf(requestJsonString.encodeToByteArray().toBase64Url())
        ).values.first()

        deviceResponse.verifySingleDoc(
            sessionTranscript = sessionTranscript,
            transactionData = transactionData
        )
        assertEquals(DeviceResponse.STATUS_OK, deviceResponse.status)
        assertEquals(1, deviceResponse.documents.size)
        val mdocDoc = deviceResponse.documents[0]
        val txElements = mdocDoc.deviceNamespaces.data[PaymentTransaction.openId4VpMdocResponseNamespace]!!
        assertEquals(-16L, txElements["transactionDataHashAlg"]!!.asNumber)
        val expectedHash = transactionData.first().computeHash(Algorithm.SHA256)
        assertEquals(expectedHash, ByteString(txElements["transactionDataHash"]!!.asBstr))
        assertEquals(24.65, txElements["tipAmount"]!!.asDouble)

        // 4. Pretty-printed entire DeviceResponse (Concise Diagnostic Notation)
        val responseCdn = Cdn.encode(
            item = deviceResponse.toDataItem(),
            options = CdnGeneratorOptions.Pretty
        )
        val expectedResponseCdn = """
            {
              "version": "1.0",
              "status": 0,
              "documents": [
                {
                  "docType": "org.multipaz.payment.sca.1",
                  "issuerSigned": {
                    "issuerAuth": [ # COSE_Sign1
                      /protected/ << {
                        /alg/ 1: -7 # ES256: ECDSA with SHA-256
                      } >>,
                      /unprotected/ {
                        /x5chain/ 33:
                        # Subject DN: C=US,CN=OWF Multipaz TEST DS
                        # Issuer DN: C=US,CN=OWF Multipaz TEST IACA
                        cert'''...'''
                      },
                      /payload/ << 24(<< {
                        "version": "1.0",
                        "digestAlgorithm": "SHA-256",
                        "docType": "org.multipaz.payment.sca.1",
                        "valueDigests": {
                          "org.multipaz.payment.sca.1": {
                            0: h'...'
                          }
                        },
                        "deviceKeyInfo": {
                          "deviceKey": { # COSE_Key
                            /kty/ 1: 2, # EC2
                            /crv/ -1: 1, # P-256
                            /x/ -2: h'...',
                            /y/ -3: h'...'
                          },
                          "keyAuthorizations": {
                            "nameSpaces": [
                              "org.iso.transactiondata",
                              "urn:eudi:sca:payment:1"
                            ]
                          }
                        },
                        "validityInfo": {
                          "signed": dt'...',
                          "validFrom": dt'...',
                          "validUntil": dt'...'
                        }
                      } >>) >>,
                      /signature/ h'...'
                    ],
                    "nameSpaces": {
                      "org.multipaz.payment.sca.1": [
                        24(<< {
                          "digestID": 0,
                          "random": h'...',
                          "elementIdentifier": "account_id",
                          "elementValue": "acc-12345"
                        } >>)
                      ]
                    }
                  },
                  "deviceSigned": {
                    "deviceAuth": {
                      "deviceSignature": [ # COSE_Sign1
                        /protected/ << {
                          /alg/ 1: -7 # ES256: ECDSA with SHA-256
                        } >>,
                        /unprotected/ {},
                        /payload/ null,
                        /signature/ h'...'
                      ]
                    },
                    "nameSpaces": 24(<< {
                      "urn:eudi:sca:payment:1": {
                        "tipAmount": 24.65,
                        "transactionDataHashAlg": -16,
                        "transactionDataHash": h'${expectedHash.toByteArray().toHex()}'
                      }
                    } >>)
                  }
                }
              ]
            }
        """.trimIndent()
        assertEquals(expectedResponseCdn, normalizeMdocResponseCdn(responseCdn.trim()))
    }

    @Test
    fun test_normalizeMdocResponseCdn() {
        assertEquals(
            "\"random\": h'...',",
            normalizeMdocResponseCdn("\"random\": h'2ba86224fbb8692180862f0b7d75',")
        )
        assertEquals(
            "\"random\": h'...',",
            normalizeMdocResponseCdn("\"random\": << 10(h'2ba86224fbb8692180862f0b7d75') >>,")
        )
    }
}

private fun normalizeMdocResponseCdn(cdn: String): String {
    return cdn
        .replace(Regex("""cert'''[\s\S]*?'''"""), "cert'''...'''")
        .replace(Regex("""0: h'[0-9a-fA-F]+'"""), "0: h'...'")
        .replace(Regex("""/x/ -2: h'[0-9a-fA-F]+'"""), "/x/ -2: h'...'")
        .replace(Regex("""/y/ -3: h'[0-9a-fA-F]+'"""), "/y/ -3: h'...'")
        .replace(Regex("""dt'[0-9T:Z-]+'"""), "dt'...'")
        .replace(Regex("""/signature/ h'[0-9a-fA-F]+'"""), "/signature/ h'...'")
        .replace(Regex(""""random": (h'[0-9a-fA-F]+'|<<[\s\S]*?>>(?=\s*[,}]))"""), "\"random\": h'...'")
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
