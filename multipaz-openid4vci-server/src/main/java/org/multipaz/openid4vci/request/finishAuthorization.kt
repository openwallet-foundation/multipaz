package org.multipaz.openid4vci.request

import io.ktor.http.ContentType
import io.ktor.http.encodeURLParameter
import io.ktor.server.application.ApplicationCall
import io.ktor.server.response.respondRedirect
import io.ktor.server.response.respondText
import kotlinx.io.bytestring.ByteString
import org.multipaz.crypto.Algorithm
import org.multipaz.crypto.Crypto
import org.multipaz.openid4vci.util.IssuanceState
import org.multipaz.openid4vci.util.OpaqueIdType
import org.multipaz.openid4vci.util.codeToId
import org.multipaz.openid4vci.util.generatePreauthorizedOffer
import org.multipaz.openid4vci.util.generateRandom
import org.multipaz.openid4vci.util.getSystemOfRecordUrl
import org.multipaz.server.getBaseUrl
import org.multipaz.openid4vci.util.idToCode
import org.multipaz.rpc.backend.BackendEnvironment
import org.multipaz.rpc.backend.Resources
import org.multipaz.rpc.handler.InvalidRequestException
import org.multipaz.util.Logger
import kotlin.time.Duration.Companion.minutes

private const val TAG = "finish_authorization"

/**
 * Finish web-based authorization and hand off the session back to the Wallet App (or
 * Wallet Server).
 */
suspend fun finishAuthorization(call: ApplicationCall) {
    val systemOfRecordUrl = BackendEnvironment.getSystemOfRecordUrl()
    val stateParam = call.request.queryParameters["state"]
        ?: throw InvalidRequestException("missing parameter 'state'")
    val id = if (systemOfRecordUrl == null) {
        // working without system of record
        codeToId(OpaqueIdType.ISSUER_STATE, stateParam)
    } else {
        // redirected from the system of record
        codeToId(OpaqueIdType.RECORDS_STATE, stateParam)
    }
    val state = IssuanceState.getIssuanceState(id)
    var error: String? = call.request.queryParameters["error"]

    if (systemOfRecordUrl == null) {
        if (state.authorized != true) {
            // We only could get there if something was tampering with redirects.
            Logger.e(TAG, "Unexpected state: not authorized, yet no error specified")
            error = "unexpected_state"
        }
    } else if (error == null) {
        // NB: System-of-Record access code is sensitive. In our simple implementation
        // we store it in the database as plain text. For better security it could be
        // encrypted using hash of an active encrypted code produced by idToCode
        // that we hand over to the client. It is ticky to manage, though (as it would have to be
        // re-encrypted any time a new code is issued) and is not warranted in the sample code.
        state.systemOfRecordAuthCode = call.request.queryParameters["code"]
            ?: throw InvalidRequestException("missing parameter 'code'")
        state.authorized = true
    }
    if (state.clientId != null) {
        // regular authorization flow
        IssuanceState.updateIssuanceState(id, state)
        processRedirect(
            call = call,
            error = error,
            authCode = if (error == null) {
                idToCode(OpaqueIdType.REDIRECT, id, 2.minutes)
            } else {
                null
            },
            state = state
        )
    } else {
        // pre-authorized flow
        val txCode: String? = if (error == null) {
            state.txCodeSpec?.generateRandom()?.also {
                state.txCodeHash = ByteString(Crypto.digest(Algorithm.SHA256, it.encodeToByteArray()))
            }
        } else {
            null
        }
        IssuanceState.updateIssuanceState(id, state)
        if (error == null) {
            preauthorizedOffer(call, id, txCode, state, state.urlSchema ?: "haip-vci")
        } else {
            preauthorizedError(call, error)
        }
    }
}

private suspend fun processRedirect(
    call: ApplicationCall,
    error: String?,
    authCode: String?,
    state: IssuanceState
) {
    val redirectUri = state.redirectUri ?: throw IllegalStateException("No redirect url")
    val parameterizedUri = buildString {
        append(redirectUri)
        append("?")
        if (error != null) {
            append("error=")
            append(error.encodeURLParameter())
            val description = call.request.queryParameters["error_description"]
            if (description != null) {
                append("&error_description=")
                append(description.encodeURLParameter())
            }
        } else {
            append("code=")
            append(authCode)
        }
        append("&iss=")
        append(BackendEnvironment.getBaseUrl().encodeURLParameter())
        val clientState = state.clientState
        if (!clientState.isNullOrEmpty()) {
            append("&state=")
            append(clientState.encodeURLParameter())
        }
    }
    if (!redirectUri.startsWith("http://") && !redirectUri.startsWith("https://")) {
        call.respondText(
            text = """
                    <!DOCTYPE html>
                    <html>
                    <head>
                    <meta name="viewport" content="width=device-width, initial-scale=1.0" />
                    <title>Redirecting...</title>
                    </head>
                    <body>
                    <p>Redirecting back to your wallet...</p>
                    <script>
                        // Attempt to automatically trigger the URI redirect
                        window.location.href = "$parameterizedUri";
                    </script>
                    <p>If you are not redirected automatically, <a href="$parameterizedUri">click here</a>.</p>
                    </body>
                    </html>
                """.trimIndent(),
            contentType = ContentType.Text.Html
        )
    } else {
        call.respondRedirect(parameterizedUri)
    }
}

private suspend fun preauthorizedOffer(
    call: ApplicationCall,
    id: String,
    txCode: String?,
    state: IssuanceState,
    urlSchema: String
) {
    val offer = generatePreauthorizedOffer(urlSchema, id, state)
    val resources = BackendEnvironment.getInterface(Resources::class)!!
    val preauthorizedHtml = resources.getStringResource("pre-authorized.html")!!
    call.respondText(
        text = preauthorizedHtml
            .replace("\$offer", offer)
            .replace("\$encodedOffer", offer.encodeURLParameter())
            .replace("\$txCode", if (txCode == null) {
                "No verification code is required"
            } else {
                "Verification code: <code>$txCode</code>"
            }),
        contentType = ContentType.Text.Html
    )
}

private suspend fun preauthorizedError(
    call: ApplicationCall,
    error: String
) {
    val description = call.request.queryParameters["error_description"]
        ?: "No further information"
    val resources = BackendEnvironment.getInterface(Resources::class)!!
    val preauthorizedHtml = resources.getStringResource("pre-authorized-error.html")!!
    call.respondText(
        text = preauthorizedHtml
            .replace("\$error", error)
            .replace("\$description", description),
        contentType = ContentType.Text.Html
    )
}