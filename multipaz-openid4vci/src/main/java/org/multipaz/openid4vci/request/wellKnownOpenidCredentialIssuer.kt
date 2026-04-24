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
import org.multipaz.device.AndroidKeystoreSecurityLevel
import org.multipaz.openid4vci.credential.CredentialFactoryRegistry
import org.multipaz.provisioning.CredentialFormat
import org.multipaz.rpc.backend.BackendEnvironment
import org.multipaz.server.common.baseUrl

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
    val registry = BackendEnvironment.getInterface(CredentialFactoryRegistry::class)!!
    val signingKeys = registry.byId.mapValues { (_, factory) -> factory.getSigningKey() }
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
                for (credentialFactory in registry.byId.values) {
                    val signingKey = signingKeys[credentialFactory.configurationId]!!
                    putJsonObject(credentialFactory.configurationId) {
                        if (useScopes) {
                            put("scope", credentialFactory.scope)
                        }
                        val format = credentialFactory.format
                        put("format", format.formatId)
                        when (format) {
                            is CredentialFormat.Mdoc -> put("doctype", format.docType)
                            is CredentialFormat.SdJwt -> put("vct", format.vct)
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
                                if (credentialFactory.acceptAndroidKeyAttestation) {
                                    val level = when (credentialFactory.keyMintSecurityLevel) {
                                        AndroidKeystoreSecurityLevel.SOFTWARE ->
                                            "Software"
                                        AndroidKeystoreSecurityLevel.TRUSTED_ENVIRONMENT ->
                                            "TrustedEnvironment"
                                        AndroidKeystoreSecurityLevel.STRONG_BOX ->
                                            "StrongBox"
                                    }
                                    putJsonObject("android_keystore_attestation") {
                                        putJsonObject("key_attestations_required") {
                                            put("key_mint_security_level", level)
                                        }
                                        putJsonArray("proof_signing_alg_values_supported") {
                                            credentialFactory.proofSigningAlgorithms.forEach {
                                                add(it)
                                            }
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
                            if (credentialFactory.format is CredentialFormat.Mdoc) {
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