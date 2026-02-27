package org.multipaz.presentment

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.HttpClientEngineFactory
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.accept
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.Parameters
import io.ktor.http.Url
import io.ktor.http.contentType
import io.ktor.http.formUrlEncode
import io.ktor.http.parseUrlEncodedParameters
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.multipaz.cbor.Bstr
import org.multipaz.cbor.Cbor
import org.multipaz.cbor.Simple
import org.multipaz.cbor.Tagged
import org.multipaz.cbor.buildCborArray
import org.multipaz.cbor.buildCborMap
import org.multipaz.crypto.Algorithm
import org.multipaz.crypto.Crypto
import org.multipaz.crypto.JsonWebSignature
import org.multipaz.document.Document
import org.multipaz.mdoc.connectionmethod.MdocConnectionMethodHttp
import org.multipaz.mdoc.engagement.Capability
import org.multipaz.mdoc.engagement.DeviceEngagement
import org.multipaz.mdoc.engagement.buildDeviceEngagement
import org.multipaz.mdoc.origininfo.OriginInfoDomain
import org.multipaz.mdoc.request.DeviceRequest
import org.multipaz.mdoc.role.MdocRole
import org.multipaz.mdoc.sessionencryption.SessionEncryption
import org.multipaz.mdoc.transport.MdocTransportClosedException
import org.multipaz.openid.OpenID4VP
import org.multipaz.util.Constants
import org.multipaz.util.Logger
import org.multipaz.util.fromBase64Url
import org.multipaz.util.toBase64Url
import kotlin.coroutines.cancellation.CancellationException

private const val TAG = "uriSchemePresentment"

/**
 * Present credentials according to OpenID4VP 1.0 w/ URI schemes.
 *
 * @param source the source of truth used for presentment.
 * @param uri the referrer.
 * @param origin the origin.
 * @param httpClientEngineFactory a [HttpClientEngineFactory].
 * @param onDocumentsInFocus called with the documents currently selected for the user, including when
 *   first shown. If the user selects a different set of documents in the prompt, this will be called again.
 * @return the redirect URI, caller should open this in the user's default browser or `null` if this is not required.
 * @throws PresentmentCanceledException if the user canceled in a consent prompt.
 * @throws PresentmentCannotSatisfyRequestException if it's not possible to satisfy the request.
 */
@Throws(
    CancellationException::class,
    IllegalStateException::class,
    PresentmentCanceledException::class,
    PresentmentCannotSatisfyRequestException::class
)
suspend fun uriSchemePresentment(
    source: PresentmentSource,
    uri: String,
    origin: String?,
    httpClientEngineFactory: HttpClientEngineFactory<*>,
    onDocumentsInFocus: (documents: List<Document>) -> Unit = {},
): String? {
    if (uri.startsWith("mdoc://")) {
        return mdocUriSchemePresentment(
            source = source,
            uri = uri,
            origin = origin,
            httpClientEngineFactory = httpClientEngineFactory,
            onDocumentsInFocus = onDocumentsInFocus
        )
    }
    val parameters = uri.parseUrlEncodedParameters()
    // TODO: maybe also support `request` in addition to `request_uri`, that is, the case
    //   where the request is passed by value instead of reference
    val requestUri = parameters["request_uri"] ?: throw IllegalStateException("No request_uri")
    val requestUriMethod = parameters["request_uri_method"] ?: "get"

    val httpClient = HttpClient(httpClientEngineFactory) {
        install(HttpTimeout)
    }
    val requestObjectMediaType = ContentType("application", "oauth-authz-req+jwt")
    val httpResponse = when (requestUriMethod) {
        "post" -> {
            // TODO: include wallet capabilities as POST body as per 5.10
            httpClient.post(requestUri) {
                contentType(ContentType.Application.FormUrlEncoded)
                accept(requestObjectMediaType)
            }
        }
        "get" -> httpClient.get(requestUri)
        else -> throw IllegalArgumentException("Unexpected method $requestUriMethod")
    }
    check(httpResponse.status == HttpStatusCode.OK)
    check(httpResponse.contentType()?.match(requestObjectMediaType) ?: false)

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
        appId = null, // TODO: maybe pass the browser's appId if we can
        origin = origin ?: "",
        request = requestObject,
        requesterCertChain = requesterChain,
        onDocumentsInFocus = onDocumentsInFocus
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
    return redirectUri
}

private suspend fun mdocUriSchemePresentment(
    source: PresentmentSource,
    uri: String,
    origin: String?,
    httpClientEngineFactory: HttpClientEngineFactory<*>,
    onDocumentsInFocus: (documents: List<Document>) -> Unit = {},
): String? {
    val url = Url(uri)
    val readerEngagementEncoded = url.host.fromBase64Url()
    val readerEngagementDataItem = Cbor.decode(readerEngagementEncoded)

    // ReaderEngagement is really the same as DeviceEngagement so just re-use the parser
    val readerEngagement = DeviceEngagement.fromDataItem(readerEngagementDataItem)
    val eReaderKey = readerEngagement.eDeviceKey

    val httpRetrievalMethod = readerEngagement.connectionMethods.find { it is MdocConnectionMethodHttp } as MdocConnectionMethodHttp?
    if (httpRetrievalMethod == null) {
        throw IllegalArgumentException("No MdocConnectionMethodHttp method found")
    }
    val readerUrl = httpRetrievalMethod.uri

    // TODO: handle this curve not being supported...
    val eDeviceKey = Crypto.createEcPrivateKey(eReaderKey.curve)
    val deviceEngagement = buildDeviceEngagement(
        eDeviceKey = eDeviceKey.publicKey,
    ) {
        addCapability(Capability.READER_AUTH_ALL_SUPPORT, Simple.TRUE)
        addCapability(Capability.EXTENDED_REQUEST_SUPPORT, Simple.TRUE)
        if (origin != null) {
            addOriginInfo(OriginInfoDomain(domain = Url(origin).host))
        } else {
            // From A.3.2:
            //
            //   The value of the “domain” element may be an empty string. This signifies that the mdoc did not
            //   receive the value of the domain, or did not receive it from a source trusted by the mdoc.
            //
            addOriginInfo(OriginInfoDomain(domain = ""))
        }
    }

    val httpClient = HttpClient(httpClientEngineFactory) {
        install(HttpTimeout)
    }
    val deviceEngagementBytesDataItem = Tagged(
        tagNumber = Tagged.ENCODED_CBOR,
        taggedItem = Bstr(Cbor.encode(deviceEngagement.toDataItem()))
    )
    val deviceEngagementMessage = buildCborMap {
        put("deviceEngagementBytes", deviceEngagementBytesDataItem)
    }

    val engagementToApp = Bstr(
        Crypto.digest(Algorithm.SHA256, Cbor.encode(
            Tagged(
                tagNumber = Tagged.ENCODED_CBOR,
                taggedItem = Bstr(readerEngagementEncoded)
            ))
        )
    )

    val sessionTranscript = buildCborArray {
        add(deviceEngagementBytesDataItem)
        add(Cbor.decode(readerEngagement.eDeviceKeyBytes.toByteArray()))
        add(engagementToApp)
    }

    val sessionEncryption = SessionEncryption(
        role = MdocRole.MDOC,
        eSelfKey = eDeviceKey,
        remotePublicKey = eReaderKey,
        encodedSessionTranscript = Cbor.encode(sessionTranscript)
    )

    var messageToPost = Cbor.encode(deviceEngagementMessage)
    do {
        val initialResponse = httpClient.post(readerUrl) {
            contentType(ContentType.Application.Cbor)
            setBody(messageToPost)
        }
        if (initialResponse.status != HttpStatusCode.OK) {
            throw IllegalStateException("Unexpected HTTP response ${initialResponse.status} from reader")
        }
        val sessionMessage = initialResponse.body<ByteArray>()

        val (messageFromReader, messageFromReaderStatus) =
            sessionEncryption.decryptMessage(sessionMessage)
        if (messageFromReaderStatus != null) {
            when (messageFromReaderStatus) {
                Constants.SESSION_DATA_STATUS_SESSION_TERMINATION -> {
                    Logger.i(TAG, "Received session termination message")
                    break
                }
                else -> {
                    throw IllegalStateException("Unexpected status $messageFromReaderStatus")
                }
            }
        }
        if (messageFromReader == null) {
            throw IllegalStateException("No data in message from reader")
        }

        val deviceRequest = DeviceRequest.fromDataItem(Cbor.decode(messageFromReader))
        deviceRequest.verifyReaderAuthentication(sessionTranscript)
        val deviceResponse = mdocPresentment(
            deviceRequest = deviceRequest,
            eReaderKey = eReaderKey,
            sessionTranscript = sessionTranscript,
            source = source,
            keyAgreementPossible = emptyList(), // TODO: support MacKeys
            requesterAppId = null, // TODO: maybe pass the browser's appId if we can
            requesterOrigin = origin ?: "",
            preselectedDocuments = emptyList(),
            onWaitingForUserInput = {},
            onDocumentsInFocus = onDocumentsInFocus
        )
        messageToPost = sessionEncryption.encryptMessage(
            messagePlaintext = Cbor.encode(deviceResponse.toDataItem()),
            statusCode = null
        )
    } while (true)
    return null
}
