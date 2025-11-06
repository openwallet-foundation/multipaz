package org.multipaz.openid4vci.request

import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.receiveParameters
import io.ktor.server.response.respondText
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.multipaz.openid4vci.util.IssuanceState
import org.multipaz.openid4vci.util.OPENID4VP_REQUEST_URI_PREFIX
import org.multipaz.openid4vci.util.OpaqueIdType
import org.multipaz.openid4vci.util.authorizeWithDpop
import org.multipaz.openid4vci.util.codeToId
import org.multipaz.openid4vci.util.createSession
import org.multipaz.openid4vci.util.idToCode
import org.multipaz.rpc.backend.BackendEnvironment
import org.multipaz.server.getBaseUrl
import java.net.URLEncoder
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * Serves "OAuth 2.0 for First-Party Applications" workflow.
 *
 * It is used for presentation during issuance.
 */
suspend fun authorizeChallenge(call: ApplicationCall) {
    val parameters = call.receiveParameters()
    val authSession = parameters["auth_session"]
    val id = if (authSession == null) {
        // Initial call, authSession was not yet established
        createSession(call.request, parameters)
    } else {
        codeToId(OpaqueIdType.AUTH_SESSION, authSession)
    }
    val presentation = parameters["presentation_during_issuance_session"]
    val state = IssuanceState.getIssuanceState(id)
    if (authSession != null) {
        authorizeWithDpop(
            call.request,
            state.dpopKey ?: throw IllegalArgumentException("DPoP is required"),
            state.clientId!!,
            null
        )
    }
    val baseUrl = BackendEnvironment.getBaseUrl()
    if (presentation == null) {
        val expirationSeconds = 600
        val code = idToCode(OpaqueIdType.PAR_CODE, id, expirationSeconds.seconds)
        val openid4VpCode = idToCode(OpaqueIdType.OPENID4VP_CODE, id, 5.minutes)
        val requestUri = URLEncoder.encode(
            OPENID4VP_REQUEST_URI_PREFIX + openid4VpCode,
            "UTF-8"
        )
        call.respondText(
            text = buildJsonObject {
                put("error", "insufficient_authorization")
                put("presentation", "$baseUrl/authorize?request_uri=$requestUri")
                put("auth_session", idToCode(OpaqueIdType.AUTH_SESSION, id, 5.minutes))
                put("request_uri", "urn:ietf:params:oauth:request_uri:$code")
                put("expires_in", expirationSeconds)
            }.toString(),
            contentType = ContentType.Application.Json,
            status = HttpStatusCode.BadRequest
        )
    } else {
        if (codeToId(OpaqueIdType.OPENID4VP_PRESENTATION, presentation) != id) {
            throw IllegalStateException("Bad presentation code")
        }
        call.respondText(
            text = buildJsonObject {
                put("authorization_code", idToCode(OpaqueIdType.REDIRECT, id, 2.minutes))
            }.toString(),
            contentType = ContentType.Application.Json
        )
    }
}