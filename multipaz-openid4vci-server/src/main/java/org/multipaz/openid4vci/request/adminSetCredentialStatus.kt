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
import org.multipaz.openid4vci.util.CredentialId
import org.multipaz.openid4vci.util.CredentialState
import org.multipaz.provisioning.CredentialFormat
import org.multipaz.rpc.handler.InvalidRequestException

/**
 * POST request to change the status of the given credential.
 */
suspend fun adminSetCredentialStatus(call: ApplicationCall) {
    val requestString = call.receiveText()
    val json = Json.parseToJsonElement(requestString) as JsonObject
    val bucket = json["bucket"]?.jsonPrimitive?.content
        ?: throw InvalidRequestException("missing or malformed parameter 'bucket'")
    val credentialIndex = json["idx"]?.jsonPrimitive?.intOrNull
        ?: throw InvalidRequestException("missing or malformed parameter 'idx'")
    if (credentialIndex < 0) {
        throw InvalidRequestException("'idx' must not be negative")
    }
    val credentialId = CredentialId(bucket, credentialIndex)
    val statusStr = json["status"]?.jsonPrimitive?.content
        ?: throw InvalidRequestException("missing parameter 'status'")
    val status = CredentialState.Status.decode(statusStr)
    val credential = CredentialState.getCredentialState(credentialId)
        ?: throw InvalidRequestException("no credential '$credentialIndex'")
    if (credential.format is CredentialFormat.Mdoc) {
        if (status != CredentialState.Status.VALID && status != CredentialState.Status.INVALID) {
            throw InvalidRequestException("Only 'valid' or 'invalid' status can be used for mdoc")
        }
    }
    CredentialState.setCredentialStatus(
        credentialId = credentialId,
        status = status,
        expiration = credential.expiration
    )
    invalidateStatusList()
    invalidateIdentifierList()
    call.respondText(
        text = buildJsonObject {
            put("success", true)
        }.toString(),
        contentType = ContentType.Application.Json
    )
}