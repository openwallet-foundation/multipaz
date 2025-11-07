package org.multipaz.openid

import kotlin.time.Clock
import kotlinx.io.bytestring.ByteString
import kotlinx.io.bytestring.encodeToByteString
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.add
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
import org.multipaz.crypto.X509CertChain
import org.multipaz.document.Document
import org.multipaz.document.NameSpacedData
import org.multipaz.jwt.buildJwt
import org.multipaz.mdoc.credential.MdocCredential
import org.multipaz.mdoc.issuersigned.IssuerNamespaces
import org.multipaz.mdoc.mso.MobileSecurityObjectParser
import org.multipaz.mdoc.response.DeviceResponseGenerator
import org.multipaz.mdoc.response.DocumentGenerator
import org.multipaz.mdoc.zkp.ZkSystem
import org.multipaz.mdoc.zkp.ZkSystemSpec
import org.multipaz.openid.dcql.DcqlQuery
import org.multipaz.openid.dcql.DcqlResponseCredentialSetOptionMemberMatch
import org.multipaz.presentment.model.PresentmentCanceled
import org.multipaz.presentment.model.PresentmentSource
import org.multipaz.presentment.model.findTrustPoint
import org.multipaz.presentment.CredentialPresentmentData
import org.multipaz.presentment.CredentialPresentmentSelection
import org.multipaz.request.JsonRequestedClaim
import org.multipaz.request.MdocRequestedClaim
import org.multipaz.request.Requester
import org.multipaz.sdjwt.SdJwt
import org.multipaz.sdjwt.credential.SdJwtVcCredential
import org.multipaz.presentment.PresentmentUnlockReason
import org.multipaz.trustmanagement.TrustPoint
import org.multipaz.util.Constants
import org.multipaz.util.Logger
import org.multipaz.util.fromBase64Url
import org.multipaz.util.toBase64Url
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
     * @param clientId the client ID, e.g. `x509_san_dns:verifier.multipaz.org` or `null`.
     * @param nonce the nonce to use.
     * @param responseEncryptionKey the key to encrypt the response against or `null`.
     * @param requestSigningKey the key to sign the request with or `null`.
     * @param responseMode the response mode.
     * @param responseUri the response URI or `null`.
     * @param dclqQuery the DCQL query.
     * @return the OpenID4VP request.
     */
    suspend fun generateRequest(
        version: Version,
        origin: String,
        clientId: String?,
        nonce: String,
        responseEncryptionKey: EcPublicKey?,
        requestSigningKey: AsymmetricKey?,
        responseMode: ResponseMode,
        responseUri: String?,
        dclqQuery: JsonObject,
    ): JsonObject {
        if (version == Version.DRAFT_24) {
            return generateRequestDraft24(
                origin = origin,
                clientId = clientId,
                nonce = nonce,
                responseEncryptionKey = responseEncryptionKey,
                requestSigningKey = requestSigningKey,
                responseMode = responseMode,
                responseUri = responseUri,
                dclqQuery = dclqQuery
            )
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
                putJsonArray("expected_origins") {
                    add(origin)
                }
            }
            put("dcql_query", dclqQuery)
            put("nonce", nonce)
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
                if (responseEncryptionKey != null) {
                    putJsonObject("jwks") {
                        putJsonArray("keys") {
                            add(responseEncryptionKey
                                .toJwk(additionalClaims = buildJsonObject {
                                    put("kid", "response-encryption-key")
                                    put("alg", "ECDH-ES")
                                    put("use", "enc")
                                }))
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
                    body = jsonBuildBlock
                ))
            }
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
                if (responseEncryptionKey != null) {
                    put("authorization_encrypted_response_alg", "ECDH-ES")
                    put("authorization_encrypted_response_enc", "A128GCM")
                    putJsonObject("jwks") {
                        putJsonArray("keys") {
                            add(
                                responseEncryptionKey
                                    .toJwk(additionalClaims = buildJsonObject {
                                        put("kid", "response-encryption-key")
                                        put("alg", "ECDH-ES")
                                        put("use", "enc")
                                    })
                            )
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
                    body = jsonBuildBlock
                ))
            }
        }
    }

    /**
     * Generates an OpenID4VP response.
     *
     * @param version the version of OpenID4VP to generate a response for.
     * @param preselectedDocuments the list of documents the user may have preselected earlier (for
     *   example an OS-provided credential picker like Android's Credential Manager) or the empty list
     *   if the user didn't preselect.
     * @param source an object for application to provide data and policy.
     * @param showConsentPrompt a function to ask the user for consent.
     * @property appId if this is a request from a local application, this contains the app identifier
     *   for example `com.example.app` on Android or `<teamId>.<bundleId>` on iOS.
     * @param origin the origin of the requester or `null` if not known.
     * @param request the authorization request object according to OpenID4VP Section 5 Authorization
     *   Request.
     * @param requesterCertChain the X.509 certificate chain if the request is signed or `null`
     *   if the request is not signed.
     * @return the generated response according to OpenID4VP Section 8 Response.
     */
    @OptIn(ExperimentalEncodingApi::class)
    suspend fun generateResponse(
        version: Version,
        preselectedDocuments: List<Document>,
        source: PresentmentSource,
        showConsentPrompt: suspend (
            credentialPresentmentData: CredentialPresentmentData,
            preselectedDocuments: List<Document>,
            requester: Requester,
            trustPoint: TrustPoint?
        ) -> CredentialPresentmentSelection?,
        appId: String?,
        origin: String?,
        request: JsonObject,
        requesterCertChain: X509CertChain?,
    ): JsonObject {
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
        val clientId = if (requesterCertChain != null) {
            request["client_id"]?.jsonPrimitive?.content
                ?: throw IllegalArgumentException("client_id not set on a signed request")
        } else {
            val syntheticOrigin = "web-origin:$origin"
            if (request.containsKey("client_id")) {
                Logger.w(
                    TAG, "Ignoring client_id value of ${request["client_id"]} for an unsigned request, " +
                            "using synthetic origin $syntheticOrigin instead"
                )
            }
            syntheticOrigin
        }

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
        val dcqlResponse = dcqlQuery.execute(
            presentmentSource = source,
        )

        val requester = Requester(
            certChain = requesterCertChain,
            appId = appId,
            origin = origin
        )
        val trustPoint = source.findTrustPoint(requester)

        val selection = if (source.skipConsentPrompt) {
            dcqlResponse.select(preselectedDocuments)
        } else {
            showConsentPrompt(
                dcqlResponse,
                preselectedDocuments,
                requester,
                trustPoint
            )
        }
        if (selection == null) {
            throw PresentmentCanceled("User canceled at document selection time")
        }

        var usingZk = false
        selection.matches.forEach { match ->
            match as DcqlResponseCredentialSetOptionMemberMatch
            val requestIsForZk = match.credentialQuery.format == "mso_mdoc_zk"
            if (requestIsForZk) {
                usingZk = true
            }
            val credentialResponse = if (match.credentialQuery.mdocDocType != null) {
                openID4VPMsoMdoc(
                    version = version,
                    match = match,
                    source = source,
                    origin = origin,
                    clientId = clientId,
                    nonce = nonce,
                    reReaderPublicKey = reReaderPublicKey,
                    responseUri = responseUri,
                    requestIsForZk = requestIsForZk
                )
            } else if (match.credentialQuery.vctValues != null) {
                openID4VPSdJwt(
                    version = version,
                    match = match,
                    origin = origin,
                    clientId = clientId,
                    nonce = nonce,
                    responseMode = responseMode
                )
            } else {
                throw IllegalArgumentException("Expected ISO mdoc or IETF SD-JWT, got neither")
            }
            vpTokens.put(match.credentialQuery.id, credentialResponse)
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
                }
            }

            Version.DRAFT_24 -> {
                buildJsonObject {
                    putJsonObject("vp_token") {
                        for ((dcqlId, response) in vpTokens) {
                            put(dcqlId, response)
                        }
                    }
                }
            }
        }
        Logger.iJson(TAG, "vpToken", vpToken)

        // If using ZKP the response will be huge so compression helps
        val compressionLevel = if (usingZk) 9 else null

        val walletGeneratedNonce = Random.nextBytes(16).toBase64Url()
        return if (reReaderPublicKey != null) {
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
    }

    private suspend fun openID4VPMsoMdoc(
        version: Version,
        match: DcqlResponseCredentialSetOptionMemberMatch,
        source: PresentmentSource,
        origin: String?,
        clientId: String,
        nonce: String,
        reReaderPublicKey: EcPublicKey?,
        responseUri: String?,
        requestIsForZk: Boolean
    ): String {
        var zkSystemMatch: ZkSystem? = null
        var zkSystemSpec: ZkSystemSpec? = null
        if (requestIsForZk) {
            val requesterSupportedZkSpecs = mutableListOf<ZkSystemSpec>()
            for (entry in match.credentialQuery.meta["zk_system_type"]!!.jsonArray) {
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
                    val zkSystem = zkSystemRepository.lookup(zkSpec.system)
                    if (zkSystem == null) {
                        continue
                    }

                    val matchingZkSystemSpec = zkSystem.getMatchingSystemSpec(
                        zkSystemSpecs = requesterSupportedZkSpecs,
                        requestedClaims = match.credentialQuery.claims
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

        val handoverInfo = Cbor.encode(
            when (version) {
                Version.DRAFT_29 -> {
                    buildCborArray {
                        val jwkThumbPrint = if (reReaderPublicKey == null) {
                            Simple.NULL
                        } else {
                            Bstr(reReaderPublicKey.toJwkThumbprint(Algorithm.SHA256).toByteArray())
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
        val encodedSessionTranscript = Cbor.encode(
            buildCborArray {
                add(Simple.NULL) // DeviceEngagementBytes
                add(Simple.NULL) // EReaderKeyBytes
                addCborArray {
                    add(handoverString)
                    add(Crypto.digest(Algorithm.SHA256, handoverInfo))
                }
            }
        )
        Logger.iCbor(TAG, "handoverInfo", handoverInfo)
        Logger.iCbor(TAG, "encodedSessionTranscript", encodedSessionTranscript)

        val mdocCredential = match.credential as MdocCredential
        val claims = match.credentialQuery.claims as List<MdocRequestedClaim>
        val documentBytes = calcDocument(
            credential = mdocCredential,
            requestedClaims = claims,
            encodedSessionTranscript = encodedSessionTranscript,
        )

        val deviceResponseGenerator = DeviceResponseGenerator(Constants.DEVICE_RESPONSE_STATUS_OK)
        if (zkSystemMatch != null) {
            val zkDocument = zkSystemMatch.generateProof(
                zkSystemSpec!!,
                ByteString(documentBytes),
                ByteString(encodedSessionTranscript)
            )
            Logger.i(TAG, "ZK Proof Size: ${zkDocument.proof.size}")
            deviceResponseGenerator.addZkDocument(zkDocument)
        } else {
            deviceResponseGenerator.addDocument(documentBytes)
        }
        val deviceResponse = deviceResponseGenerator.generate()
        mdocCredential.increaseUsageCount()
        return deviceResponse.toBase64Url()
    }

    private suspend fun calcDocument(
        credential: MdocCredential,
        requestedClaims: List<MdocRequestedClaim>,
        encodedSessionTranscript: ByteArray
    ): ByteArray {
        // TODO: support MAC keys from v1.1 request and use setDeviceNamespacesMac() when possible
        //   depending on the value of PresentmentSource.preferSignatureToKeyAgreement(). See also
        //   calcDocument in mdocPresentment.kt.
        //

        val issuerSigned = Cbor.decode(credential.issuerProvidedData)
        val issuerNamespaces = IssuerNamespaces.fromDataItem(issuerSigned["nameSpaces"])
        val issuerAuthCoseSign1 = issuerSigned["issuerAuth"].asCoseSign1
        val encodedMsoBytes = Cbor.decode(issuerAuthCoseSign1.payload!!)
        val encodedMso = Cbor.encode(encodedMsoBytes.asTaggedEncodedCbor)
        val mso = MobileSecurityObjectParser(encodedMso).parse()

        val documentGenerator = DocumentGenerator(
            mso.docType,
            Cbor.encode(issuerSigned["issuerAuth"]),
            encodedSessionTranscript,
        )

        documentGenerator.setIssuerNamespaces(issuerNamespaces.filter(requestedClaims))
        val keyInfo = credential.secureArea.getKeyInfo(credential.alias)
        if (!keyInfo.algorithm.isSigning) {
            throw IllegalStateException(
                "Signing is required for W3C DC API but its algorithm ${keyInfo.algorithm.name} is not for signing"
            )
        } else {
            documentGenerator.setDeviceNamespacesSignature(
                dataElements = NameSpacedData.Builder().build(),
                secureArea = credential.secureArea,
                keyAlias = credential.alias,
                unlockReason = PresentmentUnlockReason(credential),
            )
        }

        return documentGenerator.generate()
    }


    private suspend fun openID4VPSdJwt(
        version: Version,
        match: DcqlResponseCredentialSetOptionMemberMatch,
        origin: String?,
        clientId: String,
        nonce: String,
        responseMode: ResponseMode,
    ): String {
        val sdjwtVcCredential = match.credential as SdJwtVcCredential
        val claims = match.credentialQuery.claims as List<JsonRequestedClaim>

        val sdJwt = SdJwt(sdjwtVcCredential.issuerProvidedData.decodeToString())
        val pathsToDisclose = claims.map { claim: JsonRequestedClaim -> claim.claimPath }
        val filteredSdJwt = sdJwt.filter(pathsToDisclose)

        (sdjwtVcCredential as Credential).increaseUsageCount()
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
            ).compactSerialization
        } else {
            filteredSdJwt.compactSerialization
        }
    }
}