package org.multipaz.records.request

import io.ktor.http.ContentType
import io.ktor.http.encodeURLParameter
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.receiveParameters
import io.ktor.server.response.respondRedirect
import io.ktor.server.response.respondText
import io.ktor.utils.io.CancellationException
import kotlin.time.Clock
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import org.multipaz.records.data.AuthorizationData
import org.multipaz.records.data.Identity
import org.multipaz.records.data.IdentityNotFoundException
import org.multipaz.records.data.OauthParams
import org.multipaz.records.data.TokenType
import org.multipaz.records.data.fromCbor
import org.multipaz.records.data.idToToken
import org.multipaz.records.data.toCbor
import org.multipaz.records.data.tokenToId
import org.multipaz.rpc.backend.BackendEnvironment
import org.multipaz.rpc.backend.Resources
import org.multipaz.rpc.handler.InvalidRequestException
import org.multipaz.rpc.handler.SimpleCipher
import org.multipaz.util.Logger
import org.multipaz.util.fromBase64Url
import org.multipaz.util.toBase64Url
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes

const val OAUTH_REQUEST_URI_PREFIX = "urn:ietf:params:oauth:request_uri:"

private const val TAG = "authorize"

/**
 * Handles `authorize` GET request that comes from the user's browser (being forwarded from
 * openid4vci server) as a second step to access System-of-Record data.
 *
 * After the successful authorization, user is redirected back to openid4vci server (providing
 * authorization code in the process) and subsequently to the user's wallet app.
 */
suspend fun authorizeGet(call: ApplicationCall) {
    val requestUri = call.request.queryParameters["request_uri"] ?: ""
    if (requestUri.startsWith(OAUTH_REQUEST_URI_PREFIX)) {
        // Create a simple web page for the user to authorize the credential issuance.
        getHtml(requestUri.substring(OAUTH_REQUEST_URI_PREFIX.length), call)
    } else {
        throw InvalidRequestException("Invalid or missing 'request_uri' parameter")
    }
}

private suspend fun getHtml(code: String, call: ApplicationCall) {
    val resources = BackendEnvironment.getInterface(Resources::class)!!
    val cipher = BackendEnvironment.getInterface(SimpleCipher::class)!!
    val oauthParams = OauthParams.fromCbor(cipher.decrypt(code.fromBase64Url()))
    val authorizeHtml = resources.getStringResource("authorize.html")!!
    val personMap = buildJsonObject {
        Identity.listAll().forEach { id ->
            try {
                val identity = Identity.findById(id)
                val firstName = identity.data.core["given_name"]?.asTstr ?: "Unnamed"
                val lastName = identity.data.core["family_name"]?.asTstr ?: "NoSurname"
                val personId = idToToken(TokenType.FE_TOKEN, id, Duration.INFINITE)
                putJsonObject(personId) {
                    put("name", "$firstName $lastName")
                    putJsonObject("instances") {
                        identity.data.records[oauthParams.scope]?.forEach { recordId, recordData ->
                            val text = if (recordData.hasKey("instance_title")) {
                                recordData["instance_title"].asTstr
                            } else {
                                "Document instance $recordId"
                            }
                            put(recordId, text)
                        }
                    }
                }
            } catch (err: Exception) {
                Logger.e(TAG, "Failed to read identity '$id'", err)
            }
        }
    }
    call.respondText(
        text = authorizeHtml
            .replace("\$scope", oauthParams.scope)
            .replace("\$authorizationCode", code)
            .replace("\$personMap", personMap.toString()),
        contentType = ContentType.Text.Html
    )
}

/**
 * Handles `authorize` POST request from the authorization web page.
 *
 * If the user has successfully "authenticated" (for demo purposes we just allow
 * selecting person from a list), creates access code.
 */
suspend fun authorizePost(call: ApplicationCall) {
    val parameters = call.receiveParameters()
    val code = parameters["authorizationCode"]
        ?: throw InvalidRequestException("'authorizationCode' missing")
    val cipher = BackendEnvironment.getInterface(SimpleCipher::class)!!
    val oauthParams = OauthParams.fromCbor(cipher.decrypt(code.fromBase64Url()))
    val person = parameters["person"]
        ?: throw InvalidRequestException("'person' missing")
    val recordId = parameters["instance"]
    try {
        if (person.isEmpty()) {
            throw AuthorizationFailed()
        }
        val id = tokenToId(TokenType.FE_TOKEN, person)
        try {
            Identity.findById(id)
        } catch (_: IdentityNotFoundException) {
            throw AuthorizationFailed()
        }
        if (oauthParams.expiration < Clock.System.now()) {
            Logger.e(TAG, "Authorization session expired: ${oauthParams.expiration}")
            throw IllegalArgumentException("Authorization session expired")
        }
        val authCode = cipher.encrypt(
            AuthorizationData(
                qualifiedId = oauthParams.scope + ":" + recordId + ":" + id,
                codeChallenge = oauthParams.codeChallenge,
                expiration = Clock.System.now() + 3.minutes
            ).toCbor()
        ).toBase64Url()
        call.respondRedirect(buildString {
            append(oauthParams.redirectUri)
            append("?code=")
            append(authCode)
            val clientState = oauthParams.clientState
            if (!clientState.isNullOrEmpty()) {
                append("&state=")
                append(clientState.encodeURLParameter())
            }
        })
    } catch (err: CancellationException) {
        throw err
    } catch (err: Exception) {
        val (error, description) = when (err) {
            is InvalidRequestException -> Pair("invalid_request", err.message)
            is AuthorizationFailed -> Pair("authorization_failed", "Invalid credentials")
            else -> Pair("internal",
                (err::class.simpleName ?: "Unknown") + ": " + err.message)
        }
        Logger.e(TAG, "$error: $description", err)
        call.respondRedirect(buildString {
            append(oauthParams.redirectUri)
            append("?error=")
            append(error)
            if (description != null) {
                append("&error_description=")
                append(description.encodeURLParameter())
            }
            val clientState = oauthParams.clientState
            if (!clientState.isNullOrEmpty()) {
                append("&state=")
                append(clientState.encodeURLParameter())
            }
        })
    }
}

private class AuthorizationFailed: Exception("Authorization failed")