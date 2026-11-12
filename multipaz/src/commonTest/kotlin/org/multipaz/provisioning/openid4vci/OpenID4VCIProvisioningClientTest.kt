package org.multipaz.provisioning.openid4vci

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.http.encodeURLParameter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import org.multipaz.crypto.Algorithm
import org.multipaz.provisioning.CredentialFormat
import org.multipaz.provisioning.CredentialKeyAttestation
import org.multipaz.provisioning.KeyBindingInfo
import org.multipaz.provisioning.KeyBindingType
import org.multipaz.rpc.backend.BackendEnvironment
import org.multipaz.securearea.CreateKeySettings
import org.multipaz.securearea.KeyAttestation
import org.multipaz.securearea.SecureArea
import org.multipaz.securearea.SecureAreaProvider
import org.multipaz.securearea.software.SoftwareSecureArea
import org.multipaz.storage.ephemeral.EphemeralStorage
import kotlin.reflect.KClass
import kotlin.reflect.cast
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class OpenID4VCIProvisioningClientTest {
    private lateinit var secureArea: SecureArea
    private lateinit var secureAreaProvider: SecureAreaProvider<SecureArea>

    private val clientPreferences = OpenID4VCIClientPreferences(
        clientId = "test-client-id",
        redirectUrl = "https://example.com/redirect",
        locales = listOf("en-US"),
        signingAlgorithms = listOf(Algorithm.ESP256, Algorithm.ES256)
    )

    private val testBackend = object : OpenID4VCIBackend {
        override suspend fun getClientId(): String = "test-client-id"
        override suspend fun createJwtClientAssertion(authorizationServerIdentifier: String): String =
            "header.payload.sig"
        override suspend fun createJwtWalletAttestation(keyAttestation: KeyAttestation): String =
            "wallet.attestation.jwt"
        override suspend fun createJwtKeyAttestation(
            credentialKeyAttestations: List<CredentialKeyAttestation>,
            challenge: String,
            userAuthentication: List<String>?,
            keyStorage: List<String>?
        ): String = "key.attestation.jwt"
    }

    @BeforeTest
    fun setup() = runTest {
        val storage = EphemeralStorage()
        secureArea = SoftwareSecureArea.create(storage)
        secureAreaProvider = SecureAreaProvider(Dispatchers.Default) { secureArea }
    }

    private fun testEnvironment(httpClient: HttpClient): BackendEnvironment = object : BackendEnvironment {
        override fun <T : Any> getInterface(clazz: KClass<T>): T? {
            return clazz.cast(
                when (clazz) {
                    HttpClient::class -> httpClient
                    OpenID4VCIBackend::class -> testBackend
                    OpenID4VCIClientPreferences::class -> clientPreferences
                    SecureAreaProvider::class -> secureAreaProvider
                    else -> return null
                }
            )
        }
    }

    // TODO: Tests for out-of-spec inline metadata from unmerged OpenID4VCI PR #476
    //  (https://github.com/openid/OpenID4VCI/pull/476). Remove once standardized/no longer needed.
    @Test
    fun credentialOfferParsesInlineMetadata() = runTest {
        val offerJson = buildJsonObject {
            put("credential_issuer", "https://issuer.example.com")
            putJsonArray("credential_configuration_ids") {
                add(JsonPrimitive("org.iso.18013.5.1.mDL"))
            }
            putJsonObject("credential_issuer_metadata") {
                put("credential_issuer", "https://issuer.example.com")
                put("credential_endpoint", "https://issuer.example.com/credential")
            }
            putJsonObject("authorization_server_metadata") {
                put("issuer", "https://issuer.example.com")
                put("token_endpoint", "https://issuer.example.com/token")
            }
            putJsonObject("grants") {
                putJsonObject("urn:ietf:params:oauth:grant-type:pre-authorized_code") {
                    put("pre-authorized_code", "preauth-123")
                }
            }
        }
        val offer = CredentialOffer.parseJson(offerJson)
        assertIs<CredentialOffer.PreauthorizedCode>(offer)
        assertEquals("https://issuer.example.com", offer.issuerUri)
        assertEquals("org.iso.18013.5.1.mDL", offer.configurationId)
        assertEquals("preauth-123", offer.preauthorizedCode)
        assertNotNull(offer.credentialIssuerMetadata)
        assertNotNull(offer.authorizationServerMetadata)
    }

    @Test
    fun credentialOfferRejectsBothAuthorizationServerAndMetadata() = runTest {
        val offerJson = buildJsonObject {
            put("credential_issuer", "https://issuer.example.com")
            putJsonArray("credential_configuration_ids") {
                add(JsonPrimitive("org.iso.18013.5.1.mDL"))
            }
            putJsonObject("authorization_server_metadata") {
                put("issuer", "https://issuer.example.com")
                put("token_endpoint", "https://issuer.example.com/token")
            }
            putJsonObject("grants") {
                putJsonObject("urn:ietf:params:oauth:grant-type:pre-authorized_code") {
                    put("pre-authorized_code", "preauth-123")
                    put("authorization_server", "https://auth.example.com")
                }
            }
        }
        assertFailsWith<IllegalStateException> {
            CredentialOffer.parseJson(offerJson)
        }
    }

    @Test
    fun inlineMetadataBypassesWellKnownEndpointsAndDefaultsToClientAttestation() = runTest {
        var challengeCalled = false
        var tokenCalled = false
        var nonceCalled = false

        val mockEngine = MockEngine { request ->
            when (request.url.toString()) {
                "https://digital-credentials.dev/.well-known/openid-credential-issuer" -> {
                    respond("Not Found", HttpStatusCode.NotFound)
                }
                "https://digital-credentials.dev/openid4vci/client_attestation_challenge" -> {
                    challengeCalled = true
                    respond(
                        content = """{"attestation_challenge":"test-attestation-challenge"}""",
                        status = HttpStatusCode.OK,
                        headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString())
                    )
                }
                "https://digital-credentials.dev/openid4vci/token" -> {
                    tokenCalled = true
                    assertNotNull(request.headers["OAuth-Client-Attestation"])
                    assertNotNull(request.headers["OAuth-Client-Attestation-PoP"])
                    respond(
                        content = """{"access_token":"test-access-token","expires_in":3600}""",
                        status = HttpStatusCode.OK,
                        headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString())
                    )
                }
                "https://digital-credentials.dev/openid4vci/nonce_endpoint" -> {
                    nonceCalled = true
                    respond(
                        content = """{"c_nonce":"test-c-nonce"}""",
                        status = HttpStatusCode.OK,
                        headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString())
                    )
                }
                else -> error("Unexpected HTTP request to ${request.url}")
            }
        }
        val httpClient = HttpClient(mockEngine)

        val offerJson = """
            {
              "authorization_server_metadata": {
                "authorization_endpoint": "https://digital-credentials.dev/openid4vci/auth",
                "challenge_endpoint": "https://digital-credentials.dev/openid4vci/client_attestation_challenge",
                "grant_types_supported": [
                  "authorization_code",
                  "refresh_token",
                  "urn:ietf:params:oauth:grant-type:pre-authorized_code"
                ],
                "issuer": "https://digital-credentials.dev",
                "response_types_supported": [
                  "code",
                  "token"
                ],
                "token_endpoint": "https://digital-credentials.dev/openid4vci/token"
              },
              "credential_configuration_ids": [
                "org.iso.18013.5.1.mDL"
              ],
              "credential_issuer": "https://digital-credentials.dev",
              "credential_issuer_metadata": {
                "credential_configurations_supported": {
                  "org.iso.18013.5.1.mDL": {
                    "credential_signing_alg_values_supported": ["ES256"],
                    "cryptographic_binding_methods_supported": ["cose_key"],
                    "display": [
                      {
                        "description": "Mobile Driving License",
                        "locale": "en-US",
                        "name": "Driver's License"
                      }
                    ],
                    "doctype": "org.iso.18013.5.1.mDL",
                    "format": "mso_mdoc",
                    "proof_types_supported": {
                      "android_keystore_attestation": {
                        "proof_signing_alg_values_supported": ["ES256"]
                      },
                      "jwt": {
                        "proof_signing_alg_values_supported": ["ES256"]
                      }
                    }
                  }
                },
                "credential_endpoint": "https://digital-credentials.dev/openid4vci/credential_endpoint",
                "credential_issuer": "https://digital-credentials.dev",
                "nonce_endpoint": "https://digital-credentials.dev/openid4vci/nonce_endpoint"
              },
              "grants": {
                "urn:ietf:params:oauth:grant-type:pre-authorized_code": {
                  "pre-authorized_code": "preauth-code-xyz"
                }
              }
            }
        """.trimIndent()

        val offerUri = "openid-credential-offer://?credential_offer=${offerJson.encodeURLParameter()}"

        withContext(testEnvironment(httpClient)) {
            val client = OpenID4VCIProvisioningClient.createFromOffer(
                offerUri = offerUri,
                clientPreferences = clientPreferences
            )
            val metadata = client.getMetadata()
            val credMetadata = metadata.credentials["org.iso.18013.5.1.mDL"]
            assertNotNull(credMetadata)
            assertEquals("Driver's License", credMetadata.display.text)
            assertEquals(CredentialFormat.Mdoc("org.iso.18013.5.1.mDL"), credMetadata.format)
            assertIs<KeyBindingType.Attestation>(credMetadata.keyBindingType)

            val challenges = client.getAuthorizationChallenges()
            assertTrue(challenges.isEmpty())
            assertTrue(challengeCalled)
            assertTrue(tokenCalled)

            val cNonce = client.getKeyBindingChallenge()
            assertEquals("test-c-nonce", cNonce)
            assertTrue(nonceCalled)
        }
    }

    @Test
    fun preAuthorizedOnlyServerWithoutAuthorizationCodeMetadataSucceeds() = runTest {
        val asMetadataJson = Json.parseToJsonElement(
            """
            {
              "issuer": "https://issuer.example",
              "token_endpoint": "https://issuer.example/token",
              "grant_types_supported": ["urn:ietf:params:oauth:grant-type:pre-authorized_code"],
              "token_endpoint_auth_methods_supported": ["attest_jwt_client_auth"]
            }
            """.trimIndent()
        ).jsonObject

        val config = AuthorizationConfiguration.get(
            url = "https://issuer.example",
            clientPreferences = clientPreferences,
            authorizationServerMetadata = asMetadataJson
        )
        assertEquals("https://issuer.example", config.identifier)
        assertNull(config.authorizationEndpoint)
        assertNull(config.pushedAuthorizationRequestEndpoint)
        assertEquals("https://issuer.example/token", config.tokenEndpoint)
        assertEquals(ClientAuthenticationType.CLIENT_ATTESTATION, config.clientAuthentication)
    }

    @Test
    fun authorizationCodeFlowFailsWhenAuthorizationCodeMetadataMissing() = runTest {
        val issuerMetadataJson = """
            {
              "credential_issuer": "https://issuer.example",
              "credential_endpoint": "https://issuer.example/credential",
              "credential_configurations_supported": {
                "mDL": {
                  "format": "mso_mdoc",
                  "doctype": "org.iso.18013.5.1.mDL"
                }
              }
            }
        """.trimIndent()

        // 1. Missing authorization_endpoint
        val mockMissingAuthEndpoint = MockEngine { request ->
            when (request.url.toString()) {
                "https://issuer.example/.well-known/openid-credential-issuer" -> respond(
                    content = issuerMetadataJson,
                    status = HttpStatusCode.OK,
                    headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString())
                )
                "https://issuer.example/.well-known/oauth-authorization-server" -> respond(
                    content = """
                        {
                          "issuer": "https://issuer.example",
                          "token_endpoint": "https://issuer.example/token",
                          "pushed_authorization_request_endpoint": "https://issuer.example/par"
                        }
                    """.trimIndent(),
                    status = HttpStatusCode.OK,
                    headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString())
                )
                else -> error("Unexpected request: ${request.url}")
            }
        }
        withContext(testEnvironment(HttpClient(mockMissingAuthEndpoint))) {
            val client = OpenID4VCIProvisioningClient.createFromCredentialId(
                issuerUrl = "https://issuer.example",
                credentialId = "mDL",
                clientPreferences = clientPreferences
            )
            val err = assertFailsWith<IllegalStateException> {
                client.getAuthorizationChallenges()
            }
            assertEquals("Authorization server metadata: authorization_endpoint must be a string", err.message)
        }

        // 2. Missing pushed_authorization_request_endpoint
        val mockMissingParEndpoint = MockEngine { request ->
            when (request.url.toString()) {
                "https://issuer.example/.well-known/openid-credential-issuer" -> respond(
                    content = issuerMetadataJson,
                    status = HttpStatusCode.OK,
                    headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString())
                )
                "https://issuer.example/.well-known/oauth-authorization-server" -> respond(
                    content = """
                        {
                          "issuer": "https://issuer.example",
                          "authorization_endpoint": "https://issuer.example/authorize",
                          "token_endpoint": "https://issuer.example/token"
                        }
                    """.trimIndent(),
                    status = HttpStatusCode.OK,
                    headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString())
                )
                else -> error("Unexpected request: ${request.url}")
            }
        }
        withContext(testEnvironment(HttpClient(mockMissingParEndpoint))) {
            val client = OpenID4VCIProvisioningClient.createFromCredentialId(
                issuerUrl = "https://issuer.example",
                credentialId = "mDL",
                clientPreferences = clientPreferences
            )
            val err = assertFailsWith<IllegalStateException> {
                client.getAuthorizationChallenges()
            }
            assertEquals(
                "Authorization server metadata: pushed_authorization_request_endpoint must be a string",
                err.message
            )
        }

        // 3. Unsupported code_challenge_methods_supported (without response_types_supported present)
        val mockUnsupportedPkce = MockEngine { request ->
            when (request.url.toString()) {
                "https://issuer.example/.well-known/openid-credential-issuer" -> respond(
                    content = issuerMetadataJson,
                    status = HttpStatusCode.OK,
                    headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString())
                )
                "https://issuer.example/.well-known/oauth-authorization-server" -> respond(
                    content = """
                        {
                          "issuer": "https://issuer.example",
                          "authorization_endpoint": "https://issuer.example/authorize",
                          "pushed_authorization_request_endpoint": "https://issuer.example/par",
                          "token_endpoint": "https://issuer.example/token",
                          "code_challenge_methods_supported": ["plain"]
                        }
                    """.trimIndent(),
                    status = HttpStatusCode.OK,
                    headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString())
                )
                else -> error("Unexpected request: ${request.url}")
            }
        }
        withContext(testEnvironment(HttpClient(mockUnsupportedPkce))) {
            val client = OpenID4VCIProvisioningClient.createFromCredentialId(
                issuerUrl = "https://issuer.example",
                credentialId = "mDL",
                clientPreferences = clientPreferences
            )
            val err = assertFailsWith<IllegalStateException> {
                client.getAuthorizationChallenges()
            }
            assertEquals("challenge type 'S256' is not supported", err.message)
        }

        // 4. Unsupported response_types_supported
        val mockUnsupportedResponseType = MockEngine { request ->
            when (request.url.toString()) {
                "https://issuer.example/.well-known/openid-credential-issuer" -> respond(
                    content = issuerMetadataJson,
                    status = HttpStatusCode.OK,
                    headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString())
                )
                "https://issuer.example/.well-known/oauth-authorization-server" -> respond(
                    content = """
                        {
                          "issuer": "https://issuer.example",
                          "authorization_endpoint": "https://issuer.example/authorize",
                          "pushed_authorization_request_endpoint": "https://issuer.example/par",
                          "token_endpoint": "https://issuer.example/token",
                          "response_types_supported": ["token"]
                        }
                    """.trimIndent(),
                    status = HttpStatusCode.OK,
                    headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString())
                )
                else -> error("Unexpected request: ${request.url}")
            }
        }
        withContext(testEnvironment(HttpClient(mockUnsupportedResponseType))) {
            val client = OpenID4VCIProvisioningClient.createFromCredentialId(
                issuerUrl = "https://issuer.example",
                credentialId = "mDL",
                clientPreferences = clientPreferences
            )
            val err = assertFailsWith<IllegalStateException> {
                client.getAuthorizationChallenges()
            }
            assertEquals("response type 'code' is not supported", err.message)
        }
    }

    @Test
    fun tokenResponseAuthorizationDetailsUsesCredentialIdentifierAndUpdatesConfigurationId() = runTest {
        var credentialRequestBody: JsonObject? = null

        val mockEngine = MockEngine { request ->
            when (request.url.toString()) {
                "https://digital-credentials.dev/openid4vci/client_attestation_challenge" -> respond(
                    content = """{"attestation_challenge":"test-attestation-challenge"}""",
                    status = HttpStatusCode.OK,
                    headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString())
                )
                "https://digital-credentials.dev/openid4vci/token" -> respond(
                    content = """
                        {
                          "access_token": "test-access-token",
                          "authorization_details": [
                            {
                              "credential_configuration_id": "org.iso.18013.5.1.mDL",
                              "credential_identifiers": ["mdl1"],
                              "type": "openid_credential"
                            }
                          ],
                          "expires_in": 86400,
                          "token_type": "DPoP"
                        }
                    """.trimIndent(),
                    status = HttpStatusCode.OK,
                    headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString())
                )
                "https://digital-credentials.dev/openid4vci/nonce_endpoint" -> respond(
                    content = """{"c_nonce":"test-c-nonce"}""",
                    status = HttpStatusCode.OK,
                    headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString())
                )
                "https://digital-credentials.dev/openid4vci/credential_endpoint" -> {
                    val bodyText = request.body.toByteArray().decodeToString()
                    credentialRequestBody = Json.parseToJsonElement(bodyText).jsonObject
                    respond(
                        content = """{"credentials":[{"credential":"bWRsLWNib3I"}]}""",
                        status = HttpStatusCode.OK,
                        headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString())
                    )
                }
                else -> error("Unexpected HTTP request to ${request.url}")
            }
        }
        val httpClient = HttpClient(mockEngine)

        val offerJson = """
            {
              "authorization_server_metadata": {
                "challenge_endpoint": "https://digital-credentials.dev/openid4vci/client_attestation_challenge",
                "grant_types_supported": [
                  "urn:ietf:params:oauth:grant-type:pre-authorized_code"
                ],
                "issuer": "https://digital-credentials.dev",
                "token_endpoint": "https://digital-credentials.dev/openid4vci/token"
              },
              "credential_configuration_ids": [
                "com.emvco.payment_card"
              ],
              "credential_issuer": "https://digital-credentials.dev",
              "credential_issuer_metadata": {
                "credential_configurations_supported": {
                  "com.emvco.payment_card": {
                    "credential_signing_alg_values_supported": ["ES256"],
                    "cryptographic_binding_methods_supported": ["cose_key"],
                    "display": [
                      {
                        "description": "Payment Card",
                        "locale": "en-US",
                        "name": "Payment Card"
                      }
                    ],
                    "doctype": "com.emvco.payment_card",
                    "format": "mso_mdoc",
                    "proof_types_supported": {
                      "jwt": {
                        "proof_signing_alg_values_supported": ["ES256"]
                      }
                    }
                  },
                  "org.iso.18013.5.1.mDL": {
                    "credential_signing_alg_values_supported": ["ES256"],
                    "cryptographic_binding_methods_supported": ["cose_key"],
                    "display": [
                      {
                        "description": "Mobile Driving License",
                        "locale": "en-US",
                        "name": "Driver's License"
                      }
                    ],
                    "doctype": "org.iso.18013.5.1.mDL",
                    "format": "mso_mdoc",
                    "proof_types_supported": {
                      "android_keystore_attestation": {
                        "proof_signing_alg_values_supported": ["ES256"]
                      },
                      "jwt": {
                        "proof_signing_alg_values_supported": ["ES256"]
                      }
                    }
                  }
                },
                "credential_endpoint": "https://digital-credentials.dev/openid4vci/credential_endpoint",
                "credential_issuer": "https://digital-credentials.dev",
                "nonce_endpoint": "https://digital-credentials.dev/openid4vci/nonce_endpoint"
              },
              "grants": {
                "urn:ietf:params:oauth:grant-type:pre-authorized_code": {
                  "pre-authorized_code": "preauth-code-xyz"
                }
              }
            }
        """.trimIndent()

        val offerUri = "openid-credential-offer://?credential_offer=${offerJson.encodeURLParameter()}"

        withContext(testEnvironment(httpClient)) {
            val client = OpenID4VCIProvisioningClient.createFromOffer(
                offerUri = offerUri,
                clientPreferences = clientPreferences
            )
            val challenges = client.getAuthorizationChallenges()
            assertTrue(challenges.isEmpty())

            val metadataAfterAuth = client.getMetadata()
            val credMetadata = metadataAfterAuth.credentials["org.iso.18013.5.1.mDL"]
            assertNotNull(credMetadata)
            assertEquals("Driver's License", credMetadata.display.text)
            assertEquals(CredentialFormat.Mdoc("org.iso.18013.5.1.mDL"), credMetadata.format)

            val cNonce = client.getKeyBindingChallenge()
            assertEquals("test-c-nonce", cNonce)

            val keyInfo = secureArea.createKey(alias = null, createKeySettings = CreateKeySettings())
            val credentials = client.obtainCredentials(
                KeyBindingInfo.Attestation(
                    listOf(CredentialKeyAttestation("cred-1", keyInfo.attestation))
                )
            )
            assertEquals(1, credentials.certifications.size)
            assertEquals("cred-1", credentials.certifications.first().credentialId)

            val reqJson = credentialRequestBody
            assertNotNull(reqJson)
            assertEquals("mdl1", reqJson["credential_identifier"]?.jsonPrimitive?.content)
            assertFalse(reqJson.containsKey("credential_configuration_id"))
        }
    }
}
