package org.multipaz.openid4vci.request

import io.ktor.http.ContentType
import io.ktor.server.application.ApplicationCall
import io.ktor.server.response.header
import io.ktor.server.response.respondText
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.multipaz.openid4vci.customization.NonceManager
import org.multipaz.openid4vci.customization.NonceManagerDefault
import org.multipaz.webtoken.Challenge
import org.multipaz.rpc.backend.BackendEnvironment
import org.multipaz.rpc.backend.Configuration
import org.multipaz.rpc.handler.InvalidRequestException

/**
 * Issues a fresh wallet attestation challenge
 */
suspend fun challenge(call: ApplicationCall) {
    val nonces = NonceManager.get().challenge()
    call.response.header("Cache-Control", "no-store")
    nonces.dpopNonce?.let { call.response.header("DPoP-Nonce", it) }
    check(nonces.credentialNonce == null)
    call.respondText(
        text = buildJsonObject {
            put("attestation_challenge", nonces.clientAttestationNonce!!)
        }.toString(),
        contentType = ContentType.Application.Json
    )
}

