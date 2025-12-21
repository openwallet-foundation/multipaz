package org.multipaz.provisioning.openid4vci

import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.statement.readRawBytes
import io.ktor.http.HttpStatusCode
import kotlinx.io.bytestring.ByteString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.multipaz.openid.wellKnown
import org.multipaz.provisioning.CredentialFormat
import org.multipaz.provisioning.CredentialMetadata
import org.multipaz.provisioning.Display
import org.multipaz.provisioning.KeyBindingType
import org.multipaz.provisioning.ProvisioningMetadata
import org.multipaz.rpc.backend.BackendEnvironment
import org.multipaz.util.Logger

internal data class IssuerConfiguration(
    val nonceEndpoint: String?,
    val credentialEndpoint: String,
    val provisioningMetadata: ProvisioningMetadata,
    val authorizationServerUrls: List<String>,
    val credentialConfigurations: Map<String, CredentialConfiguration>
) {
    companion object: JsonParsing("Issuer metadata") {
        private const val TAG = "IssuerConfiguration"

        suspend fun get(url: String, clientPreferences: OpenID4VCIClientPreferences): IssuerConfiguration {
            val httpClient = BackendEnvironment.getInterface(HttpClient::class)!!

            // Fetch issuer metadata
            val issuerMetadataUrl = wellKnown(url, "openid-credential-issuer")
            val issuerMetadataRequest = httpClient.get(issuerMetadataUrl) {}
            if (issuerMetadataRequest.status != HttpStatusCode.OK) {
                throw IllegalStateException("Invalid issuer, no $issuerMetadataUrl")
            }
            val credentialMetadataText = issuerMetadataRequest.readRawBytes().decodeToString()
            val credentialMetadata = Json.parseToJsonElement(credentialMetadataText).jsonObject

            val authorizationServers = credentialMetadata.arrayOrNull("authorization_servers")
            val authorizationServerUrls =
                authorizationServers?.map { it.jsonPrimitive.content } ?: listOf(url)
            val issuerId = credentialMetadata.stringOrNull("credential_issuer")
            if (issuerId != null && issuerId != url) {
                throw IllegalStateException("Issuer metadata: 'credential_issuer' does not match server url")
            }
            val nonceEndpoint = credentialMetadata.stringOrNull("nonce_endpoint")
            val credentialEndpoint = credentialMetadata.string("credential_endpoint")
            val credentials = mutableMapOf<String, CredentialMetadata>()
            val credentialConfigurations = mutableMapOf<String, CredentialConfiguration>()

            val batchIssuance = credentialMetadata.objOrNull("batch_credential_issuance")
            val maxBatchSize = batchIssuance?.integer("batch_size") ?: 1

            for ((id, config) in credentialMetadata.obj("credential_configurations_supported")) {
                if (config !is JsonObject) {
                    throw IllegalStateException("Invalid credential configuration: '$id'")
                }
                val format = try {
                    extractFormat(config)
                } catch (err: IllegalArgumentException) {
                    // Unsupported format, ignore
                    Logger.e(TAG, "Unsupported credential format", err)
                    continue
                }
                credentialConfigurations[id] = CredentialConfiguration(
                    scope = config.stringOrNull("scope")
                )
                val keyProofType = try {
                    extractKeyProofType(config, url, clientPreferences)
                } catch (err: IllegalArgumentException) {
                    Logger.e(TAG, "Unsupported key proof type", err)
                    continue
                }
                credentials[id] = CredentialMetadata(
                    display = extractDisplay(
                        element = config.objOrNull("credential_metadata") ?: config,
                        clientPreferences = clientPreferences
                    ),
                    format = format,
                    keyBindingType = keyProofType,
                    maxBatchSize = maxBatchSize
                )
            }


            val provisioningMetadata = ProvisioningMetadata(
                display = extractDisplay(credentialMetadata, clientPreferences),
                credentials = credentials.toMap()
            )
            return IssuerConfiguration(
                nonceEndpoint = nonceEndpoint,
                credentialEndpoint = credentialEndpoint,
                provisioningMetadata = provisioningMetadata,
                authorizationServerUrls = authorizationServerUrls,
                credentialConfigurations = credentialConfigurations.toMap()
            )
        }

        private suspend fun extractDisplay(
            element: JsonObject?,
            clientPreferences: OpenID4VCIClientPreferences
        ): Display {
            val displayJson = element?.arrayOrNull("display")
            if (displayJson == null || displayJson.isEmpty()) {
                return Display("Untitled", null)
            }
            var bestMatch: JsonObject? = null
            var bestRank = Int.MAX_VALUE
            for (displayObj in displayJson) {
                if (displayObj !is JsonObject) {
                    throw IllegalStateException("Invalid display object in metadata")
                }
                val locale = displayObj["locale"]
                val localeText = if (locale == null) {
                    "unknown"
                } else {
                    if (locale !is JsonPrimitive) {
                        throw IllegalStateException("Invalid display object in metadata")
                    }
                    locale.jsonPrimitive.content
                }
                // TODO: we only do exact locale matches now, that's too restrictive
                val index = clientPreferences.locales.indexOf(localeText)
                val rank = if (index >= 0) index else clientPreferences.locales.size
                if (bestRank > rank) {
                    bestRank = rank
                    bestMatch = displayObj
                }
            }
            val text = bestMatch!!.string("name")
            val logoObj = bestMatch.objOrNull("logo")
            var logo: ByteString? = null
            if (logoObj != null) {
                val uri = logoObj.stringOrNull("uri")
                if (uri != null) {
                    val httpClient = BackendEnvironment.getInterface(HttpClient::class)!!
                    val response = httpClient.get(uri)
                    if (response.status == HttpStatusCode.OK) {
                        logo = ByteString(response.readRawBytes())
                    }
                }
            }
            return Display(text, logo)
        }

        private fun extractFormat(config: JsonObject): CredentialFormat =
            when (val format = config.string("format")) {
                "dc+sd-jwt" -> CredentialFormat.SdJwt(config.string("vct"))
                "mso_mdoc" -> CredentialFormat.Mdoc(config.string("doctype"))
                else -> throw IllegalArgumentException("Unsupported credential format: '$format'")
            }

        private fun extractKeyProofType(
            config: JsonObject,
            issuerId: String,
            clientPreferences: OpenID4VCIClientPreferences
        ): KeyBindingType {
            val proofTypes = config.objOrNull("proof_types_supported")
                ?: return KeyBindingType.Keyless
            val attestation = proofTypes.objOrNull("attestation")
            val jwt = proofTypes.objOrNull("jwt")
            val proof = attestation ?: jwt
            if (proof != null) {
                val alg = preferredAlgorithm(
                    available = proof.arrayOrNull("proof_signing_alg_values_supported"),
                    clientPreferences = clientPreferences
                )
                return if (attestation != null) {
                    KeyBindingType.Attestation(alg)
                } else {
                    KeyBindingType.OpenidProofOfPossession(
                        algorithm = alg,
                        clientId = clientPreferences.clientId,
                        aud = issuerId
                    )
                }
            }
            throw IllegalArgumentException("No supported proof types")
        }
    }
}