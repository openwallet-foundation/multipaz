package org.multipaz.openid4vci.server

import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCallPipeline
import io.ktor.server.application.call
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import kotlinx.coroutines.Deferred
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.multipaz.openid4vci.request.adminAuth
import org.multipaz.openid4vci.request.adminSessionInfo
import org.multipaz.openid4vci.request.adminListSessions
import org.multipaz.openid4vci.request.adminSetCredentialStatus
import org.multipaz.openid4vci.request.authorizeChallenge
import org.multipaz.openid4vci.request.authorizeGet
import org.multipaz.openid4vci.request.authorizePost
import org.multipaz.openid4vci.request.challenge
import org.multipaz.openid4vci.request.credential
import org.multipaz.openid4vci.request.credentialRequest
import org.multipaz.openid4vci.request.finishAuthorization
import org.multipaz.openid4vci.request.identifierList
import org.multipaz.openid4vci.request.preauthorizedOffer
import org.multipaz.openid4vci.request.nonce
import org.multipaz.openid4vci.request.openid4VpResponse
import org.multipaz.openid4vci.request.paint
import org.multipaz.openid4vci.request.pushedAuthorizationRequest
import org.multipaz.openid4vci.request.qrCode
import org.multipaz.openid4vci.request.signingCertificate
import org.multipaz.openid4vci.request.statusList
import org.multipaz.openid4vci.request.token
import org.multipaz.openid4vci.request.validateAdminCookie
import org.multipaz.openid4vci.request.wellKnownOauthAuthorization
import org.multipaz.openid4vci.request.wellKnownOpenidCredentialIssuer
import org.multipaz.openid4vci.util.OpenID4VCIRequestError
import org.multipaz.server.common.ServerEnvironment
import org.multipaz.server.request.push
import org.multipaz.server.common.serveResources
import org.multipaz.server.request.certificateAuthority
import org.multipaz.util.Logger

private const val TAG = "ApplicationExt"

/**
 * Defines server endpoints for HTTP GET and POST.
 */
fun Application.configureRouting(serverEnvironment: Deferred<ServerEnvironment>) {
    intercept(ApplicationCallPipeline.Plugins) {
        // Customize error handling for OpenId4VCI-specific exceptions
        try {
            proceed()
        } catch (err: OpenID4VCIRequestError) {
            Logger.e(TAG, "OpenID4VCI Error", err)
            err.printStackTrace()
            call.respondText(
                status = HttpStatusCode.BadRequest,
                text = buildJsonObject {
                    put("error", err.code)
                    put("error_description", err.description)
                }.toString(),
                contentType = ContentType.Application.Json
            )
        }
    }
    routing {
        push(serverEnvironment)
        certificateAuthority()
        serveResources()
        get("/authorize") { authorizeGet(call) }
        post("/authorize") { authorizePost(call) }
        post("/authorize_challenge") { authorizeChallenge(call) }
        post("/challenge") { challenge(call) }
        post("/credential_request") { credentialRequest(call) }
        post("/credential") { credential(call) }
        get("/finish_authorization") { finishAuthorization(call) }
        post("/nonce") { nonce(call) }
        post("/openid4vp_response") { openid4VpResponse(call) }
        get("/paint") { paint(call) }
        post("/par") { pushedAuthorizationRequest(call) }
        get("/qr") { qrCode(call) }
        post("/token") { token(call) }
        get("/.well-known/openid-credential-issuer") { wellKnownOpenidCredentialIssuer(call) }
        get("/.well-known/oauth-authorization-server") { wellKnownOauthAuthorization(call) }
        get("/signing_certificate") { signingCertificate(call) }
        post("/preauthorized_offer") { preauthorizedOffer(call) }
        get("/status_list/{bucket}") { statusList(call, call.parameters["bucket"]!!) }
        get("/identifier_list/{bucket}") { identifierList(call, call.parameters["bucket"]!!) }
        post("/admin_auth") { adminAuth(call) }
        get("/admin_list_sessions") {
            validateAdminCookie(call)
            adminListSessions(call)
        }
        get("/admin_session_info") {
            validateAdminCookie(call)
            adminSessionInfo(call)
        }
        post("/admin_set_credential_status") {
            validateAdminCookie(call)
            adminSetCredentialStatus(call)
        }
    }
}
