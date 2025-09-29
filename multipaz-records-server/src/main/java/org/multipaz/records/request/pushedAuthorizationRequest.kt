package org.multipaz.records.request

import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.receiveParameters
import io.ktor.server.response.respondText
import kotlin.time.Clock
import kotlinx.io.bytestring.ByteString
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.multipaz.records.data.OauthParams
import org.multipaz.records.data.recordTypes
import org.multipaz.records.data.toCbor
import org.multipaz.rpc.backend.BackendEnvironment
import org.multipaz.rpc.handler.InvalidRequestException
import org.multipaz.rpc.handler.SimpleCipher
import org.multipaz.server.getBaseUrl
import org.multipaz.jwt.JwtCheck
import org.multipaz.util.fromBase64Url
import org.multipaz.util.toBase64Url
import org.multipaz.jwt.validateJwt
import kotlin.time.Duration.Companion.minutes

/**
 * Handles `par` POST request that comes from openid4vci server as a first step to access
 * System-of-Record data.
 *
 * This request initializes authorization session and sets up most of the parameters (such as
 * redirect url to use after browser-based authorization). It also ensures that only a trusted
 * openid4vci server can connect to the System of Record.
 */
suspend fun pushedAuthorizationRequest(call: ApplicationCall) {
    val parameters = call.receiveParameters()

    val clientId = parameters["client_id"]
        ?: throw InvalidRequestException("missing parameter 'client_id'")
    val scope = parameters["scope"] ?: ""
    if (!scope.isEmpty() && !supportedScopes.contains(scope)) {
        throw InvalidRequestException("invalid parameter 'scope'")
    }
    if (parameters["response_type"] != "code") {
        throw InvalidRequestException("invalid parameter 'response_type'")
    }
    if (parameters["code_challenge_method"] != "S256") {
        throw InvalidRequestException("invalid parameter 'code_challenge_method'")
    }
    val redirectUri = parameters["redirect_uri"]
        ?: throw InvalidRequestException("missing parameter 'redirect_uri'")
    val clientState = parameters["state"]
    if (!redirectUri.matches(plausibleUrl)) {
        throw InvalidRequestException("invalid parameter value 'redirect_uri'")
    }
    val codeChallenge = try {
        ByteString(parameters["code_challenge"]!!.fromBase64Url())
    } catch (err: Exception) {
        throw InvalidRequestException("invalid parameter 'code_challenge'")
    }
    if (parameters["client_assertion_type"] != "urn:ietf:params:oauth:client-assertion-type:jwt-bearer") {
        throw InvalidRequestException("invalid parameter 'client_assertion_type'")
    }
    val clientAssertionJwt = parameters["client_assertion"]
        ?: throw InvalidRequestException("missing parameter 'client_assertion'")

    validateJwt(
        jwt = clientAssertionJwt,
        jwtName = "client_assertion",
        publicKey = null,
        checks = mapOf(
            JwtCheck.TRUST to "trusted_client_assertions",  // where to find CA
            JwtCheck.JTI to clientId,
            JwtCheck.SUB to clientId,
            JwtCheck.ISS to clientId,
            JwtCheck.AUD to BackendEnvironment.getBaseUrl()
        )
    )

    val cipher = BackendEnvironment.getInterface(SimpleCipher::class)!!
    val expiration = Clock.System.now() + validity
    val parCode = cipher.encrypt(OauthParams(
        scope = scope,
        codeChallenge = codeChallenge,
        clientState = clientState,
        redirectUri = redirectUri,
        expiration = expiration
    ).toCbor()).toBase64Url()

    call.respondText(
        text = buildJsonObject {
            put("request_uri", OAUTH_REQUEST_URI_PREFIX + parCode)
            put("expires_in", validity.inWholeSeconds)
        }.toString(),
        contentType = ContentType.Application.Json,
        status = HttpStatusCode.Created
    )
}

private val validity = 5.minutes

private val supportedScopes = recordTypes.keys + setOf(
    "utopia_naturalization_sd_jwt",
    "utopia_movie_ticket_sd_jwt"
)

// We do not allow "#", "&" and "?" characters as they belong to query/fragment part of the
// URL which must not be present
private val plausibleUrl = Regex("^[^\\s'\"#&?]+\$")

