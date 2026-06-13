package org.multipaz.records.request

import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.receiveParameters
import io.ktor.server.response.respondText
import org.multipaz.server.enrollment.Enrollment
import org.multipaz.server.enrollment.ServerIdentity
import org.multipaz.server.enrollment.enrollServer

/**
 * Handles enrollment request from another server.
 *
 * Enrollment request should be a `POST` request with JSON body in the following format:
 *
 * ```
 * {
 *     "url": "https://requesting.server.host/server/path",
 *     "request_id": "randomly-selected-id",
 *     "identity": server-identity
 * }
 * ```
 *
 * Where `server-identity` is one of the [ServerIdentity] names.
 *
 * This server then "enrolls" the requesting server by creating a requested certificate
 * through [Enrollment] interface.
 *
 * See also [enrollServer].
 */
suspend fun enroll(call: ApplicationCall) {
    val parameters = call.receiveParameters()
    enrollServer(
        url = parameters["url"]!!,
        requestId = parameters["request_id"]!!,
        serverIdentity = ServerIdentity.valueOf(parameters["identity"]!!)
    )
    call.respondText("", status = HttpStatusCode.OK)
}
