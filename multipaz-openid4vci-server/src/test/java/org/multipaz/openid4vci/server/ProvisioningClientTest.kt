package org.multipaz.openid4vci.server

import io.ktor.client.HttpClient
import io.ktor.client.request.forms.submitForm
import io.ktor.client.request.get
import io.ktor.client.request.headers
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.readBytes
import io.ktor.http.HttpStatusCode
import io.ktor.http.URLBuilder
import io.ktor.http.Url
import io.ktor.http.parameters
import io.ktor.http.protocolWithAuthority
import io.ktor.http.takeFrom
import io.ktor.server.testing.testApplication
import io.ktor.util.encodeBase64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert
import org.junit.Before
import org.junit.Test
import org.multipaz.asn1.OID
import org.multipaz.crypto.Algorithm
import org.multipaz.crypto.AsymmetricKey
import org.multipaz.crypto.Crypto
import org.multipaz.crypto.EcPrivateKey
import org.multipaz.crypto.X509Cert
import org.multipaz.provisioning.AuthorizationChallenge
import org.multipaz.provisioning.AuthorizationResponse
import org.multipaz.provisioning.KeyBindingInfo
import org.multipaz.provisioning.openid4vci.OpenID4VCI
import org.multipaz.provisioning.openid4vci.OpenID4VCIBackend
import org.multipaz.provisioning.openid4vci.OpenID4VCIBackendUtil
import org.multipaz.provisioning.openid4vci.OpenID4VCIClientPreferences
import org.multipaz.rpc.backend.BackendEnvironment
import org.multipaz.securearea.CreateKeySettings
import org.multipaz.securearea.KeyAttestation
import org.multipaz.securearea.SecureArea
import org.multipaz.securearea.SecureAreaProvider
import org.multipaz.securearea.software.SoftwareSecureArea
import org.multipaz.server.ServerConfiguration
import org.multipaz.storage.ephemeral.EphemeralStorage
import org.multipaz.util.toBase64Url
import kotlin.IllegalStateException
import kotlin.reflect.KClass
import kotlin.reflect.cast
import kotlin.text.decodeToString
import kotlin.time.Clock
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * Openid4Vci client-server integration test.
 */
class ProvisioningClientTest {
    lateinit var secureAreaProvider:SecureAreaProvider<SecureArea>

    @Before
    fun setup() {
        val storage = EphemeralStorage()
        secureAreaProvider = SecureAreaProvider<SecureArea>(Dispatchers.Default) {
            SoftwareSecureArea.create(storage)
        }
    }

    @Test
    fun authorizationCodeWithScope() {
        runWithAuthorizationCode(
            offer = OFFER_MDL,
            serverArgs = arrayOf(
                "-param", "base_url=http://localhost",
                "-param", "database_engine=ephemeral",
                "-param", "use_scopes=true"
            )
        )
    }

    @Test
    fun authorizationCodeWithScopeNoClientAttestationChallenge() {
        runWithAuthorizationCode(
            offer = OFFER_MDL,
            serverArgs = arrayOf(
                "-param", "base_url=http://localhost",
                "-param", "database_engine=ephemeral",
                "-param", "use_scopes=true",
                "-param", "use_client_attestation_challenge=false"
            )
        )
    }

    @Test
    fun authorizationCodeWithScopeWithClientAssertion() {
        runWithAuthorizationCode(
            offer = OFFER_NATURALIZATION,
            serverArgs = arrayOf(
                "-param", "base_url=http://localhost",
                "-param", "database_engine=ephemeral",
                "-param", "use_scopes=true",
                "-param", "use_client_assertion=true"
            )
        )
    }

    @Test
    fun authorizationCodeWithAuthorizationDetails() {
        runWithAuthorizationCode(
            offer = OFFER_MDL,
            serverArgs = arrayOf(
                "-param", "base_url=http://localhost",
                "-param", "database_engine=ephemeral",
                "-param", "use_scopes=false"
            )
        )
    }

    fun runWithAuthorizationCode(
        offer: String,
        serverArgs: Array<String>
    ) = testApplication {
        application {
            configureRouting(ServerConfiguration(serverArgs))
        }
        val httpClient = createClient {
            followRedirects = false
        }
        val env = TestBackendEnvironment(httpClient)
        withContext(env) {
            val provisioningClient = OpenID4VCI.createClientFromOffer(
                offerUri = offer,
                clientPreferences = testClientPreferences
            )
            val challenges = provisioningClient.getAuthorizationChallenges()
            val oauthChallenge = (challenges.first() as AuthorizationChallenge.OAuth)
            val authorizationUrl = Url(oauthChallenge.url)

            // ---------------------------------
            // Imitate user interaction, we know the structure of our server's web page, so
            // we can shortcut it.

            // Extract the parameter
            val request = client.get(authorizationUrl) {}
            Assert.assertEquals(HttpStatusCode.OK, request.status)
            val authText = request.readBytes().decodeToString()
            val pattern = "name=\"authorizationCode\" value=\""
            val index = authText.indexOf(pattern)
            Assert.assertNotEquals(-1, index)
            val first = index + pattern.length
            val last = authText.indexOf('"', first)
            val authorizationCode = authText.substring(first, last)

            // Submit the form
            var formRequest = httpClient.submitForm(
                url = authorizationUrl.protocolWithAuthority + authorizationUrl.encodedPath,
                formParameters = parameters {
                    append("authorizationCode", authorizationCode)
                    append("given_name", "Given")
                    append("family_name", "Family")
                    append("birth_date","1998-09-04")
                }
            )

            var location = ""
            while (formRequest.status == HttpStatusCode.Found) {
                location = formRequest.headers["Location"]!!
                if (location.startsWith(testClientPreferences.redirectUrl)) {
                    break
                }
                val newUrl = URLBuilder(authorizationUrl).takeFrom(location).build()
                formRequest = httpClient.get(newUrl)
            }

            // End imitating browser interaction
            //-------------------------------------------------------

            provisioningClient.authorize(
                AuthorizationResponse.OAuth(
                id = oauthChallenge.id,
                parameterizedRedirectUrl = location
            ))

            val secureArea = secureAreaProvider.get()

            provisioningClient.getKeyBindingChallenge()  // Our test backend does not verify key attestation

            val keyInfo = secureArea.createKey(null, CreateKeySettings())

            val credentials = provisioningClient.obtainCredentials(KeyBindingInfo.Attestation(listOf(keyInfo.attestation)))

            Assert.assertEquals(1, credentials.size)
        }
    }

    @Test
    fun runWithPreauthorizedCode() = testApplication {
        val serverArgs = arrayOf(
            "-param", "base_url=http://localhost",
            "-param", "database_engine=ephemeral",
        )
        application {
            configureRouting(ServerConfiguration(serverArgs))
        }
        val httpClient = createClient {
            followRedirects = false
        }
        val env = TestBackendEnvironment(httpClient)
        withContext(env) {

            val response = httpClient.post( "http://localhost/preauthorized_offer") {
                headers {
                    append("Content-Type", "application/json")
                }
                setBody(buildJsonObject {
                    put("access_token", "Given:Family:1970-01-01")
                    put("expires_in", 1000000)
                    put("refresh_token", "foobar")
                    put("scope", "mDL")
                    put("instance", "")
                    put("tx_kind", "n6")
                    put("tx_prompt", "Prompt")
                }.toString())
            }
            Assert.assertEquals(HttpStatusCode.OK, response.status)
            val json = Json.parseToJsonElement(response.readBytes().decodeToString()).jsonArray
            val offerObject = json.first().jsonObject
            val preauthorizedOffer = offerObject["offer"]!!.jsonPrimitive.content
            val txCode = offerObject["tx_code"]!!.jsonPrimitive.content

            val provisioningClient = OpenID4VCI.createClientFromOffer(
                offerUri = preauthorizedOffer,
                clientPreferences = testClientPreferences
            )
            val challenges = provisioningClient.getAuthorizationChallenges()
            Assert.assertEquals(1, challenges.size)
            val challenge = challenges.first() as AuthorizationChallenge.SecretText

            Assert.assertEquals("Prompt", challenge.request.description)
            Assert.assertTrue(challenge.request.isNumeric)

            provisioningClient.authorize(
                AuthorizationResponse.SecretText(
                    id = challenge.id,
                    secret = txCode
                )
            )

            Assert.assertEquals(0, provisioningClient.getAuthorizationChallenges().size)

            val secureArea = secureAreaProvider.get()

            provisioningClient.getKeyBindingChallenge()  // Our test backend does not verify key attestation

            val keyInfo = secureArea.createKey(null, CreateKeySettings())

            val credentials = provisioningClient.obtainCredentials(KeyBindingInfo.Attestation(listOf(keyInfo.attestation)))

            Assert.assertEquals(1, credentials.size)
        }
    }

    object TestBackend: OpenID4VCIBackend {
        override suspend fun getClientId(): String = localClientId

        override suspend fun createJwtClientAssertion(authorizationServerIdentifier: String): String =
            OpenID4VCIBackendUtil.createJwtClientAssertion(
                signingKey = clientAssertionKey,
                clientId = CLIENT_ID,
                authorizationServerIdentifier = authorizationServerIdentifier,
            )

        override suspend fun createJwtWalletAttestation(keyAttestation: KeyAttestation): String {
            // Implements this draft:
            // https://datatracker.ietf.org/doc/html/draft-ietf-oauth-attestation-based-client-auth-04

            val signatureAlgorithm = localAttestationPrivateKey.curve.defaultSigningAlgorithmFullySpecified
            val head = buildJsonObject {
                put("typ", "oauth-client-attestation+jwt")
                put("alg", signatureAlgorithm.joseAlgorithmIdentifier)
                put("x5c", buildJsonArray {
                    add(localAttestationCertificate.encoded.toByteArray().encodeBase64())
                })
            }.toString().encodeToByteArray().toBase64Url()

            val now = Clock.System.now()
            val notBefore = now - 1.seconds
            // Expiration here is only for the wallet assertion to be presented to the issuing server
            // in the given timeframe (which happens without user interaction). It does not imply that
            // the key becomes invalid at that point in time.
            val expiration = now + 5.minutes
            val payload = buildJsonObject {
                put("iss", localClientId)
                put("sub", testClientPreferences.clientId)
                put("exp", expiration.epochSeconds)
                put("cnf", buildJsonObject {
                    put("jwk", keyAttestation.publicKey.toJwk(
                        buildJsonObject {
                            put("kid", JsonPrimitive(testClientPreferences.clientId))
                        }
                    ))
                })
                put("nbf", notBefore.epochSeconds)
                put("iat", now.epochSeconds)
                put("wallet_name", "Multipaz Wallet")
                put("wallet_link", "https://multipaz.org")
            }.toString().encodeToByteArray().toBase64Url()

            val message = "$head.$payload"
            val sig = Crypto.sign(
                key = localAttestationPrivateKey,
                signatureAlgorithm = signatureAlgorithm,
                message = message.encodeToByteArray()
            )
            val signature = sig.toCoseEncoded().toBase64Url()

            return "$message.$signature"
        }

        override suspend fun createJwtKeyAttestation(
            keyAttestations: List<KeyAttestation>,
            challenge: String,
            userAuthentication: List<String>?,
            keyStorage: List<String>?
        ): String {
            // Generate key attestation
            val keyList = keyAttestations.map { it.publicKey }

            val alg = localAttestationPrivateKey.curve.defaultSigningAlgorithm.joseAlgorithmIdentifier
            val head = buildJsonObject {
                put("typ", "key-attestation+jwt")
                put("alg", alg)
                put("x5c", buildJsonArray {
                    add(localAttestationCertificate.encoded.toByteArray().encodeBase64())
                })
            }.toString().encodeToByteArray().toBase64Url()

            val now = Clock.System.now()
            val notBefore = now - 1.seconds
            val expiration = now + 5.minutes
            val payload = buildJsonObject {
                put("iss", localClientId)
                put("attested_keys", JsonArray(keyList.map { it.toJwk() }))
                put("nonce", challenge)
                put("nbf", notBefore.epochSeconds)
                put("exp", expiration.epochSeconds)
                put("iat", now.epochSeconds)
            }.toString().encodeToByteArray().toBase64Url()

            val message = "$head.$payload"
            val sig = Crypto.sign(
                key = localAttestationPrivateKey,
                signatureAlgorithm = localAttestationPrivateKey.curve.defaultSigningAlgorithm,
                message = message.encodeToByteArray()
            )
            val signature = sig.toCoseEncoded().toBase64Url()

            return "$message.$signature"
        }

        private val clientAssertionJwk = """
            {
                "kty": "EC",
                "alg": "ES256",
                "kid": "895b72b9-0808-4fcc-bb19-960d14a9e28f",
                "crv": "P-256",
                "x": "nSmAFnZx-SqgTEyqqOSmZyLESdbiSUIYlRlLLoWy5uc",
                "y": "FN1qcif7nyVX1MHN_YSbo7o7RgG2kPJUjg27YX6AKsQ",
                "d": "TdQhxDqbAUpzMJN5XXQqLea7-6LvQu2GFKzj5QmFDCw"
            }            
        """.trimIndent()

        private val attestationJwk = """
            {
                "kty": "EC",
                "alg": "ES256",
                "crv": "P-256",
                "x": "CoLFZ9sJfTqax-GarKIyw7_fX8-L446AoCTSHKJnZGs",
                "y": "ALEJB1_YQMO_0qSFQb3urFTxRfANN8-MSeWLHYU7MVI",
                "d": "nJXw7FqLff14yQLBEAwu70mu1gzlfOONh9UuealdsVM",
                "x5c": [
                    "MIIBtDCCATugAwIBAgIJAPosC/l8rotwMAoGCCqGSM49BAMCMDgxNjA0BgNVBAMTLXVybjp1dWlkOjYwZjhjMTE3LWI2OTItNGRlOC04ZjdmLTYzNmZmODUyYmFhNjAeFw0yNTA5MzAwMjUxNDRaFw0zNTA5MjgwMjUxNDRaMDgxNjA0BgNVBAMMLXVybjp1dWlkOjRjNDY0NzJiLTdlYjItNDRiNi04NTNhLWY3ZGZlMTEzYzU3NTBZMBMGByqGSM49AgEGCCqGSM49AwEHA0IABAqCxWfbCX06msfhmqyiMsO/31/Pi+OOgKAk0hyiZ2RrALEJB1/YQMO/0qSFQb3urFTxRfANN8+MSeWLHYU7MVKjLjAsMB8GA1UdIwQYMBaAFPqAK5EjiQbxFAeWt//DCaWtC57aMAkGA1UdEwQCMAAwCgYIKoZIzj0EAwIDZwAwZAIwfDEviit5J188zK5qKjkzFWkPy3ljshUg650p2kNuQq7CiQvbKyVDIlCGgOhMZyy+AjBm6ehDicFMPVBEHLUEiXO4cHw7Ed6dFpPm/6GknWcADhax62KN1tIzExo6T1l06G4=",
                    "MIIBxTCCAUugAwIBAgIJAOQTL9qcQopZMAoGCCqGSM49BAMDMDgxNjA0BgNVBAMTLXVybjp1dWlkOjYwZjhjMTE3LWI2OTItNGRlOC04ZjdmLTYzNmZmODUyYmFhNjAeFw0yNDA5MjMyMjUxMzFaFw0zNDA5MjMyMjUxMzFaMDgxNjA0BgNVBAMTLXVybjp1dWlkOjYwZjhjMTE3LWI2OTItNGRlOC04ZjdmLTYzNmZmODUyYmFhNjB2MBAGByqGSM49AgEGBSuBBAAiA2IABN4D7fpNMAv4EtxyschbITpZ6iNH90rGapa6YEO/uhKnC6VpPt5RUrJyhbvwAs0edCPthRfIZwfwl5GSEOS0mKGCXzWdRv4GGX/Y0m7EYypox+tzfnRTmoVX3v6OxQiapKMhMB8wHQYDVR0OBBYEFPqAK5EjiQbxFAeWt//DCaWtC57aMAoGCCqGSM49BAMDA2gAMGUCMEO01fJKCy+iOTpaVp9LfO7jiXcXksn2BA22reiR9ahDRdGNCrH1E3Q2umQAssSQbQIxAIz1FTHbZPcEbA5uE5lCZlRG/DQxlZhk/rZrkPyXFhqEgfMnQ45IJ6f8Utlg+4Wiiw=="
                ]
            }
           """.trimIndent()

        private val clientAssertionKey = AsymmetricKey.parseExplicit(clientAssertionJwk)
        private val attestationKey = AsymmetricKey.parseExplicit(attestationJwk)
        const val CLIENT_ID = "urn:uuid:418745b8-78a3-4810-88df-7898aff3ffb4"


        private val localAttestationCertificate = X509Cert.fromPem("""
                -----BEGIN CERTIFICATE-----
                MIIBxTCCAUugAwIBAgIJAOQTL9qcQopZMAoGCCqGSM49BAMDMDgxNjA0BgNVBAMT
                LXVybjp1dWlkOjYwZjhjMTE3LWI2OTItNGRlOC04ZjdmLTYzNmZmODUyYmFhNjAe
                Fw0yNDA5MjMyMjUxMzFaFw0zNDA5MjMyMjUxMzFaMDgxNjA0BgNVBAMTLXVybjp1
                dWlkOjYwZjhjMTE3LWI2OTItNGRlOC04ZjdmLTYzNmZmODUyYmFhNjB2MBAGByqG
                SM49AgEGBSuBBAAiA2IABN4D7fpNMAv4EtxyschbITpZ6iNH90rGapa6YEO/uhKn
                C6VpPt5RUrJyhbvwAs0edCPthRfIZwfwl5GSEOS0mKGCXzWdRv4GGX/Y0m7EYypo
                x+tzfnRTmoVX3v6OxQiapKMhMB8wHQYDVR0OBBYEFPqAK5EjiQbxFAeWt//DCaWt
                C57aMAoGCCqGSM49BAMDA2gAMGUCMEO01fJKCy+iOTpaVp9LfO7jiXcXksn2BA22
                reiR9ahDRdGNCrH1E3Q2umQAssSQbQIxAIz1FTHbZPcEbA5uE5lCZlRG/DQxlZhk
                /rZrkPyXFhqEgfMnQ45IJ6f8Utlg+4Wiiw==
                -----END CERTIFICATE-----
            """.trimIndent()
        )

        private val localAttestationPrivateKey = EcPrivateKey.fromPem("""
            -----BEGIN PRIVATE KEY-----
            ME4CAQAwEAYHKoZIzj0CAQYFK4EEACIENzA1AgEBBDBn7jeRC9u9de3kOkrt9lLT
            Pvd1hflNq1FCgs7D+qbbwz1BQa4XXU0SjsV+R1GjnAY=
            -----END PRIVATE KEY-----
            """.trimIndent(),
            localAttestationCertificate.ecPublicKey
        )

        private val localClientId =
            localAttestationCertificate.subject.components[OID.COMMON_NAME.oid]?.value
                ?: throw IllegalStateException("No common name (CN) in certificate's subject")
    }

    inner class TestBackendEnvironment(val httpClient: HttpClient): BackendEnvironment {
        override fun <T : Any> getInterface(clazz: KClass<T>): T {
            return clazz.cast(when (clazz) {
                HttpClient::class -> httpClient
                OpenID4VCIBackend::class -> TestBackend
                SecureAreaProvider::class -> secureAreaProvider
                else -> throw IllegalArgumentException("no such class available: ${clazz.simpleName}")
            })
        }
    }

    companion object {
        const val OFFER_MDL = "openid-credential-offer://?credential_offer=%7B%22credential_issuer%22%3A%22http%3A%2F%2Flocalhost%22%2C%22credential_configuration_ids%22%3A%5B%22mDL%22%5D%2C%22grants%22%3A%7B%22authorization_code%22%3A%7B%7D%7D%7D"
        const val OFFER_NATURALIZATION = "openid-credential-offer://?credential_offer=%7B%22credential_issuer%22%3A%22http%3A%2F%2Flocalhost%22%2C%22credential_configuration_ids%22%3A%5B%22utopia_naturalization%22%5D%2C%22grants%22%3A%7B%22authorization_code%22%3A%7B%7D%7D%7D"

        val testClientPreferences = OpenID4VCIClientPreferences(
            clientId = "urn:uuid:418745b8-78a3-4810-88df-7898aff3ffb4",
            redirectUrl = "https://redirect.example.com",
            locales = listOf("en-US"),
            signingAlgorithms = listOf(Algorithm.ESP256)
        )

    }
}