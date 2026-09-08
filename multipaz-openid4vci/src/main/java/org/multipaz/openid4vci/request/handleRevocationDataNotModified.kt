package org.multipaz.openid4vci.request

import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.fromHttpToGmtDate
import io.ktor.http.toHttpDate
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.header
import io.ktor.server.response.etag
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.util.date.GMTDate
import org.multipaz.revocation.RevocationData
import org.multipaz.util.Logger

internal suspend fun handleRevocationDataNotModified(
    call: ApplicationCall,
    revocationData: RevocationData
): Boolean {
    val creationMillis = revocationData.creationTime.toEpochMilliseconds()
    val eTag = "W/\"$creationMillis\""
    if (call.request.header(HttpHeaders.IfNoneMatch) == eTag) {
        call.respond(HttpStatusCode.NotModified)
        return true
    }
    try {
        call.request.header(HttpHeaders.IfModifiedSince)?.fromHttpToGmtDate()?.let {
            if (it.timestamp >= creationMillis) {
                call.respond(HttpStatusCode.NotModified)
                return true
            }
        }
    } catch (err: Exception) {
        Logger.e(TAG, "Error parsing If-Modified-Since header", err)
    }
    call.response.header(HttpHeaders.LastModified, GMTDate(creationMillis).toHttpDate())
    call.response.etag(eTag)
    return false
}

private const val TAG = "identifierList"
