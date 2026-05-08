package org.multipaz.verifier.request

import io.ktor.http.ContentType
import io.ktor.http.Url
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.receiveParameters
import io.ktor.server.request.receiveText
import io.ktor.server.response.respondText
import io.ktor.util.toMap
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import kotlinx.io.bytestring.ByteString
import kotlinx.io.bytestring.ByteStringBuilder
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import org.multipaz.cbor.Tagged
import org.multipaz.cbor.Tstr
import org.multipaz.claim.Claim
import org.multipaz.claim.JsonClaim
import org.multipaz.claim.MdocClaim
import org.multipaz.documenttype.DocumentTypeRepository
import org.multipaz.mdoc.zkp.ZkSystemRepository
import org.multipaz.rpc.backend.BackendEnvironment
import org.multipaz.rpc.handler.InvalidRequestException
import org.multipaz.rpc.handler.SimpleCipher
import org.multipaz.server.common.getBaseUrl
import org.multipaz.server.common.getDomain
import org.multipaz.server.enrollment.ServerIdentity
import org.multipaz.server.enrollment.getServerIdentity
import org.multipaz.trustmanagement.TrustManagerInterface
import org.multipaz.verification.PresentmentRecord
import org.multipaz.util.fromBase64Url
import org.multipaz.util.toBase64Url
import org.multipaz.verification.DcqlRequestDefinition
import org.multipaz.verification.JsonVerifiedPresentation
import org.multipaz.verification.MdocVerifiedPresentation
import org.multipaz.verification.QueryData
import org.multipaz.verification.VerificationSession
import org.multipaz.verifier.session.Session
import org.multipaz.verifier.customization.VerifierAssistant
import org.multipaz.verifier.customization.VerifierPresentment
import kotlin.collections.component1
import kotlin.collections.component2
import kotlin.collections.iterator
import kotlin.random.Random
import kotlin.time.Clock
import kotlin.time.Duration.Companion.minutes

private val defaultRequestTypes = setOf(
    VerificationSession.RequestType.DC_OPENID4VP,
    VerificationSession.RequestType.DC_ISO_18013,
    VerificationSession.RequestType.OPENID4VP_URI_SCHEME
)

suspend fun makeRequest(call: ApplicationCall) {
    val rawRequest = Json.parseToJsonElement(call.receiveText()) as JsonObject
    val assistant = BackendEnvironment.getInterface(VerifierAssistant::class)
    val expandedRequest = assistant?.processRequest(rawRequest)
    val request = expandedRequest?.request ?: rawRequest
    val nonce = expandedRequest?.nonce ?: ByteString(Random.nextBytes(15))
    val dcqlQuery = (request["dcql"] as? JsonObject)?.toString()
        ?: throw InvalidRequestException("'dcql' is missing or invalid")
    val requestTypes = (request["protocols"] as? JsonArray)?.map {
        when (val protocol = it.jsonPrimitive.content) {
            "org-iso-mdoc" -> VerificationSession.RequestType.DC_ISO_18013
            "openid4vp-v1" -> VerificationSession.RequestType.DC_OPENID4VP
            "openid4vp-v1-uri" -> VerificationSession.RequestType.OPENID4VP_URI_SCHEME
            else -> throw InvalidRequestException("unknown protocol '$protocol'")
        }
    }?.toSet() ?: defaultRequestTypes
    val transactionData = request["transaction_data"]?.jsonArray
    val sign = (request["sign"] as? JsonPrimitive)?.booleanOrNull != false
    val encrypt = (request["encrypt"] as? JsonPrimitive)?.booleanOrNull != false
    val baseUrl = BackendEnvironment.getBaseUrl()
    val sessionId = Session.createSession()
    val encodedSessionId = encodeSessionId(sessionId)
    val transactions = transactionData?.map { it.toString() }
    val verificationSession = VerificationSession.create(
        requestTypes = requestTypes,
        requestDefinition = DcqlRequestDefinition(
            dcql = dcqlQuery,
            transactionData = transactions ?: emptyList()
        ),
        nonce = nonce,
        origin = BackendEnvironment.getDomain(),
        clientId = getClientId(sign),
        responseUri = "$baseUrl/direct_post/$encodedSessionId",
        documentTypeRepository = BackendEnvironment.getInterface(DocumentTypeRepository::class)!!,
        readerAuthenticationKey = if (sign) getServerIdentity(ServerIdentity.VERIFIER) else null,
        encryptResponse = encrypt,
        state = encodedSessionId
    )
    Session.updateSession(sessionId, Session(dcqlQuery, transactions, verificationSession))
    call.respondText(
        contentType = ContentType.Application.Json,
        text = buildJsonObject {
            put("session_id", encodedSessionId)
            put("client_id", getClientId(sign))
            putJsonObject("dc_request") {
               put("digital", verificationSession.getDcRequest())
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
    val sessionId = decodeSessionId(encodedSessionId)
    val session = Session.getSession(sessionId)
        ?: throw InvalidRequestException("Session '$encodedSessionId' is missing or expired")
    val verificationSession = session.verificationSession
        ?: throw InvalidRequestException("Session '$encodedSessionId': response already processed")
    val presentationRecord = verificationSession.processDcResponse(dcResponse)
    respondWithResult(call, sessionId, session, presentationRecord)
}

suspend fun getOpenID4VPUriSchemaRequest(call: ApplicationCall, encodedSessionId: String) {
    val sessionId = decodeSessionId(encodedSessionId)
    val session = Session.getSession(sessionId)
        ?: throw InvalidRequestException("Session '$encodedSessionId' is missing or expired")
    val verificationSession = session.verificationSession
        ?: throw InvalidRequestException("Session '$encodedSessionId': response already processed")
    if (!verificationSession.signed) {
        throw InvalidRequestException("OpenID4VP custom uri requests must be signed")
    }
    val request = verificationSession.find<VerificationSession.OpenID4VPUriSchemeRequest>()
    val text = Json.parseToJsonElement(request.openID4VPRequest)
        .jsonObject["request"]!!.jsonPrimitive.content
    call.respondText(
        contentType = ContentType.parse("application/oauth-authz-req+jwt"),
        text = text
    )
}

// Channels to notify pending requests that wait for the result. This only works because we have
// a single process on a single machine on the server. To run it with multiple machines, sharding
// by session has to be done.
val resultChannelMutex = Mutex()
val resultChannels = mutableMapOf<String, MutableSet<Channel<String>>>()

suspend fun processDirectPost(call: ApplicationCall, encodedSessionId: String) {
    val sessionId = decodeSessionId(encodedSessionId)
    val session = Session.getSession(sessionId)
        ?: throw InvalidRequestException("Session '$encodedSessionId' is missing or expired")
    val verificationSession = session.verificationSession
        ?: throw InvalidRequestException("Session '$encodedSessionId': response already processed")
    val postedData = call.receiveParameters().toMap().mapValues { (_, value) -> value.first() }
    session.presentmentRecord = verificationSession.processOpenID4VPUriSchemeResponse(postedData)
    Session.updateSession(sessionId, session)
    val channels = resultChannelMutex.withLock {
        resultChannels[sessionId]?.toMutableSet() ?: setOf()
    }
    for (channel in channels) {
        channel.trySend("ready")
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
    val session = try {
        withTimeout(3.minutes) { channel.receive() }
        Session.getSession(sessionId)
            ?: throw InvalidRequestException("Session '$encodedSessionId' is missing or expired")
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
    val presentationRecord = session?.presentmentRecord
    if (presentationRecord == null) {
        call.respondText(
            contentType = ContentType.Application.Json,
            text = """{"status": "not_ready"}"""
        )
    } else {
        respondWithResult(call, sessionId, session, presentationRecord)
    }
}

private suspend fun respondWithResult(
    call: ApplicationCall,
    sessionId: String,
    session: Session,
    presentmentRecord: PresentmentRecord
) {
    val dcql = session.dcql!!
    val transactions = session.transactions ?: emptyList()
    session.verificationSession = null
    session.dcql = null
    session.transactions = null
    session.presentmentRecord = presentmentRecord
    Session.updateSession(sessionId, session)
    val result = processPresentation(presentmentRecord, dcql, transactions)
    call.respondText(
        contentType = ContentType.Application.Json,
        text = result.toString()
    )
}

private val standardJsonClaims =
    setOf("iss", "vct", "iat", "nbf", "exp", "nonce", "aud", "cnf", "sd_hash")

private suspend fun processPresentation(
    presentmentRecord: PresentmentRecord,
    dcql: String,
    transactions: List<String>
): JsonObject {
    val now = Clock.System.now()
    val documentTypeRepository = BackendEnvironment.getInterface(DocumentTypeRepository::class)!!
    val verifiedPresentations = presentmentRecord.verify(
        atTime = now,
        documentTypeRepository = documentTypeRepository,
        zkSystemRepository = BackendEnvironment.getInterface(ZkSystemRepository::class)
    )
    val trustManager = BackendEnvironment.getInterface(TrustManagerInterface::class)!!
    val singleDocMap = mutableMapOf<String, JsonObject>()
    val multiDocMap = mutableMapOf<String, MutableList<JsonObject>>()
    for (presentation in verifiedPresentations) {
        val trusted = presentation.documentSignerCertChain?.let {
            trustManager.verify(it.certificates, now).isTrusted
        }
        val transactions = when (presentation) {
            is JsonVerifiedPresentation ->
                presentation.transactionResponses?.let { JsonObject(it) }
            is MdocVerifiedPresentation ->
                presentation.transactionResponses?.let { responses ->
                    buildJsonObject {
                        for ((transactionId, attributes) in responses) {
                            putJsonObject(transactionId) {
                                for ((attributeId, value) in attributes) {
                                    put(attributeId, value.toJson())
                                }
                            }
                        }
                    }
                }
        }
        val id = presentation.identifier ?: "default"
        val queryMap = QueryData.fromDcql(Json.parseToJsonElement(dcql).jsonObject)
            .associateBy { it.id }
        val query = presentation.identifier?.let { queryMap[it] }
        val docResult = buildJsonObject {
            trusted?.let { put("trusted", trusted) }
            putJsonObject("claims") {
                for (claim in presentation.issuerSignedClaims) {
                    val value = when (claim) {
                        is JsonClaim -> {
                            if (claim.claimPath.size == 1 &&
                                standardJsonClaims.contains(claim.claimPath.last().jsonPrimitive.content)) {
                                continue
                            }
                            claim.value
                        }
                        is MdocClaim -> when (val value = claim.value) {
                            is Tagged -> when (value.tagNumber) {
                                Tagged.DATE_TIME_STRING,
                                Tagged.FULL_DATE_STRING ->
                                    JsonPrimitive((value.taggedItem as Tstr).asTstr)
                                else -> value.toJson()
                            }
                            else -> value.toJson()
                        }
                    }
                    put(claim.identifier, value)
                }
            }
            transactions?.let { put("transactions", it) }
            if (presentation.zkpUsed) {
                put("zkp_used", true)
            }
        }
        if (query?.multiple == true) {
            multiDocMap.getOrPut(id) { mutableListOf() }.add(docResult)
        } else {
            singleDocMap[id] = docResult
        }
    }

    val result = buildJsonObject {
        for ((id, docResult) in singleDocMap) {
            put(id, docResult)
        }
        for ((id, docResults) in multiDocMap) {
            putJsonArray(id) {
                for (docResult in docResults) {
                    add(docResult)
                }
            }
        }
    }

    val revised = BackendEnvironment.getInterface(VerifierAssistant::class)?.processResponse(
        presentment = object: VerifierPresentment {
            override val dcql get(): JsonObject =
                Json.parseToJsonElement(dcql).jsonObject
            override val transactions get(): List<JsonObject> =
                transactions.map { Json.parseToJsonElement(it).jsonObject }
            override val presentmentRecord = presentmentRecord
            override val presentations = verifiedPresentations
            override val response = result
        }
    )

    return revised ?: result
}

private suspend fun getClientId(sign: Boolean): String =
    if (sign) {
        val baseUrl = BackendEnvironment.getBaseUrl()
        val host = Url(baseUrl).host
        "x509_san_dns:$host"
    } else {
        "web-origin:" + BackendEnvironment.getDomain()
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

private val Claim.identifier: String get() = when (this) {
    is JsonClaim -> queryIdentifier ?: claimPath.last().jsonPrimitive.content
    is MdocClaim -> queryIdentifier ?: displayName
}