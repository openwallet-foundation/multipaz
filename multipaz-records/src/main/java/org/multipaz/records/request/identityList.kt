package org.multipaz.records.request

import io.ktor.http.ContentType
import io.ktor.server.application.ApplicationCall
import io.ktor.server.response.respondText
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import org.multipaz.records.data.Identity
import org.multipaz.records.data.TokenType
import org.multipaz.records.data.idToToken
import kotlin.time.Duration

/**
 * Handles `list` request.
 *
 * Returns a list of all [Identity] objects in the storage as a json array of tokens (see
 * [idToToken]).
 *
 * This will be replaced by a list of identities managed by a particular front-end admin login.
 */
suspend fun identityList(call: ApplicationCall) {
    call.respondText (
        contentType = ContentType.Application.Json,
        text = buildJsonArray {
            for (id in Identity.listAll()) {
                add(idToToken(TokenType.FE_TOKEN, id, Duration.INFINITE))
            }
        }.toString()
    )
}