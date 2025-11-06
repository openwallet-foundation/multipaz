package org.multipaz.openid4vci.request

import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.receiveParameters
import io.ktor.server.response.respondText
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.multipaz.jwt.ChallengeInvalidException
import org.multipaz.openid4vci.util.OpaqueIdType
import org.multipaz.openid4vci.util.addFreshNonceHeaders
import org.multipaz.openid4vci.util.createSession
import org.multipaz.openid4vci.util.idToCode
import org.multipaz.openid4vci.util.respondWithNewClientAttestationChallenge
import kotlin.time.Duration.Companion.seconds

/**
 * Pushed Authorization Request, which is the first request to be sent to our OpenID4VCI server
 * if authorize challenge path is not used. In theory, other, simpler (and less secure) forms of
 * client authorization are possible, but our implementation requires Pushed Authorization Request.
 *
 * The purpose of this request is for the Wallet App (or Wallet Server serving as a proxy)
 * to establish authorization session on the issuance server. This session is then referenced when
 * performing browser-based proofing/authorization, using requestUri issued in the response from
 * this request. Once web-based authorization is complete, Wallet App re-establishes control over
 * the session in [TokenServlet] by (1) proving that hash of code_verifier matches
 * code_challenge supplied to this service, (2) using DPoP authorization using the same key
 * as presented in DPoP header to this endpoint. (3) Using OAuth-Client-Attestation-PoP using
 * the same key as provided in OAuth-Client-Attestation header to this endpoint.
 *
 * One of the parameters of this servlet is redirect_uri. When the URL supplied using this
 * parameter is resolved, it signals the end of the web-based user authorization session.
 */
suspend fun pushedAuthorizationRequest(call: ApplicationCall) {
    val parameters = call.receiveParameters()

    val id = try {
        // This is where actual work happens
        createSession(call.request, parameters)
    } catch (_: ChallengeInvalidException) {
        respondWithNewClientAttestationChallenge(call)
        return
    }

    // Format the result (session identifying information).
    val expirationSeconds = 600
    val code = idToCode(OpaqueIdType.PAR_CODE, id, expirationSeconds.seconds)

    addFreshNonceHeaders(call)
    call.respondText(
        text = buildJsonObject {
                put("request_uri", "urn:ietf:params:oauth:request_uri:$code")
                put("expires_in", expirationSeconds)
            }.toString(),
        contentType = ContentType.Application.Json,
        status = HttpStatusCode.Created
    )
}
