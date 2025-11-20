package org.multipaz.openid4vci.request

import io.ktor.http.ContentType
import io.ktor.server.application.ApplicationCall
import io.ktor.server.response.respondText
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import org.multipaz.openid4vci.util.IssuanceState
import kotlin.time.Clock

/**
 * GET request to obtain the list of unexpired issuance sessions on this server in JSON format.
 */
suspend fun adminListSessions(call: ApplicationCall) {
    val list = IssuanceState.listIssuanceStates()
    val now = Clock.System.now()
    call.respondText(
        text = buildJsonObject {
            putJsonArray("sessions") {
                for ((id, state) in list) {
                    addJsonObject {
                        put("id", id)
                        state.authorized?.let { put("authorized", it.toString()) }
                        state.configurationId?.let { put("configuration_id", it) }
                        state.clientId?.let { put("client_id", it) }
                        put("credential_count", state.credentials.count { it.expiration >= now })
                        put("expiration", state.expiration.toString())
                    }
                }
            }
        }.toString(),
        contentType = ContentType.Application.Json
    )
}