package org.multipaz.openid4vci.request

import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.receiveText
import io.ktor.server.response.respondText
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.multipaz.openid4vci.util.OpaqueIdType
import org.multipaz.openid4vci.util.codeToId
import org.multipaz.openid4vci.util.idToCode
import org.multipaz.server.common.getAdminPassword
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.seconds

/** Thrown when administrative access cannot be validated. */
class AdminCookieInvalid: Exception()

/**
 * Ensures the client supplied valid "issuance_auth" cookie.
 *
 * Throws [AdminCookieInvalid] exception if no valid cookie is present.
 */
suspend fun validateAdminCookie(call: ApplicationCall) {
    val cookie = call.request.cookies["issuance_auth"] ?: throw AdminCookieInvalid()
    try {
        codeToId(OpaqueIdType.ADMIN_COOKIE, cookie)
    } catch (_: IllegalArgumentException) {
        throw AdminCookieInvalid()
    }
}

/**
 * Issues "issuance_auth" cookie to the client upon successful authentication
 * (supplied password matching given administrative password).
 */
suspend fun adminAuth(call: ApplicationCall) {
    val adminPassword = getAdminPassword()
    val request = Json.parseToJsonElement(call.receiveText()) as JsonObject
    if (request["password"]?.jsonPrimitive?.content != adminPassword) {
        call.respondText(
            status = HttpStatusCode.BadRequest,
            text = buildJsonObject {
                put("error", "auth_failed")
                put("error_description", "incorrect password")
            }.toString(),
            contentType = ContentType.Application.Json
        )
    } else {
        val duration = 1.days
        val cookie = idToCode(OpaqueIdType.ADMIN_COOKIE, "", duration + 10.seconds)
        call.respondText(
            text = buildJsonObject {
                put("cookie", cookie)
                put("expiresIn", duration.inWholeSeconds)
            }.toString(),
            contentType = ContentType.Application.Json
        )
    }
}