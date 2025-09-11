package org.multipaz.records.request

import io.ktor.client.HttpClient
import io.ktor.client.request.headers
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.readBytes
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.receiveText
import io.ktor.server.response.respondText
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.multipaz.records.data.Identity
import org.multipaz.records.data.TokenType
import org.multipaz.records.data.idToToken
import org.multipaz.records.data.tokenToId
import org.multipaz.rpc.backend.BackendEnvironment
import org.multipaz.rpc.backend.Configuration
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes

/**
 * Generates credential offer(s) for the given identity and record.
 *
 * Request format:
 * ```
 * {
 *     "token": "...",
 *     "scope": "...",  // record type (or "core")
 *     "instance": "...",  // record instance (ignored for "core")
 *     "tx_kind": "...",  // transaction code kind
 *     "tx_prompt": "..."  // transaction code prompt
 * }
 * ```
 *
 * Credential offer is generated for all credential types that reference
 * the given scope.
 *
 * Response:
 * ```
 * [
 *     {
 *        credential_id: "...",
 *        display: { ... },  // openid4vci display structure
 *        offer: "...",  // credential offer url
 *        tx_code: "..."  // generated transaction code if any
 *     },
 *     ...
 * ]
 * ```
 */
suspend fun identityOffer(call: ApplicationCall) {
    val configuration = BackendEnvironment.getInterface(Configuration::class)!!
    val httpClient = BackendEnvironment.getInterface(HttpClient::class)!!
    val issuerUrl = configuration.getValue("issuer_url")!!
    val request = Json.parseToJsonElement(call.receiveText()) as JsonObject
    val feToken = request["token"]!!.jsonPrimitive.content
    val id = tokenToId(TokenType.FE_TOKEN, feToken)
    Identity.findById(id)  // ensures it exists
    val scope = request["scope"]!!.jsonPrimitive.content
    val instanceId = if (scope == "core") "" else request["instance"]!!.jsonPrimitive.content
    val qualifiedId = "$scope:$instanceId:$id"
    val expiresIn = 60.minutes
    val accessToken = idToToken(TokenType.ACCESS_TOKEN, qualifiedId, expiresIn)
    val refreshToken = idToToken(TokenType.REFRESH_TOKEN, qualifiedId, Duration.INFINITE)
    val offerRequest = buildJsonObject {
        put("scope", scope)
        put("access_token", accessToken)
        put("expires_in", expiresIn.inWholeSeconds.toInt())
        put("refresh_token", refreshToken)
        val txKind = request["tx_kind"]!!.jsonPrimitive.content
        put("tx_kind", txKind)
        if (txKind != "none") {
            put("tx_prompt", request["tx_prompt"]!!.jsonPrimitive.content)
        }
    }
    val offerResponse = httpClient.post("$issuerUrl/preauthorized_offer") {
        headers {
            append("Content-Type", "application/json")
        }
        setBody(offerRequest.toString())
    }
    call.respondText (
        status = offerResponse.status,
        contentType = offerResponse.contentType(),
        text = offerResponse.readBytes().decodeToString()
    )
}