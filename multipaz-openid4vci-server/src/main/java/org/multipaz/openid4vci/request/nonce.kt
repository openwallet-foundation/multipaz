package org.multipaz.openid4vci.request

import io.ktor.http.ContentType
import io.ktor.server.application.ApplicationCall
import io.ktor.server.response.header
import io.ktor.server.response.respondText
import kotlin.time.Clock
import kotlinx.io.bytestring.buildByteString
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.multipaz.jwt.Challenge
import org.multipaz.openid4vci.util.addFreshNonceHeaders
import org.multipaz.rpc.backend.BackendEnvironment
import org.multipaz.rpc.backend.getTable
import org.multipaz.rpc.handler.InvalidRequestException
import org.multipaz.storage.StorageTableSpec
import kotlin.time.Duration.Companion.minutes

/**
 * Endpoint to obtain fresh `c_nonce` (challenge for device binding key attestation).
 */
suspend fun nonce(call: ApplicationCall) {
    // This is not the most scalable way of managing c_nonce values, but it is the simplest one
    // that would detect c_nonce reuse.
    val cNonce = BackendEnvironment.getTable(credentialChallengeTableSpec).insert(
        key = null,
        data = buildByteString {},
        expiration = Clock.System.now() + 10.minutes
    )
    call.response.header("Cache-Control", "no-store")
    addFreshNonceHeaders(call)
    call.respondText(
        text = buildJsonObject {
            put("c_nonce", cNonce)
        }.toString(),
        contentType = ContentType.Application.Json
    )
}

internal suspend fun validateAndConsumeCredentialChallenge(cNonce: String) {
    val table = BackendEnvironment.getTable(credentialChallengeTableSpec)
    if (!table.delete(cNonce)) {
        throw InvalidRequestException("Expired or invalid c_nonce")
    }
}

private val credentialChallengeTableSpec = StorageTableSpec(
    name = "CredentialChallenge",
    supportExpiration = true,
    supportPartitions = false
)