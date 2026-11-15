package org.multipaz.mdoc.response

/**
 * Thrown when a credential query cannot be satisfied.
 *
 * @param message error message with detail.
 * @param cause optional underlying cause.
 */
class Iso18015ResponseException(
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause)