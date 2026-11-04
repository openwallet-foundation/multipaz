package org.multipaz.presentment

import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.engine.HttpClientEngineFactory
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockEngineConfig
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.multipaz.asn1.ASN1Integer
import org.multipaz.crypto.AsymmetricKey
import org.multipaz.crypto.Crypto
import org.multipaz.crypto.EcCurve
import org.multipaz.crypto.X500Name
import org.multipaz.crypto.X509CertChain
import org.multipaz.documenttype.knowntypes.DrivingLicense
import org.multipaz.eventlogger.Event
import org.multipaz.eventlogger.EventLogger
import org.multipaz.eventlogger.EventPresentmentUriSchemeOpenID4VP
import org.multipaz.mdoc.util.MdocUtil
import org.multipaz.openid.OpenID4VP
import org.multipaz.prompt.promptModelSilentConsent
import org.multipaz.verification.VerifierIdentity
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Regression tests for the query parsing and HTTP error handling in [uriSchemePresentment].
 *
 * Running a whole URI through `parseUrlEncodedParameters()` folds the scheme into the first key,
 * so `openid4vp://?request_uri=...` used to lose `request_uri` and only worked when some other
 * parameter happened to precede it.
 */
class UriSchemeQueryParsingTest {

    @Test
    fun requestUriAsOnlyParameter() {
        val parameters = parseQueryParameters(
            "openid4vp://?request_uri=https%3A%2F%2Fverifier.example%2Foid4vp%2Frequest%2Fabc"
        )
        assertEquals("https://verifier.example/oid4vp/request/abc", parameters["request_uri"])
    }

    @Test
    fun requestUriAfterClientId() {
        val parameters = parseQueryParameters(
            "openid4vp://?client_id=x509_san_dns%3Averifier.example" +
                    "&request_uri=https%3A%2F%2Fverifier.example%2Foid4vp%2Frequest%2Fabc"
        )
        assertEquals("x509_san_dns:verifier.example", parameters["client_id"])
        assertEquals("https://verifier.example/oid4vp/request/abc", parameters["request_uri"])
    }

    @Test
    fun schemeIsNotFoldedIntoTheFirstKey() {
        val parameters = parseQueryParameters("haip-vp://?request_uri=https%3A%2F%2Fexample")
        assertEquals(setOf("request_uri"), parameters.names())
    }

    @Test
    fun requestUriMethodIsRead() {
        val parameters = parseQueryParameters(
            "openid4vp://?request_uri=https%3A%2F%2Fexample&request_uri_method=post"
        )
        assertEquals("post", parameters["request_uri_method"])
    }

    @Test
    fun noQueryYieldsNoParameters() {
        assertNull(parseQueryParameters("openid4vp://")["request_uri"])
    }

    private fun mockEngineFactory(
        handler: suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData
    ): HttpClientEngineFactory<MockEngineConfig> = object : HttpClientEngineFactory<MockEngineConfig> {
        override fun create(block: MockEngineConfig.() -> Unit): HttpClientEngine = MockEngine(handler)
    }

    private class RecordingEventLogger : EventLogger {
        val recordedEvents = mutableListOf<Event>()

        override suspend fun addEvent(event: Event): Event {
            recordedEvents.add(event)
            return event
        }

        override fun addEventAsync(event: Event) {
            recordedEvents.add(event)
        }
    }

    private suspend fun createSignedRequestJwt(
        harness: DocumentStoreTestHarness,
        responseUri: String,
        clientId: String = "x509_san_dns:verifier.example",
    ): String {
        val readerPrivateKey = Crypto.createEcPrivateKey(EcCurve.P256)
        val readerCert = MdocUtil.generateReaderCertificate(
            readerRootKey = harness.readerRootKey,
            readerKey = readerPrivateKey.publicKey,
            subject = X500Name.fromName("CN=Multipaz TEST Reader"),
            dnsName = "verifier.example",
            serial = ASN1Integer.fromRandom(128),
            validFrom = harness.validFrom,
            validUntil = harness.validUntil,
        )
        val verifierIdentity = VerifierIdentity(
            key = AsymmetricKey.X509CertifiedExplicit(
                certChain = X509CertChain(
                    listOf(readerCert) + harness.readerRootKey.certChain.certificates
                ),
                privateKey = readerPrivateKey
            ),
            clientId = clientId
        )
        val dcqlQuery = Json.decodeFromString<JsonObject>(
            """
            {
              "credentials": [
                {
                  "id": "mdl",
                  "format": "mso_mdoc",
                  "meta": {
                    "doctype_value": "${DrivingLicense.MDL_DOCTYPE}"
                  },
                  "claims": [
                    { "path": ["${DrivingLicense.MDL_NAMESPACE}", "given_name"] }
                  ]
                }
              ]
            }
            """.trimIndent()
        )
        val requestWrapper = OpenID4VP.generateRequest(
            version = OpenID4VP.Version.DRAFT_29,
            origin = "https://verifier.example",
            nonce = "test-nonce-1234",
            responseEncryptionKey = null,
            verifierIdentities = listOf(verifierIdentity),
            responseMode = OpenID4VP.ResponseMode.DIRECT_POST,
            responseUri = responseUri,
            dcqlQuery = dcqlQuery,
            state = "test-state"
        )
        return requestWrapper["request"]!!.jsonPrimitive.content
    }

    @Test
    fun requestUriNonOkStatusDoesNotLeakBody() = runTest {
        val harness = DocumentStoreTestHarness()
        harness.initialize()
        val secretErrorBody = "Internal server secret details 12345"
        val factory = mockEngineFactory {
            respond(
                content = secretErrorBody,
                status = HttpStatusCode.BadGateway,
                headers = headersOf(HttpHeaders.ContentType, "text/plain")
            )
        }
        val exception = assertFailsWith<IllegalStateException> {
            uriSchemePresentment(
                source = harness.presentmentSource,
                uri = "openid4vp://?request_uri=https%3A%2F%2Fverifier.example%2Foid4vp%2Frequest%2Fabc",
                appId = null,
                origin = "https://verifier.example",
                httpClientEngineFactory = factory
            )
        }
        val message = exception.message ?: ""
        assertTrue(message.contains("502"), "Expected status code in message, got: $message")
        assertTrue(!message.contains(secretErrorBody), "Expected body not to be leaked in message: $message")
    }

    @Test
    fun responseUriErrorIncludesStatusAndBodyAndLogsEvent() = runTest {
        val harness = DocumentStoreTestHarness()
        harness.provisionStandardDocuments()
        val eventLogger = RecordingEventLogger()
        val source = SimplePresentmentSource(
            documentStore = harness.documentStore,
            documentTypeRepository = harness.documentTypeRepository,
            eventLogger = eventLogger,
            showConsentPromptFn = ::promptModelSilentConsent,
            domainsMdocSignature = listOf("mdoc"),
        )
        val requestUri = "https://verifier.example/oid4vp/request/abc"
        val responseUri = "https://verifier.example/oid4vp/response/abc"
        val requestJwt = createSignedRequestJwt(harness, responseUri)
        val verifierErrorBody = """{"error":"invalid_request","error_description":"Missing required claim"}"""

        val factory = mockEngineFactory { request ->
            when (request.url.toString()) {
                requestUri -> respond(
                    content = requestJwt,
                    status = HttpStatusCode.OK,
                    headers = headersOf(HttpHeaders.ContentType, "application/oauth-authz-req+jwt")
                )
                responseUri -> respond(
                    content = verifierErrorBody,
                    status = HttpStatusCode.BadRequest,
                    headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString())
                )
                else -> error("Unexpected URL: ${request.url}")
            }
        }

        val exception = assertFailsWith<IllegalStateException> {
            uriSchemePresentment(
                source = source,
                uri = "openid4vp://?request_uri=https%3A%2F%2Fverifier.example%2Foid4vp%2Frequest%2Fabc",
                appId = null,
                origin = "https://verifier.example",
                httpClientEngineFactory = factory
            )
        }
        val message = exception.message ?: ""
        assertTrue(message.contains("400"), "Expected status 400 in message, got: $message")
        assertTrue(message.contains(verifierErrorBody), "Expected verifier error body in message, got: $message")

        assertEquals(1, eventLogger.recordedEvents.size, "Expected presentment event to be logged even when response_uri returns 400")
        val loggedEvent = assertIs<EventPresentmentUriSchemeOpenID4VP>(eventLogger.recordedEvents.single())
        assertNull(loggedEvent.redirectUri)
        assertEquals("test-state", loggedEvent.state)
    }

    @Test
    fun clientIdNotMatchingCertificateIsRejected() = runTest {
        val harness = DocumentStoreTestHarness()
        harness.provisionStandardDocuments()
        val requestUri = "https://verifier.example/oid4vp/request/abc"
        val responseUri = "https://verifier.example/oid4vp/response/abc"
        // Signed with a certificate for verifier.example, claiming to be another verifier.
        val requestJwt = createSignedRequestJwt(harness, responseUri, clientId = "x509_san_dns:impersonated.example")
        var responseSent = false
        val factory = mockEngineFactory { request ->
            when (request.url.toString()) {
                requestUri -> respond(
                    content = requestJwt,
                    status = HttpStatusCode.OK,
                    headers = headersOf(HttpHeaders.ContentType, "application/oauth-authz-req+jwt")
                )
                else -> {
                    responseSent = true
                    error("Unexpected URL: ${request.url}")
                }
            }
        }

        assertFailsWith<IllegalArgumentException> {
            uriSchemePresentment(
                source = harness.presentmentSource,
                uri = "openid4vp://?request_uri=https%3A%2F%2Fverifier.example%2Foid4vp%2Frequest%2Fabc",
                appId = null,
                origin = "https://verifier.example",
                httpClientEngineFactory = factory
            )
        }
        assertTrue(!responseSent, "Expected no response to be sent")
    }
}
