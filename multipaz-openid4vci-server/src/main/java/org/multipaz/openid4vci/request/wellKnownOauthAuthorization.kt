package org.multipaz.openid4vci.request

import io.ktor.http.ContentType
import io.ktor.server.application.ApplicationCall
import io.ktor.server.response.respondText
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import org.multipaz.rpc.backend.BackendEnvironment
import org.multipaz.rpc.backend.Configuration
import org.multipaz.server.baseUrl
import org.multipaz.server.getBaseUrl

/**
 * Generates `.well-known/oauth-authorization-server` metadata file.
 */
suspend fun wellKnownOauthAuthorization(call: ApplicationCall) {
    val configuration = BackendEnvironment.getInterface(Configuration::class)!!
    val baseUrl = configuration.baseUrl
    val useClientAssertion = configuration.getValue("use_client_assertion") == "true"
    val useClientAttestationChallenge =
        configuration.getValue("use_client_attestation_challenge") != "false"
    call.respondText(
        text = buildJsonObject {
            put("issuer", baseUrl)
            if (useClientAttestationChallenge) {
                put("challenge_endpoint", "$baseUrl/challenge")
            }
            put("authorization_endpoint", "$baseUrl/authorize")
            // OAuth for First-Party Apps (FiPA), this got reworked substantially, disable for now
            //put("authorization_challenge_endpoint", "$baseUrl/authorize_challenge")
            put("token_endpoint", "$baseUrl/token")
            put("pushed_authorization_request_endpoint", "$baseUrl/par")
            put("require_pushed_authorization_requests", true)
            putJsonArray("token_endpoint_auth_methods_supported") {
                if (useClientAssertion) {
                    add("private_key_jwt")
                } else {
                    add("attest_jwt_client_auth")
                }
            }
            putJsonArray("response_types_supported") {
                add("code")
            }
            putJsonArray("code_challenge_methods_supported") {
                add("S256")
            }
            putJsonArray("dpop_signing_alg_values_supported") {
                add("ES256")
            }
            putJsonArray("client_attestation_signing_alg_values_supported") {
                add("ES256")
            }
            putJsonArray("client_attestation_pop_signing_alg_values_supported") {
                add("ES256")
            }
        }.toString(),
        contentType = ContentType.Application.Json
    )
}