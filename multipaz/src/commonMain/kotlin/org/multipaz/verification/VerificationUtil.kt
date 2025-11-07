package org.multipaz.verification

import kotlinx.io.bytestring.ByteString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
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
import org.multipaz.cbor.Tstr
import org.multipaz.cbor.addCborArray
import org.multipaz.cbor.addCborMap
import org.multipaz.cbor.buildCborArray
import org.multipaz.claim.JsonClaim
import org.multipaz.claim.MdocClaim
import org.multipaz.crypto.Algorithm
import org.multipaz.crypto.Crypto
import org.multipaz.crypto.EcPrivateKey
import org.multipaz.crypto.EcPublicKey
import org.multipaz.crypto.Hpke
import org.multipaz.crypto.JsonWebEncryption
import org.multipaz.crypto.AsymmetricKey
import org.multipaz.documenttype.DocumentTypeRepository
import org.multipaz.mdoc.request.DocRequestInfo
import org.multipaz.mdoc.request.ZkRequest
import org.multipaz.mdoc.request.buildDeviceRequest
import org.multipaz.mdoc.response.DeviceResponseParser
import org.multipaz.mdoc.zkp.ZkSystemRepository
import org.multipaz.mdoc.zkp.ZkSystemSpec
import org.multipaz.openid.OpenID4VP
import org.multipaz.request.JsonRequestedClaim
import org.multipaz.request.MdocRequestedClaim
import org.multipaz.sdjwt.SdJwt
import org.multipaz.sdjwt.SdJwtKb
import org.multipaz.util.Logger
import org.multipaz.util.fromBase64Url
import org.multipaz.util.toBase64Url
import kotlin.collections.component1
import kotlin.collections.component2
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
     * - openid4v4-v1-unsigned
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
     * @param clientId the client id to use, must be non-null for signed requests.
     * @param responseEncryptionKey the key to encrypt the response against or `null` to not encrypt the response.
     *   Note that in some protocols encryption of the response is mandatory and this will throw [IllegalArgumentException]
     *   if this is `null` for such protocols
     * @param readerAuthenticationKey an optional key to use for reader authentication and its
     *    certificate chain.
     * @param zkSystemSpecs if non-empty, request a ZK proof using these systems.
     * @return a [JsonObject] with the request.
     */
    suspend fun generateDcRequestMdoc(
        exchangeProtocols: List<String>,
        docType: String,
        claims: List<MdocRequestedClaim>,
        nonce: ByteString,
        origin: String,
        clientId: String?,
        responseEncryptionKey: EcPublicKey?,
        readerAuthenticationKey: AsymmetricKey.X509Compatible?,
        zkSystemSpecs: List<ZkSystemSpec>
    ): JsonObject {
        val requests = exchangeProtocols.map { exchangeProtocol ->
            generateSingleRequest(
                exchangeProtocol = exchangeProtocol,
                docType = docType,
                claims = claims,
                nonce = nonce,
                origin = origin,
                clientId = clientId,
                responseEncryptionKey = responseEncryptionKey,
                readerAuthenticationKey = readerAuthenticationKey,
                zkSystemSpecs = zkSystemSpecs
            )
        }
        return buildJsonObject {
            put("requests", JsonArray(requests))
        }
    }

    private suspend fun generateSingleRequest(
        exchangeProtocol: String,
        docType: String,
        claims: List<MdocRequestedClaim>,
        nonce: ByteString,
        origin: String,
        clientId: String?,
        responseEncryptionKey: EcPublicKey?,
        readerAuthenticationKey: AsymmetricKey.X509Compatible?,
        zkSystemSpecs: List<ZkSystemSpec>
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
                        clientId = clientId,
                        nonce = nonce.toByteArray().toBase64Url(),
                        responseEncryptionKey = responseEncryptionKey,
                        requestSigningKey = readerAuthenticationKey,
                        responseMode = OpenID4VP.ResponseMode.DC_API,
                        responseUri = null,
                        dclqQuery = calcDcqlMdoc(docType, claims, zkSystemSpecs)
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
                val sessionTranscript = buildCborArray {
                    add(Simple.NULL) // DeviceEngagementBytes
                    add(Simple.NULL) // EReaderKeyBytes
                    addCborArray {
                        add("dcapi")
                        add(Crypto.digest(Algorithm.SHA256, Cbor.encode(dcapiInfo)))
                    }
                }
                val itemsToRequest = mutableMapOf<String, MutableMap<String, Boolean>>()
                for (claim in claims) {
                    itemsToRequest.getOrPut(claim.namespaceName) { mutableMapOf() }
                        .put(claim.dataElementName, claim.intentToRetain)
                }

                val zkRequest = if (zkSystemSpecs.size > 0) {
                    ZkRequest(
                        systemSpecs = zkSystemSpecs,
                        zkRequired = false
                    )
                } else {
                    null
                }
                val encodedDeviceRequest = Cbor.encode(buildDeviceRequest(
                    sessionTranscript = sessionTranscript
                ) {
                    if (readerAuthenticationKey != null) {
                        addDocRequest(
                            docType = docType,
                            nameSpaces = itemsToRequest,
                            docRequestInfo = DocRequestInfo(
                                zkRequest = zkRequest
                            ),
                            readerKey = readerAuthenticationKey,
                        )
                    } else {
                        addDocRequest(
                            docType = docType,
                            nameSpaces = itemsToRequest,
                            docRequestInfo = DocRequestInfo(
                                zkRequest = zkRequest
                            ),
                        )
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
     * The request can expressed for multiple exchange protocols simultaneously, for example OpenID4VP 1.0 and
     * ISO/IEC 18013:2025 Annex C.
     *
     * The following exchange protocols are supported by this function
     * - org-iso-mdoc
     * - openid4vp
     * - openid4vp-v1-signed
     * - openid4v4-v1-unsigned
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
     * @param clientId the client id to use, must be non-null for signed requests.
     * @param responseEncryptionKey the key to encrypt the response against or `null` to not encrypt the response.
     *   Note that in some protocols encryption of the response is mandatory and this will throw [IllegalArgumentException]
     *   if this is `null` for such protocols
     * @param readerAuthenticationKey an optional key to use for reader authentication and its
     *    certificate chain.
     * @return a [JsonObject] with the request.
     */
    suspend fun generateDcRequestSdJwt(
        exchangeProtocols: List<String>,
        vct: List<String>,
        claims: List<JsonRequestedClaim>,
        nonce: ByteString,
        origin: String,
        clientId: String?,
        responseEncryptionKey: EcPublicKey?,
        readerAuthenticationKey: AsymmetricKey?
    ): JsonObject {
        val requests = exchangeProtocols.map { exchangeProtocol ->
            buildJsonObject {
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
                                clientId = clientId,
                                nonce = nonce.toByteArray().toBase64Url(),
                                responseEncryptionKey = responseEncryptionKey,
                                requestSigningKey = readerAuthenticationKey,
                                responseMode = OpenID4VP.ResponseMode.DC_API,
                                responseUri = null,
                                dclqQuery = calcDcqlSdJwt(vct, claims)
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
        // TODO: Change responseEncryptionKey to be a SigningKey
        val exchangeProtocol = response["protocol"]!!.jsonPrimitive.content
        when (exchangeProtocol) {
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
                    buildCborArray {
                        val jwkThumbPrint = if (responseEncryptionKey == null) {
                            Simple.NULL
                        } else {
                            Bstr(responseEncryptionKey.publicKey.toJwkThumbprint(Algorithm.SHA256).toByteArray())
                        }
                        // B.2.6.2. Invocation via the Digital Credentials API
                        add(origin)
                        add(nonce.toByteArray().toBase64Url())
                        add(jwkThumbPrint)
                    }
                }
                val encodedHandoverInfo = Cbor.encode(handoverInfo)
                Logger.iCbor(TAG, "handoverInfo", encodedHandoverInfo)
                val sessionTranscript = buildCborArray {
                    add(Simple.NULL) // DeviceEngagementBytes
                    add(Simple.NULL) // EReaderKeyBytes
                    addCborArray {
                        add("OpenID4VPDCAPIHandover")
                        add(Crypto.digest(Algorithm.SHA256, encodedHandoverInfo))
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
                val sessionTranscript = buildCborArray {
                    add(Simple.NULL) // DeviceEngagementBytes
                    add(Simple.NULL) // EReaderKeyBytes
                    addCborArray {
                        add("dcapi")
                        add(Crypto.digest(Algorithm.SHA256, Cbor.encode(dcapiInfo)))
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
     * @param sessionTranscript the ISO mdoc `SessionTranscript` CBOR.
     * @param nonce the nonce used in the request.
     * @param documentTypeRepository a [DocumentTypeRepository] or `null`.
     * @param zkSystemRepository a [ZkSystemRepository] used for verifying ZKP proofs or `null`.
     * @return a list of [VerifiedPresentation], one for each credential in the response.
     */
    suspend fun verifyOpenID4VPResponse(
        now: Instant,
        vpToken: JsonObject,
        sessionTranscript: DataItem,
        nonce: ByteString,
        documentTypeRepository: DocumentTypeRepository?,
        zkSystemRepository: ZkSystemRepository?,
    ): List<VerifiedPresentation> {
        val verifiedPresentations = mutableListOf<VerifiedPresentation>()
        for ((credId, credValue) in vpToken.entries) {
            val creds = credValue as? JsonArray ?: JsonArray(listOf(credValue))
            for (cred in creds) {
                val credBase64 = cred.jsonPrimitive.content
                // Simple heuristic to determine if this is JSON or CBOR
                if (credBase64.startsWith("ey")) {
                    val sdjwtVerifiedPresentations = verifySdJwtPresentation(
                        now = now,
                        compactSerialization = credBase64,
                        nonce = nonce,
                        documentTypeRepository = documentTypeRepository,
                    )
                    verifiedPresentations.add(sdjwtVerifiedPresentations)
                } else {
                    val mdocVerifiedPresentations = verifyMdocDeviceResponse(
                        now = now,
                        deviceResponse = Cbor.decode(credBase64.fromBase64Url()),
                        sessionTranscript = sessionTranscript,
                        eReaderKey = null,
                        documentTypeRepository = documentTypeRepository,
                        zkSystemRepository = zkSystemRepository
                    )
                    verifiedPresentations.addAll(mdocVerifiedPresentations)
                }
            }
        }
        return verifiedPresentations
    }

    /**
     * Generates [VerifiedPresentation] from an SD-JWT / SD-JWT+KB presentation.
     *
     * @param now the current time.
     * @param compactSerialization the compact serialization of the SD-JWT or SD-JWT+KB.
     * @param nonce the nonce used in the request.
     * @param documentTypeRepository a [DocumentTypeRepository] or `null`.
     * @return a [VerifiedPresentation] instance.
     */
    fun verifySdJwtPresentation(
        now: Instant,
        compactSerialization: String,
        nonce: ByteString,
        documentTypeRepository: DocumentTypeRepository?,
    ): VerifiedPresentation {
        val (sdJwt, sdJwtKb) = if (compactSerialization.endsWith("~")) {
            Pair(SdJwt(compactSerialization), null)
        } else {
            val sdJwtKb = SdJwtKb(compactSerialization)
            Pair(sdJwtKb.sdJwt, sdJwtKb)
        }

        val issuerCertChain = sdJwt.x5c
        if (issuerCertChain == null) {
            throw IllegalStateException("Issuer-signed key not in `x5c` in header")
        }
        val processedPayload = if (sdJwtKb != null) {
            sdJwtKb.verify(
                issuerKey = issuerCertChain.certificates.first().ecPublicKey,
                checkNonce = { nonceInCredential -> nonceInCredential == nonce.toByteArray().toBase64Url() },
                checkAudience = { true }, // TODO
                checkCreationTime = { true }
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
                claimPath = JsonArray(listOf(JsonPrimitive(claimName))),
                value = claimValue
            ))
        }

        val deviceSignedClaims = mutableListOf<JsonClaim>()
        if (sdJwtKb != null) {
            for ((claimName, claimValue) in sdJwtKb.jwtBody) {
                val jsonAttr = dt?.jsonDocumentType?.getDocumentAttribute(claimName)
                claims.add(JsonClaim(
                    displayName = jsonAttr?.displayName ?: claimName + " (Device Signed)",
                    attribute = jsonAttr,
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
            vct = vct
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
     * @return a list of [VerifiedPresentation], one for each document in the response.
     */
    suspend fun verifyMdocDeviceResponse(
        now: Instant,
        deviceResponse: DataItem,
        sessionTranscript: DataItem,
        eReaderKey: AsymmetricKey?,
        documentTypeRepository: DocumentTypeRepository?,
        zkSystemRepository: ZkSystemRepository?,
    ): List<VerifiedPresentation> {
        val parser = DeviceResponseParser(
            encodedDeviceResponse = Cbor.encode(deviceResponse),
            encodedSessionTranscript = Cbor.encode(sessionTranscript)
        )
        eReaderKey?.let { parser.setEphemeralReaderKey(it) }
        val dr = parser.parse()
        val verifiedPresentations = mutableListOf<VerifiedPresentation>()
        for (document in dr.documents) {
            if (!document.issuerSignedAuthenticated) {
                throw IllegalStateException("Issuer-signed data failed authentication")
            }
            if (document.numIssuerEntryDigestMatchFailures > 0) {
                throw IllegalStateException(
                    "${document.numIssuerEntryDigestMatchFailures} digest failures in issuer-signed data"
                )
            }
            Logger.iCbor(TAG, "sessionTranscript", Cbor.encode(sessionTranscript))
            if (!document.deviceSignedAuthenticated) {
                throw IllegalStateException("Device-signed data failed authentication")
            }
            val dt = documentTypeRepository?.getDocumentTypeForMdoc(document.docType)

            val issuerSignedClaims = mutableListOf<MdocClaim>()
            for (namespaceName in document.issuerNamespaces) {
                for (dataElementName in document.getIssuerEntryNames(namespaceName)) {
                    val value = document.getIssuerEntryData(namespaceName, dataElementName)
                    val mdocAttr = dt?.mdocDocumentType?.namespaces?.get(namespaceName)?.dataElements?.get(dataElementName)
                    issuerSignedClaims.add(
                        MdocClaim(
                            displayName = mdocAttr?.attribute?.displayName ?: dataElementName,
                            attribute = mdocAttr?.attribute,
                            namespaceName = namespaceName,
                            dataElementName = dataElementName,
                            value = Cbor.decode(value)
                        )
                    )
                }
            }

            val deviceSignedClaims = mutableListOf<MdocClaim>()
            for (namespaceName in document.deviceNamespaces) {
                for (dataElementName in document.getDeviceEntryNames(namespaceName)) {
                    val value = document.getDeviceEntryData(namespaceName, dataElementName)
                    val mdocAttr = dt?.mdocDocumentType?.namespaces?.get(namespaceName)?.dataElements?.get(dataElementName)
                    deviceSignedClaims.add(
                        MdocClaim(
                            displayName = mdocAttr?.attribute?.displayName ?: dataElementName,
                            attribute = mdocAttr?.attribute,
                            namespaceName = namespaceName,
                            dataElementName = dataElementName,
                            value = Cbor.decode(value)
                        )
                    )
                }
            }
            verifiedPresentations.add(
                MdocVerifiedPresentation(
                    documentSignerCertChain = document.issuerCertificateChain,
                    issuerSignedClaims = issuerSignedClaims,
                    deviceSignedClaims = deviceSignedClaims,
                    zkpUsed = false,
                    validFrom = document.validityInfoValidFrom,
                    validUntil = document.validityInfoValidUntil,
                    expectedUpdate = document.validityInfoExpectedUpdate,
                    signedAt = document.validityInfoSigned,
                    docType = document.docType
                )
            )

        }
        for (zkDocument in dr.zkDocuments) {
            val zkSystemSpec = zkSystemRepository?.getAllZkSystemSpecs()?.find {
                it.id == zkDocument.documentData.zkSystemSpecId
            } ?: throw IllegalStateException("Zk System '${zkDocument.documentData.zkSystemSpecId}' was not found.")
            zkSystemRepository.lookup(zkSystemSpec.system)
                ?.verifyProof(
                    zkDocument = zkDocument,
                    zkSystemSpec = zkSystemSpec,
                    encodedSessionTranscript = ByteString(Cbor.encode(sessionTranscript))
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
                            namespaceName = namespaceName,
                            dataElementName = dataElementName,
                            value = value
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
                            namespaceName = namespaceName,
                            dataElementName = dataElementName,
                            value = value
                        )
                    )
                }
            }

            verifiedPresentations.add(
                MdocVerifiedPresentation(
                    documentSignerCertChain = zkDocument.documentData.msoX5chain!!,
                    issuerSignedClaims = issuerSignedClaims,
                    deviceSignedClaims = deviceSignedClaims,
                    zkpUsed = true,
                    validFrom = null,
                    validUntil = null,
                    expectedUpdate = null,
                    signedAt = null,
                    docType = zkDocument.documentData.docType
                )
            )
        }

        return verifiedPresentations
    }
}
