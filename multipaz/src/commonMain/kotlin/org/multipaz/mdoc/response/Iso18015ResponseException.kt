package org.multipaz.mdoc.response

/**
 * Thrown when a credential query cannot be satisfied.
 *
 * @param message error message with detail.
 */
class Iso18015ResponseException(message: String): Exception(message)