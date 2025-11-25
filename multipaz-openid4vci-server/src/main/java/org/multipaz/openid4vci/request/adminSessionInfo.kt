package org.multipaz.openid4vci.request

import io.ktor.http.ContentType
import io.ktor.server.application.ApplicationCall
import io.ktor.server.response.respondText
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.multipaz.openid4vci.util.CredentialState
import org.multipaz.openid4vci.util.IssuanceState
import org.multipaz.rpc.handler.InvalidRequestException

/**
 * GET request to obtain information on a particular issuance session including all unexpired
 * credentials issued in this session.
 */
suspend fun adminSessionInfo(call: ApplicationCall) {
    val sessionId = call.request.queryParameters["session_id"]
        ?: throw InvalidRequestException("'session_id' parameter required")
    val state = IssuanceState.getIssuanceState(sessionId)
    val credentials = mutableListOf<JsonElement>()
    state.credentials.forEach { credentialData ->
        val credentialId = credentialData.id
        val credentialState = CredentialState.getCredentialState(credentialId)
        if (credentialState != null) {
            val status = CredentialState.getCredentialStatus(credentialId)
            credentials.add(buildJsonObject {
                put("idx", credentialData.index)
                put("bucket", credentialData.bucket)
                put("kid", credentialState.keyId)
                put("creation", credentialState.creation.toString())
                put("expiration", credentialState.expiration.toString())
                put("status", status.jsonName)
            })
        }
    }
    call.respondText(
        text = buildJsonObject {
            put("id", sessionId)
            put("scope", state.scope)
            state.redirectUri?.let { put("redirect_uri", it) }
            state.authorized?.let { put("authorized", it.toString()) }
            state.configurationId?.let { put("configuration_id", it) }
            state.clientId?.let { put("client_id", it) }
            put("expiration", state.expiration.toString())
            put("credentials", JsonArray(credentials))
        }.toString(),
        contentType = ContentType.Application.Json
    )
}