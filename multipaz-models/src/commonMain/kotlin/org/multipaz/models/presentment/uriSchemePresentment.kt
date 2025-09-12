package org.multipaz.models.presentment

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.accept
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.Parameters
import io.ktor.http.contentType
import io.ktor.http.formUrlEncode
import io.ktor.http.parseUrlEncodedParameters
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.multipaz.crypto.JsonWebSignature
import org.multipaz.document.Document
import org.multipaz.documenttype.DocumentTypeRepository
import org.multipaz.models.openid.OpenID4VP
import org.multipaz.presentment.CredentialPresentmentData
import org.multipaz.presentment.CredentialPresentmentSelection
import org.multipaz.request.Requester
import org.multipaz.trustmanagement.TrustPoint
import org.multipaz.util.toBase64Url

private const val TAG = "uriSchemePresentment"

internal suspend fun uriSchemePresentment(
    documentTypeRepository: DocumentTypeRepository,
    source: PresentmentSource,
    mechanism: UriSchemePresentmentMechanism,
    dismissable: MutableStateFlow<Boolean>,
    showConsentPrompt: suspend (
        credentialPresentmentData: CredentialPresentmentData,
        preselectedDocuments: List<Document>,
        requester: Requester,
        trustPoint: TrustPoint?
    ) -> CredentialPresentmentSelection?
) {
    val parameters = mechanism.uri.parseUrlEncodedParameters()
    // TODO: maybe also support `request` in addition to `request_uri`, that is, the case
    //   where the request is passed by value instead of reference
    val requestUri = parameters["request_uri"] ?: throw IllegalStateException("No request_uri")
    val requestUriMethod = parameters["request_uri_method"] ?: "get"

    val httpClient = HttpClient(mechanism.httpClientEngineFactory) {
        install(HttpTimeout)
    }
    val httpResponse = when (requestUriMethod) {
        "post" -> {
            // TODO: include wallet capabilities as POST body as per 5.10
            httpClient.post(requestUri) {
                contentType(ContentType.Application.FormUrlEncoded)
                accept(ContentType("application", "oauth-authz-req+jwt"))
            }
        }
        "get" -> httpClient.get(requestUri)
        else -> throw IllegalArgumentException("Unexpected method $requestUriMethod")
    }
    check(httpResponse.status == HttpStatusCode.OK)
    check(httpResponse.contentType() == ContentType("application", "oauth-authz-req+jwt"))

    val reqJwt = (httpResponse.body() as ByteArray).decodeToString()
    val info = JsonWebSignature.getInfo(reqJwt)
    val requestObject = info.claimsSet
    val requesterChain = info.x5c!!
    JsonWebSignature.verify(reqJwt, requesterChain.certificates.first().ecPublicKey)
    check(info.type == "oauth-authz-req+jwt")

    val responseUri = requestObject["response_uri"]?.jsonPrimitive?.content
        ?: throw IllegalArgumentException("response_uri not set in request")
    val response = OpenID4VP.generateResponse(
        version = OpenID4VP.Version.DRAFT_29,
        preselectedDocuments = listOf(),
        source = source,
        showConsentPrompt = showConsentPrompt,
        appId = null, // TODO: maybe pass the browser's appId if we can
        origin = mechanism.origin,
        request = requestObject,
        requesterCertChain = requesterChain,
    )

    val responseCs = when (requestObject["response_mode"]!!.jsonPrimitive.content) {
        "direct_post" -> {
            // Return an unsecured JWT as per https://datatracker.ietf.org/doc/html/rfc7519#section-6
            val protectedHeader = buildJsonObject { put("alg", "none") }
            val headerb64 = Json.encodeToString(protectedHeader).encodeToByteArray().toBase64Url()
            val bodyb64 = Json.encodeToString(response).encodeToByteArray().toBase64Url()
            "$headerb64.$bodyb64."
        }
        "direct_post.jwt" -> {
            response.get("response")!!.jsonPrimitive.content
        }
        else -> throw IllegalArgumentException("Unexpected response_mode")
    }

    val postResponseResponse = httpClient.post(responseUri) {
        contentType(ContentType.Application.FormUrlEncoded)
        setBody(
            Parameters.build {
                append("response", responseCs)
                // TODO: remember state
            }.formUrlEncode().encodeToByteArray()
        )
    }
    check(postResponseResponse.status == HttpStatusCode.OK)
    check(postResponseResponse.contentType()!! == ContentType.Application.Json)
    val bodyText = (postResponseResponse.body() as ByteArray).decodeToString()
    val postResponseBody = Json.decodeFromString<JsonObject>(bodyText)
    val redirectUri = postResponseBody["redirect_uri"]!!.jsonPrimitive.content
    mechanism.openUriInBrowser(redirectUri)
}