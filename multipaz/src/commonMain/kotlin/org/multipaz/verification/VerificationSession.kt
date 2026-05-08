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
import org.multipaz.cbor.Simple
import org.multipaz.cbor.Tagged
import org.multipaz.cbor.Tstr
import org.multipaz.cbor.addCborArray
import org.multipaz.cbor.addCborMap
import org.multipaz.cbor.annotation.CborSerializable
import org.multipaz.cbor.buildCborArray
import org.multipaz.cbor.toDataItem
import org.multipaz.crypto.Algorithm
import org.multipaz.crypto.AsymmetricKey
import org.multipaz.crypto.Crypto
import org.multipaz.crypto.EcCurve
import org.multipaz.crypto.EcPrivateKey
import org.multipaz.crypto.EcPublicKey
import org.multipaz.crypto.Hpke
import org.multipaz.crypto.JsonWebEncryption
import org.multipaz.documenttype.DocumentTypeRepository
import org.multipaz.mdoc.request.buildDeviceRequestFromDcql
import org.multipaz.openid.OpenID4VP
import org.multipaz.presentment.TransactionDataJson
import org.multipaz.presentment.TransactionDataJson.Companion.convertToDocRequestOtherInfo
import org.multipaz.rpc.handler.InvalidRequestException
import org.multipaz.util.fromBase64Url
import org.multipaz.util.toBase64Url
import kotlin.collections.component1
import kotlin.collections.component2
import kotlin.random.Random

/**
 * A serializable object that encapsulates all the information needed to process a verification
 * request, possibly using multiple protocols.
 *
 * Use [VerificationSession.create] method to generate an instance of this class that contains
 * a set of requests. Verification requests can be used to ask for credential presentment.
 * Individual requests can be accessed using [find] method (or [getDcRequest] helper method
 * that creates DC API object that can contains requests in several formats). The requests
 * then should be sent to the credential holder, which will generate a single response (also
 * known as "presentation"). Then use an appropriate "processXXXResponse" method to process the
 * response and create a [PresentmentRecord]. [PresentmentRecord] then can then be verified either
 * immediately or saved and verified (or re-verified) later and used to extract presented
 * documents and transaction processing results.
 *
 * @param requests individual requests that are semantically equivalent, but formatted to be sent
 *  through various supported protocols
 * @param signed whether the verification request(s) are signed
 * @param requestDefinition [RequestDefinition] that describes the semantics of the verification
 *  request being processed
 */
@CborSerializable
class VerificationSession(
    val requests: List<Request>,
    val signed: Boolean,
    val requestDefinition: RequestDefinition?,
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
                    put("protocol", if (signed) {
                        "openid4vp-v1-signed"
                    } else {
                        "openid4vp-v1-unsigned"
                    })
                    put("data", Json.parseToJsonElement(request.openID4VPRequest))
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
     * @return [MdocPresentmentRecord] that can be verified and used to extract presented data
     *   and transaction data.
     */
    fun processIso18013ProximityResponse(
        deviceResponse: DataItem
    ): MdocPresentmentRecord {
        val request = find<Iso18013ProximityRequest>()
        val sessionTranscript = proximitySessionTranscript(
            deviceEngagement = request.deviceEngagement,
            handover = request.handover,
            eReaderKey = request.eDeviceKey.publicKey
        )
        return MdocPresentmentRecord(
            response = deviceResponse,
            request = request.deviceRequest,
            sessionTranscript = sessionTranscript,
            requestDefinition = requestDefinition,
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
    }

    /**
     * Request of ISO/IEC 18013 family type.
     */
    sealed class Iso18013Request: Request() {
        /**
         * Private key to decrypt the response.
         */
        abstract val responseEncryptionKey: EcPrivateKey

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
        override val responseEncryptionKey: EcPrivateKey,
        override val deviceRequest: DataItem
    ): Iso18013Request() {
        override val requestType: RequestType get() = RequestType.ISO_18013_PROXIMITY

        /** Session transcript */
        val sessionTranscript: DataItem get() =
            proximitySessionTranscript(deviceEngagement, eDeviceKey.publicKey, handover)
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

        val sessionTranscript = dcSessionTranscript(request.encryptionInfo, request.origin)

        val decrypter = Hpke.getDecrypter(
            cipherSuite = Hpke.CipherSuite.DHKEM_P256_HKDF_SHA256_HKDF_SHA256_AES_128_GCM,
            receiverPrivateKey = AsymmetricKey.AnonymousExplicit(request.responseEncryptionKey),
            encapsulatedKey = enc,
            info = Cbor.encode(sessionTranscript)
        )
        val deviceResponseRaw = decrypter.decrypt(ciphertext = cipherText, aad = byteArrayOf())

        return MdocPresentmentRecord(
            response = Cbor.decode(deviceResponseRaw),
            request = request.deviceRequest,
            requestDefinition = requestDefinition,
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
        val vpRequest = if (signed) {
            // Signed request, extract JWT body
            val parsedRequest = Json.parseToJsonElement(request.openID4VPRequest).jsonObject
            val jwtParts = parsedRequest["request"]!!.jsonPrimitive.content.split('.')
            jwtParts[1].fromBase64Url().decodeToString()
        } else {
            request.openID4VPRequest
        }
        val jsonRequest = Json.parseToJsonElement(vpRequest).jsonObject
        val queryData = QueryData.fromDcql(jsonRequest["dcql_query"]!!.jsonObject)
        val nonceFromRequest = jsonRequest["nonce"]!!.jsonPrimitive.content
        val sessionTranscript = if (queryData.find { it is MdocQueryData } == null) {
            // No mdocs, session transcript is not needed
            null
        } else if (request is DcOpenID4VPDraft24Request) {
            // OpenID4VP Draft 24 over W3C DC API: handover info is [origin, clientId, nonce]
            // where clientId is the request's client_id for signed requests and the synthetic
            // `web-origin:<origin>` for unsigned requests.
            val effectiveClientId = if (signed) {
                jsonRequest["client_id"]!!.jsonPrimitive.content
            } else {
                "web-origin:${request.requestorId}"
            }
            vpSessionTranscriptDraft24(
                origin = request.requestorId,
                clientId = effectiveClientId,
                nonce = nonceFromRequest
            )
        } else {
            vpSessionTranscript(
                encryptionPrivateKey = request.responseEncryptionKey,
                requestorId = request.requestorId,
                nonce = nonceFromRequest,
                responseUri = jsonRequest["response_uri"]?.jsonPrimitive?.content
            )
        }
        return OpenID4VPPresentmentRecord(
            vpToken = vpToken,
            vpRequest = vpRequest,
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

        /**
         * Makes a request for credential presentation using
         * [W3C Digital Credentials API](https://www.w3.org/TR/digital-credentials)
         *
         * Single DC request can contain sections for several protocols, the response always
         * comes using only one of the requested protocols.
         *
         * @param requestTypes list of request types that should be created; multiple requests can
         *  be created and sent, typically only one of these requests will be responded to
         * @param requestDefinition semantics of the request: what credentials and claim to query
         *  and associated transaction data
         * @param origin protocol and authority of the server that makes the request (e.g.
         *   `https://example.com:8000`) or an appropriate platform-specific origin for
         *   app-to-app requests
         * @param clientId OpenID4VP client id, must be non-null for signed request
         * @param readerAuthenticationKey certified key to sign the request, if null the request
         *   is unsigned
         * @param nonce nonce to use, for OpenID4VP it will be Base64Url encoded
         * @param encryptResponse true if response must be encrypted
         * @param deviceEngagement ISO 18013-5 device engagement data
         * @param handover ISO 18013-5 handover data
         * @param documentTypeRepository repository that contains all transaction types for
         *   transaction data that are used in the request; if no transaction data is used it can
         *   be `null`
         * @param state optional state parameter for OpenID4VP protocol
         * @return a pair of the DC request and the session that is needed to process the response
         *  to that request
         */
        suspend fun create(
            requestTypes: Collection<RequestType>,
            requestDefinition: RequestDefinition,
            readerAuthenticationKey: AsymmetricKey.X509Compatible?,
            origin: String? = null,
            clientId: String? = null,
            nonce: ByteString = ByteString(Random.nextBytes(18)),
            encryptResponse: Boolean = true,
            responseUri: String? = null,
            documentTypeRepository: DocumentTypeRepository? = null,
            deviceEngagement: ByteString? = null,
            handover: DataItem? = null,
            eReaderKey: EcPrivateKey? = null,
            state: String? = null
        ): VerificationSession {
            val encryptionPrivateKey = if (encryptResponse) {
                Crypto.createEcPrivateKey(EcCurve.P256)
            } else {
                null
            }
            requestDefinition as DcqlRequestDefinition
            val dcql = Json.parseToJsonElement(requestDefinition.dcql).jsonObject
            val nonceStr = nonce.toByteArray().toBase64Url()

            val docRequestOtherInfo = if (requestDefinition.transactionData.isNullOrEmpty()) {
                null
            } else {
                lazy {
                    TransactionDataJson.parse(
                        base64UrlEncodedJson = requestDefinition.transactionData.map {
                            it.encodeToByteArray().toBase64Url()
                        },
                        documentTypeRepository = documentTypeRepository!!
                    ).mapValues { (_, transactionData) ->
                        transactionData.convertToDocRequestOtherInfo()
                    }
                }
            }

            suspend fun createOpenIDRequest(
                responseMode: OpenID4VP.ResponseMode,
                responseUri: String? = null,
                version: OpenID4VP.Version = OpenID4VP.Version.DRAFT_29,
            ): String = OpenID4VP.generateRequest(
                    version = version,
                    dcqlQuery = dcql,
                    jsonTransactionData = requestDefinition.transactionData ?: emptyList(),
                    nonce = nonceStr,
                    state = state,
                    origin = origin
                        ?: throw IllegalArgumentException("'origin' is required for OpenID4VP"),
                    clientId = clientId,
                    responseEncryptionKey = encryptionPrivateKey?.publicKey,
                    requestSigningKey = readerAuthenticationKey,
                    responseMode = responseMode,
                    responseUri = responseUri
                ).toString()

            suspend fun createIso18013Request(
                sessionTranscript: DataItem
            ): DataItem = buildDeviceRequestFromDcql(
                sessionTranscript = sessionTranscript,
                dcql = dcql,
                docRequestOtherInfo = docRequestOtherInfo?.value ?: emptyMap(),
            ) {
                readerAuthenticationKey?.let { addReaderAuthAll(it) }
            }.toDataItem()

            val requests = requestTypes.map { requestType ->
                when (requestType) {
                    RequestType.DC_OPENID4VP ->
                        DcOpenID4VPRequest(
                            requestorId = origin
                                ?: throw IllegalArgumentException("'origin' is required for DC API"),
                            responseEncryptionKey = encryptionPrivateKey,
                            openID4VPRequest = createOpenIDRequest(
                                responseMode = OpenID4VP.ResponseMode.DC_API
                            )
                        )

                    RequestType.DC_OPENID4VP_DRAFT_24 ->
                        DcOpenID4VPDraft24Request(
                            requestorId = origin
                                ?: throw IllegalArgumentException("'origin' is required for DC API"),
                            responseEncryptionKey = encryptionPrivateKey,
                            openID4VPRequest = createOpenIDRequest(
                                responseMode = OpenID4VP.ResponseMode.DC_API,
                                version = OpenID4VP.Version.DRAFT_24,
                            )
                        )

                    RequestType.OPENID4VP_URI_SCHEME ->
                        OpenID4VPUriSchemeRequest(
                            requestorId = clientId
                                ?: throw IllegalArgumentException("clientId must be specified"),
                            responseEncryptionKey = encryptionPrivateKey,
                            openID4VPRequest = createOpenIDRequest(
                                responseMode = OpenID4VP.ResponseMode.DIRECT_POST,
                                responseUri = responseUri
                                    ?: throw IllegalArgumentException("responseUri must be specified")
                            )
                        )

                    RequestType.DC_ISO_18013 -> {
                        if (encryptionPrivateKey == null) {
                            throw IllegalArgumentException("encryptResponse must be true")
                        }
                        val encryptionInfo = buildCborArray {
                            add("dcapi")
                            addCborMap {
                                put("nonce", nonce.toByteArray())
                                put("recipientPublicKey", encryptionPrivateKey.publicKey.toCoseKey().toDataItem())
                            }
                        }
                        DcIso18013Request(
                            origin = origin
                                ?: throw IllegalArgumentException("'origin' is required for DC API"),
                            responseEncryptionKey = encryptionPrivateKey,
                            deviceRequest = createIso18013Request(
                                sessionTranscript = dcSessionTranscript(encryptionInfo, origin)
                            ),
                            encryptionInfo = encryptionInfo
                        )
                    }

                    RequestType.ISO_18013_PROXIMITY -> {
                        val sessionTranscript = proximitySessionTranscript(
                            deviceEngagement = deviceEngagement!!,
                            handover = handover!!,
                            eReaderKey = eReaderKey!!.publicKey
                        )
                        Iso18013ProximityRequest(
                            deviceEngagement = deviceEngagement,
                            handover = handover,
                            eDeviceKey = eReaderKey,
                            responseEncryptionKey = encryptionPrivateKey
                                ?: throw IllegalArgumentException("encryptResponse must be true"),
                            deviceRequest = createIso18013Request(
                                sessionTranscript = sessionTranscript
                            )
                        )
                    }
                }
            }

            return VerificationSession(
                signed = readerAuthenticationKey != null,
                requests = requests,
                requestDefinition = requestDefinition
            )
        }

        private fun proximitySessionTranscript(
            deviceEngagement: ByteString,
            eReaderKey: EcPublicKey,
            handover: DataItem
        ): DataItem {
            val encodedEReaderKey = Cbor.encode(eReaderKey.toCoseKey().toDataItem())
            return buildCborArray {
                add(Tagged(Tagged.ENCODED_CBOR, deviceEngagement.toByteArray().toDataItem()))
                add(Tagged(Tagged.ENCODED_CBOR, encodedEReaderKey.toDataItem()))
                add(handover)
            }
        }

        private suspend fun dcSessionTranscript(
            encryptionInfo: DataItem,
            origin: String
        ): DataItem {
            val encodedEncryptionInfo = Cbor.encode(encryptionInfo)
            val dcapiInfo = buildCborArray {
                add(encodedEncryptionInfo.toBase64Url())
                add(origin)
            }
            val dcapiInfoDigest = Crypto.digest(Algorithm.SHA256, Cbor.encode(dcapiInfo))
            return buildCborArray {
                add(Simple.NULL) // DeviceEngagementBytes
                add(Simple.NULL) // EReaderKeyBytes
                addCborArray {
                    add("dcapi")
                    add(dcapiInfoDigest)
                }
            }
        }

        private suspend fun vpSessionTranscriptDraft24(
            origin: String,
            clientId: String,
            nonce: String
        ): DataItem {
            val handoverInfo = Cbor.encode(
                buildCborArray {
                    add(origin)
                    add(clientId)
                    add(nonce)
                }
            )
            val handoverInfoDigest = Crypto.digest(Algorithm.SHA256, handoverInfo)
            return buildCborArray {
                add(Simple.NULL) // DeviceEngagementBytes
                add(Simple.NULL) // EReaderKeyBytes
                addCborArray {
                    add("OpenID4VPDCAPIHandover")
                    add(handoverInfoDigest)
                }
            }
        }

        private suspend fun vpSessionTranscript(
            encryptionPrivateKey: EcPrivateKey?,
            requestorId: String,
            nonce: String,
            responseUri: String?
        ): DataItem {
            val jwkThumbPrint = encryptionPrivateKey?.publicKey
                ?.toJwkThumbprint(Algorithm.SHA256)?.toByteArray()?.toDataItem()
            val handoverInfo = Cbor.encode(
                buildCborArray {
                    add(requestorId)
                    add(nonce)
                    add(jwkThumbPrint ?: Simple.NULL)
                    responseUri?.let { add(it) }
                }
            )
            val handoverInfoDigest = Crypto.digest(Algorithm.SHA256, handoverInfo)
            return buildCborArray {
                add(Simple.NULL) // DeviceEngagementBytes
                add(Simple.NULL) // EReaderKeyBytes
                addCborArray {
                    val handoverId = if (responseUri == null) {
                        "OpenID4VPDCAPIHandover"
                    } else {
                        "OpenID4VPHandover"
                    }
                    add(handoverId)
                    add(handoverInfoDigest)
                }
            }
        }
    }
}