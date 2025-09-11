package org.multipaz.openid4vci.request

import io.ktor.http.ContentType
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.receiveText
import io.ktor.server.response.respondText
import kotlinx.io.bytestring.ByteString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import org.multipaz.crypto.Algorithm
import org.multipaz.crypto.Crypto
import org.multipaz.openid4vci.credential.CredentialFactory
import org.multipaz.openid4vci.util.IssuanceState
import org.multipaz.openid4vci.util.SystemOfRecordAccess
import org.multipaz.openid4vci.util.generatePreauthorizedOffer
import org.multipaz.openid4vci.util.generateRandom
import org.multipaz.openid4vci.util.parseTxKind
import org.multipaz.rpc.backend.BackendEnvironment
import org.multipaz.rpc.backend.Configuration
import org.multipaz.server.getBaseUrl
import kotlin.time.Clock
import kotlin.time.Duration.Companion.seconds

private const val OFFER_URL_SCHEMA = "openid-credential-offer:"

/**
 * Server-to-server RPC that System of Record can call to create pre-authorized offers at
 * this issuance server.
 *
 * This request is outside of scope of OpenID4VCI and thus is not specified in the standard.
 *
 * Issuance server is assumed to be trusted by the System of Record, its address must be
 * specified in the System of Record configuration and not come from external/untrusted sources.
 *
 * Request format:
 * ```
 * {
 *     "access_token": "...",  // OpenID access token
 *     "expires_in": "...",  // Number of seconds that access_token stays valid
 *     "refresh_token": "...",  // OpenID refresh token
 *     "scope": "...",  // record type (or "core")
 *     "instance": "...",  // record instance (ignored for "core")
 *     "tx_kind": "...",  // transaction code kind
 *     "tx_prompt": "..."  // transaction code prompt
 * }
 * ```
 *
 * Credential offer is generated for all credential types that reference
 * the given scope.
 *
 * Response:
 * ```
 * [
 *     {
 *        credential_id: "...",
 *        display: { ... },  // openid4vci display structure
 *        offer: "...",  // credential offer url
 *        tx_code: "..."  // generated transaction code if any
 *     },
 *     ...
 * ]
 * ```
 */
suspend fun preauthorizedOffer(call: ApplicationCall) {
    val baseUrl = BackendEnvironment.getBaseUrl()
    val configuration = BackendEnvironment.getInterface(Configuration::class)!!
    val request = Json.parseToJsonElement(call.receiveText()) as JsonObject
    val access = SystemOfRecordAccess(
        accessToken = request["access_token"]!!.jsonPrimitive.content,
        accessTokenExpiration = Clock.System.now() + request["expires_in"]!!.jsonPrimitive.int.seconds,
        refreshToken = request["refresh_token"]!!.jsonPrimitive.content,
    )
    val scope = request["scope"]!!.jsonPrimitive.content
    val txCodeSpec = parseTxKind(
        txKind = request["tx_kind"]?.jsonPrimitive?.content,
        txPrompt = request["tx_prompt"]?.jsonPrimitive?.content
    )
    val locale = configuration.getValue("issuer_locale") ?: "en-US"
    val response = buildJsonArray {
        for ((configId, config) in CredentialFactory.getRegisteredFactories().byOfferId) {
            if (config.scope != scope) {
                continue
            }
            val txCode = txCodeSpec?.generateRandom()
            val state = IssuanceState(
                clientId = null,
                scope = scope,
                clientAttestationKey = null,
                dpopKey = null,
                redirectUri = null,
                codeChallenge = null,
                configurationId = configId,
                authorized = true,
                systemOfRecordAccess = access,
                txCodeSpec = txCodeSpec,
                txCodeHash = txCode?.let {
                    ByteString(Crypto.digest(Algorithm.SHA256, it.encodeToByteArray()))
                }
            )
            val id = IssuanceState.createIssuanceState(state)
            val offer = generatePreauthorizedOffer(
                offerSchema = OFFER_URL_SCHEMA,
                id = id,
                state = state
            )
            addJsonObject {
                put("configuration_id", configId)
                putJsonArray("display") {
                    addJsonObject {
                        put("name", config.name)
                        put("locale", locale)
                        if (config.logo != null) {
                            put("logo", buildJsonObject {
                                put("uri", JsonPrimitive("$baseUrl/${config.logo}"))
                            })
                        }
                    }
                }
                put("offer", offer)
                if (txCode != null) {
                    put("tx_code", txCode)
                }
            }
        }
    }
    call.respondText(
        contentType = ContentType.Application.Json,
        text = response.toString()
    )
}