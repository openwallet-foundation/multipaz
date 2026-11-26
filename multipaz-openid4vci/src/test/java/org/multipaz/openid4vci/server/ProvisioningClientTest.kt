package org.multipaz.openid4vci.server

import io.ktor.client.HttpClient
import io.ktor.client.plugins.api.createClientPlugin
import io.ktor.client.request.forms.submitForm
import io.ktor.client.request.get
import io.ktor.client.request.headers
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.readRawBytes
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.http.URLBuilder
import io.ktor.http.Url
import io.ktor.http.content.TextContent
import io.ktor.http.parameters
import io.ktor.http.protocolWithAuthority
import io.ktor.http.takeFrom
import io.ktor.server.application.createApplicationPlugin
import io.ktor.server.application.install
import io.ktor.server.request.path
import io.ktor.server.testing.testApplication
import io.ktor.util.encodeBase64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.io.bytestring.decodeToString
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
import org.junit.jupiter.api.assertThrows
import org.multipaz.asn1.OID
import org.multipaz.credential.Credential
import org.multipaz.crypto.Algorithm
import org.multipaz.crypto.AsymmetricKey
import org.multipaz.crypto.Crypto
import org.multipaz.crypto.EcPrivateKey
import org.multipaz.crypto.MlDsaPublicKey
import org.multipaz.crypto.RsaPublicKey
import org.multipaz.crypto.X509Cert
import org.multipaz.document.DocumentStore
import org.multipaz.document.buildDocumentStore
import org.multipaz.documenttype.knowntypes.DrivingLicense
import org.multipaz.mdoc.credential.KeylessMdocCredential
import org.multipaz.mdoc.credential.MdocCredential
import org.multipaz.openid4vci.credential.CredentialFactoryDigitalPaymentCredential
import org.multipaz.openid4vci.credential.CredentialFactoryDigitalPaymentCredentialSdJwt
import org.multipaz.openid4vci.credential.CredentialFactoryMdl
import org.multipaz.openid4vci.credential.CredentialFactoryRegistry
import org.multipaz.openid4vci.credential.CredentialFactoryUtopiaMovieTicket
import org.multipaz.openid4vci.credential.CredentialFactoryUtopiaMovieTicketMdoc
import org.multipaz.openid4vci.credential.CredentialFactoryUtopiaNaturalization
import org.multipaz.openid4vci.request.invalidateIdentifierList
import org.multipaz.openid4vci.request.invalidateStatusList
import org.multipaz.openid4vci.util.CredentialId
import org.multipaz.openid4vci.util.CredentialState
import org.multipaz.provisioning.AuthorizationChallenge
import org.multipaz.provisioning.AuthorizationResponse
import org.multipaz.provisioning.CredentialFormat
import org.multipaz.provisioning.CredentialNotificationEvent
import org.multipaz.provisioning.KeyBindingInfo
import org.multipaz.provisioning.Provisioning
import org.multipaz.provisioning.CredentialKeyAttestation
import org.multipaz.provisioning.CredentialMetadata
import org.multipaz.provisioning.Display
import org.multipaz.provisioning.DocumentProvisioningHandler
import org.multipaz.provisioning.KeyBindingType
import org.multipaz.provisioning.ProvisioningMetadata
import org.multipaz.provisioning.openid4vci.OpenID4VCI
import org.multipaz.provisioning.openid4vci.OpenID4VCIBackend
import org.multipaz.provisioning.openid4vci.OpenID4VCIBackendUtil
import org.multipaz.provisioning.openid4vci.OpenID4VCIClientPreferences
import org.multipaz.revocation.CachingRevocationChecker
import org.multipaz.revocation.RevocationCheckState
import org.multipaz.revocation.RevocationStatus
import org.multipaz.revocation.check
import org.multipaz.revocation.getRevocationInfo
import org.multipaz.rpc.backend.BackendEnvironment
import org.multipaz.securearea.CreateKeySettings
import org.multipaz.securearea.KeyAttestation
import org.multipaz.securearea.KeyUnlockData
import org.multipaz.securearea.KeyUnlockDataProvider
import org.multipaz.securearea.SecureArea
import org.multipaz.securearea.SecureAreaProvider
import org.multipaz.securearea.SecureAreaRepository
import org.multipaz.securearea.software.SoftwareKeyUnlockData
import org.multipaz.securearea.software.SoftwareSecureArea
import org.multipaz.prompt.Reason
import org.multipaz.server.common.ServerConfiguration
import org.multipaz.server.common.ServerEnvironment
import org.multipaz.server.common.installServerEnvironment
import org.multipaz.server.enrollment.ServerIdentity
import org.multipaz.server.enrollment.getServerIdentity
import org.multipaz.storage.Storage
import org.multipaz.storage.ephemeral.EphemeralStorage
import org.multipaz.trustmanagement.ConfigurableTrustManager
import org.multipaz.trustmanagement.TrustEntryX509Cert
import org.multipaz.trustmanagement.TrustMetadata
import org.multipaz.util.toBase64Url
import org.multipaz.sdjwt.SdJwt
import org.multipaz.sdjwt.credential.KeyBoundSdJwtVcCredential
import org.multipaz.sdjwt.credential.KeylessSdJwtVcCredential
import org.multipaz.utopia.knowntypes.DigitalPaymentCredential
import org.multipaz.utopia.knowntypes.DigitalPaymentCredentialSdJwt
import org.multipaz.utopia.knowntypes.UtopiaMovieTicket
import org.multipaz.utopia.knowntypes.UtopiaNaturalization
import org.multipaz.webtoken.buildJwt
import kotlin.IllegalStateException
import kotlin.reflect.KClass
import kotlin.reflect.cast
import kotlin.test.assertIs
import kotlin.text.decodeToString
import kotlin.time.Clock
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * Openid4Vci client-server integration test.
 */
class ProvisioningClientTest {
    lateinit var storage: Storage
    lateinit var secureAreaProvider:SecureAreaProvider<SecureArea>
    lateinit var secureAreaRepository: SecureAreaRepository

    lateinit var credentalFactoryRegistry: CredentialFactoryRegistry

    lateinit var documentStore: DocumentStore

    lateinit var documentProvisioningHandler: DocumentProvisioningHandler

    private val credentialRequestBodies = mutableListOf<Any>()
    private val credentialResponseBodies = mutableListOf<String>()
    private var checkedCredentialRequests = 0
    private val notificationRequestBodies = mutableListOf<Any>()
    private val notificationResponseStatuses = mutableListOf<HttpStatusCode>()
    private val notificationResponseErrors = mutableListOf<String>()
    private val notificationResponseChallenges = mutableListOf<String?>()

    // Records the body of every request sent to the credential endpoint, whatever its type,
    // so that assertCredentialRequestsCompliant() can reject bodies that are not JSON text.
    private val credentialRequestRecorder = createClientPlugin("CredentialRequestRecorder") {
        onRequest { request, content ->
            if (request.url.pathSegments.lastOrNull() == "credential") {
                credentialRequestBodies.add(content)
            } else if (request.url.pathSegments.lastOrNull() == "notification") {
                notificationRequestBodies.add(content)
            }
        }
        onResponse { response ->
            when (response.call.request.url.segments.lastOrNull()) {
                "credential" -> credentialResponseBodies.add(response.readRawBytes().decodeToString())
                "notification" -> {
                    notificationResponseStatuses.add(response.status)
                    notificationResponseChallenges.add(response.headers["WWW-Authenticate"])
                    if (response.status == HttpStatusCode.NoContent) {
                        Assert.assertNull(response.headers["Content-Type"])
                        Assert.assertTrue(response.readRawBytes().isEmpty())
                    }
                    if (response.status == HttpStatusCode.BadRequest) {
                        Assert.assertEquals(
                            ContentType.Application.Json.toString(),
                            response.headers["Content-Type"]?.substringBefore(';')
                        )
                        val json = Json.parseToJsonElement(response.readRawBytes().decodeToString()).jsonObject
                        notificationResponseErrors.add(json["error"]!!.jsonPrimitive.content)
                    }
                }
            }
        }
    }

    private val tokenResponseStatuses = mutableListOf<HttpStatusCode>()

    // Records the status of every response from the token endpoint, so that a test can tell
    // a token request that succeeded at once from one the server refused first.
    private val tokenResponseRecorder = createClientPlugin("TokenResponseRecorder") {
        onResponse { response ->
            if (response.call.request.url.segments.lastOrNull() == "token") {
                tokenResponseStatuses.add(response.status)
            }
        }
    }

    @Before
    fun setup() = runTest {
        storage = EphemeralStorage()
        val secureArea = SoftwareSecureArea.create(storage)
        secureAreaRepository = SecureAreaRepository.Builder().add(secureArea).build()
        secureAreaProvider = SecureAreaProvider<SecureArea>(Dispatchers.Default) {
            secureArea
        }
        documentStore = buildDocumentStore(storage, secureAreaRepository) {}
        notificationRequestBodies.clear()
        notificationResponseStatuses.clear()
        notificationResponseErrors.clear()
        notificationResponseChallenges.clear()
        credentialRequestBodies.clear()
        credentialResponseBodies.clear()
        checkedCredentialRequests = 0
        documentProvisioningHandler = DocumentProvisioningHandler(secureArea, documentStore)
        credentalFactoryRegistry = CredentialFactoryRegistry(
            listOf(
                CredentialFactoryMdl(),
                CredentialFactoryUtopiaNaturalization(),
                CredentialFactoryDigitalPaymentCredential(),
                CredentialFactoryDigitalPaymentCredentialSdJwt(),
                CredentialFactoryUtopiaMovieTicket(),
                CredentialFactoryUtopiaMovieTicketMdoc()
            )
        )
        credentalFactoryRegistry.initialize()
    }

    @Test
    fun authorizationCodeWithScope() {
        runWithAuthorizationCode(
            offer = OFFER_MDL,
            credentialFormat = CredentialFormat.Mdoc(DrivingLicense.MDL_DOCTYPE),
            serverArgs = arrayOf(
                "-param", "base_url=http://localhost",
                "-param", "database_engine=ephemeral",
                "-param", "use_scopes=true"
            )
        )
    }

    @Test
    fun authorizationCodeWithScopeWithIdentifierListRevocation() {
        runWithAuthorizationCode(
            offer = OFFER_MDL,
            credentialFormat = CredentialFormat.Mdoc(DrivingLicense.MDL_DOCTYPE),
            serverArgs = arrayOf(
                "-param", "base_url=http://localhost",
                "-param", "database_engine=ephemeral",
                "-param", "use_scopes=true"
            ),
            extraTests = ::runRevocationTests
        )
    }

    @Test
    fun authorizationCodeWithScopeNoClientAttestationChallenge() {
        runWithAuthorizationCode(
            offer = OFFER_MDL,
            credentialFormat = CredentialFormat.Mdoc(DrivingLicense.MDL_DOCTYPE),
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
            credentialFormat = CredentialFormat.SdJwt(UtopiaNaturalization.VCT),
            serverArgs = arrayOf(
                "-param", "base_url=http://localhost",
                "-param", "database_engine=ephemeral",
                "-param", "use_scopes=true",
                "-param", "support_client_assertion=true",
                "-param", "support_client_attestation=false"
            )
        )
    }

    @Test
    fun authorizationCodeWithAuthorizationDetails() {
        runWithAuthorizationCode(
            offer = OFFER_MDL,
            credentialFormat = CredentialFormat.Mdoc(DrivingLicense.MDL_DOCTYPE),
            serverArgs = arrayOf(
                "-param", "base_url=http://localhost",
                "-param", "database_engine=ephemeral",
                "-param", "use_scopes=false"
            )
        )
    }

    @Test
    fun authorizationCodeWithPaymentCredential() {
        runWithAuthorizationCode(
            offer = OFFER_PAYMENT,
            credentialFormat = CredentialFormat.Mdoc(DigitalPaymentCredential.CARD_DOCTYPE),
            serverArgs = arrayOf(
                "-param", "base_url=http://localhost",
                "-param", "database_engine=ephemeral",
                "-param", "use_scopes=true"
            )
        )
    }

    @Test
    fun authorizationCodeWithPaymentCredentialSdJwt() {
        runWithAuthorizationCode(
            offer = OFFER_PAYMENT_SD_JWT,
            credentialFormat = CredentialFormat.SdJwt(DigitalPaymentCredentialSdJwt.CARD_VCT),
            serverArgs = arrayOf(
                "-param", "base_url=http://localhost",
                "-param", "database_engine=ephemeral",
                "-param", "use_scopes=true"
            )
        )
    }

    @Test
    fun authorizationCodeWithPaymentCredentialSdJwtWithStatusListRevocation() {
        runWithAuthorizationCode(
            offer = OFFER_PAYMENT_SD_JWT,
            credentialFormat = CredentialFormat.SdJwt(DigitalPaymentCredentialSdJwt.CARD_VCT),
            serverArgs = arrayOf(
                "-param", "base_url=http://localhost",
                "-param", "database_engine=ephemeral",
                "-param", "use_scopes=true"
            ),
            extraTests = ::runRevocationTests
        )
    }

    @Test
    fun authorizationCodeWithKeylessMovieTicketMdocAndRevocation() {
        runWithAuthorizationCode(
            offer = OFFER_MOVIE_TICKET_MDOC,
            credentialFormat = CredentialFormat.Mdoc(UtopiaMovieTicket.MOVIE_TICKET_DOCTYPE),
            serverArgs = arrayOf(
                "-param", "base_url=http://localhost",
                "-param", "database_engine=ephemeral",
                "-param", "use_scopes=true"
            ),
            keyless = true,
            extraTests = ::runRevocationTests
        )
    }

    @Test
    fun authorizationCodeWithKeylessMovieTicketSdJwtAndRevocation() {
        runWithAuthorizationCode(
            offer = OFFER_MOVIE_TICKET_SD_JWT,
            credentialFormat = CredentialFormat.SdJwt(UtopiaMovieTicket.MOVIE_TICKET_VCT),
            serverArgs = arrayOf(
                "-param", "base_url=http://localhost",
                "-param", "database_engine=ephemeral",
                "-param", "use_scopes=true"
            ),
            keyless = true,
            extraTests = ::runRevocationTests
        )
    }

    @Test
    fun authorizationCodeWithAuthorizationDetailsKeylessMovieTicketMdoc() {
        runWithAuthorizationCode(
            offer = OFFER_MOVIE_TICKET_MDOC,
            credentialFormat = CredentialFormat.Mdoc(UtopiaMovieTicket.MOVIE_TICKET_DOCTYPE),
            serverArgs = arrayOf(
                "-param", "base_url=http://localhost",
                "-param", "database_engine=ephemeral",
                "-param", "use_scopes=false"
            ),
            keyless = true
        )
    }

    @Test
    fun credentialEndpointRejectsMismatchedScopeConfigurationId() {
        Assert.assertThrows(IllegalStateException::class.java) {
            runWithAuthorizationCode(
                offer = OFFER_PAYMENT,
                credentialFormat = CredentialFormat.Mdoc(DigitalPaymentCredential.CARD_DOCTYPE),
                serverArgs = arrayOf(
                    "-param", "base_url=http://localhost",
                    "-param", "database_engine=ephemeral",
                    "-param", "use_scopes=true"
                ),
                overrideCredentialConfigurationId = "mDL"
            )
        }
    }

    @Test
    fun credentialEndpointRejectsMismatchedAuthorizedConfigurationIdWithinSameScope() {
        Assert.assertThrows(IllegalStateException::class.java) {
            runWithAuthorizationCode(
                offer = OFFER_PAYMENT,
                credentialFormat = CredentialFormat.Mdoc(DigitalPaymentCredential.CARD_DOCTYPE),
                serverArgs = arrayOf(
                    "-param", "base_url=http://localhost",
                    "-param", "database_engine=ephemeral",
                    "-param", "use_scopes=false"
                ),
                overrideCredentialConfigurationId = "payment_sca_sd_jwt"
            )
        }
    }

    @Test
    fun authorizationCodeWithMlDsaKeys() {
        runWithAuthorizationCode(
            offer = OFFER_MDL,
            credentialFormat = CredentialFormat.Mdoc(DrivingLicense.MDL_DOCTYPE),
            serverArgs = arrayOf(
                "-param", "base_url=http://localhost",
                "-param", "database_engine=ephemeral",
                "-param", "use_scopes=true"
            ),
            signingAlgorithm = Algorithm.ML_DSA_44,
            extraTests = { _, credential ->
                val mdoc = assertIs<MdocCredential>(credential)
                val deviceKey = assertIs<MlDsaPublicKey>(mdoc.mso.deviceKey)
                Assert.assertEquals(Algorithm.ML_DSA_44, deviceKey.algorithm)
            }
        )
        runWithAuthorizationCode(
            offer = OFFER_NATURALIZATION,
            credentialFormat = CredentialFormat.SdJwt(UtopiaNaturalization.VCT),
            serverArgs = arrayOf(
                "-param", "base_url=http://localhost",
                "-param", "database_engine=ephemeral",
                "-param", "use_scopes=true"
            ),
            signingAlgorithm = Algorithm.ML_DSA_44,
            useJwtProof = true,
            extraTests = { _, credential ->
                val sdJwtCred = assertIs<KeyBoundSdJwtVcCredential>(credential)
                val sdJwt = SdJwt.fromCompactSerialization(sdJwtCred.issuerProvidedData.decodeToString())
                val kbKey = assertIs<MlDsaPublicKey>(sdJwt.kbKey)
                Assert.assertEquals(Algorithm.ML_DSA_44, kbKey.algorithm)
            }
        )
    }

    @Test
    fun authorizationCodeWithRsaKeys() {
        runWithAuthorizationCode(
            offer = OFFER_MDL,
            credentialFormat = CredentialFormat.Mdoc(DrivingLicense.MDL_DOCTYPE),
            serverArgs = arrayOf(
                "-param", "base_url=http://localhost",
                "-param", "database_engine=ephemeral",
                "-param", "use_scopes=true"
            ),
            signingAlgorithm = Algorithm.RS256_2048,
            extraTests = { _, credential ->
                val mdoc = assertIs<MdocCredential>(credential)
                assertIs<RsaPublicKey>(mdoc.mso.deviceKey)
            }
        )
        runWithAuthorizationCode(
            offer = OFFER_NATURALIZATION,
            credentialFormat = CredentialFormat.SdJwt(UtopiaNaturalization.VCT),
            serverArgs = arrayOf(
                "-param", "base_url=http://localhost",
                "-param", "database_engine=ephemeral",
                "-param", "use_scopes=true"
            ),
            signingAlgorithm = Algorithm.RS256_2048,
            useJwtProof = true,
            extraTests = { _, credential ->
                val sdJwtCred = assertIs<KeyBoundSdJwtVcCredential>(credential)
                val sdJwt = SdJwt.fromCompactSerialization(sdJwtCred.issuerProvidedData.decodeToString())
                assertIs<RsaPublicKey>(sdJwt.kbKey)
            }
        )
    }

    @Test
    fun notificationEndpointRejectsUnknownNotificationId() {
        runWithAuthorizationCode(
            offer = OFFER_MDL,
            credentialFormat = CredentialFormat.Mdoc(DrivingLicense.MDL_DOCTYPE),
            serverArgs = arrayOf(
                "-param", "base_url=https://localhost",
                "-param", "database_engine=ephemeral",
                "-param", "use_scopes=true",
                "-param", "notification_endpoint_enabled=true"
            ),
            notificationIdOverride = "unknown-notification-id",
            expectedNotificationStatus = HttpStatusCode.BadRequest,
            expectedNotificationError = "invalid_notification_id"
        )
    }

    @Test
    fun notificationEndpointRejectsUnsupportedEvent() {
        runWithAuthorizationCode(
            offer = OFFER_MDL,
            credentialFormat = CredentialFormat.Mdoc(DrivingLicense.MDL_DOCTYPE),
            serverArgs = arrayOf(
                "-param", "base_url=https://localhost",
                "-param", "database_engine=ephemeral",
                "-param", "use_scopes=true",
                "-param", "notification_endpoint_enabled=true"
            ),
            notificationEventOverride = "credential_issued",
            expectedNotificationStatus = HttpStatusCode.BadRequest,
            expectedNotificationError = "invalid_notification_request"
        )
    }

    @Test
    fun notificationEndpointValidatesEventBeforeId() {
        runWithAuthorizationCode(
            offer = OFFER_MDL,
            credentialFormat = CredentialFormat.Mdoc(DrivingLicense.MDL_DOCTYPE),
            serverArgs = arrayOf(
                "-param", "base_url=https://localhost",
                "-param", "database_engine=ephemeral",
                "-param", "use_scopes=true",
                "-param", "notification_endpoint_enabled=true"
            ),
            notificationIdOverride = "unknown-notification-id",
            notificationEventOverride = "invalid",
            expectedNotificationStatus = HttpStatusCode.BadRequest,
            expectedNotificationError = "invalid_notification_request"
        )
    }

    @Test
    fun notificationEndpointRejectsMissingDpopProof() {
        runWithAuthorizationCode(
            offer = OFFER_MDL,
            credentialFormat = CredentialFormat.Mdoc(DrivingLicense.MDL_DOCTYPE),
            serverArgs = arrayOf(
                "-param", "base_url=https://localhost",
                "-param", "database_engine=ephemeral",
                "-param", "use_scopes=true",
                "-param", "notification_endpoint_enabled=true"
            ),
            removeNotificationDpop = true,
            expectedNotificationStatus = HttpStatusCode.Unauthorized,
            expectedNotificationChallenge = "DPoP error=\"invalid_dpop_proof\""
        )
    }

    @Test
    fun notificationEndpointAcceptsJsonContentTypeWithCharset() {
        runWithAuthorizationCode(
            offer = OFFER_MDL,
            credentialFormat = CredentialFormat.Mdoc(DrivingLicense.MDL_DOCTYPE),
            serverArgs = arrayOf(
                "-param", "base_url=https://localhost",
                "-param", "database_engine=ephemeral",
                "-param", "use_scopes=true",
                "-param", "notification_endpoint_enabled=true"
            ),
            notificationContentType = "application/json ; charset=utf-8"
        )
    }

    @Test
    fun notificationEndpointAcceptsFailureEvent() {
        runWithAuthorizationCode(
            offer = OFFER_MDL,
            credentialFormat = CredentialFormat.Mdoc(DrivingLicense.MDL_DOCTYPE),
            serverArgs = arrayOf(
                "-param", "base_url=https://localhost",
                "-param", "database_engine=ephemeral",
                "-param", "use_scopes=true",
                "-param", "notification_endpoint_enabled=true"
            ),
            notificationEvent = CredentialNotificationEvent.FAILURE
        )
    }

    @Test
    fun notificationEndpointAcceptsUserDeclinedIssuanceEvent() {
        runWithAuthorizationCode(
            offer = OFFER_MDL,
            credentialFormat = CredentialFormat.Mdoc(DrivingLicense.MDL_DOCTYPE),
            serverArgs = arrayOf(
                "-param", "base_url=https://localhost",
                "-param", "database_engine=ephemeral",
                "-param", "use_scopes=true",
                "-param", "notification_endpoint_enabled=true"
            ),
            notificationEvent = CredentialNotificationEvent.DELETED
        )
    }

    @Test
    fun notificationEndpointRejectsMissingEvent() {
        runWithAuthorizationCode(
            offer = OFFER_MDL,
            credentialFormat = CredentialFormat.Mdoc(DrivingLicense.MDL_DOCTYPE),
            serverArgs = arrayOf(
                "-param", "base_url=https://localhost",
                "-param", "database_engine=ephemeral",
                "-param", "use_scopes=true",
                "-param", "notification_endpoint_enabled=true"
            ),
            notificationBodyOverride = """{"notification_id":"test-id"}""",
            expectedNotificationStatus = HttpStatusCode.BadRequest,
            expectedNotificationError = "invalid_notification_request"
        )
    }

    @Test
    fun notificationEndpointRejectsMalformedJson() {
        runWithAuthorizationCode(
            offer = OFFER_MDL,
            credentialFormat = CredentialFormat.Mdoc(DrivingLicense.MDL_DOCTYPE),
            serverArgs = arrayOf(
                "-param", "base_url=https://localhost",
                "-param", "database_engine=ephemeral",
                "-param", "use_scopes=true",
                "-param", "notification_endpoint_enabled=true"
            ),
            notificationBodyOverride = "{",
            expectedNotificationStatus = HttpStatusCode.BadRequest,
            expectedNotificationError = "invalid_notification_request"
        )
    }

    @Test
    fun notificationEndpointRejectsNonAsciiEventDescription() {
        runWithAuthorizationCode(
            offer = OFFER_MDL,
            credentialFormat = CredentialFormat.Mdoc(DrivingLicense.MDL_DOCTYPE),
            serverArgs = arrayOf(
                "-param", "base_url=https://localhost",
                "-param", "database_engine=ephemeral",
                "-param", "use_scopes=true",
                "-param", "notification_endpoint_enabled=true"
            ),
            notificationBodyOverride =
                """{"notification_id":"test-id","event":"credential_accepted","event_description":"bad\ntext"}""",
            expectedNotificationStatus = HttpStatusCode.BadRequest,
            expectedNotificationError = "invalid_notification_request"
        )
    }

    @Test
    fun notificationEndpointAdvertisedWhenEnabled() = testApplication {
        val configuration = ServerConfiguration(arrayOf(
            "-param", "base_url=https://localhost",
            "-param", "database_engine=ephemeral",
            "-param", "notification_endpoint_enabled=true"
        ))
        val serverEnvironment = ServerEnvironment.create(configuration) {
            add(CredentialFactoryRegistry::class, credentalFactoryRegistry)
        }
        application {
            installServerEnvironment(serverEnvironment)
            configureRouting(serverEnvironment)
        }
        val httpClient = createClient {}
        val response = httpClient.get("http://localhost/.well-known/openid-credential-issuer")
        Assert.assertEquals(HttpStatusCode.OK, response.status)
        val json = Json.parseToJsonElement(response.readRawBytes().decodeToString()).jsonObject
        Assert.assertEquals("https://localhost/notification", json["notification_endpoint"]?.jsonPrimitive?.content)
    }

    @Test
    fun notificationEndpointOmittedByDefault() = testApplication {
        val configuration = ServerConfiguration(arrayOf(
            "-param", "base_url=http://localhost",
            "-param", "database_engine=ephemeral"
        ))
        val serverEnvironment = ServerEnvironment.create(configuration) {
            add(CredentialFactoryRegistry::class, credentalFactoryRegistry)
        }
        application {
            installServerEnvironment(serverEnvironment)
            configureRouting(serverEnvironment)
        }
        val response = createClient {}.get("http://localhost/.well-known/openid-credential-issuer")
        Assert.assertEquals(HttpStatusCode.OK, response.status)
        val json = Json.parseToJsonElement(response.readRawBytes().decodeToString()).jsonObject
        Assert.assertFalse(json.containsKey("notification_endpoint"))
        val disabledResponse = createClient {}.post("http://localhost/notification")
        Assert.assertEquals(HttpStatusCode.NotFound, disabledResponse.status)
        Assert.assertNull(disabledResponse.headers["Content-Type"])
        Assert.assertTrue(disabledResponse.readRawBytes().isEmpty())
    }

    @Test
    fun notificationEndpointRequiresAccessToken() = testApplication {
        val configuration = ServerConfiguration(arrayOf(
            "-param", "base_url=https://localhost",
            "-param", "database_engine=ephemeral",
            "-param", "notification_endpoint_enabled=true"
        ))
        val serverEnvironment = ServerEnvironment.create(configuration) {
            add(CredentialFactoryRegistry::class, credentalFactoryRegistry)
        }
        application {
            installServerEnvironment(serverEnvironment)
            configureRouting(serverEnvironment)
        }
        val response = createClient {}.post("http://localhost/notification") {
            contentType(ContentType.Application.Json)
            setBody("""{"notification_id":"id","event":"credential_accepted"}""")
        }
        Assert.assertEquals(HttpStatusCode.Unauthorized, response.status)
        Assert.assertEquals("DPoP", response.headers["WWW-Authenticate"])

        val invalidTokenResponse = createClient {}.post("http://localhost/notification") {
            headers.append("Authorization", "DPoP invalid")
            contentType(ContentType.Application.Json)
            setBody("""{"notification_id":"id","event":"credential_accepted"}""")
        }
        Assert.assertEquals(HttpStatusCode.Unauthorized, invalidTokenResponse.status)
        Assert.assertTrue(invalidTokenResponse.headers["WWW-Authenticate"]?.contains("invalid_token") == true)
    }

    fun runWithAuthorizationCode(
        offer: String,
        credentialFormat: CredentialFormat,
        serverArgs: Array<String>,
        overrideCredentialConfigurationId: String? = null,
        keyless: Boolean = false,
        signingAlgorithm: Algorithm = Algorithm.ESP256,
        useJwtProof: Boolean = false,
        notificationIdOverride: String? = null,
        notificationEventOverride: String? = null,
        expectedNotificationStatus: HttpStatusCode = HttpStatusCode.NoContent,
        notificationEvent: CredentialNotificationEvent = CredentialNotificationEvent.ACCEPTED,
        notificationBodyOverride: String? = null,
        expectedNotificationError: String? = null,
        removeNotificationDpop: Boolean = false,
        expectedNotificationChallenge: String? = null,
        notificationContentType: String? = null,
        extraTests: suspend (httpClient: HttpClient, credential: Credential) -> Unit = { _, _ -> }
    ) = testApplication {
        val observedNotificationContentTypes = mutableListOf<String?>()
        val configuration = ServerConfiguration(serverArgs)
        val serverEnvironment = ServerEnvironment.create(configuration) {
            add(CredentialFactoryRegistry::class, credentalFactoryRegistry)
        }
        application {
            install(createApplicationPlugin("ObserveNotificationContentType") {
                onCall { call ->
                    if (call.request.path().endsWith("/notification")) {
                        observedNotificationContentTypes.add(call.request.headers["Content-Type"])
                    }
                }
            })
            installServerEnvironment(serverEnvironment)
            configureRouting(serverEnvironment)
        }
        val httpClient = createClient {
            followRedirects = false
            install(credentialRequestRecorder)
            if (removeNotificationDpop) {
                install(createClientPlugin("RemoveNotificationDpop") {
                    onRequest { request, _ ->
                        if (request.url.pathSegments.lastOrNull() == "notification") {
                            request.headers.remove("DPoP")
                        }
                    }
                })
            }
            if (notificationContentType != null) {
                install(createClientPlugin("NotificationContentType") {
                    onRequest { request, _ ->
                        if (request.url.pathSegments.lastOrNull() == "notification") {
                            request.headers.remove("Content-Type")
                            request.headers.append("Content-Type", notificationContentType)
                        }
                    }
                })
            }
            if (overrideCredentialConfigurationId != null) {
                install(createClientPlugin("CredentialConfigurationIdOverrider") {
                    transformRequestBody { request, content, _ ->
                        if (request.url.pathSegments.lastOrNull() == "credential" && content is String) {
                            val mutated = buildJsonObject {
                                for ((k, v) in Json.parseToJsonElement(content).jsonObject) {
                                    if (k == "credential_configuration_id") {
                                        put(k, overrideCredentialConfigurationId)
                                    } else {
                                        put(k, v)
                                    }
                                }
                            }.toString()
                            TextContent(mutated, ContentType.Application.Json)
                        } else {
                            null
                        }
                    }
                })
            }
            if (notificationIdOverride != null || notificationEventOverride != null || notificationBodyOverride != null) {
                install(createClientPlugin("NotificationRequestOverrider") {
                    transformRequestBody { request, content, _ ->
                        if (request.url.build().encodedPath.endsWith("/notification") && content is String) {
                            if (notificationBodyOverride != null) {
                                return@transformRequestBody TextContent(notificationBodyOverride, ContentType.Application.Json)
                            }
                            val notification = Json.parseToJsonElement(content).jsonObject
                            val mutated = buildJsonObject {
                                for ((key, value) in notification) {
                                    when (key) {
                                        "notification_id" -> put(
                                            key,
                                            notificationIdOverride?.let(::JsonPrimitive) ?: value
                                        )
                                        "event" -> put(
                                            key,
                                            notificationEventOverride?.let(::JsonPrimitive) ?: value
                                        )
                                        else -> put(key, value)
                                    }
                                }
                            }
                            TextContent(mutated.toString(), ContentType.Application.Json)
                        } else {
                            null
                        }
                    }
                })
            }
        }
        val clientPrefs = testClientPreferences.copy(signingAlgorithms = listOf(signingAlgorithm))
        val env = TestBackendEnvironment(httpClient, clientPrefs)
        withContext(env) {
            val offerUri = if (serverArgs.contains("notification_endpoint_enabled=true")) {
                offer.replace("http%3A%2F%2Flocalhost", "https%3A%2F%2Flocalhost")
            } else {
                offer
            }
            val provisioningClient = OpenID4VCI.createClientFromOffer(
                offerUri = offerUri,
                clientPreferences = clientPrefs
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
            val authText = request.readRawBytes().decodeToString()
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

            val document = documentStore.createDocument()
            val display = Display("test")
            val credential: Credential
            val credentialData = if (keyless) {
                credential = documentProvisioningHandler.getPendingKeylessCredentials(
                    document = document,
                    credentialMetadata = CredentialMetadata(
                        display = display,
                        format = credentialFormat,
                        keyBindingType = KeyBindingType.Keyless,
                        maxBatchSize = 1
                    ),
                    issuerMetadata = ProvisioningMetadata("http://testul", display, mapOf())
                ).first()
                provisioningClient.obtainCredentials(KeyBindingInfo.Keyless)
            } else if (useJwtProof) {
                val challenge = provisioningClient.getKeyBindingChallenge()
                val keyBoundCredential = documentProvisioningHandler.getPendingKeyBoundCredentials(
                    document = document,
                    credentialMetadata = CredentialMetadata(
                        display = display,
                        format = credentialFormat,
                        keyBindingType = KeyBindingType.OpenidProofOfPossession(
                            algorithm = signingAlgorithm,
                            clientId = clientPrefs.clientId,
                            aud = "http://localhost"
                        ),
                        maxBatchSize = 1
                    ),
                    issuerMetadata = ProvisioningMetadata("http://testul", display, mapOf()),
                    createKeySettings = CreateKeySettings(algorithm = signingAlgorithm)
                ).last()
                credential = keyBoundCredential
                val signingKey = AsymmetricKey.anonymous(
                    secureArea = keyBoundCredential.secureArea,
                    alias = keyBoundCredential.alias
                )
                val unlockProvider = object : KeyUnlockDataProvider {
                    override suspend fun getKeyUnlockData(
                        secureArea: SecureArea,
                        alias: String,
                        algorithm: Algorithm,
                        unlockReason: Reason
                    ): KeyUnlockData = SoftwareKeyUnlockData(
                        secureArea = secureArea as SoftwareSecureArea,
                        alias = alias,
                        userAuthenticated = true
                    )
                }
                val proofJwt = withContext(unlockProvider) {
                    buildJwt(
                        type = "openid4vci-proof+jwt",
                        key = signingKey,
                        header = {
                            put("jwk", signingKey.publicKey.toJwk(buildJsonObject {
                                put("kid", keyBoundCredential.identifier)
                            }))
                        }
                    ) {
                        put("iss", clientPrefs.clientId)
                        put("aud", "http://localhost")
                        put("nonce", challenge)
                    }
                }
                provisioningClient.obtainCredentials(KeyBindingInfo.OpenidProofOfPossession(listOf(proofJwt)))
            } else {
                // Our test backend does not verify key attestation, so the result can be ignored
                provisioningClient.getKeyBindingChallenge()
                val keyBoundCredential = documentProvisioningHandler.getPendingKeyBoundCredentials(
                    document = document,
                    credentialMetadata = CredentialMetadata(
                        display = display,
                        format = credentialFormat,
                        keyBindingType = KeyBindingType.Attestation(
                            signingAlgorithm.curve?.defaultSigningAlgorithm ?: signingAlgorithm
                        ),
                        maxBatchSize = 1
                    ),
                    issuerMetadata = ProvisioningMetadata("http://testul", display, mapOf()),
                    createKeySettings = CreateKeySettings(algorithm = signingAlgorithm)
                ).last()
                credential = keyBoundCredential
                provisioningClient.obtainCredentials(KeyBindingInfo.Attestation(
                    listOf(CredentialKeyAttestation(
                        credentialId = keyBoundCredential.identifier,
                        keyAttestation = keyBoundCredential.getAttestation()
                    ))))
            }

            Assert.assertEquals(1, credentialData.certifications.size)
            assertCredentialRequestsCompliant(expectProofs = !keyless)
            Assert.assertTrue("Notification sent before storage", notificationRequestBodies.isEmpty())
            val credentialResponse =
                Json.parseToJsonElement(credentialResponseBodies.last()).jsonObject
            Assert.assertEquals(
                serverArgs.contains("notification_endpoint_enabled=true"),
                credentialResponse.containsKey("notification_id")
            )

            if (notificationEvent != CredentialNotificationEvent.DELETED) {
                credential.certify(credentialData.certifications.first().issuerData)
            }
            provisioningClient.notifyCredentialResult(notificationEvent)
            if (serverArgs.contains("notification_endpoint_enabled=true")) {
                Assert.assertEquals(expectedNotificationStatus, notificationResponseStatuses.last())
                if (expectedNotificationError != null) {
                    Assert.assertEquals(expectedNotificationError, notificationResponseErrors.last())
                }
                if (expectedNotificationChallenge != null) {
                    Assert.assertEquals(expectedNotificationChallenge, notificationResponseChallenges.last())
                }
                if (notificationContentType != null) {
                    Assert.assertEquals(
                        ContentType.parse(notificationContentType).toString(),
                        observedNotificationContentTypes.last()
                    )
                }
                val notificationBody = notificationRequestBodies.last() as String
                val notification = Json.parseToJsonElement(notificationBody).jsonObject
                val expectedEvent = when (notificationEvent) {
                    CredentialNotificationEvent.ACCEPTED -> "credential_accepted"
                    CredentialNotificationEvent.FAILURE -> "credential_failure"
                    CredentialNotificationEvent.DELETED -> "credential_deleted"
                }
                Assert.assertEquals(expectedEvent, notification["event"]?.jsonPrimitive?.content)
                Assert.assertTrue(notification["notification_id"]?.jsonPrimitive?.content?.isNotEmpty() == true)
                val requestCount = notificationRequestBodies.size
                provisioningClient.notifyCredentialResult(notificationEvent)
                Assert.assertEquals("Notification sent twice", requestCount, notificationRequestBodies.size)
            } else {
                Assert.assertTrue("Notification sent when support is disabled", notificationRequestBodies.isEmpty())
            }
            if (credential is MdocCredential && notificationEvent != CredentialNotificationEvent.DELETED) {
                // Assert that the IACA root certificate is excluded from x5chain
                Assert.assertEquals(1, credential.issuerCertChain.certificates.size)
            }
            if (keyless) {
                when (credentialFormat) {
                    is CredentialFormat.Mdoc -> {
                        val keylessMdoc = assertIs<KeylessMdocCredential>(credential)
                        Assert.assertNull(keylessMdoc.mso.deviceKey)
                    }
                    is CredentialFormat.SdJwt -> {
                        assertIs<KeylessSdJwtVcCredential>(credential)
                    }
                }
            }

            withContext(serverEnvironment.await()) {
                // Run this in server environment!
                extraTests.invoke(httpClient, credential)
            }
        }
    }

    @Test
    fun runWithPreauthorizedCodeAndRefresh() = testApplication {
        val serverArgs = arrayOf(
            "-param", "base_url=https://localhost",
            "-param", "database_engine=ephemeral",
            "-param", "notification_endpoint_enabled=true"
        )
        val configuration = ServerConfiguration(serverArgs)
        val serverEnvironment = ServerEnvironment.create(configuration) {
            add(CredentialFactoryRegistry::class, credentalFactoryRegistry)
        }
        application {
            installServerEnvironment(serverEnvironment)
            configureRouting(serverEnvironment)
        }
        val httpClient = createClient {
            followRedirects = false
            install(credentialRequestRecorder)
            install(tokenResponseRecorder)
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
            val json = Json.parseToJsonElement(response.readRawBytes().decodeToString()).jsonArray
            val offerObject = json.first().jsonObject
            val preauthorizedOffer = offerObject["offer"]!!.jsonPrimitive.content
                .replace("http://localhost", "https://localhost")
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

            val credentials = provisioningClient.obtainCredentials(KeyBindingInfo.Attestation(
                    listOf(CredentialKeyAttestation("bar", keyInfo.attestation))))

            Assert.assertEquals(1, credentials.certifications.size)
            val firstNotificationId = Json.parseToJsonElement(credentialResponseBodies.last())
                .jsonObject["notification_id"]!!.jsonPrimitive.content
            provisioningClient.notifyCredentialResult(CredentialNotificationEvent.ACCEPTED)

            val authorizationData = provisioningClient.getAuthorizationData()!!

            // Test refresh workflow
            tokenResponseStatuses.clear()
            val refreshClient = Provisioning.createClientFromAuthorizationData(authorizationData)
            refreshClient.getKeyBindingChallenge()
            val refreshKeyInfo = secureArea.createKey(null, CreateKeySettings())
            val refreshed = refreshClient.obtainCredentials(KeyBindingInfo.Attestation(
                listOf(CredentialKeyAttestation("refresh", refreshKeyInfo.attestation))))
            Assert.assertEquals(1, refreshed.certifications.size)
            // The refresh token request carries a fresh client attestation challenge, so it
            // is not first refused with `use_attestation_challenge`.
            Assert.assertEquals(listOf(HttpStatusCode.OK), tokenResponseStatuses)
            val refreshNotificationId = Json.parseToJsonElement(credentialResponseBodies.last())
                .jsonObject["notification_id"]!!.jsonPrimitive.content
            Assert.assertNotEquals(firstNotificationId, refreshNotificationId)
            refreshClient.notifyCredentialResult(CredentialNotificationEvent.ACCEPTED)
            Assert.assertEquals(
                2,
                notificationResponseStatuses.count { it == HttpStatusCode.NoContent }
            )
            val notifiedIds = notificationRequestBodies.map {
                Json.parseToJsonElement(it as String).jsonObject["notification_id"]!!
                    .jsonPrimitive.content
            }.toSet()
            Assert.assertEquals(setOf(firstNotificationId, refreshNotificationId), notifiedIds)
            assertCredentialRequestsCompliant()

            // Clean up authorization data
            Provisioning.cleanupAuthorizationData(
                authorizationData, secureAreaRepository, storage)

            // Now refreshing will fail
            val failingClient = Provisioning.createClientFromAuthorizationData(authorizationData)
            failingClient.getKeyBindingChallenge()  // nonce does not require authorization
            val failingKeyInfo = secureArea.createKey(null, CreateKeySettings())
            val exception = assertThrows<Exception>("Should not succeed, keys were deleted") {
                refreshClient.obtainCredentials(
                    KeyBindingInfo.Attestation(
                        listOf(CredentialKeyAttestation("fail", failingKeyInfo.attestation))
                    )
                )
            }
            // Exception is thrown because key could not be found
            assertIs<IllegalArgumentException>(exception)
        }
    }

    /**
     * Checks the credential requests sent since the previous call against OpenID4VCI 1.0
     * section 8.2. Each request must be a JSON object that uses only the parameters defined
     * there. The spec allows extension parameters, but this client sends none, so this check
     * catches parameters left over from earlier drafts, such as `format`, `doctype` and `vct`.
     * Which identifier a request uses depends on the token response: `credential_identifier`
     * if it returned `credential_identifiers`, and `credential_configuration_id` otherwise.
     * The test issuer never returns `credential_identifiers`, so each request here must use
     * `credential_configuration_id` and not `credential_identifier`.
     *
     * @throws AssertionError if no new credential request was sent, or if a request is not
     *   compliant.
     */
    private fun assertCredentialRequestsCompliant(expectProofs: Boolean? = null) {
        val newBodies = credentialRequestBodies.drop(checkedCredentialRequests)
        checkedCredentialRequests = credentialRequestBodies.size
        Assert.assertTrue("No new credential request was recorded", newBodies.isNotEmpty())
        for (body in newBodies) {
            Assert.assertTrue(
                "Credential request body is not JSON text: ${body::class.simpleName}",
                body is String
            )
            val request = Json.parseToJsonElement(body as String).jsonObject
            Assert.assertTrue(
                "Unexpected credential request parameters: ${request.keys - CREDENTIAL_REQUEST_PARAMETERS}",
                CREDENTIAL_REQUEST_PARAMETERS.containsAll(request.keys)
            )
            Assert.assertTrue(
                "credential_configuration_id is required when no credential_identifiers were issued",
                request.containsKey("credential_configuration_id")
            )
            Assert.assertFalse(
                "credential_identifier must not be sent with credential_configuration_id",
                request.containsKey("credential_identifier")
            )
            if (expectProofs != null) {
                Assert.assertEquals(
                    "Unexpected presence of proofs in credential request",
                    expectProofs,
                    request.containsKey("proofs")
                )
            }
        }
    }

    private suspend fun runRevocationTests(
        httpClient: HttpClient,
        credential: Credential
    ) {
        runRevocationTest(
            httpClient = httpClient,
            credential = credential,
            useLastModified = true,
            useETag = false,
            bypassCache = false,
            preferJwt = true
        )
        runRevocationTest(
            httpClient = httpClient,
            credential = credential,
            useLastModified = false,
            useETag = true,
            bypassCache = false
        )
        runRevocationTest(
            httpClient = httpClient,
            credential = credential,
            useLastModified = false,
            useETag = false,
            bypassCache = true
        )
        /*
            // This should fail since without conditional HTTP request cache becomes stale:
            runRevocationTest(
                httpClient = httpClient,
                credential = credential,
                useLastModified = false,
                useETag = false,
                bypassCache = false
            )
        */
    }

    private suspend fun runRevocationTest(
        httpClient: HttpClient,
        credential: Credential,
        useETag: Boolean,
        useLastModified: Boolean,
        bypassCache: Boolean,
        preferJwt: Boolean = false
    ) {
        val serverKey = getServerIdentity(ServerIdentity.CREDENTIAL_SIGNING) as AsymmetricKey.X509Certified
        val rootCert = serverKey.certChain.certificates.last()
        val trustManager = ConfigurableTrustManager(
            identifier = "builtInIssuerTrustManager",
            entries = buildList {
                add(
                    TrustEntryX509Cert(
                        identifier = "test_issuer",
                        metadata = TrustMetadata(
                            displayName = rootCert.subject.components["CN"]?.value ?: "test",
                        ),
                        certificate = rootCert
                    )
                )
            }
        )

        val revocationChecker = CachingRevocationChecker(
            storage = storage,
            httpClient = httpClient,
            useETag = useETag,
            useLastModified = useLastModified
        )
        val revocationInfo = credential.getRevocationInfo(trustManager)!!
        val credentialId = when (val status = revocationInfo.revocationStatus) {
            is RevocationStatus.IdentifierList ->
                CredentialId(
                    bucket = status.uri.substring(status.uri.lastIndexOf('/') + 1),
                    index = CredentialState.identifierToIndex(status.id)
                )
            is RevocationStatus.StatusList ->
                CredentialId(
                    bucket = status.uri.substring(status.uri.lastIndexOf('/') + 1),
                    index = status.idx
                )
            else -> throw IllegalStateException()
        }

        val result1 = revocationChecker.check(
            credential = credential,
            trustManager = trustManager,
            bypassCache = bypassCache,
            preferJwt = preferJwt
        )
        Assert.assertNull(result1.error)
        Assert.assertEquals(RevocationCheckState.VALID, result1.state)

        CredentialState.setCredentialStatus(
            credentialId = credentialId,
            status = CredentialState.Status.INVALID,
            expiration = CredentialState.getCredentialState(credentialId)!!.expiration
        )
        invalidateStatusList(credentialId.bucket)
        invalidateIdentifierList(credentialId.bucket)

        val result2 = revocationChecker.check(
            credential = credential,
            trustManager = trustManager,
            bypassCache = bypassCache,
            preferJwt = preferJwt
        )
        Assert.assertNull(result2.error)
        Assert.assertEquals(RevocationCheckState.INVALID, result2.state)

        CredentialState.setCredentialStatus(
            credentialId = credentialId,
            status = CredentialState.Status.VALID,
            expiration = CredentialState.getCredentialState(credentialId)!!.expiration
        )
        invalidateStatusList(credentialId.bucket)
        invalidateIdentifierList(credentialId.bucket)

        val result3 = revocationChecker.check(
            credential = credential,
            trustManager = trustManager,
            bypassCache = bypassCache,
            preferJwt = preferJwt
        )
        Assert.assertNull(result3.error)
        Assert.assertEquals(RevocationCheckState.VALID, result3.state)
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
            credentialKeyAttestations: List<CredentialKeyAttestation>,
            challenge: String,
            userAuthentication: List<String>?,
            keyStorage: List<String>?
        ): String {
            // Generate key attestation
            val keyList = credentialKeyAttestations.map { it.keyAttestation.publicKey }

            val alg = localAttestationPrivateKey.curve.defaultSigningAlgorithmFullySpecified.joseAlgorithmIdentifier
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
                "alg": "ESP256",
                "kid": "895b72b9-0808-4fcc-bb19-960d14a9e28f",
                "crv": "P-256",
                "x": "nSmAFnZx-SqgTEyqqOSmZyLESdbiSUIYlRlLLoWy5uc",
                "y": "FN1qcif7nyVX1MHN_YSbo7o7RgG2kPJUjg27YX6AKsQ",
                "d": "TdQhxDqbAUpzMJN5XXQqLea7-6LvQu2GFKzj5QmFDCw"
            }            
        """.trimIndent()

        private val clientAssertionKey = AsymmetricKey.parseExplicit(clientAssertionJwk)
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

    inner class TestBackendEnvironment(
        val httpClient: HttpClient,
        val clientPreferences: OpenID4VCIClientPreferences = testClientPreferences
    ): BackendEnvironment {
        override fun <T : Any> getInterface(clazz: KClass<T>): T {
            return clazz.cast(when (clazz) {
                HttpClient::class -> httpClient
                OpenID4VCIBackend::class -> TestBackend
                OpenID4VCIClientPreferences::class -> clientPreferences
                SecureAreaProvider::class -> secureAreaProvider
                else -> throw IllegalArgumentException("no such class available: ${clazz.simpleName}")
            })
        }
    }

    companion object {
        const val OFFER_MDL = "openid-credential-offer://?credential_offer=%7B%22credential_issuer%22%3A%22http%3A%2F%2Flocalhost%22%2C%22credential_configuration_ids%22%3A%5B%22mDL%22%5D%2C%22grants%22%3A%7B%22authorization_code%22%3A%7B%7D%7D%7D"
        const val OFFER_NATURALIZATION = "openid-credential-offer://?credential_offer=%7B%22credential_issuer%22%3A%22http%3A%2F%2Flocalhost%22%2C%22credential_configuration_ids%22%3A%5B%22utopia_naturalization%22%5D%2C%22grants%22%3A%7B%22authorization_code%22%3A%7B%7D%7D%7D"
        const val OFFER_PAYMENT = "openid-credential-offer://?credential_offer=%7B%22credential_issuer%22%3A%22http%3A%2F%2Flocalhost%22%2C%22credential_configuration_ids%22%3A%5B%22payment_sca_mdoc%22%5D%2C%22grants%22%3A%7B%22authorization_code%22%3A%7B%7D%7D%7D"
        const val OFFER_PAYMENT_SD_JWT = "openid-credential-offer://?credential_offer=%7B%22credential_issuer%22%3A%22http%3A%2F%2Flocalhost%22%2C%22credential_configuration_ids%22%3A%5B%22payment_sca_sd_jwt%22%5D%2C%22grants%22%3A%7B%22authorization_code%22%3A%7B%7D%7D%7D"
        const val OFFER_MOVIE_TICKET_MDOC = "openid-credential-offer://?credential_offer=%7B%22credential_issuer%22%3A%22http%3A%2F%2Flocalhost%22%2C%22credential_configuration_ids%22%3A%5B%22utopia_movie_ticket_mdoc%22%5D%2C%22grants%22%3A%7B%22authorization_code%22%3A%7B%7D%7D%7D"
        const val OFFER_MOVIE_TICKET_SD_JWT = "openid-credential-offer://?credential_offer=%7B%22credential_issuer%22%3A%22http%3A%2F%2Flocalhost%22%2C%22credential_configuration_ids%22%3A%5B%22utopia_movie_ticket_sd_jwt%22%5D%2C%22grants%22%3A%7B%22authorization_code%22%3A%7B%7D%7D%7D"

        // Credential Request parameters defined in OpenID4VCI 1.0 section 8.2.
        val CREDENTIAL_REQUEST_PARAMETERS = setOf(
            "credential_identifier",
            "credential_configuration_id",
            "proofs",
            "credential_response_encryption"
        )

        val testClientPreferences = OpenID4VCIClientPreferences(
            clientId = "urn:uuid:418745b8-78a3-4810-88df-7898aff3ffb4",
            redirectUrl = "https://redirect.example.com",
            locales = listOf("en-US"),
            signingAlgorithms = listOf(Algorithm.ESP256)
        )
    }
}
