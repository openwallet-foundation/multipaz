package org.multipaz.presentment

/**
 * Thrown when it's not possible to satisfy the request.
 *
 * @property message message to display or `null`.
 * @property cause the cause.
 */
class PresentmentCannotSatisfyRequestException(message: String?, cause: Throwable): Exception(message, cause)