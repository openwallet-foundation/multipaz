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
import org.multipaz.cbor.DataItem
import org.multipaz.cbor.Simple
import org.multipaz.cbor.Tagged
import org.multipaz.cbor.buildCborArray
import org.multipaz.cbor.buildCborMap
import org.multipaz.crypto.Algorithm
import org.multipaz.crypto.Crypto
import org.multipaz.crypto.JsonWebSignature
import org.multipaz.document.Document
import org.multipaz.eventlogger.EventPresentmentData
import org.multipaz.eventlogger.EventPresentmentIso18013AnnexA
import org.multipaz.eventlogger.EventPresentmentUriSchemeOpenID4VP
import org.multipaz.mdoc.connectionmethod.MdocConnectionMethodHttp
import org.multipaz.mdoc.engagement.Capability
import org.multipaz.mdoc.engagement.DeviceEngagement
import org.multipaz.mdoc.engagement.buildDeviceEngagement
import org.multipaz.mdoc.origininfo.OriginInfoDomain
import org.multipaz.mdoc.request.DeviceRequest
import org.multipaz.mdoc.role.MdocRole
import org.multipaz.mdoc.sessionencryption.SessionEncryption
import org.multipaz.openid.OpenID4VP
import org.multipaz.request.OpenID4VPRequesterIdentity
import org.multipaz.util.Constants
import org.multipaz.util.Logger
import org.multipaz.util.fromBase64Url
import org.multipaz.util.toBase64Url
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Clock
import kotlin.time.Duration

private const val TAG = "uriSchemePresentment"

/**
 * Parses the query component of [uri] into parameters.
 *
 * [parseUrlEncodedParameters] splits on `&` and then on the first `=`, so handing it a whole
 * URI folds the scheme into the first key: `openid4vp://?request_uri=...` yields the single key
 * `openid4vp://?request_uri`. Taking the substring after the first `?` keeps that from happening,
 * which matters because a signed request object carries `client_id` and a verifier may send
 * `request_uri` as the only query parameter.
 *
 * A URI with no `?` yields no parameters.
 */
internal fun parseQueryParameters(uri: String) = uri.substringAfter('?', "").parseUrlEncodedParameters()

/**
 * Present credentials according to OpenID4VP 1.0 w/ URI schemes.
 *
 * @param source the source of truth used for presentment.
 * @param uri the referrer.
 * @param appId the appId of the browser or app which invoked us, if known.
 * @param origin the origin, if known.
 * @param httpClientEngineFactory a [HttpClientEngineFactory].
 * @param onDocumentsInFocus called with the documents currently selected for the user, including when
 *   first shown. If the user selects a different set of documents in the prompt, this will be called again.
 * @param onTelemetry callback invoked with the final [PresentmentTelemetry]. This is guaranteed to
 *   be called both before returning normally and before an exception is thrown.
 * @return the redirect URI, caller should open this in the user's default browser.
 * @throws AuthenticationFailedException if user authentication failed or was canceled.
 * @throws FaceNotMatchedException if face matching failed or was canceled.
 * @throws PresentmentCanceledException if the user canceled in a consent prompt.
 * @throws PresentmentCannotSatisfyRequestException if it's not possible to satisfy the request.
 */
@Throws(
    AuthenticationFailedException::class,
    CancellationException::class,
    FaceNotMatchedException::class,
    IllegalStateException::class,
    PresentmentCanceledException::class,
    PresentmentCannotSatisfyRequestException::class
)
suspend fun uriSchemePresentment(
    source: PresentmentSource,
    uri: String,
    appId: String?,
    origin: String?,
    httpClientEngineFactory: HttpClientEngineFactory<*>,
    onDocumentsInFocus: (documents: List<Document>) -> Unit = {},
    onTelemetry: (telemetry: PresentmentTelemetry) -> Unit = {},
): String? {
    if (uri.startsWith("mdoc://")) {
        return mdocUriSchemePresentment(
            source = source,
            uri = uri,
            appId = appId,
            origin = origin,
            httpClientEngineFactory = httpClientEngineFactory,
            onDocumentsInFocus = onDocumentsInFocus,
            onTelemetry = onTelemetry
        )
    }
    val startTime = Clock.System.now()
    var outcome = PresentmentOutcome.SUCCESS
    var requestSizeBytes: Int? = null
    var responseSizeBytes: Int? = null
    var waitingForRequestTime: Duration? = null
    var responseGenerationTime: Duration? = null
    var sendingResponseTime: Duration? = null
    var reqJwt: String? = null
    var responseObject: OpenID4VP.OpenID4VPResponse? = null
    var redirectUri: String? = null
    var telemetry: PresentmentTelemetry? = null

    try {
        val parameters = parseQueryParameters(uri)
        // TODO: maybe also support `request` in addition to `request_uri`, that is, the case
        //   where the request is passed by value instead of reference
        val requestUri = parameters["request_uri"] ?: throw IllegalStateException("No request_uri")
        val requestUriMethod = parameters["request_uri_method"] ?: "get"

        val httpClient = HttpClient(httpClientEngineFactory) {
            install(HttpTimeout)
        }
        val requestObjectMediaType = ContentType("application", "oauth-authz-req+jwt")
        val tBeforeFetch = Clock.System.now()
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
        check(httpResponse.status == HttpStatusCode.OK) {
            "Unexpected HTTP response ${httpResponse.status} from $requestUri"
        }
        val requestContentType = httpResponse.contentType()
        check(requestContentType != null && requestContentType.match(requestObjectMediaType)) {
            "Expected $requestObjectMediaType, got $requestContentType"
        }

        val reqJwtBytes = httpResponse.body() as ByteArray
        waitingForRequestTime = Clock.System.now() - tBeforeFetch
        requestSizeBytes = reqJwtBytes.size
        reqJwt = reqJwtBytes.decodeToString()
        val info = JsonWebSignature.getInfo(reqJwt)
        val requestObject = info.claimsSet
        val requesterIdentity = OpenID4VPRequesterIdentity(
            certChain = info.x5c!!,
            clientId = requestObject["client_id"]!!.jsonPrimitive.content
        )
        JsonWebSignature.verify(reqJwt, requesterIdentity.certChain.certificates.first().publicKey)
        check(info.type == "oauth-authz-req+jwt") {
            "Expected JWT type oauth-authz-req+jwt, got ${info.type}"
        }

        val responseUri = requestObject["response_uri"]?.jsonPrimitive?.content
            ?: throw IllegalArgumentException("response_uri not set in request")
        val tBeforeGen = Clock.System.now()
        responseObject = OpenID4VP.generateResponse(
            version = OpenID4VP.Version.DRAFT_29,
            preselectedDocuments = listOf(),
            source = source,
            appId = appId,
            origin = origin ?: "",
            request = requestObject,
            requesterIdentities = listOf(requesterIdentity),
            onDocumentsInFocus = onDocumentsInFocus
        )
        responseGenerationTime = Clock.System.now() - tBeforeGen
        val response = responseObject.response

        val responseCs = when (requestObject["response_mode"]!!.jsonPrimitive.content) {
            "direct_post" -> {
                // Return an unsecured JWT as per https://datatracker.ietf.org/doc/html/rfc7519#section-6
                val protectedHeader = buildJsonObject { put("alg", "none") }
                val headerb64 = Json.encodeToString(protectedHeader).encodeToByteArray().toBase64Url()
                val bodyb64 = Json.encodeToString(response).encodeToByteArray().toBase64Url()
                "$headerb64.$bodyb64."
            }
            "direct_post.jwt" -> {
                response["response"]!!.jsonPrimitive.content
            }
            else -> throw IllegalArgumentException("Unexpected response_mode")
        }

        val postBodyBytes = Parameters.build {
            append("response", responseCs)
            // TODO: remember state
        }.formUrlEncode().encodeToByteArray()
        responseSizeBytes = postBodyBytes.size

        val tBeforePost = Clock.System.now()
        val postResponseResponse = httpClient.post(responseUri) {
            contentType(ContentType.Application.FormUrlEncoded)
            setBody(postBodyBytes)
        }
        val bodyText = (postResponseResponse.body() as ByteArray).decodeToString()
        sendingResponseTime = Clock.System.now() - tBeforePost
        check(postResponseResponse.status == HttpStatusCode.OK) {
            val truncatedBody = bodyText.take(1024)
            "Unexpected HTTP response ${postResponseResponse.status} from $responseUri: $truncatedBody"
        }
        val contentType = postResponseResponse.contentType()
        check(contentType != null && contentType.match(ContentType.Application.Json)) {
            "Expected ${ContentType.Application.Json}, got $contentType"
        }
        val postResponseBody = Json.decodeFromString<JsonObject>(bodyText)
        redirectUri = postResponseBody["redirect_uri"]?.jsonPrimitive?.content
    } catch (e: Throwable) {
        outcome = classifyPresentmentException(e)
        throw e
    } finally {
        telemetry = PresentmentTelemetry(
            protocol = PresentmentProtocol.URI_SCHEME_OPENID4VP,
            outcome = outcome,
            requesterInTrustList = responseObject?.eventData?.let { it.trustMetadata != null },
            requestSizeBytes = requestSizeBytes,
            responseSizeBytes = responseSizeBytes,
            numRequestsServed = if (outcome == PresentmentOutcome.SUCCESS) 1 else 0,
            numDocumentsPresented = responseObject?.eventData?.requestedDocuments?.size ?: 0,
            durationConnectedToRequestReceived = waitingForRequestTime,
            durationResponseGeneration = responseGenerationTime,
            durationResponseTransmission = sendingResponseTime,
            durationTotal = Clock.System.now() - startTime,
        )
        onTelemetry(telemetry)
        source.eventLogger?.addEventAsync(
            EventPresentmentUriSchemeOpenID4VP(
                presentmentData = responseObject?.eventData,
                uri = uri,
                appId = appId,
                origin = origin,
                requestJwt = reqJwt,
                vpToken = responseObject?.let { Json.encodeToString(it.vpToken) },
                redirectUri = redirectUri,
                state = responseObject?.state,
                telemetry = telemetry
            )
        )
    }

    return redirectUri
}

private data class CompletedAnnexAExchange(
    val presentmentData: EventPresentmentData,
    val request: DataItem,
    val response: DataItem,
    val sessionTranscript: DataItem,
)

private suspend fun mdocUriSchemePresentment(
    source: PresentmentSource,
    uri: String,
    appId: String?,
    origin: String?,
    httpClientEngineFactory: HttpClientEngineFactory<*>,
    onDocumentsInFocus: (documents: List<Document>) -> Unit = {},
    onTelemetry: (telemetry: PresentmentTelemetry) -> Unit = {},
): String? {
    val startTime = Clock.System.now()
    var outcome = PresentmentOutcome.SUCCESS
    var requestSizeBytes: Int? = null
    var responseSizeBytes: Int? = null
    var waitingForRequestTime: Duration? = null
    var userInputTime: Duration? = null
    var responseGenerationTime: Duration? = null
    var sendingResponseTime: Duration? = null
    var numRequestsServed = 0
    var numDocumentsPresented = 0
    var requesterInTrustList: Boolean? = null
    var lastEventData: EventPresentmentData? = null
    var lastDeviceRequest: DataItem? = null
    var lastDeviceResponse: DataItem? = null
    var sessionTranscriptDataItem: DataItem? = null
    var readerEngagementDataItemLogged: DataItem? = null
    val completedExchanges = mutableListOf<CompletedAnnexAExchange>()
    var telemetry: PresentmentTelemetry? = null

    try {
        val url = Url(uri)
        val readerEngagementEncoded = url.host.fromBase64Url()
        val readerEngagementDataItem = Cbor.decode(readerEngagementEncoded)

        // ReaderEngagement is really the same as DeviceEngagement so just re-use the parser
        val readerEngagement = DeviceEngagement.fromDataItem(readerEngagementDataItem)
        readerEngagementDataItemLogged = readerEngagement.toDataItem()
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
        sessionTranscriptDataItem = sessionTranscript
        Logger.dCbor(TAG, "SessionTranscript", sessionTranscript)

        val sessionEncryption = SessionEncryption(
            role = MdocRole.MDOC,
            eSelfKey = eDeviceKey,
            remotePublicKey = eReaderKey,
            encodedSessionTranscript = Cbor.encode(sessionTranscript)
        )

        val tBeforeInitialPost = Clock.System.now()
        val initialResponse = httpClient.post(readerUrl) {
            contentType(ContentType.Application.Cbor)
            setBody(Cbor.encode(deviceEngagementMessage))
        }
        if (initialResponse.status != HttpStatusCode.OK) {
            throw IllegalStateException("Unexpected HTTP response ${initialResponse.status} from reader")
        }
        var sessionMessage = initialResponse.body<ByteArray>()
        waitingForRequestTime = Clock.System.now() - tBeforeInitialPost

        while (true) {
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
            requestSizeBytes = (requestSizeBytes ?: 0) + messageFromReader.size

            Logger.dCbor(TAG, "DeviceRequest", messageFromReader)
            val deviceRequest = DeviceRequest.fromDataItem(Cbor.decode(messageFromReader))
            val currentRequestDataItem = deviceRequest.toDataItem()
            lastDeviceRequest = currentRequestDataItem
            deviceRequest.verifyReaderAuthentication(sessionTranscript)

            val responseObject = mdocPresentment(
                deviceRequest = deviceRequest,
                eReaderKey = eReaderKey,
                sessionTranscript = sessionTranscript,
                source = source,
                keyAgreementPossible = emptyList(), // TODO: support MacKeys
                requesterAppId = appId,
                requesterOrigin = origin ?: "",
                preselectedDocuments = emptyList(),
                onWaitingForUserInput = {},
                onDocumentsInFocus = onDocumentsInFocus
            )
            userInputTime = (userInputTime ?: Duration.ZERO) + responseObject.durationUserConsentAndAuth

            lastEventData = responseObject.eventData
            val currentResponseDataItem = responseObject.deviceResponse.toDataItem()
            lastDeviceResponse = currentResponseDataItem
            requesterInTrustList = responseObject.eventData.trustMetadata != null

            val encryptStartTime = Clock.System.now()
            val responsePlaintext = Cbor.encode(currentResponseDataItem)
            responseSizeBytes = (responseSizeBytes ?: 0) + responsePlaintext.size
            val encryptedResponse = sessionEncryption.encryptMessage(
                messagePlaintext = responsePlaintext,
                statusCode = null
            )
            responseGenerationTime = (responseGenerationTime ?: Duration.ZERO) +
                responseObject.durationResponseGeneration + (Clock.System.now() - encryptStartTime)

            val tBeforeResponsePost = Clock.System.now()
            val postResponse = httpClient.post(readerUrl) {
                contentType(ContentType.Application.Cbor)
                setBody(encryptedResponse)
            }
            if (postResponse.status != HttpStatusCode.OK) {
                throw IllegalStateException("Unexpected HTTP response ${postResponse.status} from reader")
            }
            sessionMessage = postResponse.body<ByteArray>()
            sendingResponseTime = (sendingResponseTime ?: Duration.ZERO) + (Clock.System.now() - tBeforeResponsePost)

            numRequestsServed += 1
            numDocumentsPresented += responseObject.eventData.requestedDocuments.size
            completedExchanges.add(
                CompletedAnnexAExchange(
                    presentmentData = responseObject.eventData,
                    request = currentRequestDataItem,
                    response = currentResponseDataItem,
                    sessionTranscript = sessionTranscript,
                )
            )
        }
    } catch (e: Throwable) {
        outcome = classifyPresentmentException(e)
        throw e
    } finally {
        telemetry = PresentmentTelemetry(
            protocol = PresentmentProtocol.ISO_18013_7_ANNEX_A,
            outcome = outcome,
            requesterInTrustList = requesterInTrustList,
            requestSizeBytes = requestSizeBytes,
            responseSizeBytes = responseSizeBytes,
            numRequestsServed = numRequestsServed,
            numDocumentsPresented = numDocumentsPresented,
            durationConnectedToRequestReceived = waitingForRequestTime,
            durationUserConsentAndAuth = userInputTime,
            durationResponseGeneration = responseGenerationTime,
            durationResponseTransmission = sendingResponseTime,
            durationTotal = Clock.System.now() - startTime,
        )
        onTelemetry(telemetry)
        if (completedExchanges.isNotEmpty()) {
            for (exchange in completedExchanges) {
                source.eventLogger?.addEventAsync(
                    EventPresentmentIso18013AnnexA(
                        presentmentData = exchange.presentmentData,
                        uri = uri,
                        request = exchange.request,
                        response = exchange.response,
                        sessionTranscript = exchange.sessionTranscript,
                        appId = appId,
                        origin = origin,
                        readerEngagement = readerEngagementDataItemLogged,
                        telemetry = telemetry
                    )
                )
            }
        } else if (outcome != PresentmentOutcome.SUCCESS) {
            source.eventLogger?.addEventAsync(
                EventPresentmentIso18013AnnexA(
                    presentmentData = lastEventData,
                    uri = uri,
                    request = lastDeviceRequest,
                    response = lastDeviceResponse,
                    sessionTranscript = sessionTranscriptDataItem,
                    appId = appId,
                    origin = origin,
                    readerEngagement = readerEngagementDataItemLogged,
                    telemetry = telemetry
                )
            )
        }
    }

    return null
}
