package org.multipaz.provisioning.openid4vci

import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.statement.readRawBytes
import io.ktor.http.HttpStatusCode
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import org.multipaz.crypto.Algorithm
import org.multipaz.openid.wellKnown
import org.multipaz.rpc.backend.BackendEnvironment
import org.multipaz.util.Logger

internal data class AuthorizationConfiguration(
    val identifier: String,
    val challengeEndpoint: String?,
    val pushedAuthorizationRequestEndpoint: String,
    val authorizationEndpoint: String,
    val tokenEndpoint: String,
    val dpopSigningAlgorithm: Algorithm,
    val clientAttestationSigningAlgorithm: Algorithm,
    val clientAuthentication: ClientAuthenticationType
) {
    companion object: JsonParsing("Authorization server metadata") {
        suspend fun get(url: String, clientPreferences: OpenID4VCIClientPreferences): AuthorizationConfiguration {
            val httpClient = BackendEnvironment.getInterface(HttpClient::class)!!

            // Fetch issuer metadata
            val metadataUrl = wellKnown(url, "oauth-authorization-server")
            val metadataRequest = httpClient.get(metadataUrl) {}
            if (metadataRequest.status != HttpStatusCode.OK) {
                throw IllegalStateException("Invalid issuer, no $metadataUrl")
            }
            val metadataText = metadataRequest.readRawBytes().decodeToString()
            val metadata = Json.parseToJsonElement(metadataText).jsonObject
            val identifier = metadata.stringOrNull("issuer") ?: url
            val challengeEndpoint = metadata.stringOrNull("challenge_endpoint")
            val authorizationEndpoint = metadata.string("authorization_endpoint")
            val parEndpoint = metadata.string("pushed_authorization_request_endpoint")
            val tokenEndpoint = metadata.string("token_endpoint")

            val responseType = metadata.arrayOrNull("response_types_supported")
            if (responseType != null) {
                var codeSupported = false
                for (response in responseType) {
                    if (response is JsonPrimitive && response.content == "code") {
                        codeSupported = true
                        break
                    }
                }
                if (!codeSupported) {
                    throw IllegalStateException("response type 'code' is not supported")
                }
            }
            val codeChallengeMethods = metadata.array("code_challenge_methods_supported")
            if (responseType != null) {
                var challengeSupported = false
                for (method in codeChallengeMethods) {
                    if (method is JsonPrimitive && method.content == "S256") {
                        challengeSupported = true
                        break
                    }
                }
                if (!challengeSupported) {
                    throw IllegalStateException("challenge type 'S256' is not supported")
                }
            }

            val dpopSigningAlgorithm = preferredAlgorithm(
                available = metadata.arrayOrNull("dpop_signing_alg_values_supported"),
                clientPreferences = clientPreferences)
            val clientAttestationSigningAlgorithm = preferredAlgorithm(
                available = metadata.arrayOrNull("client_attestation_pop_signing_alg_values_supported"),
                clientPreferences = clientPreferences
            )
            var clientAuthentication = ClientAuthenticationType.CLIENT_ASSERTION
            val authMethods = metadata.arrayOrNull("token_endpoint_auth_methods_supported")
            if (authMethods != null) {
                var walletAttestationSupported = false
                var clientAssertionSupported = false
                var noAuthentication = false
                for (authMethod in authMethods) {
                    if (authMethod is JsonPrimitive) {
                        when (val auth = authMethod.content) {
                            "private_key_jwt" -> clientAssertionSupported = true
                            "attest_jwt_client_auth" -> walletAttestationSupported = true
                            "none" -> noAuthentication = true
                            else -> Logger.w(TAG, "Unknown auth method: '$auth'")
                        }
                    }
                }
                if (noAuthentication) {
                    Logger.i(TAG, "Will use no client authentication")
                    clientAuthentication = ClientAuthenticationType.NONE
                } else if (!walletAttestationSupported && clientAssertionSupported) {
                    Logger.w(TAG, "Will use client assertion, client attestation is not supported")
                    clientAuthentication = ClientAuthenticationType.CLIENT_ASSERTION
                } else {
                    Logger.i(TAG, "Will use client attestation")
                    clientAuthentication = ClientAuthenticationType.CLIENT_ATTESTATION
                }
            }
            return AuthorizationConfiguration(
                identifier = identifier,
                challengeEndpoint = challengeEndpoint,
                pushedAuthorizationRequestEndpoint = parEndpoint,
                authorizationEndpoint = authorizationEndpoint,
                tokenEndpoint = tokenEndpoint,
                dpopSigningAlgorithm = dpopSigningAlgorithm,
                clientAttestationSigningAlgorithm = clientAttestationSigningAlgorithm,
                clientAuthentication = clientAuthentication
            )
        }

        const val TAG = "AuthorizationConfiguration"
    }
}