package org.multipaz.openid4vci.request

import io.ktor.client.HttpClient
import io.ktor.client.request.headers
import io.ktor.client.request.post
import io.ktor.client.request.request
import io.ktor.client.request.setBody
import io.ktor.client.statement.readBytes
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.Parameters
import io.ktor.http.encodeURLParameter
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.ApplicationRequest
import io.ktor.server.request.receiveParameters
import io.ktor.server.response.header
import io.ktor.server.response.respondText
import kotlin.time.Clock
import org.multipaz.crypto.Algorithm
import org.multipaz.crypto.Crypto
import org.multipaz.rpc.handler.InvalidRequestException
import org.multipaz.util.toBase64Url
import kotlinx.io.bytestring.ByteString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.multipaz.crypto.EcPublicKey
import org.multipaz.jwt.Challenge
import org.multipaz.jwt.ChallengeInvalidException
import org.multipaz.openid4vci.util.IssuanceState
import org.multipaz.openid4vci.util.OpaqueIdType
import org.multipaz.openid4vci.util.OpenID4VCIRequestError
import org.multipaz.openid4vci.util.SystemOfRecordAccess
import org.multipaz.openid4vci.util.addFreshNonceHeaders
import org.multipaz.openid4vci.util.authorizeWithDpop
import org.multipaz.openid4vci.util.codeToId
import org.multipaz.openid4vci.util.getSystemOfRecordUrl
import org.multipaz.openid4vci.util.idToCode
import org.multipaz.openid4vci.util.processInitialDPoP
import org.multipaz.openid4vci.util.respondWithNewClientAttestationChallenge
import org.multipaz.openid4vci.util.respondWithNewDPoPNonce
import org.multipaz.openid4vci.util.validateClientAssertion
import org.multipaz.openid4vci.util.validateClientAttestation
import org.multipaz.openid4vci.util.validateClientAttestationPoP
import org.multipaz.rpc.backend.BackendEnvironment
import org.multipaz.util.Logger
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

private const val PRE_AUTHORIZED_GRANT_TYPE = "urn:ietf:params:oauth:grant-type:pre-authorized_code"

/**
 * Takes control over authentication session after web-based user authentication. This is a
 * counterpart of the [pushedAuthorizationRequest]. It checks that (1) hash of code_verifier
 * supplied here matches code_challenge supplied to push authorization, (2) performs DPoP
 * authorization using the key established in push authorization. Once all the checks are done
 * it issues access token that can be used to request a credential and possibly a refresh token
 * that can be used to request more access tokens.
 */
suspend fun token(call: ApplicationCall) {
    val digest: ByteString?
    val parameters = call.receiveParameters()
    val grantType = parameters["grant_type"]
    val id = when (grantType) {
        "authorization_code" -> {
            val code = parameters["code"]
                ?: throw InvalidRequestException("'code' parameter missing")
            val codeVerifier = parameters["code_verifier"]
                ?: throw InvalidRequestException("'code_verifier' parameter missing")
            digest = ByteString(Crypto.digest(Algorithm.SHA256, codeVerifier.toByteArray()))
            codeToId(OpaqueIdType.REDIRECT, code)
        }
        "refresh_token" -> {
            val refreshToken = parameters["refresh_token"]
                ?: throw InvalidRequestException("'refresh_token' parameter missing")
            digest = null
            codeToId(OpaqueIdType.REFRESH_TOKEN, refreshToken)
        }
        PRE_AUTHORIZED_GRANT_TYPE -> {
            val code = parameters["pre-authorized_code"]
                ?: throw InvalidRequestException("'pre-authorized_code' parameter missing")
            digest = null
            codeToId(OpaqueIdType.PRE_AUTHORIZED, code)
        }
        else -> throw InvalidRequestException("invalid parameter 'grant_type'")
    }
    val state = IssuanceState.getIssuanceState(id)
    if (digest != null) {
        if (state.codeChallenge == digest) {
            state.codeChallenge = null  // challenge met
        } else {
            throw InvalidRequestException("authorization: bad code_verifier")
        }
    }
    if (grantType == PRE_AUTHORIZED_GRANT_TYPE && state.txCodeHash != null) {
        val txCode = parameters["tx_code"]
            ?: throw OpenID4VCIRequestError("invalid_request", "'tx_code' parameter missing")
        val txCodeHash =
            ByteString(Crypto.digest(Algorithm.SHA256, txCode.encodeToByteArray()))
        if (txCodeHash != state.txCodeHash) {
            // TODO: only allow certain number of attempts
            throw OpenID4VCIRequestError("invalid_grant", "incorrect 'tx_code' parameter value")
        }
    } else {
        if (parameters["tx_code"] != null) {
            throw InvalidRequestException("'tx_code' parameter must not be used")
        }
    }
    val initialPreauthorized = grantType == PRE_AUTHORIZED_GRANT_TYPE && state.clientId == null
    if (initialPreauthorized) {
        state.clientId = parameters["client_id"]
            ?: throw InvalidRequestException("'client_id' parameter missing")
    }
    // NB: attestationKey can be null there; a token will be issued anyway. However, if a
    // credential factory requires client attestation, credential will only be issued
    // if state.clientAttestationKey is not null
    val clientId = state.clientId!!
    val attestationKey = validateClientAttestation(call.request, clientId)
    if (initialPreauthorized) {
        if (state.clientAttestationKey != null) {
            throw IllegalStateException()
        }
        state.clientAttestationKey = attestationKey
    } else {
        if (state.clientAttestationKey != attestationKey) {
            throw InvalidRequestException("inconsistent client attestation key")
        }
    }
    val clientAttestationKey = state.clientAttestationKey
    if (clientAttestationKey != null) {
        try {
            validateClientAttestationPoP(call.request, clientId, clientAttestationKey)
        } catch (_: ChallengeInvalidException) {
            respondWithNewClientAttestationChallenge(call)
            return
        }
    }

    if (state.dpopKey != null) {
        try {
            authorizeWithDpop(call.request, state.dpopKey!!, clientId, null)
        } catch (_: ChallengeInvalidException) {
            respondWithNewDPoPNonce(call)
            return
        }
    } else {
        establishDPopKey(call.request, parameters, state)
    }
    state.redirectUri = null
    state.clientState = null
    val expiresIn = 60.minutes
    val accessToken = idToCode(OpaqueIdType.ACCESS_TOKEN, id, expiresIn)
    val refreshToken = idToCode(OpaqueIdType.REFRESH_TOKEN, id, Duration.INFINITE)
    if (state.systemOfRecordAuthCode != null) {
        val systemOfRecordUrl = BackendEnvironment.getSystemOfRecordUrl()
        // NB: this method stores System-of-Record access data (token and refresh_token) in the
        // database as plain text. This is OK for our simple implementation, but in real
        // deployments, it would be better to encrypt it using material from accessToken and
        // refreshToken as key. This way if an attacker gains access to database data, they still
        // would not be able to access System of Record.
        obtainSystemOfRecordToken(systemOfRecordUrl!!, state)
    }
    IssuanceState.updateIssuanceState(id, state)
    addFreshNonceHeaders(call)
    call.respondText(
        text = buildJsonObject {
                put("access_token", accessToken)
                put("refresh_token", refreshToken)
                put("expires_in", expiresIn.inWholeSeconds.toInt())
                put("token_type", "DPoP")
            }.toString(),
        contentType = ContentType.Application.Json
    )
}

private suspend fun establishDPopKey(
    request: ApplicationRequest,
    parameters: Parameters,
    state: IssuanceState
): EcPublicKey {
    check(state.dpopKey == null)
    if (state.clientAttestationKey == null && !validateClientAssertion(parameters, state.clientId!!)) {
        throw InvalidRequestException("clientId must be authenticated")
    }
    val dpopKey = processInitialDPoP(request) ?: throw InvalidRequestException("DPoP is required")
    authorizeWithDpop(
        request = request,
        publicKey = dpopKey,
        clientId = state.clientId!!,
        accessToken = null,
        initial = true  // no nonce, no need to handle ChallengeInvalidException
    )
    state.dpopKey = dpopKey
    return dpopKey
}

private const val TAG = "token"

private suspend fun obtainSystemOfRecordToken(systemOfRecordUrl: String, state: IssuanceState) {
    val req = buildMap {
        put("grant_type", "authorization_code")
        put("code", state.systemOfRecordAuthCode!!)
        put("code_verifier", state.systemOfRecordCodeVerifier!!.toByteArray().toBase64Url())
    }
    val httpClient = BackendEnvironment.getInterface(HttpClient::class)!!
    val response = httpClient.post("$systemOfRecordUrl/token") {
        headers {
            append("Content-Type", "application/x-www-form-urlencoded")
        }
        setBody(req.map { (name, value) ->
            name.encodeURLParameter() + "=" + value.encodeURLParameter()
        }.joinToString("&"))
    }
    val responseText = response.readBytes().decodeToString()
    if (response.status != HttpStatusCode.OK) {
        Logger.e(TAG, "token request error: ${response.status}: $responseText")
    }
    val parsedResponse = Json.parseToJsonElement(responseText).jsonObject
    state.systemOfRecordAccess = SystemOfRecordAccess(
        parsedResponse["access_token"]!!.jsonPrimitive.content,
        accessTokenExpiration = Clock.System.now() +
                (parsedResponse["expires_in"]?.jsonPrimitive?.intOrNull ?: 60).seconds,
        refreshToken = parsedResponse["refresh_token"]!!.jsonPrimitive.content,
    )
    state.systemOfRecordCodeVerifier = null
    state.systemOfRecordAuthCode = null
}