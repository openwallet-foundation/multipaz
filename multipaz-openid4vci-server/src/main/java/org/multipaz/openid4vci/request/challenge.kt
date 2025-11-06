package org.multipaz.openid4vci.request

import io.ktor.http.ContentType
import io.ktor.server.application.ApplicationCall
import io.ktor.server.response.header
import io.ktor.server.response.respondText
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.multipaz.jwt.Challenge
import org.multipaz.rpc.backend.BackendEnvironment
import org.multipaz.rpc.backend.Configuration
import org.multipaz.rpc.handler.InvalidRequestException

/**
 * Issues a fresh wallet attestation challenge
 */
suspend fun challenge(call: ApplicationCall) {
    val configuration = BackendEnvironment.getInterface(Configuration::class)!!
    val useClientAttestationChallenge =
        configuration.getValue("use_client_attestation_challenge") != "false"
    if (!useClientAttestationChallenge) {
        throw InvalidRequestException("client attestation challenge is not supported")
    }
    call.response.header("Cache-Control", "no-store")
    call.response.header("DPoP-Nonce", Challenge.create())
    call.respondText(
        text = buildJsonObject {
            put("attestation_challenge", Challenge.create())
        }.toString(),
        contentType = ContentType.Application.Json
    )
}

