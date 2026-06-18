package org.multipaz.verification

import kotlinx.io.bytestring.ByteString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import org.multipaz.cbor.Bstr
import org.multipaz.cbor.Cbor
import org.multipaz.cbor.DataItem
import org.multipaz.cbor.Simple
import org.multipaz.cbor.Tagged
import org.multipaz.cbor.Tstr
import org.multipaz.cbor.addCborArray
import org.multipaz.cbor.addCborMap
import org.multipaz.cbor.buildCborArray
import org.multipaz.cbor.toDataItem
import org.multipaz.claim.JsonClaim
import org.multipaz.claim.MdocClaim
import org.multipaz.crypto.Algorithm
import org.multipaz.crypto.Crypto
import org.multipaz.crypto.EcPublicKey
import org.multipaz.crypto.Hpke
import org.multipaz.crypto.JsonWebEncryption
import org.multipaz.crypto.AsymmetricKey
import org.multipaz.crypto.EcCurve
import org.multipaz.crypto.EcPrivateKey
import org.multipaz.documenttype.DocumentTypeRepository
import org.multipaz.mdoc.request.DeviceRequest
import org.multipaz.mdoc.request.DeviceRequestInfo
import org.multipaz.mdoc.request.DocRequestInfo
import org.multipaz.mdoc.request.DocumentSet
import org.multipaz.mdoc.request.UseCase
import org.multipaz.mdoc.request.ZkRequest
import org.multipaz.mdoc.request.buildDeviceRequest
import org.multipaz.mdoc.request.buildDeviceRequestFromDcql
import org.multipaz.mdoc.response.DeviceResponse
import org.multipaz.mdoc.zkp.ZkSystemRepository
import org.multipaz.mdoc.zkp.ZkSystemSpec
import org.multipaz.openid.OpenID4VP
import org.multipaz.presentment.TransactionData
import org.multipaz.presentment.TransactionDataJson
import org.multipaz.presentment.TransactionDataJson.Companion.convertToDocRequestOtherInfo
import org.multipaz.request.JsonRequestedClaim
import org.multipaz.request.MdocRequestedClaim
import org.multipaz.sdjwt.SdJwt
import org.multipaz.sdjwt.SdJwtKb
import org.multipaz.util.Logger
import org.multipaz.util.fromBase64Url
import org.multipaz.util.toBase64Url
import org.multipaz.util.zlibInflate
import kotlin.collections.component1
import kotlin.collections.component2
import kotlin.coroutines.cancellation.CancellationException
import kotlin.random.Random
import kotlin.time.Instant

private const val TAG = "VerificationUtil"

/**
 * Utility functions for requesting and verifying credentials.
 */
object VerificationUtil {
    /**
     * Utility function to generate a W3C Digital Credentials API request for requesting a single ISO mdoc credential.
     *
     * The request can expressed for multiple exchange protocols simultaneously, for example OpenID4VP 1.0 and
     * ISO/IEC 18013:2025 Annex C.
     *
     * The following exchange protocols are supported by this function
     * - org-iso-mdoc
     * - openid4vp
     * - openid4vp-v1-signed
     * - openid4vp-v1-unsigned
     *
     * This can be used on the server-side to generate the request. The resulting [JsonObject] can be serialized
     * to a string using [Json.encodeToString] and sent to the browser or requesting app which can pass it to
     * `navigator.credentials.get()` or its native Credential Manager implementation.
     *
     * @param exchangeProtocols a list of W3C Exchange Protocol strings to generate requests for. The order of
     *   requests in the resulting JSON will match the order in this list.
     * @param docType the ISO mdoc document type, e.g. "org.iso.18013.5.1.mDL".
     * @param claims the namespaces and data elements to request.
     * @param nonce the nonce to use. For OpenID4VP, this will be base64url-encoded without padding. For mdoc-api
     *   this will be used as is.
     * @param origin the origin to use.
     * @param responseEncryptionKey the key to encrypt the response against or `null` to not encrypt the response.
     *   Note that in some protocols encryption of the response is mandatory and this will throw [IllegalArgumentException]
     *   if this is `null` for such protocols
     * @param verifierIdentities a list of verifier identities used to sign the request; empty list
     *  for unsigned request
     * @param zkSystemSpecs if non-empty, request a ZK proof using these systems.
     * @param jsonTransactionData JSON-formatted transaction data, *before* base64url encoding,
     *   see OpenID4VP 1.0 section 8.4.
     * @param docRequestOtherInfo transaction data encoded for use in requestInfo` map in ISO 18013-7.
     * @return a [JsonObject] with the request.
     */
    @Throws(CancellationException::class)
    suspend fun generateDcRequestMdoc(
        exchangeProtocols: List<String>,
        docType: String,
        claims: List<MdocRequestedClaim>,
        nonce: ByteString,
        origin: String,
        responseEncryptionKey: EcPublicKey?,
        verifierIdentities: List<VerifierIdentity>,
        zkSystemSpecs: List<ZkSystemSpec>,
        jsonTransactionData: List<String> = emptyList(),
        docRequestOtherInfo: Map<String, DataItem> = emptyMap()
    ): JsonObject {
        val requests = exchangeProtocols.map { exchangeProtocol ->
            generateSingleRequest(
                exchangeProtocol = exchangeProtocol,
                docType = docType,
                claims = claims,
                docFormat = null,
                dataElementIdentifierMapping = emptyMap(),
                nonce = nonce,
                origin = origin,
                responseEncryptionKey = responseEncryptionKey,
                verifierIdentities = verifierIdentities,
                zkSystemSpecs = zkSystemSpecs,
                jsonTransactionData = jsonTransactionData,
                docRequestOtherInfo = docRequestOtherInfo
            )
        }
        return buildJsonObject {
            put("requests", JsonArray(requests))
        }
    }

    /**
     * Utility function to generate a W3C Digital Credentials API request for requesting credentials.
     *
     * The request can expressed for multiple exchange protocols simultaneously, for example OpenID4VP 1.0 and
     * ISO/IEC 18013:2025 Annex C. In the ISO 18013-5 case the DCQL is converted using the
     * [buildDeviceRequestFromDcql].
     *
     * The following exchange protocols are supported by this function
     * - org-iso-mdoc
     * - openid4vp
     * - openid4vp-v1-signed
     * - openid4vp-v1-unsigned
     *
     * This can be used on the server-side to generate the request. The resulting [JsonObject] can be serialized
     * to a string using [Json.encodeToString] and sent to the browser or requesting app which can pass it to
     * `navigator.credentials.get()` or its native Credential Manager implementation.
     *
     * @param exchangeProtocols a list of W3C Exchange Protocol strings to generate requests for. The order of
     *   requests in the resulting JSON will match the order in this list.
     * @param dcql the DCQL to use.
     * @param nonce the nonce to use. For OpenID4VP, this will be base64url-encoded without padding. For mdoc-api
     *   this will be used as is.
     * @param origin the origin to use.
     * @param responseEncryptionKey the key to encrypt the response against or `null` to not encrypt the response.
     *   Note that in some protocols encryption of the response is mandatory and this will throw [IllegalArgumentException]
     *   if this is `null` for such protocols
     * @param verifierIdentities a list of verifier identities used to sign the request; empty list
     *  for unsigned request
     * @param jsonTransactionData JSON-formatted transaction data, *before* base64url encoding,
     *   see OpenID4VP 1.0 section 8.4.
     * @param docRequestOtherInfo transaction data encoded for use in requestInfo map in ISO 18013-7.
     * @param state optional state parameter defined in OpenID4VP and ISO 18013-7 that is then
     *   included in the presentation
     * @throws IllegalArgumentException if [dcql] contains features not supported by [DeviceRequest], for
     *   example a request for credentials that aren't ISO mdocs.
     * @return a [JsonObject] with the request.
     */
    @Throws(IllegalArgumentException::class, CancellationException::class)
    suspend fun generateDcRequestDcql(
        exchangeProtocols: List<String>,
        dcql: JsonObject,
        nonce: ByteString,
        origin: String,
        responseEncryptionKey: EcPublicKey?,
        verifierIdentities: List<VerifierIdentity>,
        jsonTransactionData: List<String> = emptyList(),
        docRequestOtherInfo: Map<String, Map<String, DataItem>> = emptyMap(),
        state: String? = null
    ): JsonObject {
        val requests = exchangeProtocols.map { exchangeProtocol ->
            generateSingleRequestDcql(
                exchangeProtocol = exchangeProtocol,
                dcql = dcql,
                nonce = nonce,
                origin = origin,
                responseEncryptionKey = responseEncryptionKey,
                verifierIdentities = verifierIdentities,
                jsonTransactionData = jsonTransactionData,
                docRequestOtherInfo = docRequestOtherInfo,
                state = state
            )
        }
        return buildJsonObject {
            put("requests", JsonArray(requests))
        }
    }

    internal suspend fun generateSingleRequestDcql(
        exchangeProtocol: String,
        dcql: JsonObject,
        nonce: ByteString,
        origin: String,
        responseEncryptionKey: EcPublicKey?,
        verifierIdentities: List<VerifierIdentity>,
        jsonTransactionData: List<String>,
        docRequestOtherInfo: Map<String, Map<String, DataItem>>,
        state: String?
    ): JsonObject = buildJsonObject {
        put("protocol", exchangeProtocol)
        when (exchangeProtocol) {
            "openid4vp",
            "openid4vp-v1-unsigned",
            "openid4vp-v1-signed" -> {
                put(
                    "data",
                    OpenID4VP.generateRequest(
                        version = if (exchangeProtocol == "openid4vp") {
                            OpenID4VP.Version.DRAFT_24
                        } else {
                            OpenID4VP.Version.DRAFT_29
                        },
                        origin = origin,
                        nonce = nonce.toByteArray().toBase64Url(),
                        state = state,
                        responseEncryptionKey = responseEncryptionKey,
                        verifierIdentities = verifierIdentities,
                        responseMode = OpenID4VP.ResponseMode.DC_API,
                        responseUri = null,
                        dcqlQuery = dcql,
                        jsonTransactionData = jsonTransactionData
                    )
                )
            }

            "org-iso-mdoc" -> {
                if (responseEncryptionKey == null) {
                    throw IllegalArgumentException("Response encryption is mandatory for org-iso-mdoc")
                }
                val encryptionInfo = buildCborArray {
                    add("dcapi")
                    addCborMap {
                        put("nonce", nonce.toByteArray())
                        put("recipientPublicKey", responseEncryptionKey.toCoseKey().toDataItem())
                    }
                }
                val base64EncryptionInfo = Cbor.encode(encryptionInfo).toBase64Url()
                val dcapiInfo = buildCborArray {
                    add(base64EncryptionInfo)
                    add(origin)
                }
                val dcapiInfoDigest = Crypto.digest(Algorithm.SHA256, Cbor.encode(dcapiInfo))
                val sessionTranscript = buildCborArray {
                    add(Simple.NULL) // DeviceEngagementBytes
                    add(Simple.NULL) // EReaderKeyBytes
                    addCborArray {
                        add("dcapi")
                        add(dcapiInfoDigest)
                    }
                }
                val encodedDeviceRequest = Cbor.encode(
                    buildDeviceRequestFromDcql(
                        sessionTranscript = sessionTranscript,
                        dcql = dcql,
                        docRequestOtherInfo = docRequestOtherInfo,
                        // TODO: sign individual requests with readerAuthenticationKey
                    ) {
                        verifierIdentities.forEach { readerIdentity ->
                            addReaderAuthAll(readerKey = readerIdentity.key)
                        }
                    }.toDataItem()
                )
                Logger.iCbor(TAG, "deviceRequest", encodedDeviceRequest)
                val dr = DeviceRequest.fromDataItem(Cbor.decode(encodedDeviceRequest))
                dr.verifyReaderAuthentication(sessionTranscript)
                val base64DeviceRequest = encodedDeviceRequest.toBase64Url()
                putJsonObject("data") {
                    put("deviceRequest", base64DeviceRequest)
                    put("encryptionInfo", base64EncryptionInfo)
                }
            }

            else -> throw IllegalArgumentException("Unsupported exchange protocol $exchangeProtocol")
        }
    }

    private suspend fun generateSingleRequest(
        exchangeProtocol: String,
        docType: String,
        claims: List<MdocRequestedClaim>,
        docFormat: String?,
        dataElementIdentifierMapping: Map<String, JsonArray>,
        nonce: ByteString,
        origin: String,
        responseEncryptionKey: EcPublicKey?,
        verifierIdentities: List<VerifierIdentity>,
        zkSystemSpecs: List<ZkSystemSpec>,
        jsonTransactionData: List<String>,
        docRequestOtherInfo: Map<String, DataItem>
    ): JsonObject = buildJsonObject {
        put("protocol", exchangeProtocol)
        when (exchangeProtocol) {
            "openid4vp",
            "openid4vp-v1-unsigned",
            "openid4vp-v1-signed",
            "openid4vp-v1-multisigned"-> {
                put(
                    "data",
                    OpenID4VP.generateRequest(
                        version = if (exchangeProtocol == "openid4vp") {
                            OpenID4VP.Version.DRAFT_24
                        } else {
                            OpenID4VP.Version.DRAFT_29
                        },
                        origin = origin,
                        nonce = nonce.toByteArray().toBase64Url(),
                        responseEncryptionKey = responseEncryptionKey,
                        verifierIdentities = verifierIdentities,
                        responseMode = OpenID4VP.ResponseMode.DC_API,
                        responseUri = null,
                        dcqlQuery = calcDcqlMdoc(docType, claims, zkSystemSpecs),
                        jsonTransactionData = jsonTransactionData
                    )
                )
            }

            "org-iso-mdoc" -> {
                if (responseEncryptionKey == null) {
                    throw IllegalArgumentException("Response encryption is mandatory for org-iso-mdoc")
                }
                val encryptionInfo = buildCborArray {
                    add("dcapi")
                    addCborMap {
                        put("nonce", nonce.toByteArray())
                        put("recipientPublicKey", responseEncryptionKey.toCoseKey().toDataItem())
                    }
                }
                val base64EncryptionInfo = Cbor.encode(encryptionInfo).toBase64Url()
                val dcapiInfo = buildCborArray {
                    add(base64EncryptionInfo)
                    add(origin)
                }
                val dcapiInfoDigest = Crypto.digest(Algorithm.SHA256, Cbor.encode(dcapiInfo))
                val sessionTranscript = buildCborArray {
                    add(Simple.NULL) // DeviceEngagementBytes
                    add(Simple.NULL) // EReaderKeyBytes
                    addCborArray {
                        add("dcapi")
                        add(dcapiInfoDigest)
                    }
                }
                val itemsToRequest = mutableMapOf<String, MutableMap<String, Boolean>>()
                for (claim in claims) {
                    itemsToRequest.getOrPut(claim.namespaceName) { mutableMapOf() }
                        .put(claim.dataElementName, claim.intentToRetain)
                }

                val zkRequest = if (zkSystemSpecs.isNotEmpty()) {
                    ZkRequest(
                        systemSpecs = zkSystemSpecs,
                        zkRequired = false
                    )
                } else {
                    null
                }
                val encodedDeviceRequest = Cbor.encode(buildDeviceRequest(
                    sessionTranscript = sessionTranscript,
                    // TODO: UseCases is optional even in a 1.1 request but iOS 26 currently assumes it's set.
                    //   This has been reported to Apple so this can be removed once their bug-fix is out.
                    deviceRequestInfo = DeviceRequestInfo.fromValues(
                        useCases = listOf(UseCase(
                            mandatory = true,
                            documentSets = listOf(
                                DocumentSet(
                                    docRequestIds = listOf(0)
                                )
                            ),
                            purposeHints = emptyMap()
                        ))
                    )
                ) {
                    addDocRequest(
                        docType = docType,
                        nameSpaces = itemsToRequest,
                        docRequestInfo = DocRequestInfo(
                            zkRequest = zkRequest,
                            docFormat = docFormat,
                            dataElementIdentifierMapping = dataElementIdentifierMapping,
                            otherInfo = docRequestOtherInfo
                        )
                    )
                    verifierIdentities.forEach { readerIdentity ->
                        addReaderAuthAll(readerKey = readerIdentity.key)
                    }
                }.toDataItem())
                val base64DeviceRequest = encodedDeviceRequest.toBase64Url()
                putJsonObject("data") {
                    put("deviceRequest", base64DeviceRequest)
                    put("encryptionInfo", base64EncryptionInfo)
                }
            }

            else -> throw IllegalArgumentException("Unsupported exchange protocol $exchangeProtocol")
        }
    }

    /**
     * Utility function to generate a W3C Digital Credentials API request for requesting a single SD-JWT credential.
     *
     * The request can be expressed for multiple exchange protocols simultaneously, for example OpenID4VP 1.0 and
     * ISO/IEC 18013:2025 Annex C.
     *
     * The following exchange protocols are supported by this function
     * - org-iso-mdoc
     * - openid4vp
     * - openid4vp-v1-signed
     * - openid4vp-v1-unsigned
     *
     * This can be used on the server-side to generate the request. The resulting [JsonObject] can be serialized
     * to a string using [Json.encodeToString] and sent to the browser or requesting app which can pass it to
     * `navigator.credentials.get()` or its native Credential Manager implementation.
     *
     * @param exchangeProtocols a list of W3C Exchange Protocol strings to generate requests for. The order of
     *   requests in the resulting JSON will match the order in this list.
     * @param vct the Verifiable Credential Types to request, e.g. "urn:eudi:pid:1".
     * @param claims the claims to request.
     * @param nonce the nonce to use. For OpenID4VP, this will be base64url-encoded without padding. For mdoc-api
     *   this will be used as is.
     * @param origin the origin to use.
     * @param responseEncryptionKey the key to encrypt the response against or `null` to not encrypt the response.
     *   Note that in some protocols encryption of the response is mandatory and this will throw [IllegalArgumentException]
     *   if this is `null` for such protocols
     * @param verifierIdentities a list of verifier identities used to sign the request; empty list
     *  for unsigned request
     * @return a [JsonObject] with the request.
     */
    @Throws(CancellationException::class)
    suspend fun generateDcRequestSdJwt(
        exchangeProtocols: List<String>,
        vct: List<String>,
        claims: List<JsonRequestedClaim>,
        nonce: ByteString,
        origin: String,
        responseEncryptionKey: EcPublicKey?,
        verifierIdentities: List<VerifierIdentity>,
        jsonTransactionData: List<String> = emptyList()
    ): JsonObject {
        val requests = exchangeProtocols.map { exchangeProtocol ->
            buildJsonObject {
                put("protocol", exchangeProtocol)
                when (exchangeProtocol) {
                    "org-iso-mdoc" -> {
                        // TODO: 18013-5 request protocol only supports requesting a single VCT right now
                        require(vct.size == 1) { "Only a single VCT is supported right now" }
                        val docType = vct.first()

                        val mapping = mutableMapOf<String, JsonArray>()
                        val mdocClaims = claims.map { jsonRequestedClaim ->
                            val flattenedPath = jsonRequestedClaim.claimPath.joinToString(separator = "_") { it.jsonPrimitive.content }
                            val dataElementName = "sdjwtvc_$flattenedPath"
                            mapping[dataElementName] = JsonArray(jsonRequestedClaim.claimPath)
                            MdocRequestedClaim(
                                docType = docType,
                                namespaceName = "_",
                                dataElementName = dataElementName,
                                intentToRetain = false,  // TODO: maybe have caller pass a value
                            )
                        }
                        put(
                            "data",
                            generateSingleRequest(
                                exchangeProtocol = exchangeProtocol,
                                docType = docType,
                                claims = mdocClaims,
                                docFormat = "sd-jwt+kb",
                                dataElementIdentifierMapping = mapping,
                                nonce = nonce,
                                origin = origin,
                                responseEncryptionKey = responseEncryptionKey,
                                verifierIdentities = verifierIdentities,
                                zkSystemSpecs = emptyList(),
                                docRequestOtherInfo = emptyMap(), // TODO: implement transactions
                                jsonTransactionData = emptyList()
                            )["data"] as JsonObject
                        )
                    }
                    "openid4vp",
                    "openid4vp-v1-unsigned",
                    "openid4vp-v1-signed",
                    "openid4vp-v1-multisigned" -> {
                        put(
                            "data",
                            OpenID4VP.generateRequest(
                                version = if (exchangeProtocol == "openid4vp") {
                                    OpenID4VP.Version.DRAFT_24
                                } else {
                                    OpenID4VP.Version.DRAFT_29
                                },
                                origin = origin,
                                nonce = nonce.toByteArray().toBase64Url(),
                                responseEncryptionKey = responseEncryptionKey,
                                verifierIdentities = verifierIdentities,
                                responseMode = OpenID4VP.ResponseMode.DC_API,
                                responseUri = null,
                                dcqlQuery = calcDcqlSdJwt(vct, claims),
                                jsonTransactionData = jsonTransactionData
                            )
                        )
                    }
                    else -> throw IllegalArgumentException("Unsupported exchange protocol $exchangeProtocol")
                }
            }
        }
        return buildJsonObject {
            put("requests", JsonArray(requests))
        }
    }

    private fun calcDcqlMdoc(
        docType: String,
        claims: List<MdocRequestedClaim>,
        zkSystemSpecs: List<ZkSystemSpec>
    ) = buildJsonObject {
        putJsonArray("credentials") {
            addJsonObject {
                put("id", JsonPrimitive("cred1"))
                if (zkSystemSpecs.isNotEmpty()) {
                    put("format", JsonPrimitive("mso_mdoc_zk"))
                } else {
                    put("format", JsonPrimitive("mso_mdoc"))
                }
                putJsonObject("meta") {
                    put("doctype_value", JsonPrimitive(docType))
                    if (zkSystemSpecs.isNotEmpty()) {
                        putJsonArray("zk_system_type") {
                            for (spec in zkSystemSpecs) {
                                addJsonObject {
                                    put("system", spec.system)
                                    put("id", spec.id)
                                    spec.params.forEach { param ->
                                        put(param.key, param.value.toJson())
                                    }
                                }
                            }
                        }
                    }
                }
                putJsonArray("claims") {
                    for (claim in claims) {
                        addJsonObject {
                            putJsonArray("path") {
                                add(JsonPrimitive(claim.namespaceName))
                                add(JsonPrimitive(claim.dataElementName))
                            }
                            put("intent_to_retain", JsonPrimitive(claim.intentToRetain))
                        }
                    }
                }
            }
        }
    }

    private fun calcDcqlSdJwt(
        vct: List<String>,
        claims: List<JsonRequestedClaim>
    ) = buildJsonObject {
        putJsonArray("credentials") {
            addJsonObject {
                put("id", JsonPrimitive("cred1"))
                put("format", JsonPrimitive("dc+sd-jwt"))
                putJsonObject("meta") {
                    put(
                        "vct_values",
                        buildJsonArray {
                            vct.forEach {
                                add(it)
                            }
                        }
                    )
                }
                putJsonArray("claims") {
                    for (claim in claims) {
                        addJsonObject {
                            putJsonArray("path") {
                                claim.claimPath.forEach {
                                    add(it)
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    /**
     * Decrypts a W3C Digital Credentials response.
     *
     * @param response the W3C Digital Credentials API response.
     * @param nonce the nonce.
     * @param origin the origin.
     * @param responseEncryptionKey the response encryption or `null` if the response isn't encrypted.
     * @return a [OpenID4VPDcResponse] or [MdocApiDcResponse] with the cleartext response.
     */
    suspend fun decryptDcResponse(
        response: JsonObject,
        nonce: ByteString,
        origin: String,
        responseEncryptionKey: AsymmetricKey?,
    ): DcResponse {
        when (val exchangeProtocol = response["protocol"]!!.jsonPrimitive.content) {
            "openid4vp",
            "openid4vp-v1-signed",
            "openid4vp-v1-unsigned" -> {
                val response = response["data"]!!.jsonObject["response"]!!.jsonPrimitive.content
                val splits = response.split(".")
                val responseObj = if (splits.size == 3) {
                    // Unsecured JWT
                    Json.decodeFromString(JsonObject.serializer(), splits[1].fromBase64Url().decodeToString())
                } else {
                    if (responseEncryptionKey == null) {
                        throw IllegalStateException("Response is encryption but no key was provided for decryption")
                    }
                    JsonWebEncryption.decrypt(
                        encryptedJwt = response,
                        recipientKey = responseEncryptionKey
                    )
                }
                val vpToken = responseObj["vp_token"]!!.jsonObject

                val handoverInfo = if (exchangeProtocol == "openid4vp") {
                    // Draft 24
                    buildCborArray {
                        add(origin)
                        add("web-origin:$origin")
                        add(nonce.toByteArray().toBase64Url())
                    }
                } else {
                    // OpenID4VP 1.0
                    val responseEncryptionKeyJwkThumbprint = responseEncryptionKey?.publicKey?.toJwkThumbprint(Algorithm.SHA256)?.toByteArray()
                    buildCborArray {
                        val jwkThumbPrint = if (responseEncryptionKeyJwkThumbprint == null) {
                            Simple.NULL
                        } else {
                            Bstr(responseEncryptionKeyJwkThumbprint)
                        }
                        // B.2.6.2. Invocation via the Digital Credentials API
                        add(origin)
                        add(nonce.toByteArray().toBase64Url())
                        add(jwkThumbPrint)
                    }
                }
                val encodedHandoverInfo = Cbor.encode(handoverInfo)
                Logger.iCbor(TAG, "handoverInfo", encodedHandoverInfo)
                val encodedHandoverInfoDigest = Crypto.digest(Algorithm.SHA256, encodedHandoverInfo)
                val sessionTranscript = buildCborArray {
                    add(Simple.NULL) // DeviceEngagementBytes
                    add(Simple.NULL) // EReaderKeyBytes
                    addCborArray {
                        add("OpenID4VPDCAPIHandover")
                        add(encodedHandoverInfoDigest)
                    }
                }
                Logger.iCbor(TAG, "sessionTranscript", Cbor.encode(sessionTranscript))
                return OpenID4VPDcResponse(
                    vpToken = vpToken,
                    sessionTranscript = sessionTranscript
                )
            }

            "org-iso-mdoc" -> {
                if (responseEncryptionKey == null) {
                    throw IllegalStateException("Response is encryption but no key was provided for decryption")
                }
                val encryptionInfo = buildCborArray {
                    add("dcapi")
                    addCborMap {
                        put("nonce", nonce.toByteArray())
                        put("recipientPublicKey", responseEncryptionKey.publicKey.toCoseKey().toDataItem())
                    }
                }
                val base64EncryptionInfo = Cbor.encode(encryptionInfo).toBase64Url()
                val dcapiInfo = buildCborArray {
                    add(base64EncryptionInfo)
                    add(origin)
                }
                val dcapiInfoDigest = Crypto.digest(Algorithm.SHA256, Cbor.encode(dcapiInfo))
                val sessionTranscript = buildCborArray {
                    add(Simple.NULL) // DeviceEngagementBytes
                    add(Simple.NULL) // EReaderKeyBytes
                    addCborArray {
                        add("dcapi")
                        add(dcapiInfoDigest)
                    }
                }
                val encryptedResponseBase64 = response["data"]!!.jsonObject["response"]!!.jsonPrimitive.content
                val array = Cbor.decode(encryptedResponseBase64.fromBase64Url()).asArray
                if (array[0].asTstr != "dcapi") {
                    throw IllegalArgumentException("Excepted dcapi as first array element")
                }
                val encryptionParameters = array[1].asMap
                val enc = encryptionParameters[Tstr("enc")]!!.asBstr
                val ciphertext = encryptionParameters[Tstr("cipherText")]!!.asBstr
                val decrypter = Hpke.getDecrypter(
                    cipherSuite = Hpke.CipherSuite.DHKEM_P256_HKDF_SHA256_HKDF_SHA256_AES_128_GCM,
                    receiverPrivateKey = responseEncryptionKey,
                    encapsulatedKey = enc,
                    info = Cbor.encode(sessionTranscript),
                )
                val encodedDeviceResponse = decrypter.decrypt(
                    ciphertext = ciphertext,
                    aad = ByteArray(0),
                )
                return MdocApiDcResponse(
                    deviceResponse = Cbor.decode(encodedDeviceResponse),
                    sessionTranscript = sessionTranscript
                )
            }

            else -> throw IllegalArgumentException("Unsupported exchange protocol $exchangeProtocol")
        }
    }

    /**
     * Generates [VerifiedPresentation] from an OpenID4VP response.
     *
     * @param now the current time.
     * @param vpToken the `vp_token` according to OpenID4VP 1.0.
     * @param sessionTranscript the ISO mdoc `SessionTranscript` CBOR, required if there are any
     *  ISO mdoc credentials were presented.
     * @param nonce the nonce used in the request.
     * @param documentTypeRepository a [DocumentTypeRepository] or `null`.
     * @param zkSystemRepository a [ZkSystemRepository] used for verifying ZKP proofs or `null`.
     * @param transactionDataMap maps credential id in the query to the list of transactions
     *  for that credential
     * @param queryData maps credential id in the query to information about the credential query
     *  in the request.
     * @return a list of [VerifiedPresentation], one for each credential in the response.
     */
    suspend fun verifyOpenID4VPResponse(
        now: Instant,
        vpToken: JsonObject,
        sessionTranscript: DataItem?,
        nonce: String,
        documentTypeRepository: DocumentTypeRepository?,
        zkSystemRepository: ZkSystemRepository?,
        transactionDataMap: Map<String, List<TransactionDataJson>> = emptyMap(),
        queryData: Map<String, QueryData>
    ): List<VerifiedPresentation> {
        val verifiedPresentations = mutableListOf<VerifiedPresentation>()
        for ((credId, credValue) in vpToken.entries) {
            val creds = credValue as? JsonArray ?: JsonArray(listOf(credValue))
            val transactionData = transactionDataMap[credId] ?: listOf()
            for (cred in creds) {
                val credBase64 = cred.jsonPrimitive.content
                val isSdJwt = queryData.let { it[credId] is SdJwtQueryData }
                if (isSdJwt) {
                    val sdjwtVerifiedPresentations = verifySdJwtPresentation(
                        compactSerialization = credBase64,
                        nonce = nonce,
                        documentTypeRepository = documentTypeRepository,
                        transactionData = transactionData,
                        identifier = credId
                    )
                    verifiedPresentations.add(sdjwtVerifiedPresentations)
                } else {
                    val mdocVerifiedPresentations = verifySingleDocMdocDeviceResponse(
                        now = now,
                        deviceResponse = Cbor.decode(credBase64.fromBase64Url()),
                        sessionTranscript = sessionTranscript!!,
                        documentTypeRepository = documentTypeRepository,
                        zkSystemRepository = zkSystemRepository,
                        transactionData = transactionData,
                        vpTokenIdentifier = credId
                    )
                    check(mdocVerifiedPresentations.size == 1)
                    verifiedPresentations.addAll(mdocVerifiedPresentations)
                }
            }
        }
        return verifiedPresentations
    }

    /**
     * Generates [VerifiedPresentation] from an SD-JWT / SD-JWT+KB presentation.
     *
     * @param compactSerialization the compact serialization of the SD-JWT or SD-JWT+KB.
     * @param nonce the nonce used in the request.
     * @param documentTypeRepository a [DocumentTypeRepository] or `null`.
     * @return a [VerifiedPresentation] instance.
     */
    suspend fun verifySdJwtPresentation(
        compactSerialization: String,
        nonce: String,
        documentTypeRepository: DocumentTypeRepository?,
        transactionData: List<TransactionData>,
        identifier: String? = null
    ): VerifiedPresentation {
        val (sdJwt, sdJwtKb) = if (compactSerialization.endsWith("~")) {
            Pair(SdJwt.fromCompactSerialization(compactSerialization), null)
        } else {
            val sdJwtKb = SdJwtKb.fromCompactSerialization(compactSerialization)
            Pair(sdJwtKb.sdJwt, sdJwtKb)
        }

        val issuerCertChain = sdJwt.x5c
            ?: throw IllegalStateException("Issuer-signed key not in `x5c` in header")
        val processedPayload = if (sdJwtKb != null) {
            sdJwtKb.verify(
                issuerKey = issuerCertChain.certificates.first().ecPublicKey,
                checkNonce = { nonceInCredential -> nonceInCredential == nonce },
                checkAudience = { true }, // TODO
                checkCreationTime = { true },
                transactionData = transactionData
            )
        } else {
            sdJwt.verify(
                issuerKey = issuerCertChain.certificates.first().ecPublicKey
            )
        }

        val vct = processedPayload["vct"]!!.jsonPrimitive.content
        val validFrom = processedPayload["nbf"]?.jsonPrimitive?.intOrNull?.let { Instant.fromEpochSeconds(it.toLong()) }
        val validUntil = processedPayload["exp"]?.jsonPrimitive?.intOrNull?.let { Instant.fromEpochSeconds(it.toLong()) }
        val signedAt = processedPayload["iat"]?.jsonPrimitive?.intOrNull?.let { Instant.fromEpochSeconds(it.toLong()) }
        val dt = documentTypeRepository?.getDocumentTypeForJson(vct)

        val claims = mutableListOf<JsonClaim>()
        for ((claimName, claimValue) in processedPayload) {
            val jsonAttr = dt?.jsonDocumentType?.getDocumentAttribute(claimName)
            claims.add(JsonClaim(
                displayName = jsonAttr?.displayName ?: claimName,
                attribute = jsonAttr,
                vct = vct,
                claimPath = JsonArray(listOf(JsonPrimitive(claimName))),
                value = claimValue
            ))
        }

        val transactionResults = if (transactionData.isEmpty() || sdJwtKb == null) {
            null
        } else {
            buildMap<String, JsonElement> {
                for (transaction in transactionData) {
                    sdJwtKb.jwtBody[transaction.type.kbJwtResponseClaimName]?.let {
                        put(transaction.type.identifier, it)
                    }
                }
            }
        }

        val deviceSignedClaims = mutableListOf<JsonClaim>()
        if (sdJwtKb != null) {
            for ((claimName, claimValue) in sdJwtKb.jwtBody) {
                val jsonAttr = dt?.jsonDocumentType?.getDocumentAttribute(claimName)
                // TODO: should we pass claims that we know were transaction results? Right now
                //  we pass everything.
                claims.add(JsonClaim(
                    displayName = jsonAttr?.displayName ?: "$claimName (Device Signed)",
                    attribute = jsonAttr,
                    vct = vct,
                    claimPath = JsonArray(listOf(JsonPrimitive(claimName))),
                    value = claimValue
                ))
            }
        }

        return JsonVerifiedPresentation(
            documentSignerCertChain = issuerCertChain,
            issuerSignedClaims = claims,
            deviceSignedClaims = deviceSignedClaims,
            zkpUsed = false,
            validFrom = validFrom,
            validUntil = validUntil,
            signedAt = signedAt,
            expectedUpdate = null,  // Not defined for SD-JWT
            vct = vct,
            transactionResponses = transactionResults,
            vpTokenIdentifier = identifier,
            transactionData = transactionData
        )
    }

    /**
     * Generates [VerifiedPresentation] from an ISO 18013-5 response.
     *
     * @param now the current time.
     * @param deviceResponse the `DeviceResponse` CBOR.
     * @param sessionTranscript the ISO mdoc `SessionTranscript` CBOR.
     * @param eReaderKey the ephemeral reader key, if 18013-5 session encryption is used.
     * @param documentTypeRepository a [DocumentTypeRepository] or `null`.
     * @param zkSystemRepository a [ZkSystemRepository] used for verifying ZKP proofs or `null`.
     * @param request request that produced this response, only required for transaction processing
     * @return a list of [VerifiedPresentation], one for each document in the response.
     */
    suspend fun verifyMdocDeviceResponse(
        now: Instant,
        deviceResponse: DataItem,
        sessionTranscript: DataItem,
        eReaderKey: AsymmetricKey?,
        documentTypeRepository: DocumentTypeRepository?,
        zkSystemRepository: ZkSystemRepository?,
        request: DeviceRequest?,
    ): List<VerifiedPresentation> {
        val deviceResponse = DeviceResponse.fromDataItem(deviceResponse)
        deviceResponse.verify(
            sessionTranscript = sessionTranscript,
            eReaderKey = eReaderKey,
            deviceRequest = request,
            documentTypeRepository = documentTypeRepository,
            atTime = now
        )
        return createVerifiedPresentationList(
            deviceResponse = deviceResponse,
            sessionTranscript = sessionTranscript,
            documentTypeRepository = documentTypeRepository,
            zkSystemRepository = zkSystemRepository
        )
    }

    /**
     * Generates [VerifiedPresentation] from an ISO 18013-5 response.
     *
     * @param now the current time.
     * @param deviceResponse the `DeviceResponse` CBOR.
     * @param sessionTranscript the ISO mdoc `SessionTranscript` CBOR.
     * @param documentTypeRepository a [DocumentTypeRepository] or `null`.
     * @param zkSystemRepository a [ZkSystemRepository] used for verifying ZKP proofs or `null`.
     * @return a list of [VerifiedPresentation], one for each document in the response.
     */
    internal suspend fun verifySingleDocMdocDeviceResponse(
        now: Instant,
        deviceResponse: DataItem,
        sessionTranscript: DataItem,
        documentTypeRepository: DocumentTypeRepository?,
        zkSystemRepository: ZkSystemRepository?,
        transactionData: List<TransactionData>,
        vpTokenIdentifier: String
    ): List<VerifiedPresentation> {
        val deviceResponse = DeviceResponse.fromDataItem(deviceResponse)
        deviceResponse.verifySingleDoc(
            sessionTranscript = sessionTranscript,
            transactionData = transactionData,
            atTime = now
        )
        return createVerifiedPresentationList(
            deviceResponse = deviceResponse,
            sessionTranscript = sessionTranscript,
            documentTypeRepository = documentTypeRepository,
            zkSystemRepository = zkSystemRepository,
            vpTokenIdentifier = vpTokenIdentifier
        )
    }

    private suspend fun createVerifiedPresentationList(
        deviceResponse: DeviceResponse,
        sessionTranscript: DataItem,
        documentTypeRepository: DocumentTypeRepository?,
        zkSystemRepository: ZkSystemRepository?,
        vpTokenIdentifier: String? = null
    ): List<VerifiedPresentation> {
        val verifiedPresentations = mutableListOf<VerifiedPresentation>()
        for (document in deviceResponse.documents) {
            val transactionResponses = document.transactionResponse
            val dt = documentTypeRepository?.getDocumentTypeForMdoc(document.docType)
            val issuerSignedClaims = mutableListOf<MdocClaim>()
            document.issuerNamespaces.data.forEach { (namespaceName, issuerSignedItemsMap) ->
                issuerSignedItemsMap.forEach { (dataElementName, issuerSignedItem) ->
                    val mdocAttr = dt?.mdocDocumentType?.namespaces?.get(namespaceName)?.dataElements?.get(dataElementName)
                    issuerSignedClaims.add(
                        MdocClaim(
                            displayName = mdocAttr?.attribute?.displayName ?: dataElementName,
                            attribute = mdocAttr?.attribute,
                            docType = document.docType,
                            namespaceName = namespaceName,
                            dataElementName = dataElementName,
                            value = issuerSignedItem.dataElementValue,
                        )
                    )
                }
            }

            val deviceSignedClaims = mutableListOf<MdocClaim>()
            document.deviceNamespaces.data.forEach { (namespaceName, dataElementsMap) ->
                dataElementsMap.forEach { (dataElementName, dataElementValue) ->
                    val mdocAttr = dt?.mdocDocumentType?.namespaces?.get(namespaceName)?.dataElements?.get(dataElementName)
                    deviceSignedClaims.add(
                        MdocClaim(
                            displayName = mdocAttr?.attribute?.displayName ?: dataElementName,
                            attribute = mdocAttr?.attribute,
                            docType = document.docType,
                            namespaceName = namespaceName,
                            dataElementName = dataElementName,
                            value = dataElementValue
                        )
                    )
                }
            }
            verifiedPresentations.add(
                MdocVerifiedPresentation(
                    documentSignerCertChain = document.issuerCertChain,
                    issuerSignedClaims = issuerSignedClaims,
                    deviceSignedClaims = deviceSignedClaims,
                    zkpUsed = false,
                    validFrom = document.mso.validFrom,
                    validUntil = document.mso.validUntil,
                    expectedUpdate = document.mso.expectedUpdate,
                    signedAt = document.mso.signedAt,
                    docType = document.docType,
                    transactionResponses = transactionResponses.ifEmpty { null },
                    vpTokenIdentifier = vpTokenIdentifier,
                    transactionData = document.transactionData
                )
            )
        }
        for (zkDocument in deviceResponse.zkDocuments) {
            val zkSystemSpec = zkSystemRepository?.getAllZkSystemSpecs()?.find {
                it.id == zkDocument.documentData.zkSystemSpecId
            } ?: throw IllegalStateException("Zk System '${zkDocument.documentData.zkSystemSpecId}' was not found.")
            zkSystemRepository.lookup(zkSystemSpec.system)
                ?.verifyProof(
                    zkDocument = zkDocument,
                    zkSystemSpec = zkSystemSpec,
                    sessionTranscript = sessionTranscript,
                )
                ?: throw IllegalStateException("Zk System '${zkSystemSpec.system}' was not found.")

            val dt = documentTypeRepository?.getDocumentTypeForMdoc(zkDocument.documentData.docType)

            if (zkDocument.documentData.msoX5chain == null) {
                throw IllegalStateException("Expected x5chain for the issuer")
            }

            val issuerSignedClaims = mutableListOf<MdocClaim>()
            for ((namespaceName, dataElements) in zkDocument.documentData.issuerSigned) {
                for ((dataElementName, value) in dataElements) {
                    val mdocAttr = dt?.mdocDocumentType?.namespaces?.get(namespaceName)?.dataElements?.get(dataElementName)
                    issuerSignedClaims.add(
                        MdocClaim(
                            displayName = mdocAttr?.attribute?.displayName ?: dataElementName,
                            attribute = mdocAttr?.attribute,
                            docType = zkDocument.documentData.docType,
                            namespaceName = namespaceName,
                            dataElementName = dataElementName,
                            value = value,
                        )
                    )
                }
            }

            val deviceSignedClaims = mutableListOf<MdocClaim>()
            for ((namespaceName, dataElements) in zkDocument.documentData.deviceSigned) {
                for ((dataElementName, value) in dataElements) {
                    val mdocAttr = dt?.mdocDocumentType?.namespaces?.get(namespaceName)?.dataElements?.get(dataElementName)
                    issuerSignedClaims.add(
                        MdocClaim(
                            displayName = mdocAttr?.attribute?.displayName ?: dataElementName,
                            attribute = mdocAttr?.attribute,
                            docType = zkDocument.documentData.docType,
                            namespaceName = namespaceName,
                            dataElementName = dataElementName,
                            value = value
                        )
                    )
                }
            }

            verifiedPresentations.add(
                MdocVerifiedPresentation(
                    documentSignerCertChain = zkDocument.documentData.msoX5chain,
                    issuerSignedClaims = issuerSignedClaims,
                    deviceSignedClaims = deviceSignedClaims,
                    zkpUsed = true,
                    validFrom = null,
                    validUntil = null,
                    expectedUpdate = null,
                    signedAt = null,
                    docType = zkDocument.documentData.docType,
                    transactionResponses = null,
                    vpTokenIdentifier = vpTokenIdentifier,
                    transactionData = listOf()  // No transactions for ZKP
                )
            )
        }

        for (document in deviceResponse.otherDocuments) {
            if (document.docFormat != "sd-jwt+kb") {
                Logger.w(TAG, "Unknown docFormat ${document.docFormat}")
                continue
            }
            val sdJwtKbCompactSerialization = document.data.toByteArray().zlibInflate().decodeToString()

            val sessionTranscriptBytes = Tagged(
                tagNumber = Tagged.ENCODED_CBOR,
                taggedItem = Bstr(Cbor.encode(sessionTranscript))
            )
            val nonce = Crypto.digest(Algorithm.SHA256, Cbor.encode(sessionTranscriptBytes))

            // TODO: we want this routine (createVerifiedPresentationList) to just extract
            //  the data, rather than deal with all verification details (including transactions),
            //  like we do for mdoc and zkp, we will need to split verifySdJwtPresentation into
            //  verification and and data extraction for this
            verifiedPresentations.add(
                verifySdJwtPresentation(
                    compactSerialization = sdJwtKbCompactSerialization,
                    nonce = nonce.toBase64Url(),
                    documentTypeRepository = documentTypeRepository,
                    transactionData = document.transactionData
                )
            )
        }

        return verifiedPresentations
    }

    /**
     * Makes a request for credential presentation using
     * [W3C Digital Credentials API](https://www.w3.org/TR/digital-credentials)
     *
     * Single DC request can contain sections for several protocols, the response always
     * comes using only one of the requested protocols.
     *
     * @param requestTypes list of request types that should be created; multiple requests can
     *  be created and sent, typically only one of these requests will be responded to
     * @param dcql DCQL representation of the request, this gives the list of needed credentials
     *   and which claims are needed from each credential
     * @param origin protocol and authority of the server that makes the request (e.g.
     *   `https://example.com:8000`) or an appropriate platform-specific origin for
     *   app-to-app requests
     * @param verifierIdentities a list of verifier identities used to sign the request; empty list
     *  for unsigned request
     * @param transactionData transaction data in OpenID4VP JSON format
     *   (before Base64Url encoding), note that credentialId uses credential ids used in DCQL
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
    suspend fun generateVerificationSessionForDcql(
        requestTypes: Collection<VerificationSession.RequestType>,
        dcql: String,
        verifierIdentities: List<VerifierIdentity> = listOf(),
        origin: String? = null,
        transactionData: List<String>? = null,
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
        val dcqlJson = Json.parseToJsonElement(dcql).jsonObject
        val nonceStr = nonce.toByteArray().toBase64Url()

        val docRequestOtherInfo = if (transactionData.isNullOrEmpty()) {
            null
        } else {
            lazy {
                TransactionDataJson.parse(
                    base64UrlEncodedJson = transactionData.map {
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
            verifierIdentities: List<VerifierIdentity>
        ): String = OpenID4VP.generateRequest(
            version = version,
            dcqlQuery = dcqlJson,
            jsonTransactionData =transactionData ?: emptyList(),
            nonce = nonceStr,
            state = state,
            origin = origin
                ?: throw IllegalArgumentException("'origin' is required for OpenID4VP"),
            responseEncryptionKey = encryptionPrivateKey?.publicKey,
            verifierIdentities = verifierIdentities,
            responseMode = responseMode,
            responseUri = responseUri
        ).toString()

        suspend fun createIso18013Request(
            sessionTranscript: DataItem
        ): DataItem = buildDeviceRequestFromDcql(
            sessionTranscript = sessionTranscript,
            dcql = dcqlJson,
            docRequestOtherInfo = docRequestOtherInfo?.value ?: emptyMap(),
        ) {
            verifierIdentities.forEach { addReaderAuthAll(it.key) }
        }.toDataItem()

        val requests = requestTypes.map { requestType ->
            when (requestType) {
                VerificationSession.RequestType.DC_OPENID4VP ->
                    VerificationSession.DcOpenID4VPRequest(
                        requestorId = origin
                            ?: throw IllegalArgumentException("'origin' is required for DC API"),
                        responseEncryptionKey = encryptionPrivateKey,
                        openID4VPRequest = createOpenIDRequest(
                            responseMode = OpenID4VP.ResponseMode.DC_API,
                            verifierIdentities = verifierIdentities
                        )
                    )

                VerificationSession.RequestType.DC_OPENID4VP_DRAFT_24 ->
                    VerificationSession.DcOpenID4VPDraft24Request(
                        requestorId = origin
                            ?: throw IllegalArgumentException("'origin' is required for DC API"),
                        responseEncryptionKey = encryptionPrivateKey,
                        openID4VPRequest = createOpenIDRequest(
                            responseMode = OpenID4VP.ResponseMode.DC_API,
                            version = OpenID4VP.Version.DRAFT_24,
                            verifierIdentities = verifierIdentities
                        )
                    )

                VerificationSession.RequestType.OPENID4VP_URI_SCHEME -> {
                    VerificationSession.OpenID4VPUriSchemeRequest(
                        requestorId = verifierIdentities.first().clientId
                            ?: throw IllegalArgumentException("clientId is required for OpenID4VCI"),
                        responseEncryptionKey = encryptionPrivateKey,
                        openID4VPRequest = createOpenIDRequest(
                            responseMode = OpenID4VP.ResponseMode.DIRECT_POST,
                            responseUri = responseUri
                                ?: throw IllegalArgumentException("responseUri must be specified"),
                            verifierIdentities = verifierIdentities.subList(0, 1)
                        )
                    )
                }

                VerificationSession.RequestType.DC_ISO_18013 -> {
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
                    VerificationSession.DcIso18013Request(
                        origin = origin
                            ?: throw IllegalArgumentException("'origin' is required for DC API"),
                        responseEncryptionKey = encryptionPrivateKey,
                        deviceRequest = createIso18013Request(
                            sessionTranscript = dcSessionTranscript(
                                encryptionInfo,
                                origin
                            )
                        ),
                        encryptionInfo = encryptionInfo
                    )
                }

                VerificationSession.RequestType.ISO_18013_PROXIMITY -> {
                    val sessionTranscript = proximitySessionTranscript(
                        deviceEngagement = deviceEngagement!!,
                        handover = handover!!,
                        eReaderKey = eReaderKey!!.publicKey
                    )
                    VerificationSession.Iso18013ProximityRequest(
                        deviceEngagement = deviceEngagement,
                        handover = handover,
                        eDeviceKey = eReaderKey,
                        deviceRequest = createIso18013Request(
                            sessionTranscript = sessionTranscript
                        )
                    )
                }
            }
        }

        return VerificationSession(requests)
    }

    internal fun proximitySessionTranscript(
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

    internal suspend fun dcSessionTranscript(
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

    internal suspend fun vpSessionTranscriptDraft24(
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

    internal suspend fun vpSessionTranscript(
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
