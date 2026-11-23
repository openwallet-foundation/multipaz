package org.multipaz.openid4vci.request

import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.receiveText
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.SerializationException
import org.multipaz.openid4vci.customization.NonceManager
import org.multipaz.openid4vci.util.IssuanceState
import org.multipaz.openid4vci.util.NotificationState
import org.multipaz.openid4vci.util.OpaqueIdType
import org.multipaz.openid4vci.util.authorizeWithDpop
import org.multipaz.openid4vci.util.codeToId
import org.multipaz.openid4vci.util.extractAccessToken
import org.multipaz.openid4vci.util.respondWithNewDPoPNonce
import org.multipaz.webtoken.ChallengeInvalidException
import org.multipaz.util.Logger
import org.multipaz.rpc.backend.BackendEnvironment
import org.multipaz.rpc.backend.Configuration as BackendConfiguration
import org.multipaz.rpc.handler.InvalidRequestException
import org.multipaz.server.common.baseUrl

private const val TAG = "notification"

/**
 * Returns the notification endpoint URL when notification support is explicitly enabled.
 * The endpoint is only advertised over HTTPS, as required by OpenID4VCI.
 */
internal suspend fun configuredNotificationEndpoint(): String? {
    val configuration = BackendEnvironment.getInterface(BackendConfiguration::class)!!
    // Notification support is opt-in; do not advertise an endpoint without HTTPS.
    if (configuration.getValue("notification_endpoint_enabled") != "true") {
        return null
    }
    val baseUrl = configuration.baseUrl
    if (!baseUrl.startsWith("https://", ignoreCase = true)) {
        return null
    }
    return "$baseUrl/notification"
}

/**
 * Handles credential notification requests at the /notification endpoint.
 */
suspend fun notification(call: ApplicationCall) {
    if (configuredNotificationEndpoint() == null) {
        call.respond(HttpStatusCode.NotFound)
        return
    }

    val accessToken = try {
        extractAccessToken(call.request)
    } catch (_: InvalidRequestException) {
        respondWithAuthorizationError(call, error = null)
        return
    }
    val id = try {
        codeToId(OpaqueIdType.ACCESS_TOKEN, accessToken)
    } catch (err: CancellationException) {
        throw err
    } catch (_: Exception) {
        respondWithAuthorizationError(call)
        return
    }
    val state = try {
        IssuanceState.getIssuanceState(id)
    } catch (_: IllegalStateException) {
        respondWithAuthorizationError(call)
        return
    }
    val dpopKey = state.dpopKey
    val clientId = state.clientId
    if (dpopKey == null || clientId == null) {
        respondWithAuthorizationError(call)
        return
    }
    val nonceManager = NonceManager.get()
    try {
        authorizeWithDpop(
            request = call.request,
            publicKey = dpopKey,
            clientId = clientId,
            accessToken = accessToken,
            isResourceServer = true,
            initial = false
        )
    } catch (_: ChallengeInvalidException) {
        respondWithNewDPoPNonce(call, nonceManager.credential())
        return
    } catch (_: InvalidRequestException) {
        respondWithAuthorizationError(call, error = "invalid_dpop_proof")
        return
    } catch (_: IllegalArgumentException) {
        respondWithAuthorizationError(call, error = "invalid_dpop_proof")
        return
    }

    if (!isJsonNotificationContentType(call.request.headers[HttpHeaders.ContentType])) {
        respondWithNotificationError(call, "invalid_notification_request")
        return
    }

    val json = try {
        Json.parseToJsonElement(call.receiveText()) as? JsonObject
    } catch (_: SerializationException) {
        null
    }
    if (json == null) {
        respondWithNotificationError(call, "invalid_notification_request")
        return
    }
    Logger.dJson(TAG, "Received notification request", json)

    val notificationId = (json["notification_id"] as? JsonPrimitive)
        ?.takeIf { it.isString }
        ?.content
    val event = (json["event"] as? JsonPrimitive)
        ?.takeIf { it.isString }
        ?.content
    val eventDescription = json["event_description"]
    val eventDescriptionText = (eventDescription as? JsonPrimitive)
        ?.takeIf { it.isString }
        ?.content
    if (notificationId.isNullOrEmpty() || event == null) {
        respondWithNotificationError(call, "invalid_notification_request")
        return
    }
    if (eventDescription != null &&
        (eventDescriptionText == null || eventDescriptionText.any {
            it.code !in 0x20..0x21 && it.code !in 0x23..0x5b && it.code !in 0x5d..0x7e
        })
    ) {
        respondWithNotificationError(call, "invalid_notification_request")
        return
    }
    if (event !in setOf("credential_accepted", "credential_failure", "credential_deleted")) {
        respondWithNotificationError(call, "invalid_notification_request")
        return
    }

    when (NotificationState.recordEvent(notificationId, id, event)) {
        NotificationState.Result.INVALID_ID -> {
            respondWithNotificationError(call, "invalid_notification_id")
            return
        }
        NotificationState.Result.CONFLICT -> {
            respondWithNotificationError(call, "invalid_notification_request")
            return
        }
        NotificationState.Result.RECORDED -> Logger.i(TAG, "Received notification: id=$notificationId, event=$event")
        NotificationState.Result.RETRY -> Logger.d(TAG, "Received repeated notification: id=$notificationId")
    }

    call.respond(HttpStatusCode.NoContent)
}

internal fun isJsonNotificationContentType(contentType: String?): Boolean =
    contentType?.substringBefore(';')?.trim()
        ?.equals(ContentType.Application.Json.toString(), ignoreCase = true) == true

private suspend fun respondWithNotificationError(call: ApplicationCall, error: String) {
    call.response.headers.append(HttpHeaders.CacheControl, "no-store")
    call.respondText(
        status = HttpStatusCode.BadRequest,
        contentType = ContentType.Application.Json,
        text = JsonObject(mapOf("error" to JsonPrimitive(error))).toString()
    )
}

private suspend fun respondWithAuthorizationError(call: ApplicationCall, error: String? = "invalid_token") {
    val challenge = if (error == null) "DPoP" else "DPoP error=\"$error\""
    call.response.headers.append(HttpHeaders.WWWAuthenticate, challenge)
    call.response.headers.append(HttpHeaders.CacheControl, "no-store")
    call.respondText(
        status = HttpStatusCode.Unauthorized,
        contentType = if (error == null) null else ContentType.Application.Json,
        text = if (error == null) "" else JsonObject(mapOf("error" to JsonPrimitive(error))).toString()
    )
}
