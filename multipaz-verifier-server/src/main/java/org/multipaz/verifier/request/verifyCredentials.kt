package org.multipaz.verifier.request

import io.ktor.http.ContentType
import io.ktor.http.Url
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.receiveParameters
import io.ktor.server.request.receiveText
import io.ktor.server.response.respondText
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import kotlinx.io.bytestring.ByteString
import kotlinx.io.bytestring.ByteStringBuilder
import kotlinx.io.bytestring.encodeToByteString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import org.multipaz.cbor.Bstr
import org.multipaz.cbor.Cbor
import org.multipaz.cbor.DataItem
import org.multipaz.cbor.Nint
import org.multipaz.cbor.Simple
import org.multipaz.cbor.Tstr
import org.multipaz.cbor.Uint
import org.multipaz.cbor.addCborArray
import org.multipaz.cbor.addCborMap
import org.multipaz.cbor.buildCborArray
import org.multipaz.crypto.Algorithm
import org.multipaz.crypto.AsymmetricKey
import org.multipaz.crypto.Crypto
import org.multipaz.crypto.Hpke
import org.multipaz.crypto.JsonWebEncryption
import org.multipaz.mdoc.response.DeviceResponse
import org.multipaz.openid.OpenID4VP
import org.multipaz.openid.TransactionData
import org.multipaz.rpc.backend.BackendEnvironment
import org.multipaz.rpc.cache
import org.multipaz.rpc.handler.InvalidRequestException
import org.multipaz.rpc.handler.SimpleCipher
import org.multipaz.sdjwt.SdJwt
import org.multipaz.sdjwt.SdJwtKb
import org.multipaz.server.common.getBaseUrl
import org.multipaz.storage.ephemeral.EphemeralStorage
import org.multipaz.trustmanagement.TrustManager
import org.multipaz.trustmanagement.TrustManagerLocal
import org.multipaz.util.Logger
import org.multipaz.util.fromBase64Url
import org.multipaz.util.toBase64
import org.multipaz.util.toBase64Url
import org.multipaz.verification.VerificationUtil
import org.multipaz.verifier.session.RequestedClaim
import org.multipaz.verifier.session.RequestedDocument
import org.multipaz.verifier.session.Session
import org.multipaz.verifier.session.TransactionDataHashes
import kotlin.collections.component1
import kotlin.collections.component2
import kotlin.collections.iterator
import kotlin.time.Duration.Companion.minutes

private val defaultExchangeProtocols = listOf("org-iso-mdoc", "openid4vp-v1-signed")

suspend fun makeRequest(call: ApplicationCall) {
    val request = Json.parseToJsonElement(call.receiveText()) as JsonObject
    val dcqlQuery = request["dcql"] as? JsonObject
        ?: throw InvalidRequestException("'dcql' is missing or invalid")
    val credentials = dcqlQuery["credentials"] as? JsonArray
        ?: throw InvalidRequestException("'dcql.credentials' is missing or invalid")
    val exchangeProtocols = (request["protocols"] as? JsonArray)?.map { it.jsonPrimitive.content }
        ?: defaultExchangeProtocols
    val transactionData = request["transaction_data"]?.jsonArray?.map {
        it.toString().encodeToByteArray().toBase64Url()
    }
    val nonMdoc = credentials.firstOrNull {
        it.jsonObject["format"]!!.jsonPrimitive.content != "mso_mdoc"
    }
    val protocols = if (transactionData == null && nonMdoc == null) {
        exchangeProtocols
    } else {
        // no support for transaction data yet in org_iso_mdoc
        // org_iso_mdoc can only handle mdoc credentials
        exchangeProtocols.filter { it != "org-iso-mdoc" }
    }
    val (sessionId, session) = Session.createSession(
        dcqlQuery = dcqlQuery.toString(),
        transactionData = transactionData
    )
    val encodedSessionId = encodeSessionId(sessionId)
    val dcRequest = generateRequest(
        encodedSessionId = encodedSessionId,
        sessionId = sessionId,
        session = session,
        responseMode = OpenID4VP.ResponseMode.DC_API,
        exchangeProtocols = protocols
    )
    call.respondText(
        contentType = ContentType.Application.Json,
        text = buildJsonObject {
            put("session_id", encodedSessionId)
            put("client_id", getClientId())
            putJsonObject("dc_request") {
               put("digital", dcRequest)
               put("mediation", "required")
            }
        }.toString()
    )
}

suspend fun processResponse(call: ApplicationCall) {
    val request = Json.parseToJsonElement(call.receiveText()) as JsonObject
    val encodedSessionId = (request["session_id"] as? JsonPrimitive)?.content
        ?: throw InvalidRequestException("'session_id' is missing or invalid")
    val dcResponse = request["dc_response"] as? JsonObject
        ?: throw InvalidRequestException("'dc_response' is missing or invalid")
    val protocol = (dcResponse["protocol"] as? JsonPrimitive)?.content
        ?: throw InvalidRequestException("'dc_response.protocol' is missing or invalid")
    val dcData = dcResponse["data"] as? JsonObject
        ?: throw InvalidRequestException("'dc_response.data' is missing or invalid")
    val sessionId = decodeSessionId(encodedSessionId)
    val session = Session.getSession(sessionId)
        ?: throw InvalidRequestException("Session '$encodedSessionId' is missing or expired")
    session.responseProtocol = protocol
    val result = if (protocol == "org-iso-mdoc") {
        val response = dcData["response"]!!.jsonPrimitive.content.fromBase64Url()
        session.response = ByteString(response)
        processIsoMdocResponse(
            session = session,
            response = response
        )
    } else {
        val responseText = dcData["response"]!!.jsonPrimitive.content
        session.response = responseText.encodeToByteString()
        processOpenID4VPResponseText(
            session = session,
            responseText = responseText
        )
    }
    session.result = result.toString()
    Session.updateSession(sessionId, session)
    call.respondText(
        contentType = ContentType.Application.Json,
        text = buildJsonObject {
            put("session_id", encodedSessionId)
            put("result", result)
        }.toString()
    )
}

suspend fun getRequest(call: ApplicationCall, encodedSessionId: String) {
    val sessionId = decodeSessionId(encodedSessionId)
    val session = Session.getSession(sessionId)
        ?: throw InvalidRequestException("Session '$encodedSessionId' is missing or expired")
    val requestObject = generateRequest(
        encodedSessionId = encodedSessionId,
        sessionId = sessionId,
        session = session,
        responseMode = OpenID4VP.ResponseMode.DIRECT_POST,
        exchangeProtocols = listOf()  // N/A for custom url schemes
    )
    val signedRequestCs = requestObject["request"]!!.jsonPrimitive.content
    call.respondText(
        contentType = ContentType.parse("application/oauth-authz-req+jwt"),
        text = signedRequestCs
    )
}

// Channels to notify pending requests that wait for the result. This only works because we have
// a single process on a single machine on the server. To run it with multiple machines, sharding
// by session has to be done.
val resultChannelMutex = Mutex()
val resultChannels = mutableMapOf<String, MutableSet<Channel<String>>>()

suspend fun processDirectPost(call: ApplicationCall, encodedSessionId: String) {
    val sessionId = decodeSessionId(encodedSessionId)
    val postedData = call.receiveParameters()
    val responseText = postedData["response"]
        ?: throw InvalidRequestException("'response' parameter is missing")
    val session = Session.getSession(sessionId)
        ?: throw InvalidRequestException("Session '$encodedSessionId' is missing or expired")
    session.responseProtocol = "openid4vp-v1-signed"
    session.response = responseText.encodeToByteString()
    val result = processOpenID4VPResponseText(
        session = session,
        responseText = responseText
    ).toString()
    session.result = result
    Session.updateSession(sessionId, session)
    val channels = resultChannelMutex.withLock {
        resultChannels[sessionId]?.toMutableSet() ?: setOf()
    }
    for (channel in channels) {
        channel.trySend(result)
    }
    call.respondText(
        contentType = ContentType.Application.Json,
        text = "{}"
    )
}

suspend fun getResult(call: ApplicationCall, encodedSessionId: String) {
    val sessionId = decodeSessionId(encodedSessionId)
    val channel = Channel<String>(Channel.RENDEZVOUS)
    resultChannelMutex.withLock {
        resultChannels.getOrPut(sessionId) { mutableSetOf() }.add(channel)
    }
    val result = try {
        val session = Session.getSession(sessionId)
            ?: throw InvalidRequestException("Session '$encodedSessionId' is missing or expired")
        session.result ?: withTimeout(3.minutes) { channel.receive() }
    } catch (_: TimeoutCancellationException) {
        null
    } finally {
        resultChannelMutex.withLock {
            channel.close()
            val channels = resultChannels[sessionId]!!
            channels.remove(channel)
            if (channels.isEmpty()) {
                resultChannels.remove(sessionId)
            }
        }
    }
    call.respondText(
        contentType = ContentType.Application.Json,
        text = buildJsonObject {
            if (result == null) {
                put("status", "not_ready")
            } else {
                put("status", "ready")
                put("session_id", encodedSessionId)
                put("result", Json.parseToJsonElement(result))
            }
        }.toString()
    )
}

private suspend fun generateRequest(
    encodedSessionId: String,
    sessionId: String,
    session: Session,
    responseMode: OpenID4VP.ResponseMode,
    exchangeProtocols: List<String>,  // needed for DC API
): JsonObject {
    val sessionIdentity = session.getIdentity(sessionId)
    val baseUrl = BackendEnvironment.getBaseUrl()
    val query = Json.parseToJsonElement(session.dcqlQuery).jsonObject
    return if (responseMode == OpenID4VP.ResponseMode.DC_API) {
        VerificationUtil.generateDcRequestDcql(
            exchangeProtocols = exchangeProtocols,
            dcql = query,
            transactionData = session.transactionData ?: listOf(),
            nonce = session.nonce,
            origin = baseUrl,
            clientId = getClientId(),
            responseEncryptionKey = session.encryptionPrivateKey.publicKey,
            readerAuthenticationKey = sessionIdentity,
        )
    } else {
        OpenID4VP.generateRequest(
            version = OpenID4VP.Version.DRAFT_29,
            nonce = session.nonce.toByteArray().toBase64Url(),
            origin = baseUrl,
            clientId = getClientId(),
            responseEncryptionKey = session.encryptionPrivateKey.publicKey,
            requestSigningKey = sessionIdentity,
            responseMode = responseMode,
            responseUri = if (responseMode == OpenID4VP.ResponseMode.DIRECT_POST) {
                "$baseUrl/direct_post/$encodedSessionId"
            } else {
                null
            },
            dclqQuery = Json.parseToJsonElement(session.dcqlQuery).jsonObject,
            transactionData = session.transactionData ?: listOf()
        )
    }
}

private suspend fun processOpenID4VPResponseText(
    session: Session,
    responseText: String
): JsonObject {
    val decryptedResponse = JsonWebEncryption.decrypt(
        responseText,
        AsymmetricKey.anonymous(
            privateKey = session.encryptionPrivateKey,
            algorithm = session.encryptionPrivateKey.curve.defaultKeyAgreementAlgorithm
        )
    ).jsonObject
    val baseUrl = BackendEnvironment.getBaseUrl()
    val token = decryptedResponse["vp_token"]!!.jsonObject
    val jwkThumbPrint = session.encryptionPrivateKey.publicKey
        .toJwkThumbprint(Algorithm.SHA256).toByteArray()
    val nonce = session.nonce.toByteArray().toBase64Url()
    val handoverInfo = Cbor.encode(
        buildCborArray {
            add(baseUrl)
            add(nonce)
            add(jwkThumbPrint)
        }
    )
    val handoverInfoDigest = Crypto.digest(Algorithm.SHA256, handoverInfo)
    val mdocSessionTranscript by lazy {
        buildCborArray {
            add(Simple.NULL) // DeviceEngagementBytes
            add(Simple.NULL) // EReaderKeyBytes
            addCborArray {
                add("OpenID4VPDCAPIHandover")
                add(handoverInfoDigest)
            }
        }
    }
    val requestedDocuments = extractRequestedDocuments(
        dcql = Json.parseToJsonElement(session.dcqlQuery).jsonObject
    )
    val documentRequests = requestedDocuments.associateBy { it.id }
    val transactionDataHashMap = mutableMapOf<String, TransactionDataHashes>()
    val result = buildJsonObject {
        for ((id, value) in token) {
            val documentRequest = documentRequests[id]!!
            val cbor = documentRequest.format == "mso_mdoc"
            val responses = value.jsonArray.map { credentialResponse ->
                val responseText = credentialResponse.jsonPrimitive.content
                if (cbor) {
                    val decoded = Cbor.decode(responseText.fromBase64Url())
                    val responses = processMdocResponse(
                        credentialResponse = decoded,
                        mdocSessionTranscript = mdocSessionTranscript,
                        documentRequests = listOf(documentRequest)
                    )
                    check(responses.size == 1)
                    Pair(responses.first(), null)
                } else {
                    processSdJwtResponse(
                        credentialResponse = responseText,
                        documentRequest = documentRequest,
                        sessionNonce = nonce
                    )
                }
            }
            responses.first().second?.let { transactionDataHashMap.put(id, it) }
            if (documentRequest.multiple) {
                put(id, JsonArray(responses.map { it.first }))
                for (response in responses) {
                    // Ensures that all responses include exactly the same transaction data hashes
                    check(responses.first().second == response.second)
                }
            } else {
                check(responses.size == 1)
                put(id, responses.first().first)
            }
        }
    }

    // Validate that transaction data got acknowledged in the response
    if (session.transactionData == null) {
        // No transaction data hashes most have been embedded in the response
        check(transactionDataHashMap.isEmpty())
    } else {
        val transactionDataMap = TransactionData.parse(
            transactionData = session.transactionData,
            hashAlgorithmOverrides = transactionDataHashMap.mapValues { (_, transactionDataHashes) ->
                transactionDataHashes.hashAlgorithm
            }
        )
        check(transactionDataMap.size == transactionDataHashMap.size)
        for ((id, transactionDataHashes) in transactionDataHashMap) {
            val transactionDataList = transactionDataMap[id]!!
            check(transactionDataList.size == transactionDataHashes.hashes.size)
            for ((transactionData, hash) in transactionDataList.zip(transactionDataHashes.hashes)) {
                check(transactionData.hash == hash)
            }
        }
    }

    return result
}

private suspend fun processIsoMdocResponse(
    session: Session,
    response: ByteArray
): JsonObject {
    val array = Cbor.decode(response).asArray
    if (array.first().asTstr != "dcapi") {
        throw IllegalArgumentException("Excepted dcapi as first array element")
    }
    val encryptionParameters = array[1].asMap
    val enc = encryptionParameters[Tstr("enc")]!!.asBstr
    val cipherText = encryptionParameters[Tstr("cipherText")]!!.asBstr

    val encryptionKey = session.encryptionPrivateKey
    val encryptionInfo = buildCborArray {
        add("dcapi")
        addCborMap {
            put("nonce", session.nonce.toByteArray())
            put("recipientPublicKey", encryptionKey.publicKey.toCoseKey().toDataItem())
        }
    }
    val base64EncryptionInfo = Cbor.encode(encryptionInfo).toBase64Url()

    val baseUrl = BackendEnvironment.getBaseUrl()
    val dcapiInfo = buildCborArray {
        add(base64EncryptionInfo)
        add(baseUrl)  // origin
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

    val decrypter = Hpke.getDecrypter(
        cipherSuite = Hpke.CipherSuite.DHKEM_P256_HKDF_SHA256_HKDF_SHA256_AES_128_GCM,
        receiverPrivateKey = AsymmetricKey.AnonymousExplicit(encryptionKey),
        encapsulatedKey = enc,
        info = Cbor.encode(sessionTranscript)
    )
    val deviceResponseRaw = decrypter.decrypt(ciphertext = cipherText, aad = byteArrayOf())
    val requestedDocuments = extractRequestedDocuments(
        dcql = Json.parseToJsonElement(session.dcqlQuery).jsonObject
    )
    // In ISO world document ids are not used and responses rely on document index
    // TODO: it is not clear how this would work when there are document sets.
    val jsonResponses = processMdocResponse(
        credentialResponse = Cbor.decode(deviceResponseRaw),
        mdocSessionTranscript = sessionTranscript,
        documentRequests = requestedDocuments
    )
    return buildJsonObject {
        for ((documentRequest, jsonResponse) in requestedDocuments.zip(jsonResponses)) {
            put(documentRequest.id, jsonResponse)
        }
    }
}

private suspend fun getTrustManager(): TrustManager =
    BackendEnvironment.cache(TrustManager::class) { configuration, resources ->
        // TODO: load from configuration?
        TrustManagerLocal(EphemeralStorage())
    }

private suspend fun processMdocResponse(
    credentialResponse: DataItem,
    mdocSessionTranscript: DataItem,
    documentRequests: List<RequestedDocument>
): List<JsonObject> {
    val trustManager = getTrustManager()
    val deviceResponse = DeviceResponse.fromDataItem(credentialResponse)
    try {
        deviceResponse.verify(sessionTranscript = mdocSessionTranscript)
    } catch (err: Exception) {
        Logger.e(TAG, "Device response verification failed", err)
    }
    return deviceResponse.documents.zip(documentRequests).map { (document, request) ->
        buildJsonObject {
            try {
                val trustResult = trustManager.verify(document.issuerCertChain.certificates)
                put("_trusted", trustResult.isTrusted)
            } catch (e: Throwable) {
                Logger.e(TAG, "Trust verification failed", e)
            }

            for (claim in request.claims) {
                if (claim.path.size != 2) {
                    // TODO: nested values in mdoc?
                    continue
                }
                val issuerSignedItemsMap = document.issuerNamespaces.data[claim.path.first().asTstr]
                    ?: continue
                val issuerSignedItem = issuerSignedItemsMap[claim.path.last().asTstr]
                    ?: continue
                val jsonItem = when (val item = issuerSignedItem.dataElementValue) {
                    is Tstr -> JsonPrimitive(item.asTstr)
                    is Bstr -> JsonPrimitive(item.asBstr.toBase64())
                    is Nint, is Uint -> JsonPrimitive(item.asNumber)
                    Simple.TRUE -> JsonPrimitive(true)
                    Simple.FALSE -> JsonPrimitive(false)
                    Simple.NULL -> JsonPrimitive(null as String?)
                    else -> JsonPrimitive("<unsupported>")
                }
                val id = claim.id ?: claim.path.last().asTstr
                put(id, jsonItem)
            }
        }
    }
}

private suspend fun processSdJwtResponse(
    credentialResponse: String,
    documentRequest: RequestedDocument,
    sessionNonce: String
): Pair<JsonObject, TransactionDataHashes?> {
    val (sdJwt, sdJwtKb) = if (credentialResponse.endsWith("~")) {
        Pair(SdJwt.fromCompactSerialization(credentialResponse), null)
    } else {
        val sdJwtKb = SdJwtKb.fromCompactSerialization(credentialResponse)
        Pair(sdJwtKb.sdJwt, sdJwtKb)
    }
    if (sdJwtKb == null && sdJwt.jwtBody["cnf"] != null) {
        throw InvalidRequestException("`cnf` claim present but we got a SD-JWT, not a SD-JWT+KB")
    }
    val issuerCert = sdJwt.x5c?.certificates?.first()
        ?: throw InvalidRequestException("'x5c' not found")
    val trustManager = getTrustManager()
    val trustResult = trustManager.verify(sdJwt.x5c!!.certificates)
    val jsonResult = buildJsonObject {
        put("_trusted", trustResult.isTrusted)

        val claimMap = sdJwtKb?.verify(
            issuerKey = issuerCert.ecPublicKey,
            checkNonce = { nonce -> nonce == sessionNonce },
            // TODO: check audience, and creationTime
            checkAudience = { audience -> true },
            checkCreationTime = { creationTime -> true },
        )
            ?: sdJwt.verify(issuerCert.ecPublicKey)

        for (claim in documentRequest.claims) {
            var value: JsonElement = claimMap
            for (key in claim.path) {
                value = when (key) {
                    is Tstr -> value.jsonObject[key.asTstr]!!
                    is Uint -> value.jsonArray[key.asNumber.toInt()]
                    else -> throw IllegalStateException("Unexpected key in claim path")
                }
            }
            val id = claim.id ?: claim.path.last().asTstr
            put(id, value)
        }
    }

    val transactionDataHashes = sdJwtKb?.jwtBody["transaction_data_hashes"]?.let { hashes ->
        val hashAlgorithm = sdJwtKb.jwtBody["transaction_data_hashes_alg"]?.jsonPrimitive?.content
        TransactionDataHashes(
            hashAlgorithm = hashAlgorithm?.let {
                Algorithm.fromHashAlgorithmIdentifier(hashAlgorithm)
            } ?: Algorithm.SHA256,
            hashes = hashes.jsonArray.map { ByteString(it.jsonPrimitive.content.fromBase64Url()) }
        )
    }

    return Pair(jsonResult, transactionDataHashes)
}

private fun extractRequestedDocuments(
    dcql: JsonObject
): List<RequestedDocument> {
    val credentials = dcql["credentials"] as? JsonArray
        ?: throw InvalidRequestException("'credentials' is missing or invalid in dsql")
    return credentials.map { credential ->
        credential as? JsonObject
            ?: throw InvalidRequestException("credential query most be an object")
        val id = credential["id"] as? JsonPrimitive
            ?: throw InvalidRequestException("'id' is missing or invalid")
        val format = credential["format"] as? JsonPrimitive
            ?: throw InvalidRequestException("'format' is missing or invalid")
        val claims = credential["claims"] as? JsonArray
            ?: throw InvalidRequestException("'claims' is missing or invalid")
        RequestedDocument(
            id = id.content,
            format = format.content,
            multiple = (credential["multiple"] as JsonPrimitive?)?.booleanOrNull ?: false,
            claims = claims.map { claim ->
                claim as? JsonObject
                    ?: throw InvalidRequestException("claim is not an object")
                val id = claim["id"] as? JsonPrimitive
                val path = claim["path"] as? JsonArray
                    ?: throw InvalidRequestException("'path' is missing or invalid")
                RequestedClaim(
                    id = id?.content,
                    path = path.map {
                        it as? JsonPrimitive
                            ?: throw InvalidRequestException("path element is not primitive")
                        if (it.isString) {
                            Tstr(it.content)
                        } else if (it.contentOrNull == null) {
                            Simple.NULL
                        } else if (it.long >= 0){
                            Uint(it.long.toULong())
                        } else {
                            throw InvalidRequestException("path element is negative")
                        }
                    }
                )
            }
        )
    }
}

private suspend fun getClientId(): String {
    val baseUrl = BackendEnvironment.getBaseUrl()
    val host = Url(baseUrl).host
    return "x509_san_dns:$host"
}

private suspend fun encodeSessionId(sessionId: String): String {
    val buf = ByteStringBuilder()
    buf.append(57.toByte())
    val idBytes = sessionId.fromBase64Url()
    buf.append(idBytes.size.toByte())
    buf.append(idBytes)
    val cipher = BackendEnvironment.getInterface(SimpleCipher::class)!!
    return cipher.encrypt(buf.toByteString().toByteArray()).toBase64Url()
}

private suspend fun decodeSessionId(code: String): String {
    val cipher = BackendEnvironment.getInterface(SimpleCipher::class)!!
    val buf = cipher.decrypt(code.fromBase64Url())
    if (buf[0].toInt() != 57) {
        throw IllegalArgumentException("Not a valid session id")
    }
    val len = buf[1].toInt()
    if (len != buf.size - 2) {
        throw IllegalArgumentException("Not a valid session id")
    }
    return buf.sliceArray(2..<buf.size).toBase64Url()
}

private const val TAG = "verifyCredentials"

