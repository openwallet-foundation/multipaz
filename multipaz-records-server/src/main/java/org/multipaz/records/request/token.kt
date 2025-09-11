package org.multipaz.records.request

import io.ktor.http.ContentType
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.receiveParameters
import io.ktor.server.response.respondText
import kotlin.time.Clock
import kotlinx.io.bytestring.ByteString
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.multipaz.crypto.Algorithm
import org.multipaz.crypto.Crypto
import org.multipaz.records.data.AuthorizationData
import org.multipaz.records.data.TokenType
import org.multipaz.records.data.fromCbor
import org.multipaz.records.data.idToToken
import org.multipaz.records.data.tokenToId
import org.multipaz.rpc.backend.BackendEnvironment
import org.multipaz.rpc.handler.InvalidRequestException
import org.multipaz.rpc.handler.SimpleCipher
import org.multipaz.util.fromBase64Url
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes

/**
 * Handles `token` POST request that comes from openid4vci server as a third step to access
 * System-of-Record data.
 *
 * Using this request openid4vci server surrenders authorization code that was given
 * to openid4vci server when redirecting back after successful user authentication. In exchange
 * it is given an access token (to authorize `data` request) and refresh token that can be
 * used to get a fresh access token later.
 */
suspend fun token(call: ApplicationCall) {
    val parameters = call.receiveParameters()
    val qualifiedId = when (parameters["grant_type"]) {
        "authorization_code" -> {
            val code = parameters["code"]
                ?: throw InvalidRequestException("'code' parameter missing")
            val cipher = BackendEnvironment.getInterface(SimpleCipher::class)!!
            val authorizationData = AuthorizationData.fromCbor(
                cipher.decrypt(code.fromBase64Url()))
            if (authorizationData.expiration < Clock.System.now()) {
                throw InvalidRequestException("'code' parameter expired")
            }
            val codeVerifier = parameters["code_verifier"]
                ?: throw InvalidRequestException("'code_verifier' parameter missing")
            val digest = ByteString(Crypto.digest(Algorithm.SHA256, codeVerifier.toByteArray()))
            if (authorizationData.codeChallenge != digest) {
                throw InvalidRequestException("authorization: bad code_verifier")
            }
            authorizationData.qualifiedId
        }
        "refresh_token" -> {
            val refreshToken = parameters["refresh_token"]
                ?: throw InvalidRequestException("'refresh_token' parameter missing")
            tokenToId(TokenType.REFRESH_TOKEN, refreshToken)
        }
        else -> throw InvalidRequestException("invalid parameter 'grant_type'")
    }
    val expiresIn = 60.minutes
    val accessToken = idToToken(TokenType.ACCESS_TOKEN, qualifiedId, expiresIn)
    val refreshToken = idToToken(TokenType.REFRESH_TOKEN, qualifiedId, Duration.INFINITE)
    call.respondText(
        text = buildJsonObject {
            put("access_token", accessToken)
            put("refresh_token", refreshToken)
            put("expires_in", expiresIn.inWholeSeconds)
        }.toString(),
        contentType = ContentType.Application.Json
    )
}
