package org.multipaz.util

import kotlinx.coroutines.CancellationException

/**
 * Unwraps nested [CancellationException]s to find the root cause.
 *
 * @receiver the [Throwable] to unwrap.
 * @return the root cause [Throwable].
 */
internal fun Throwable.unwrapCancellationException(): Throwable {
    var exception: Throwable = this
    while (exception is CancellationException) {
        val cause = exception.cause ?: return exception
        if (cause == exception) {
            return exception
        }
        exception = cause
    }
    return exception
}
