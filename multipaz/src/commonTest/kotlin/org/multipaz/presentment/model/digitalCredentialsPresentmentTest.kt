package org.multipaz.presentment.model

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.multipaz.asn1.ASN1Integer
import org.multipaz.cbor.Cbor
import org.multipaz.cbor.DiagnosticOption
import org.multipaz.cbor.Simple
import org.multipaz.cbor.addCborArray
import org.multipaz.cbor.buildCborArray
import org.multipaz.crypto.Algorithm
import org.multipaz.crypto.Crypto
import org.multipaz.crypto.EcCurve
import org.multipaz.crypto.EcPrivateKey
import org.multipaz.crypto.JsonWebEncryption
import org.multipaz.crypto.AsymmetricKey
import org.multipaz.crypto.X500Name
import org.multipaz.crypto.X509CertChain
import org.multipaz.document.Document
import org.multipaz.mdoc.response.DeviceResponseParser
import org.multipaz.mdoc.util.MdocUtil
import org.multipaz.openid.OpenID4VP
import org.multipaz.presentment.CredentialPresentmentData
import org.multipaz.request.Requester
import org.multipaz.sdjwt.SdJwtKb
import org.multipaz.storage.ephemeral.EphemeralStorage
import org.multipaz.trustmanagement.TrustManagerLocal
import org.multipaz.trustmanagement.TrustPoint
import org.multipaz.util.Constants
import org.multipaz.util.Logger
import org.multipaz.util.fromBase64Url
import org.multipaz.util.toBase64Url
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DigitalCredentialsPresentmentTest {
    companion object {
        private const val TAG = "DigitalCredentialsPresentmentTest"

        private const val CLIENT_ID = "x509_san_dns:verifier.multipaz.org"
        private const val ORIGIN = "https://verifier.multipaz.org"
        private const val APP_ID = "org.multipaz.testApp"
    }

    val documentStoreTestHarness = DocumentStoreTestHarness()

    class TestPresentmentMechanism(
        protocol: String,
        data: JsonObject,
        documents: List<Document>,
        var response: String? = null,
        var closed: Boolean = false
    ): DigitalCredentialsPresentmentMechanism(
        appId = APP_ID,
        origin = ORIGIN,
        protocol = protocol,
        data = data,
        preselectedDocuments = documents,
    ) {
        override fun sendResponse(protocol: String, data: JsonObject) {
            this.response = Json.encodeToString(data)
        }

        override fun close() {
            closed = true
        }
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
        dcql: JsonObject
    ): TestOpenID4VPResponse {
        documentStoreTestHarness.initialize()
        documentStoreTestHarness.provisionStandardDocuments()

        val readerTrustManager = TrustManagerLocal(EphemeralStorage())
        val presentmentSource = SimplePresentmentSource(
            documentStore = documentStoreTestHarness.documentStore,
            documentTypeRepository = documentStoreTestHarness.documentTypeRepository,
            readerTrustManager = readerTrustManager,
            preferSignatureToKeyAgreement = true,
            domainMdocSignature = "mdoc",
            domainKeyBoundSdJwt = "sdjwt",
        )

        val nonce = Random.Default.nextBytes(16).toBase64Url()

        val readerAuthKey = if (signRequest) {
            val key = Crypto.createEcPrivateKey(EcCurve.P256)
            val readerRootCerts = documentStoreTestHarness.readerRootKey.certChain.certificates
            val cert = MdocUtil.generateReaderCertificate(
                readerRootKey = documentStoreTestHarness.readerRootKey,
                readerKey = key.publicKey,
                subject = X500Name.fromName("CN=Multipaz Reader Cert Single-Use key"),
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
            dclqQuery = dcql
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
        val presentmentMechanism = TestPresentmentMechanism(
            protocol = protocol,
            data = request,
            documents = emptyList(),
        )

        val shownConsentPrompts = mutableListOf<ShownConsentPrompt>()

        val dismissable = MutableStateFlow<Boolean>(true)
        digitalCredentialsPresentment(
            documentTypeRepository = documentStoreTestHarness.documentTypeRepository,
            source = presentmentSource,
            mechanism = presentmentMechanism,
            dismissable = dismissable,
            showConsentPrompt = { presentmentData, preselectedDocuments, requester, trustPoint ->
                shownConsentPrompts.add(ShownConsentPrompt(presentmentData, preselectedDocuments, requester, trustPoint))
                presentmentData.select(preselectedDocuments)
            }
        )
        val dcResponseObject = Json.decodeFromString(JsonObject.serializer(), presentmentMechanism.response!!)
        val decryptedDcResponse = if (encryptionKey != null) {
            val jweCompactSerialization = dcResponseObject["response"]!!.jsonPrimitive.content
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
            dcResponseObject
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
        expectedMdocResponse: String
    ) {
        val response = testOpenID4VP(
            version = version,
            signRequest = signRequest,
            encryptionKey = encryptionKey,
            dcql = Json.decodeFromString(JsonObject.serializer(), dcql)
        )
        assertEquals(1, response.vpToken.keys.size)
        val credId = response.vpToken.keys.first()
        val encodedDeviceResponse = response.vpToken[credId]!![0].fromBase64Url()

        val handoverInfo = if (version == OpenID4VP.Version.DRAFT_29) {
            Cbor.encode(
                buildCborArray {
                    add(response.origin)
                    add(response.nonce)
                    if (encryptionKey != null) {
                        add(encryptionKey.publicKey.toJwkThumbprint(Algorithm.SHA256).toByteArray())
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
        val encodedSessionTranscript = Cbor.encode(
            buildCborArray {
                add(Simple.NULL) // DeviceEngagementBytes
                add(Simple.NULL) // EReaderKeyBytes
                addCborArray {
                    add("OpenID4VPDCAPIHandover")
                    add(Crypto.digest(Algorithm.SHA256, handoverInfo))
                }
            }
        )
        val deviceResponse = DeviceResponseParser(encodedDeviceResponse, encodedSessionTranscript).parse()
        assertEquals(Constants.DEVICE_RESPONSE_STATUS_OK, deviceResponse.status)
        assertEquals(1, deviceResponse.documents.size)
        val doc = deviceResponse.documents[0]
        assertTrue(doc.issuerSignedAuthenticated)
        assertTrue(doc.deviceSignedAuthenticated)
        assertEquals(0, doc.numIssuerEntryDigestMatchFailures)
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
        expectedSdJwtResponse: String
    ) {
        val response = testOpenID4VP(
            version = version,
            signRequest = signRequest,
            encryptionKey = encryptionKey,
            dcql = Json.decodeFromString(JsonObject.serializer(), dcql)
        )
        assertEquals(1, response.vpToken.keys.size)
        val credId = response.vpToken.keys.first()
        val compactSerialization = response.vpToken[credId]!![0]
        val sdJwtKb = SdJwtKb(compactSerialization)
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
        ).filterKeys { key -> !setOf("iat", "nbf", "exp", "cnf").contains(key) }  // filter out variable claims
        assertEquals(
            expectedSdJwtResponse,
            Json {
                prettyPrint = true
                prettyPrintIndent = "  "
            }.encodeToString(processedJwt)
        )
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
        )
    }

    @Test fun OID4VP_24_NoSignedRequest_NoEncryptedResponse_mDL() = runTest { test_OID4VP_mDL(24, false, false) }
    @Test fun OID4VP_24_NoSignedRequest_EncryptedResponse_mDL() = runTest { test_OID4VP_mDL(24, false, true) }
    @Test fun OID4VP_24_SignedRequest_NoEncryptedResponse_mDL() = runTest { test_OID4VP_mDL(24, true, false) }
    @Test fun OID4VP_24_SignedRequest_EncryptedResponse_mDL() = runTest { test_OID4VP_mDL(24, true, true) }

    @Test fun OID4VP_24_NoSignedRequest_NoEncryptedResponse_SDJWT() = runTest { test_OID4VP_SDJWT(24, false, false) }
    @Test fun OID4VP_24_NoSignedRequest_EncryptedResponse_SDJWT() = runTest { test_OID4VP_SDJWT(24, false, true) }
    @Test fun OID4VP_24_SignedRequest_NoEncryptedResponse_SDJWT() = runTest { test_OID4VP_SDJWT(24, true, false) }
    @Test fun OID4VP_24_SignedRequest_EncryptedResponse_SDJWT() = runTest { test_OID4VP_SDJWT(24, true, true) }

    @Test fun OID4VP_29_NoSignedRequest_NoEncryptedResponse_mDL() = runTest { test_OID4VP_mDL(29, false, false) }
    @Test fun OID4VP_29_NoSignedRequest_EncryptedResponse_mDL() = runTest { test_OID4VP_mDL(29, false, true) }
    @Test fun OID4VP_29_SignedRequest_NoEncryptedResponse_mDL() = runTest { test_OID4VP_mDL(29, true, false) }
    @Test fun OID4VP_29_SignedRequest_EncryptedResponse_mDL() = runTest { test_OID4VP_mDL(29, true, true) }

    @Test fun OID4VP_29_NoSignedRequest_NoEncryptedResponse_SDJWT() = runTest { test_OID4VP_SDJWT(29, false, false) }
    @Test fun OID4VP_29_NoSignedRequest_EncryptedResponse_SDJWT() = runTest { test_OID4VP_SDJWT(29, false, true) }
    @Test fun OID4VP_29_SignedRequest_NoEncryptedResponse_SDJWT() = runTest { test_OID4VP_SDJWT(29, true, false) }
    @Test fun OID4VP_29_SignedRequest_EncryptedResponse_SDJWT() = runTest { test_OID4VP_SDJWT(29, true, true) }

}

private fun DeviceResponseParser.DeviceResponse.prettyPrint(): String {
    val diagOptions = setOf(DiagnosticOption.BSTR_PRINT_LENGTH)
    val sb = StringBuilder()
    for (n in documents.indices) {
        val doc = documents[n]
        sb.appendLine("Document $n:")
        sb.appendLine("  DocType: ${doc.docType}")
        sb.appendLine("  IssuerSigned:")
        for (namespaceName in doc.issuerNamespaces) {
            sb.appendLine("    $namespaceName:")
            for (dataElementName in doc.getIssuerEntryNames(namespaceName)) {
                val encodedValue = doc.getIssuerEntryData(namespaceName, dataElementName)
                sb.appendLine("      $dataElementName: ${Cbor.toDiagnostics(encodedValue, diagOptions)}")
            }
        }

    }
    return sb.toString()
}
