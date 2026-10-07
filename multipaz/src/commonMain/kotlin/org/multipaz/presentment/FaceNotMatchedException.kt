package org.multipaz.presentment

/**
 * Thrown when face matching fails or is canceled by the user during presentment.
 *
 * @param message detail message.
 * @param cause the cause if any.
 */
class FaceNotMatchedException(
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause)
