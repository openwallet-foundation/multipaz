package org.multipaz.openid

import io.ktor.utils.io.core.toByteArray
import kotlinx.io.bytestring.decodeToString
import kotlin.time.Clock
import kotlinx.io.bytestring.encodeToByteString
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import org.multipaz.cbor.Bstr
import org.multipaz.cbor.Cbor
import org.multipaz.cbor.Simple
import org.multipaz.cbor.addCborArray
import org.multipaz.cbor.buildCborArray
import org.multipaz.credential.Credential
import org.multipaz.credential.SecureAreaBoundCredential
import org.multipaz.crypto.Algorithm
import org.multipaz.crypto.Crypto
import org.multipaz.crypto.EcCurve
import org.multipaz.crypto.EcPublicKey
import org.multipaz.crypto.EcPublicKeyDoubleCoordinate
import org.multipaz.crypto.JsonWebEncryption
import org.multipaz.crypto.AsymmetricKey
import org.multipaz.document.Document
import org.multipaz.eventlogger.EventPresentmentData
import org.multipaz.webtoken.buildJwt
import org.multipaz.mdoc.credential.MdocCredential
import org.multipaz.mdoc.response.DeviceResponse
import org.multipaz.mdoc.response.MdocDocument
import org.multipaz.mdoc.response.buildDeviceResponse
import org.multipaz.mdoc.zkp.ZkSystem
import org.multipaz.mdoc.zkp.ZkSystemSpec
import org.multipaz.openid.dcql.DcqlCredentialQueryException
import org.multipaz.openid.dcql.DcqlQuery
import org.multipaz.presentment.CredentialMatchSourceOpenID4VP
import org.multipaz.presentment.CredentialPresentmentSetOptionMemberMatch
import org.multipaz.presentment.PresentmentCanceledException
import org.multipaz.presentment.PresentmentCannotSatisfyRequestException
import org.multipaz.presentment.PresentmentSource
import org.multipaz.request.JsonRequestedClaim
import org.multipaz.request.MdocRequestedClaim
import org.multipaz.request.Requester
import org.multipaz.sdjwt.SdJwt
import org.multipaz.sdjwt.credential.SdJwtVcCredential
import org.multipaz.presentment.PresentmentUnlockReason
import org.multipaz.presentment.TransactionDataJson
import org.multipaz.presentment.ConsentData
import org.multipaz.presentment.TransactionData
import org.multipaz.presentment.computeTransactionResponse
import org.multipaz.request.OpenID4VPRequesterIdentity
import org.multipaz.request.RequesterIdentity
import org.multipaz.util.Logger
import org.multipaz.util.fromBase64Url
import org.multipaz.util.toBase64Url
import org.multipaz.verification.VerifierIdentity
import org.multipaz.webtoken.buildMultisignedJwt
import kotlin.coroutines.cancellation.CancellationException
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.random.Random

object OpenID4VP {
    private const val TAG = "OpenID4VP"

    enum class Version {
        DRAFT_24,
        DRAFT_29
    }

    enum class ResponseMode {
        DIRECT_POST,
        DC_API,
    }

    /**
     * Generates an OpenID4VP request.
     *
     * @param version the version of OpenID4vp to generate the request for.
     * @param origin the origin, e.g. `https://verifier.multipaz.org` or `android:apk-key-hash:<sha256_hash-of-apk-signing-cert>`.
     * @param nonce the nonce to use.
     * @param responseEncryptionKey the key to encrypt the response against or `null`.
     * @param verifierIdentities verifier identities that are used to sign the request (there may be
     *  multiple for multisigned requests, or none for unsigned requests).
     * @param responseMode the response mode.
     * @param responseUri the response URI or `null`.
     * @param dcqlQuery the DCQL query.
     * @param jsonTransactionData strings from `transaction_data` array *before* base64url encoding,
     *   see OpenID4VP 1.0 section 8.4.
     * @param state OpenID4VP state parameter (optional)
     * @return the OpenID4VP request.
     */
    suspend fun generateRequest(
        version: Version,
        origin: String,
        nonce: String,
        responseEncryptionKey: EcPublicKey?,
        verifierIdentities: List<VerifierIdentity>,
        responseMode: ResponseMode,
        responseUri: String?,
        dcqlQuery: JsonObject,
        jsonTransactionData: List<String> = emptyList(),
        state: String? = null
    ): JsonObject {
        if (version == Version.DRAFT_24) {
            check(jsonTransactionData.isEmpty())
            check(verifierIdentities.size <= 1)
            return generateRequestDraft24(
                origin = origin,
                clientId = verifierIdentities.firstOrNull()?.clientId,
                nonce = nonce,
                responseEncryptionKey = responseEncryptionKey,
                requestSigningKey = verifierIdentities.firstOrNull()?.key,
                responseMode = responseMode,
                responseUri = responseUri,
                dclqQuery = dcqlQuery
            )
        }
        val responseEncryptionKeyJwk = responseEncryptionKey?.let {
            it.toJwk(additionalClaims = buildJsonObject {
                put("kid", "response-encryption-key")
                put("alg", "ECDH-ES")
                put("use", "enc")
            })
        }
        val jsonBuildBlock: JsonObjectBuilder.() -> Unit = {
            put("response_type", "vp_token")
            put("response_mode",
                when (responseMode) {
                    ResponseMode.DC_API -> if (responseEncryptionKey != null) "dc_api.jwt" else "dc_api"
                    ResponseMode.DIRECT_POST -> if (responseEncryptionKey != null) "direct_post.jwt" else "direct_post"
                }
            )
            responseUri?.let { put("response_uri", it)}
            if (verifierIdentities.size == 1) {
                // for multisign request, client_id goes into the header instead
                put("client_id", verifierIdentities.first().clientId)
                putJsonArray("expected_origins") {
                    add(origin)
                }
            }
            put("dcql_query", dcqlQuery)
            put("nonce", nonce)
            state?.let { put("state", it) }
            putJsonObject("client_metadata") {
                // TODO: take parameters for all these
                put("vp_formats_supported", buildJsonObject {
                    putJsonObject("mso_mdoc") {
                        putJsonArray("issuerauth_alg_values") {
                            add(Algorithm.ESP256.coseAlgorithmIdentifier)
                            add(Algorithm.ES256.coseAlgorithmIdentifier)
                        }
                        putJsonArray("deviceauth_alg_values") {
                            add(Algorithm.ESP256.coseAlgorithmIdentifier)
                            add(Algorithm.ES256.coseAlgorithmIdentifier)
                        }
                    }
                    putJsonObject("dc+sd-jwt") {
                        putJsonArray("sd-jwt_alg_values") {
                            add(Algorithm.ESP256.joseAlgorithmIdentifier)
                        }
                        putJsonArray("kb-jwt_alg_values") {
                            add(Algorithm.ESP256.joseAlgorithmIdentifier)
                        }
                    }
                })
                if (responseEncryptionKeyJwk != null) {
                    putJsonObject("jwks") {
                        putJsonArray("keys") {
                            add(responseEncryptionKeyJwk)
                        }
                    }
                }
            }
            if (jsonTransactionData.isNotEmpty()) {
                put("transaction_data", JsonArray(jsonTransactionData.map {
                    JsonPrimitive(it.toByteArray().toBase64Url())
                }))
            }
        }

        return if (verifierIdentities.isEmpty()) {
            // unsigned request
            buildJsonObject {
                jsonBuildBlock()
            }
        } else if (verifierIdentities.size == 1) {
            // single signature, compact serialization
            buildJsonObject {
                put(
                    "request", buildJwt(
                        key = verifierIdentities.first().key,
                        type = "oauth-authz-req+jwt",
                        builderAction = jsonBuildBlock
                    )
                )
            }
        } else {
            // multisigned request
            buildMultisignedJwt(
                keys = verifierIdentities.map { it.key },
                type = "oauth-authz-req+jwt",
                builderAction = jsonBuildBlock,
                header = { index -> put("client_id", verifierIdentities[index].clientId) }
            )
        }
    }

    private suspend fun generateRequestDraft24(
        origin: String?,
        clientId: String?,
        nonce: String,
        responseEncryptionKey: EcPublicKey?,
        requestSigningKey: AsymmetricKey?,
        responseMode: ResponseMode,
        responseUri: String?,
        dclqQuery: JsonObject
    ): JsonObject {
        val responseEncryptionKeyJwk = responseEncryptionKey?.let {
            it.toJwk(additionalClaims = buildJsonObject {
                put("kid", "response-encryption-key")
                put("alg", "ECDH-ES")
                put("use", "enc")
            })
        }
        val jsonBuildBlock: JsonObjectBuilder.() -> Unit = {
            put("response_type", "vp_token")
            put("response_mode",
                when (responseMode) {
                    ResponseMode.DC_API -> if (responseEncryptionKey != null) "dc_api.jwt" else "dc_api"
                    ResponseMode.DIRECT_POST -> if (responseEncryptionKey != null) "direct_post.jwt" else "direct_post"
                }
            )
            responseUri?.let { put("response_uri", it)}
            if (requestSigningKey != null) {
                require(clientId != null) { "clientId must be set for signed requests"}
                put("client_id", clientId)
                if (origin != null) {
                    putJsonArray("expected_origins") {
                        add(origin)
                    }
                }
            }
            put("dcql_query", dclqQuery)
            put("nonce", nonce)
            putJsonObject("client_metadata") {
                put("vp_formats", buildJsonObject {
                    putJsonObject("mso_mdoc") {
                        putJsonArray("alg") {
                            add("ES256")
                        }
                    }
                    putJsonObject("dc+sd-jwt") {
                        putJsonArray("sd-jwt_alg_values") {
                            add("ES256")
                        }
                        putJsonArray("kb-jwt_alg_values") {
                            add("ES256")
                        }
                    }
                })
                if (responseEncryptionKeyJwk != null) {
                    put("authorization_encrypted_response_alg", "ECDH-ES")
                    put("authorization_encrypted_response_enc", "A128GCM")
                    putJsonObject("jwks") {
                        putJsonArray("keys") {
                            add(responseEncryptionKeyJwk)
                        }
                    }
                }
            }
        }

        return buildJsonObject {
            if (requestSigningKey == null) {
                jsonBuildBlock()
            } else {
                put("request", buildJwt(
                    key = requestSigningKey,
                    type = "oauth-authz-req+jwt",
                    builderAction = jsonBuildBlock
                ))
            }
        }
    }

    /**
     * Represents the response to an OpenID4VP request.
     *
     * @property response the response containing [vpToken], possibly encrypted.
     * @property vpToken the VP Token.
     * @property eventData a [EventPresentmentData] to be used for logging.
     * @property state state parameter as specified in the request (if given)
     */
    data class OpenID4VPResponse(
        val response: JsonObject,
        val vpToken: JsonObject,
        val eventData: EventPresentmentData,
        val state: String?,
    )

    /**
     * Generates an OpenID4VP response.
     *
     * @param version the version of OpenID4VP to generate a response for.
     * @param preselectedDocuments the list of documents the user may have preselected earlier (for
     *   example an OS-provided credential picker like Android's Credential Manager) or the empty list
     *   if the user didn't preselect.
     * @param source an object for application to provide data and policy.
     * @property appId if this is a request from a local application, this contains the app identifier
     *   for example `com.example.app` on Android or `<teamId>.<bundleId>` on iOS.
     * @param origin the origin of the requester or `null` if not known.
     * @param request the authorization request object according to OpenID4VP Section 5 Authorization
     *   Request.
     * @param requesterIdentities verifier identities that were used to sign the request (there
     *  may be multiple for multisigned requests, or none for unsigned requests).
     * @return the generated response according to OpenID4VP Section 8 Response.
     * @throws PresentmentCanceledException if the user canceled in a consent prompt.
     * @throws PresentmentCannotSatisfyRequestException if it's not possible to satisfy the request.
     */
    @Throws(
        CancellationException::class,
        IllegalStateException::class,
        PresentmentCanceledException::class,
        PresentmentCannotSatisfyRequestException::class
    )
    @OptIn(ExperimentalEncodingApi::class)
    suspend fun generateResponse(
        version: Version,
        preselectedDocuments: List<Document>,
        source: PresentmentSource,
        appId: String?,
        origin: String?,
        request: JsonObject,
        requesterIdentities: List<RequesterIdentity>,
        onDocumentsInFocus: (documents: List<Document>) -> Unit = {},
    ): OpenID4VPResponse {
        Logger.iJson(TAG, "request", request)

        val nonce = request["nonce"]!!.jsonPrimitive.content
        val responseModeText = request["response_mode"]!!.jsonPrimitive.content

        // TODO: when we're done implementing all the features, read through spec and make sure we check
        //   each and every parameter and throw helpful errors.

        val (responseUri, responseMode) = when (responseModeText) {
            "dc_api", "dc_api.jwt" -> Pair(null, ResponseMode.DC_API)
            "direct_post", "direct_post.jwt" -> {
                val uri = request["response_uri"]?.jsonPrimitive?.content
                    ?: throw IllegalArgumentException("No response_uri set for $responseModeText")
                Pair(uri, ResponseMode.DIRECT_POST)
            }

            else -> throw IllegalArgumentException("Unexpected response_mode $responseModeText")
        }
        // TODO: in the future, maybe flat out reject requests that doesn't use encrypted response

        // TODO: handle expected_origins

        // Get public key to encrypt response against...
        var reEncAlg: Algorithm = Algorithm.UNSET
        var reKid: String? = null
        val reReaderPublicKey: EcPublicKey? = when (responseModeText) {
            "dc_api", "direct_post" -> null
            "dc_api.jwt", "direct_post.jwt" -> {
                val clientMetadata = request["client_metadata"]!!.jsonObject
                if (version == Version.DRAFT_29) {
                    val jwks = clientMetadata["jwks"]!!.jsonObject
                    val keys = jwks["keys"]!!.jsonArray
                    val jwk = keys[0].jsonObject
                    val encKey = EcPublicKey.fromJwk(jwk)
                    reKid = jwk["kid"]?.jsonPrimitive?.content
                    val alg = jwk["alg"]?.jsonPrimitive?.content
                    /* comment back when digital-credentials.dev have been fixed
                    if (alg == null) {
                        throw IllegalStateException("alg not set on response encryption key")
                    }
                    if (alg != "ECDH-ES") {
                        throw IllegalStateException("alg is '$alg' but only ECDH-ES is supported")
                    }

                     */
                    // TODO: check encrypted_response_enc_values_supported
                    reEncAlg = Algorithm.A128GCM
                    encKey
                } else {
                    val reAlg =
                        clientMetadata["authorization_encrypted_response_alg"]?.jsonPrimitive?.content
                            ?: "ECDH-ES"
                    if (reAlg != "ECDH-ES") {
                        throw IllegalStateException("Only ECDH-ES is supported for authorization_encrypted_response_alg")
                    }
                    val reEnc =
                        clientMetadata["authorization_encrypted_response_enc"]?.jsonPrimitive?.content
                            ?: "A128GCM"
                    reEncAlg = when (reEnc) {
                        "A128GCM" -> Algorithm.A128GCM
                        "A192GCM" -> Algorithm.A192GCM
                        "A256GCM" -> Algorithm.A256GCM
                        else -> {
                            throw IllegalStateException("Only A128GCM, A192GCM, A256GCM is supported for authorization_encrypted_response_enc")
                        }
                    }

                    // For now, just use the first key as encryption key (there should be only one). This
                    // will probably be specced out in OpenID4VP.
                    val jwks = clientMetadata["jwks"]!!.jsonObject
                    val keys = jwks["keys"]!!.jsonArray
                    val encKey = keys[0]
                    val x = encKey.jsonObject["x"]!!.jsonPrimitive.content.fromBase64Url()
                    val y = encKey.jsonObject["y"]!!.jsonPrimitive.content.fromBase64Url()
                    EcPublicKeyDoubleCoordinate(EcCurve.P256, x, y)
                }
            }

            else -> throw IllegalStateException("Unexpected response_mode $responseModeText")
        }

        val vpTokens = mutableMapOf<String, String>()
        val dcqlQuery = DcqlQuery.fromJson(request["dcql_query"]!!.jsonObject)
        val transactionDataMap = request["transaction_data"]?.let {
            try {
                TransactionDataJson.parse(it, source.documentTypeRepository)
            } catch (err: IllegalArgumentException) {
                throw IllegalStateException("Problem processing transaction(s)", err)
            }
        } ?: emptyMap()
        val dcqlResponse = try {
            dcqlQuery.execute(
                presentmentSource = source,
                transactionDataMap = transactionDataMap
            )
        } catch (e: DcqlCredentialQueryException) {
            throw PresentmentCannotSatisfyRequestException("Unable to satisfy the request", e)
        }

        val requester = Requester(
            requesterIdentities = requesterIdentities,
            appId = appId,
            origin = origin
        )

        val trustedRequesterIdentity = source.resolveTrust(requester)

        // For unsigned requests, we must ignore client_id as per OpenID4VP section A.2. Request:
        //
        //   The client_id parameter MUST be omitted in unsigned requests defined in Appendix A.3.1. The Wallet
        //   determines the effective Client Identifier from the Origin. The effective Client Identifier is
        //   composed of a synthetic Client Identifier Scheme of web-origin and the Origin itself. For example,
        //   an Origin of https://verifier.example.com would result in an effective Client Identifier of
        //   web-origin:https://verifier.example.com. The transport of the request and Origin to the Wallet
        //   is platform-specific and is out of scope of OpenID4VP over the W3C Digital Credentials API. The
        //   Wallet MUST ignore any client_id parameter that is present in an unsigned request.
        //
        val clientId = (trustedRequesterIdentity?.identity as? OpenID4VPRequesterIdentity)?.clientId ?: run {
            if (requester.requesterIdentities.isEmpty()) {
                // Unsigned request
                val syntheticOrigin = "web-origin:$origin"
                if (request.containsKey("client_id")) {
                    Logger.w(
                        TAG,
                        "Ignoring client_id value of ${request["client_id"]} for an unsigned request, " +
                                "using synthetic origin $syntheticOrigin instead"
                    )
                }
                syntheticOrigin
            } else {
                // Signed request, but none of the signatures are trusted. Just pick the first one.
                // clientId must not be null in the context of OpenID4VP protocol
                (requester.requesterIdentities.first() as OpenID4VPRequesterIdentity).clientId
            }
        }

        val selection = source.showConsentPrompt(
            requester = requester,
            trustedRequesterIdentity = trustedRequesterIdentity,
            consentData = ConsentData.fromCredentialQueryResult(
                credentialQueryResult = dcqlResponse,
                source = source
            ),
            preselectedDocuments = preselectedDocuments,
            onDocumentsInFocus = onDocumentsInFocus
        )
        if (selection == null) {
            throw PresentmentCanceledException("User canceled at document selection time")
        }

        var usingZk = false
        val credentialsPresented = mutableSetOf<Credential>()
        selection.matches.forEach { match ->
            match.source as CredentialMatchSourceOpenID4VP
            val requestIsForZk = match.source.credentialQuery.format == "mso_mdoc_zk"
            if (requestIsForZk) {
                usingZk = true
            }
            val credentialResponse = if (match.source.credentialQuery.mdocDocType != null) {
                openID4VPMsoMdoc(
                    version = version,
                    match = match,
                    source = source,
                    origin = origin,
                    clientId = clientId,
                    nonce = nonce,
                    reReaderPublicKey = reReaderPublicKey,
                    responseUri = responseUri,
                    requestIsForZk = requestIsForZk,
                )
            } else if (match.source.credentialQuery.vctValues != null) {
                openID4VPSdJwt(
                    version = version,
                    match = match,
                    origin = origin,
                    clientId = clientId,
                    nonce = nonce,
                    responseMode = responseMode,
                )
            } else {
                throw IllegalArgumentException("Expected ISO mdoc or IETF SD-JWT, got neither")
            }
            vpTokens[match.source.credentialQuery.id] = credentialResponse
            credentialsPresented.add(match.credential)
        }

        val vpToken = when (version) {
            Version.DRAFT_29 -> {
                buildJsonObject {
                    putJsonObject("vp_token") {
                        for ((dcqlId, response) in vpTokens) {
                            putJsonArray(dcqlId) {
                                // For now we only support returning a single Verifiable Presentation per requested credential,
                                add(response)
                            }
                        }
                    }
                    (request["state"] as? JsonPrimitive)?.let {
                        put("state", it)
                    }
                }
            }

            Version.DRAFT_24 -> {
                buildJsonObject {
                    putJsonObject("vp_token") {
                        for ((dcqlId, response) in vpTokens) {
                            put(dcqlId, response)
                        }
                    }
                    (request["state"] as? JsonPrimitive)?.let {
                        put("state", it)
                    }
                }
            }
        }
        Logger.iJson(TAG, "vpToken", vpToken)

        // If using ZKP the response will be huge so compression helps
        val compressionLevel = if (usingZk) 9 else null

        val walletGeneratedNonce = Random.nextBytes(16).toBase64Url()
        val response = if (reReaderPublicKey != null) {
            buildJsonObject {
                put("response",
                    JsonWebEncryption.encrypt(
                        claimsSet = vpToken.jsonObject,
                        recipientPublicKey = reReaderPublicKey,
                        encAlg = reEncAlg,
                        apu = nonce.encodeToByteString(),
                        apv = walletGeneratedNonce.encodeToByteString(),
                        kid = reKid,
                        compressionLevel = compressionLevel
                    )
                )
            }
        } else {
            vpToken
        }

        return OpenID4VPResponse(
            response = response,
            vpToken = vpToken,
            eventData = EventPresentmentData.fromPresentmentSelection(
                selection = selection,
                requester = requester,
                trustedRequesterIdentity = trustedRequesterIdentity,
            ),
            state = (request["state"] as? JsonPrimitive)?.content
        )
    }

    private suspend fun openID4VPMsoMdoc(
        version: Version,
        match: CredentialPresentmentSetOptionMemberMatch,
        source: PresentmentSource,
        origin: String?,
        clientId: String,
        nonce: String,
        reReaderPublicKey: EcPublicKey?,
        responseUri: String?,
        requestIsForZk: Boolean,
        onDocumentsInFocus: (documents: List<Document>) -> Unit = {},
    ): String {
        match.source as CredentialMatchSourceOpenID4VP
        var zkSystemMatch: ZkSystem? = null
        var zkSystemSpec: ZkSystemSpec? = null
        if (requestIsForZk) {
            check(match.transactionData.isEmpty())
            val requesterSupportedZkSpecs = mutableListOf<ZkSystemSpec>()
            for (entry in match.source.credentialQuery.meta["zk_system_type"]!!.jsonArray) {
                entry as JsonObject
                val system = entry["system"]!!.jsonPrimitive.content
                val id = entry["id"]!!.jsonPrimitive.content
                val item = ZkSystemSpec(id, system)
                for (param in entry) {
                    if (param.key == "system" || param.key == "id") {
                        continue
                    }
                    item.addParam(param.key, param.value)
                }
                requesterSupportedZkSpecs.add(item)
            }
            val zkSystemRepository = source.zkSystemRepository
            if (zkSystemRepository != null) {
                // Find the first ZK System that the requester supports and matches the document
                for (zkSpec in requesterSupportedZkSpecs) {
                    val zkSystem = zkSystemRepository.lookup(zkSpec.system) ?: continue

                    val matchingZkSystemSpec = zkSystem.getMatchingSystemSpec(
                        zkSystemSpecs = requesterSupportedZkSpecs,
                        requestedClaims = match.source.credentialQuery.claims
                    )
                    if (matchingZkSystemSpec != null) {
                        zkSystemMatch = zkSystem
                        zkSystemSpec = matchingZkSystemSpec
                        break
                    }
                }
            }

            if (zkSystemMatch == null) {
                throw IllegalStateException("Request is for ZK but no matching ZK system was found")
            }
        }

        val reReaderPublicKeJwkThumbprint = reReaderPublicKey
            ?.toJwkThumbprint(Algorithm.SHA256)
            ?.toByteArray()
        val handoverInfo = Cbor.encode(
            when (version) {
                Version.DRAFT_29 -> {
                    buildCborArray {
                        val jwkThumbPrint = if (reReaderPublicKeJwkThumbprint == null) {
                            Simple.NULL
                        } else {
                            Bstr(reReaderPublicKeJwkThumbprint)
                        }
                        if (responseUri != null) {
                            // B.2.6.1. Invocation via Redirects
                            add(clientId)
                            add(nonce)
                            add(jwkThumbPrint)
                            add(responseUri)
                        } else {
                            // B.2.6.2. Invocation via the Digital Credentials API
                            add(origin!!)
                            add(nonce)
                            add(jwkThumbPrint)
                        }
                    }
                }
                Version.DRAFT_24 -> {
                    buildCborArray {
                        add(origin!!)
                        add(clientId)
                        add(nonce)
                    }
                }
            }
        )
        Logger.iCbor(TAG, "handoverInfo", handoverInfo)

        val handoverString = if (responseUri != null) {
            "OpenID4VPHandover"
        } else {
            "OpenID4VPDCAPIHandover"
        }
        val handoverInfoDigest = Crypto.digest(Algorithm.SHA256, handoverInfo)
        val encodedSessionTranscript = Cbor.encode(
            buildCborArray {
                add(Simple.NULL) // DeviceEngagementBytes
                add(Simple.NULL) // EReaderKeyBytes
                addCborArray {
                    add(handoverString)
                    add(handoverInfoDigest)
                }
            }
        )
        Logger.iCbor(TAG, "handoverInfo", handoverInfo)
        Logger.iCbor(TAG, "encodedSessionTranscript", encodedSessionTranscript)

        val mdocCredential = match.credential as MdocCredential
        val document = MdocDocument.fromPresentment(
            sessionTranscript = Cbor.decode(encodedSessionTranscript),
            credential = mdocCredential,
            requestedClaims = match.source.credentialQuery.claims as List<MdocRequestedClaim>,
            deviceNamespaces = computeTransactionResponse(match)
        )
        val deviceResponse = buildDeviceResponse(
            sessionTranscript = Cbor.decode(encodedSessionTranscript),
            status = DeviceResponse.STATUS_OK,
        ) {
            if (zkSystemMatch != null) {
                val zkDocument = zkSystemMatch.generateProof(
                    zkSystemSpec = zkSystemSpec!!,
                    document = document,
                    sessionTranscript = sessionTranscript
                )
                Logger.i(TAG, "ZK Proof Size: ${zkDocument.proof.size}")
                addZkDocument(zkDocument)
            } else {
                addDocument(document)
            }
        }
        mdocCredential.increaseUsageCount()
        return Cbor.encode(deviceResponse.toDataItem()).toBase64Url()
    }

    /**
     * Processes transaction data for the given SD-JWT credential.
     *
     * @param credential credential which transactions are targeting, must be key-bound if any
     *   transactions are present (i.e. [transactionData] is not empty)
     * @param transactionData transaction data for the credential
     * @param docRequestId index of the specific document request in the context of ISO 18013
     *   presentation
     * @return additional attributes to add to the SD-JWT+KB body
     */
    suspend fun processTransactions(
        credential: SdJwtVcCredential,
        transactionData: List<TransactionData>,
        docRequestId: Int? = null
    ): Map<String, JsonElement> {
        val transactionResponse = mutableMapOf<String, JsonElement>()
        for (data in transactionData) {
            val response = data.type.applyJson(data, credential as Credential)
            if (response != null || docRequestId != null) {
                transactionResponse[data.type.kbJwtResponseClaimName] = if (docRequestId == null) {
                    response!!
                } else {
                    buildJsonObject {
                        if (response != null) {
                            for ((name, value) in response.jsonObject) {
                                put(name, value)
                            }
                        }
                        put("doc_request_id", docRequestId)
                    }
                }
            }
        }
        val hashAlgorithm = transactionData.firstNotNullOfOrNull {
            it.getHashAlgorithm()
        }
        if (hashAlgorithm != null) {
            // Non-default hash algorithm; ensure all transaction data items are
            // using the same one
            transactionData.forEach { data ->
                check(hashAlgorithm == (data.getHashAlgorithm() ?: Algorithm.SHA256))
            }
            transactionResponse["transaction_data_hashes_alg"] =
                JsonPrimitive(hashAlgorithm.hashAlgorithmName)
        }
        transactionResponse["transaction_data_hashes"] = buildJsonArray {
            transactionData.forEach {
                    data -> add(data.getHash(
                hashAlgorithm ?: Algorithm.SHA256).toByteArray().toBase64Url())
            }
        }
        return transactionResponse
    }

    private suspend fun openID4VPSdJwt(
        version: Version,
        match: CredentialPresentmentSetOptionMemberMatch,
        origin: String?,
        clientId: String,
        nonce: String,
        responseMode: ResponseMode,
    ): String {
        match.source as CredentialMatchSourceOpenID4VP
        val sdjwtVcCredential = match.credential as SdJwtVcCredential
        val claims = match.source.credentialQuery.claims as List<JsonRequestedClaim>

        val sdJwt = SdJwt.fromCompactSerialization(sdjwtVcCredential.issuerProvidedData.decodeToString())
        val pathsToDisclose = claims.map { claim: JsonRequestedClaim -> claim.claimPath }
        val filteredSdJwt = sdJwt.filter(pathsToDisclose)

        (sdjwtVcCredential as Credential).increaseUsageCount()

        val transactionResponse = processTransactions(sdjwtVcCredential, match.transactionData)

        return if (sdjwtVcCredential is SecureAreaBoundCredential) {
            filteredSdJwt.present(
                signingKey = AsymmetricKey.anonymous(
                    alias = sdjwtVcCredential.alias,
                    secureArea = sdjwtVcCredential.secureArea,
                    unlockReason = PresentmentUnlockReason(sdjwtVcCredential),
                ),
                nonce = nonce,
                audience = if (version == Version.DRAFT_29) {
                    // From B.3.6. Presentation Response:
                    //
                    // the aud claim MUST be the value of the Client Identifier, except for requests over the DC API
                    // where it MUST be the Origin prefixed with origin:, as described in Appendix A.4.
                    when(responseMode) {
                        ResponseMode.DIRECT_POST -> clientId
                        ResponseMode.DC_API -> "origin:$origin"
                    }
                } else {
                    clientId
                },
                creationTime = Clock.System.now()
            ) {
                if (!match.transactionData.isEmpty()) {
                    for ((key, response) in transactionResponse) {
                        put(key, response)
                    }
                }
            }.compactSerialization
        } else {
            filteredSdJwt.compactSerialization
        }
    }

}