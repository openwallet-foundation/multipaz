package org.multipaz.openid4vci.request

import io.ktor.http.ContentType
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.receiveParameters
import io.ktor.server.request.receiveText
import io.ktor.server.response.respondText
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.multipaz.openid4vci.util.CredentialState
import org.multipaz.rpc.handler.InvalidRequestException

/**
 * POST request to change the status of the given credential.
 */
suspend fun adminSetCredentialStatus(call: ApplicationCall) {
    val requestString = call.receiveText()
    val json = Json.parseToJsonElement(requestString) as JsonObject
    val credentialIndex = json["idx"]?.jsonPrimitive?.intOrNull
        ?: throw InvalidRequestException("missing or malformed parameter 'idx'")
    if (credentialIndex < 0) {
        throw InvalidRequestException("'idx' must not be negative")
    }
    val status = json["status"]?.jsonPrimitive?.content
        ?: throw InvalidRequestException("missing parameter 'status'")
    val credential = CredentialState.getCredentialState(credentialIndex)
        ?: throw InvalidRequestException("no credential '$credentialIndex'")
    CredentialState.setCredentialStatus(
        credentialIndex = credentialIndex,
        status = CredentialState.Status.decode(status),
        expiration = credential.expiration
    )
    invalidateStatusList()
    call.respondText(
        text = buildJsonObject {
            put("success", true)
        }.toString(),
        contentType = ContentType.Application.Json
    )
}