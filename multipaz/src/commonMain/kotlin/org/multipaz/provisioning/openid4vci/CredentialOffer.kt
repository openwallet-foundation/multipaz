package org.multipaz.provisioning.openid4vci

import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.statement.readRawBytes
import io.ktor.http.HttpStatusCode
import io.ktor.http.Url
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.multipaz.provisioning.SecretCodeRequest
import org.multipaz.util.Logger

/**
 * Parsed Openid4Vci credential offer.
 */
internal sealed class CredentialOffer {
    abstract val issuerUri: String
    abstract val configurationId: String
    abstract val authorizationServer: String?
    // TODO: Out-of-spec extension from unmerged OpenID4VCI PR #476
    //  (https://github.com/openid/OpenID4VCI/pull/476) used by https://digital-credentials.dev.
    //  Remove or update once OpenID4VCI over Digital Credentials API is standardized.
    abstract val credentialIssuerMetadata: JsonObject?
    abstract val authorizationServerMetadata: JsonObject?


    /**
     * Credential offer that does not contain grants.
     */
    data class Grantless(
        override val issuerUri: String,
        override val configurationId: String,
        override val authorizationServer: String? = null,
        override val credentialIssuerMetadata: JsonObject? = null,
        override val authorizationServerMetadata: JsonObject? = null
    ): CredentialOffer()

    /**
     * Credential offer with Grant Type `urn:ietf:params:oauth:grant-type:pre-authorized_code`.
     */
    data class PreauthorizedCode(
        override val issuerUri: String,
        override val configurationId: String,
        override val authorizationServer: String?,
        val preauthorizedCode: String,
        /* Describes tx_code parameter (see OpenId4Vci spec referenced above) */
        val txCode: SecretCodeRequest?,
        override val credentialIssuerMetadata: JsonObject? = null,
        override val authorizationServerMetadata: JsonObject? = null
    ) : CredentialOffer()

    /**
     * Credential offer with Grant Type `authorization_code`.
     */
    data class AuthorizationCode(
        override val issuerUri: String,
        override val configurationId: String,
        override val authorizationServer: String?,
        val issuerState: String?,
        override val credentialIssuerMetadata: JsonObject? = null,
        override val authorizationServerMetadata: JsonObject? = null
    ) : CredentialOffer()


    internal companion object: JsonParsing("Credential offer") {
        private const val TAG = "CredentialOffer"

        /**
         * Parse openid4vci credential offer Url (from a deep link or Qr scan) and return
         * a [CredentialOffer].
         */
        suspend fun parseCredentialOffer(
            offerUri: String
        ): CredentialOffer {
            try {
                Logger.d(TAG, "Parsing credential offer from URI: $offerUri")
                val params = Url(offerUri).parameters
                var credentialOfferString = params["credential_offer"]
                if (credentialOfferString == null) {
                    val url = params["credential_offer_uri"]
                        ?: throw IllegalStateException("Neither 'credential_offer' nor 'credential_offer_uri' are given")
                    Logger.d(TAG, "Fetching credential offer from URI: $url")
                    val response = HttpClient().get(url) {}
                    if (response.status != HttpStatusCode.OK) {
                        throw IllegalStateException("Error retrieving '$url'")
                    }
                    credentialOfferString = response.readRawBytes().decodeToString()
                }
                val json = Json.parseToJsonElement(credentialOfferString).jsonObject
                Logger.dJson(TAG, "Parsed credential offer", json)
                return parseJson(json)
            } catch (err: CancellationException) {
                throw err
            } catch (err: Exception) {
                Logger.e(TAG, "Parsing error", err)
                throw err
            }
        }

        fun parseJson(json: JsonObject): CredentialOffer {
            val credentialIssuerUri = json.string("credential_issuer")
            val credentialConfigurationIds = json.array("credential_configuration_ids")
            // Right now only use the first configuration id
            val credentialConfigurationId = credentialConfigurationIds[0].jsonPrimitive.content
            // TODO: Out-of-spec extension from unmerged OpenID4VCI PR #476
            //  (https://github.com/openid/OpenID4VCI/pull/476) used by https://digital-credentials.dev.
            //  Remove or update once OpenID4VCI over Digital Credentials API is standardized.
            val credentialIssuerMetadata = json.objOrNull("credential_issuer_metadata")
            val authorizationServerMetadata = json.objOrNull("authorization_server_metadata")
            if (!json.containsKey("grants")) {
                return Grantless(
                    issuerUri = credentialIssuerUri,
                    configurationId = credentialConfigurationId,
                    credentialIssuerMetadata = credentialIssuerMetadata,
                    authorizationServerMetadata = authorizationServerMetadata
                )
            }
            val grants = json.obj("grants")
            val preAuthGrant = grants.objOrNull("urn:ietf:params:oauth:grant-type:pre-authorized_code")
            if (preAuthGrant != null) {
                val preauthorizedCode = preAuthGrant.string("pre-authorized_code")
                val authorizationServer = preAuthGrant.stringOrNull("authorization_server")
                if (authorizationServerMetadata != null && authorizationServer != null) {
                    throw IllegalStateException(
                        "Credential offer: 'authorization_server' must not be present when 'authorization_server_metadata' is provided"
                    )
                }
                val txCode = extractTxCode(preAuthGrant.objOrNull("tx_code"))
                return PreauthorizedCode(
                    issuerUri = credentialIssuerUri,
                    configurationId = credentialConfigurationId,
                    authorizationServer = authorizationServer,
                    preauthorizedCode = preauthorizedCode,
                    txCode = txCode,
                    credentialIssuerMetadata = credentialIssuerMetadata,
                    authorizationServerMetadata = authorizationServerMetadata
                )
            } else {
                val grant = grants.obj("authorization_code")
                val issuerState = grant.stringOrNull("issuer_state")
                val authorizationServer = grant.stringOrNull("authorization_server")
                if (authorizationServerMetadata != null && authorizationServer != null) {
                    throw IllegalStateException(
                        "Credential offer: 'authorization_server' must not be present when 'authorization_server_metadata' is provided"
                    )
                }
                return AuthorizationCode(
                    issuerUri = credentialIssuerUri,
                    configurationId = credentialConfigurationId,
                    authorizationServer = authorizationServer,
                    issuerState = issuerState,
                    credentialIssuerMetadata = credentialIssuerMetadata,
                    authorizationServerMetadata = authorizationServerMetadata
                )
            }
        }

        private fun extractTxCode(txCodeJson: JsonElement?): SecretCodeRequest? {
            return if (txCodeJson == null) {
                null
            } else {
                val obj = txCodeJson.jsonObject
                SecretCodeRequest(
                    description = obj.stringOrNull("description"),
                    length = obj.integerOrNull("length") ?: Int.MAX_VALUE,
                    isNumeric = obj.stringOrNull("input_mode") != "text"
                )
            }
        }
    }
}