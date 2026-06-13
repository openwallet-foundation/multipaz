package org.multipaz.records.request

import io.ktor.http.ContentType
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
import org.multipaz.records.data.tokenToId

/**
 * Handles `delete` POST request.
 *
 * Deletes an identity with the given token (encrypted id - see [tokenToId]) from the storage.
 *
 * Request format:
 * ```
 * {
 *     "token": "...."
 * }
 * ```
 *
 * Response format:
 * ```
 * {
 *     "deleted": <boolean>
 * }
 * ```
 * */
suspend fun identityDelete(call: ApplicationCall) {
    val request = Json.parseToJsonElement(call.receiveText()) as JsonObject
    val id = tokenToId(TokenType.FE_TOKEN, request["token"]!!.jsonPrimitive.content)
    call.respondText (
        contentType = ContentType.Application.Json,
        text = buildJsonObject {
           put("deleted", Identity.deleteById(id))
        }.toString()
    )
}
