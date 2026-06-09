package org.multipaz.verification

import kotlinx.io.bytestring.ByteString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import org.multipaz.cbor.Cbor
import org.multipaz.cbor.DataItem
import org.multipaz.cbor.Tstr
import org.multipaz.cbor.annotation.CborSerializable
import org.multipaz.crypto.AsymmetricKey
import org.multipaz.crypto.EcPrivateKey
import org.multipaz.crypto.Hpke
import org.multipaz.crypto.JsonWebEncryption
import org.multipaz.openid.OpenID4VP
import org.multipaz.rpc.handler.InvalidRequestException
import org.multipaz.util.fromBase64Url
import org.multipaz.util.toBase64Url
import kotlin.collections.component1
import kotlin.collections.component2

/**
 * A serializable object that encapsulates all the information needed to process a verification
 * request, possibly using multiple protocols.
 *
 * Use [VerificationUtil.generateVerificationSessionForDcql] method to generate an instance of
 * this class that contains a set of requests. Verification requests can be used to ask for
 * credential presentment. Individual requests can be accessed using [find] method
 * (or [getDcRequest] helper method that creates DC API object that can contains requests in
 * several formats). The requests then should be sent to the credential holder, which will
 * generate a single response (also known as "presentation"). Then use an appropriate
 * "processXXXResponse" method to process the response and create a [PresentmentRecord].
 * [PresentmentRecord] then can then be verified either immediately or saved and verified
 * (or re-verified) later and used to extract presented documents and transaction
 * processing results.
 *
 * @param requests individual requests that are semantically equivalent, but formatted to be sent
 *  through various supported protocols
 */
@CborSerializable
class VerificationSession(
    val requests: List<Request>
) {
    /**
     * @return a request of type [T] from the list of [requests] or null if it cannot be found.
     */
    inline fun<reified T: Request> findOrNull(): T? =
        requests.find { it is T } as? T

    /**
     * @return a request of type [T] from the list of [requests]
     * @throws IllegalStateException if the request cannot be found.
     */
    inline fun<reified T: Request> find(): T =
        findOrNull<T>() ?: throw IllegalStateException("No request: ${T::class}")

    /**
     * Generate W3C DC API request.
     *
     * W3C DC API acts as a container layer. It carries one or more parallel requests
     * that are expressed to be processed through a variety of protocols. The credential
     * holder then picks the desired protocol and generates a response.
     *
     * This method generates the DC request that contains DC-API-compatible requests from
     * the [requests] list:
     * - [DcIso18013Request] for use with "org-iso-mdoc" protocol, and/or
     * - [DcOpenID4VPRequest] for use with "openid4vp-v1-signed" or "openid4vp-v1-unsigned"
     *   protocols.
     * - [DcOpenID4VPDraft24Request] for use with "openid4vp" protocol.
     */
    fun getDcRequest(): JsonObject = buildJsonObject {
        putJsonArray("requests") {
            findOrNull<DcIso18013Request>()?.let { request ->
                addJsonObject {
                    put("protocol", "org-iso-mdoc")
                    putJsonObject("data") {
                        put("deviceRequest", Cbor.encode(request.deviceRequest).toBase64Url())
                        put("encryptionInfo", Cbor.encode(request.encryptionInfo).toBase64Url())
                    }
                }
            }
            findOrNull<DcOpenID4VPRequest>()?.let { request ->
                addJsonObject {
                    val parsedRequest =
                        Json.parseToJsonElement(request.openID4VPRequest).jsonObject
                    put("protocol", if (OpenID4VPRequest.isSignedRequest(parsedRequest)) {
                        "openid4vp-v1-signed"
                    } else {
                        "openid4vp-v1-unsigned"
                    })
                    put("data", parsedRequest)
                }
            }
            findOrNull<DcOpenID4VPDraft24Request>()?.let { request ->
                addJsonObject {
                    put("protocol", "openid4vp")
                    put("data", Json.parseToJsonElement(request.openID4VPRequest))
                }
            }
        }
    }

    /**
     * Processes OpenID4VP response for URI scheme request.
     *
     * @param postedData decoded values for parameters posted to response endpoint.
     * @return self-contained verifiable presentation record
     */
    suspend fun processOpenID4VPUriSchemeResponse(
        postedData: Map<String, String>
    ): PresentmentRecord {
        val request = find<OpenID4VPUriSchemeRequest>()
        return processOpenID4VPResponse(request, postedData)
    }

    /**
     * Processes mdoc proximity presentation response.
     *
     * @param deviceResponse response from the device that hold the credential
     * @return [Iso18013PresentmentRecord] that can be verified and used to extract presented data
     *   and transaction data.
     */
    fun processIso18013ProximityResponse(
        deviceResponse: DataItem
    ): Iso18013PresentmentRecord {
        val request = find<Iso18013ProximityRequest>()
        val sessionTranscript = VerificationUtil.proximitySessionTranscript(
            deviceEngagement = request.deviceEngagement,
            handover = request.handover,
            eReaderKey = request.eDeviceKey.publicKey
        )
        return Iso18013PresentmentRecord(
            response = deviceResponse,
            request = request.deviceRequest,
            sessionTranscript = sessionTranscript,
            encryptionInfo = null,
            origin = null,
            eDeviceKey = request.eDeviceKey
        )
    }

    /**
     * Processes the [W3C Digital Credentials API](https://www.w3.org/TR/digital-credentials)
     * response.
     *
     * @param dcResponse W3C DC API response as JSON object
     * @return [PresentmentRecord] object that can be used to verify the presentation and extract
     *   requested claim values
     */
    suspend fun processDcResponse(
        dcResponse: JsonObject,
    ): PresentmentRecord {
        val protocol = (dcResponse["protocol"] as? JsonPrimitive)?.content
            ?: throw IllegalArgumentException("'protocol' is missing or invalid in DC API response")
        val dcData = dcResponse["data"] as? JsonObject
            ?: throw IllegalArgumentException("'data' is missing or invalid in DC API response")
        return when (protocol) {
            "org-iso-mdoc" -> processDcIso18013Response(dcData)
            "openid4vp" -> processDcOpenID4VPResponse(dcData, find<DcOpenID4VPDraft24Request>())
            "openid4vp-v1-unsigned",
            "openid4vp-v1-signed" -> processDcOpenID4VPResponse(dcData, find<DcOpenID4VPRequest>())
            else -> throw IllegalArgumentException("unknown protocol: '$protocol'")
        }
    }

    /**
     * Serializes this object to a string when serialization to a binary cannot be used.
     *
     * In most cases this class should be serialized as binary using [toCbor] method instead.
     *
     * It can be deserialized using [VerificationSession.deserializeFromString].
     *
     * @return string serialization
     */
    fun serializeToString() = toCbor().toBase64Url()

    /**
     * Describes available verification request types.
     *
     * When a [VerificationSession] is created, requests of one or more types are generated and
     * passed to a credential holder.
     */
    enum class RequestType {
        /**
         * Generates [DcIso18013Request] (typically accessed using
         * [VerificationSession.getDcRequest] method), use [VerificationSession.processDcResponse]
         * to process the response.
         */
        DC_ISO_18013,
        /**
         * Generates [DcOpenID4VPRequest] (typically accessed using
         * [VerificationSession.getDcRequest] method), use [VerificationSession.processDcResponse]
         * to process the response.
         *
         * The request is generated with [OpenID4VP.Version.DRAFT_29]. The W3C DC API protocol
         * value is `openid4vp-v1-signed` or `openid4vp-v1-unsigned` depending on whether the
         * request is signed.
         */
        DC_OPENID4VP,
        /**
         * Like [DC_OPENID4VP], but the request is generated with [OpenID4VP.Version.DRAFT_24]
         * and emitted under the W3C DC API protocol value `openid4vp` regardless of signing.
         * Produces a [DcOpenID4VPDraft24Request]; use [VerificationSession.processDcResponse]
         * to process the response.
         */
        DC_OPENID4VP_DRAFT_24,
        /**
         * Generates [OpenID4VPUriSchemeRequest], use
         * [VerificationSession.processOpenID4VPUriSchemeResponse] to process the response.
         */
        OPENID4VP_URI_SCHEME,
        /**
         * Generates [Iso18013ProximityRequest], use
         * [VerificationSession.processIso18013ProximityResponse] to process the response.
         */
        ISO_18013_PROXIMITY,
    }

    /**
     * Ready-to-use request of a particular type and auxiliary data required for the response
     * verification.
     */
    @CborSerializable
    sealed class Request {
        /**
         * Type of this request.
         */
        abstract val requestType: RequestType
        companion object
    }

    /**
     * Request of OpenID4VP family type.
     */
    sealed class OpenID4VPRequest: Request() {
        /**
         * Requestor id, should be set to origin for W3C DC API request and client id for
         * custom URI schema request.
         */
        abstract val requestorId: String

        /**
         * Private key to decrypt the response.
         */
        abstract val responseEncryptionKey: EcPrivateKey?

        /**
         * OpenID4VP request as serialized JSON.
         *
         * NB: when the request is signed, it is expressed as an object with the single key named
         * "request" and the value being signed JWT serialized as string.
         */
        abstract val openID4VPRequest: String

        companion object {
            /**
             * Heuristic to determine if the request was signed.
             *
             * We want to have a single place where it is defined in case it needs to be tweaked.
             *
             * @param parsedRequest [OpenID4VPRequest.openID4VPRequest] parsed as JSON object
             * @return whether the request was signed
             */
            fun isSignedRequest(parsedRequest: JsonObject): Boolean =
                !parsedRequest.containsKey("dcql_query")
        }
    }

    /**
     * Request of ISO/IEC 18013 family type.
     */
    sealed class Iso18013Request: Request() {
        /**
         * Private key to decrypt the response.
         */
        abstract val responseEncryptionKey: EcPrivateKey?

        /**
         * Request as CBOR data.
         */
        abstract val deviceRequest: DataItem
    }

    /**
     * ISO/IEC 18013 request using W3C DC API.
     *
     * @property origin server domain for web or app identifier for app-to-app presentations.
     * @param encryptionInfo ISO/IEC 18013 encryption info data
     */
    data class DcIso18013Request(
        val origin: String,
        override val responseEncryptionKey: EcPrivateKey,
        override val deviceRequest: DataItem,
        val encryptionInfo: DataItem
    ): Iso18013Request() {
        override val requestType: RequestType get() = RequestType.DC_ISO_18013
    }

    /**
     * OpenID4VP request using W3C DC API, generated with [OpenID4VP.Version.DRAFT_29].
     */
    data class DcOpenID4VPRequest(
        override val requestorId: String,
        override val responseEncryptionKey: EcPrivateKey?,
        override val openID4VPRequest: String,
    ): OpenID4VPRequest() {
        override val requestType: RequestType get() = RequestType.DC_OPENID4VP
    }

    /**
     * OpenID4VP request using W3C DC API, generated with [OpenID4VP.Version.DRAFT_24]. The W3C DC
     * API protocol value is `openid4vp` regardless of whether the request is signed.
     */
    data class DcOpenID4VPDraft24Request(
        override val requestorId: String,
        override val responseEncryptionKey: EcPrivateKey?,
        override val openID4VPRequest: String,
    ): OpenID4VPRequest() {
        override val requestType: RequestType get() = RequestType.DC_OPENID4VP_DRAFT_24
    }

    /**
     * OpenID4VP request using custom URI scheme.
     */
    data class OpenID4VPUriSchemeRequest(
        override val requestorId: String,
        override val responseEncryptionKey: EcPrivateKey?,
        override val openID4VPRequest: String,
    ): OpenID4VPRequest() {
        override val requestType: RequestType get() = RequestType.OPENID4VP_URI_SCHEME
    }


    /**
     * ISO/IEC 18013 proximity request.
     *
     * @param eDeviceKey proximity message encryption key
     * @param handover device handover data
     * @param deviceEngagement device engagement data
     */
    data class Iso18013ProximityRequest(
        val eDeviceKey: EcPrivateKey,
        val handover: DataItem,
        val deviceEngagement: ByteString,
        override val deviceRequest: DataItem
    ): Iso18013Request() {
        override val requestType: RequestType get() = RequestType.ISO_18013_PROXIMITY
        override val responseEncryptionKey: EcPrivateKey? get() = null

        /** Session transcript */
        val sessionTranscript: DataItem get() =
            VerificationUtil.proximitySessionTranscript(
                deviceEngagement = deviceEngagement,
                eReaderKey = eDeviceKey.publicKey,
                handover = handover
            )
    }

    private suspend fun processDcOpenID4VPResponse(
        dcData: JsonObject,
        request: OpenID4VPRequest,
    ): PresentmentRecord {
        return processOpenID4VPResponse(request, dcData.mapValues { (_, value) ->
            if (value is JsonPrimitive) {
                value.content
            } else {
                value.toString()
            }
        })
    }

    private suspend fun processOpenID4VPResponse(
        request: OpenID4VPRequest,
        dcData: Map<String, String>
    ): PresentmentRecord {
        val responseEncryptionKey = request.responseEncryptionKey
        val vpToken = if (responseEncryptionKey != null) {
            val encryptedData = dcData["response"]
                ?: throw InvalidRequestException("'response' parameter is missing")
            val decryptedResponse = JsonWebEncryption.decrypt(
                encryptedData,
                AsymmetricKey.anonymous(
                    privateKey = responseEncryptionKey,
                    algorithm = responseEncryptionKey.curve.defaultKeyAgreementAlgorithm
                )
            ).jsonObject
            decryptedResponse["vp_token"]?.toString()
                ?: throw IllegalArgumentException("'vp_token' is missing or invalid")
        } else {
            dcData["vp_token"] ?: run {
                val decodedResponse = Json.parseToJsonElement(
                    dcData["response"]!!.split('.')[1].fromBase64Url().decodeToString()).jsonObject
                decodedResponse["vp_token"]?.toString()
            } ?: throw IllegalArgumentException("'vp_token' is missing or invalid")
        }
        return processOpenID4VPToken(request, vpToken)
    }

    private suspend fun processDcIso18013Response(dcData: JsonObject): PresentmentRecord {
        val request = find<DcIso18013Request>()
        val response = dcData["response"]!!.jsonPrimitive.content.fromBase64Url()
        val array = Cbor.decode(response).asArray
        if (array.first().asTstr != "dcapi") {
            throw IllegalArgumentException("excepted dcapi as first array element")
        }
        val encryptionParameters = array[1].asMap
        val enc = encryptionParameters[Tstr("enc")]!!.asBstr
        val cipherText = encryptionParameters[Tstr("cipherText")]!!.asBstr

        val sessionTranscript = VerificationUtil.dcSessionTranscript(request.encryptionInfo, request.origin)

        val decrypter = Hpke.getDecrypter(
            cipherSuite = Hpke.CipherSuite.DHKEM_P256_HKDF_SHA256_HKDF_SHA256_AES_128_GCM,
            receiverPrivateKey = AsymmetricKey.AnonymousExplicit(request.responseEncryptionKey),
            encapsulatedKey = enc,
            info = Cbor.encode(sessionTranscript)
        )
        val deviceResponseRaw = decrypter.decrypt(ciphertext = cipherText, aad = byteArrayOf())

        return Iso18013PresentmentRecord(
            response = Cbor.decode(deviceResponseRaw),
            request = request.deviceRequest,
            origin = request.origin,
            sessionTranscript = sessionTranscript,
            encryptionInfo = ByteString(Cbor.encode(request.encryptionInfo)),
            eDeviceKey = null
        )
    }

    private suspend fun processOpenID4VPToken(
        request: OpenID4VPRequest,
        vpToken: String
    ): PresentmentRecord {
        val parsedRequest = Json.parseToJsonElement(request.openID4VPRequest).jsonObject
        val isSigned = OpenID4VPRequest.isSignedRequest(parsedRequest)
        val jsonRequest = if (isSigned) {
            // This got to be signed request, extract JWT body
            val jwtParts = parsedRequest["request"]!!.jsonPrimitive.content.split('.')
            Json.parseToJsonElement(jwtParts[1].fromBase64Url().decodeToString()).jsonObject
        } else {
            parsedRequest
        }
        val queryData = QueryData.fromDcql(jsonRequest["dcql_query"]!!.jsonObject)
        val nonceFromRequest = jsonRequest["nonce"]!!.jsonPrimitive.content
        val sessionTranscript = if (queryData.find { it is MdocQueryData } == null) {
            // No mdocs, session transcript is not needed
            null
        } else if (request is DcOpenID4VPDraft24Request) {
            // OpenID4VP Draft 24 over W3C DC API: handover info is [origin, clientId, nonce]
            // where clientId is the request's client_id for signed requests and the synthetic
            // `web-origin:<origin>` for unsigned requests.
            val effectiveClientId = if (isSigned) {
                jsonRequest["client_id"]!!.jsonPrimitive.content
            } else {
                "web-origin:${request.requestorId}"
            }
            VerificationUtil.vpSessionTranscriptDraft24(
                origin = request.requestorId,
                clientId = effectiveClientId,
                nonce = nonceFromRequest
            )
        } else {
            VerificationUtil.vpSessionTranscript(
                encryptionPrivateKey = request.responseEncryptionKey,
                requestorId = request.requestorId,
                nonce = nonceFromRequest,
                responseUri = jsonRequest["response_uri"]?.jsonPrimitive?.content
            )
        }
        return OpenID4VPPresentmentRecord(
            vpToken = vpToken,
            vpRequest = jsonRequest.toString(),
            mdocSessionTranscript = sessionTranscript,
        )
    }

    companion object {

        /**
         * Deserializes [VerificationSession] from a string produced by
         * [VerificationSession.serializeToString].
         *
         * @return deserialized [VerificationSession] object
         */
        fun deserializeFromString(serialized: String): VerificationSession =
            VerificationSession.fromCbor(serialized.fromBase64Url())
    }
}