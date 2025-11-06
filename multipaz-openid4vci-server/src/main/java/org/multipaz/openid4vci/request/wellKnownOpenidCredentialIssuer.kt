package org.multipaz.openid4vci.request

import io.ktor.http.ContentType
import io.ktor.server.application.ApplicationCall
import io.ktor.server.response.respondText
import org.multipaz.rpc.backend.Configuration
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import org.multipaz.openid4vci.credential.CredentialFactory
import org.multipaz.openid4vci.credential.Openid4VciFormatMdoc
import org.multipaz.openid4vci.credential.Openid4VciFormatSdJwt
import org.multipaz.rpc.backend.BackendEnvironment
import org.multipaz.server.baseUrl

const val PREFIX = "openid4vci.issuer"

/**
 * Generates `.well-known/openid-credential-issuer` metadata file.
 */
suspend fun wellKnownOpenidCredentialIssuer(call: ApplicationCall) {
    val configuration = BackendEnvironment.getInterface(Configuration::class)!!
    val baseUrl = configuration.baseUrl
    val useScopes = configuration.getValue("use_scopes") != "false"
    val name = configuration.getValue("issuer_name") ?: "Multipaz Sample Issuer"
    val locale = configuration.getValue("issuer_locale") ?: "en-US"
    val byOfferId = CredentialFactory.getRegisteredFactories().byOfferId
    call.respondText(
        text = buildJsonObject {
            put("credential_issuer", baseUrl)
            put("credential_endpoint", "$baseUrl/credential")
            put("nonce_endpoint", "$baseUrl/nonce")
            putJsonArray("authorization_servers") {
                add(JsonPrimitive(baseUrl))
            }
            putJsonArray("display") {
                addJsonObject {
                    put("name", name)
                    put("locale", locale)
                    put("logo", buildJsonObject {
                        put("uri", JsonPrimitive("$baseUrl/logo.png"))
                    })
                }
            }
            putJsonObject("batch_credential_issuance") {
                val batchSize =
                    configuration.getValue("$PREFIX.batch_size")?.toIntOrNull() ?: 12
                put("batch_size", JsonPrimitive(batchSize))
            }
            putJsonObject("credential_configurations_supported") {
                for (credentialFactory in byOfferId.values) {
                    putJsonObject(credentialFactory.offerId) {
                        if (useScopes) {
                            put("scope", credentialFactory.scope)
                        }
                        val format = credentialFactory.format
                        put("format", format.id)
                        when (format) {
                            is Openid4VciFormatMdoc -> put("doctype", format.docType)
                            is Openid4VciFormatSdJwt -> put("vct", format.vct)
                        }
                        if (credentialFactory.proofSigningAlgorithms.isNotEmpty()) {
                            putJsonObject("proof_types_supported") {
                                if (!credentialFactory.requireKeyAttestation) {
                                    putJsonObject("jwt") {
                                        putJsonArray("proof_signing_alg_values_supported") {
                                            credentialFactory.proofSigningAlgorithms.forEach {
                                                add(it)
                                            }
                                        }
                                    }
                                }
                                putJsonObject("attestation") {
                                    putJsonArray("proof_signing_alg_values_supported") {
                                        credentialFactory.proofSigningAlgorithms.forEach {
                                            add(it)
                                        }
                                    }
                                }
                            }
                        }
                        if (credentialFactory.cryptographicBindingMethods.isNotEmpty()) {
                            putJsonArray("cryptographic_binding_methods_supported") {
                                credentialFactory.cryptographicBindingMethods.forEach {
                                    add(it)
                                }
                            }
                        }
                        putJsonArray("credential_signing_alg_values_supported") {
                            val signingKey = credentialFactory.signingKey
                            if (credentialFactory.format is Openid4VciFormatMdoc) {
                                add(signingKey.algorithm.coseAlgorithmIdentifier)
                            } else {
                                add(signingKey.algorithm.joseAlgorithmIdentifier)
                            }
                        }
                        putJsonObject("credential_metadata") {
                            putJsonArray("display") {
                                addJsonObject {
                                    put("name", credentialFactory.name)
                                    put("locale", locale)
                                    if (credentialFactory.logo != null) {
                                        putJsonObject("logo") {
                                            put("uri", "$baseUrl/${credentialFactory.logo}")
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }.toString(),
        contentType = ContentType.Application.Json
    )
}