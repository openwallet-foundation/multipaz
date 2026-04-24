package org.multipaz.openid4vci.request

import io.ktor.http.ContentType
import io.ktor.server.application.ApplicationCall
import io.ktor.server.response.header
import io.ktor.server.response.respondText
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.multipaz.openid4vci.customization.NonceManager
import org.multipaz.rpc.backend.BackendEnvironment
import org.multipaz.rpc.backend.getTable
import org.multipaz.rpc.handler.InvalidRequestException
import org.multipaz.storage.StorageTableSpec

/**
 * Endpoint to obtain fresh `c_nonce` (challenge for device binding key attestation).
 */
suspend fun nonce(call: ApplicationCall) {
    val nonces = NonceManager.get().cNonce()
    call.response.header("Cache-Control", "no-store")
    nonces.dpopNonce?.let { call.response.header("DPoP-Nonce", it) }
    nonces.clientAttestationNonce?.let {
        call.response.header("OAuth-Client-Attestation-Challenge", it)
    }
    call.respondText(
        text = buildJsonObject {
            put("c_nonce", nonces.credentialNonce!!)
        }.toString(),
        contentType = ContentType.Application.Json
    )
}
