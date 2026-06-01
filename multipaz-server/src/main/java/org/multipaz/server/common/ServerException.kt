package org.multipaz.server.common

/**
 * Exception that indicates that the currently processed HTTP request should fail.
 *
 * The following JSON-formatted response is sent to the client along with HTTP 400 status:
 * ```{ "error": <code>, "error_description": <message> }```
 *
 * @param code machine-readable error code
 * @param message human-readable error message
 */
class ServerException(
    val code: String,
    message: String,
    cause: Throwable? = null
): Exception(message, cause)