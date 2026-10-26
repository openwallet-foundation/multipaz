package org.multipaz.presentment

/**
 * Thrown when user authentication (such as biometric or passphrase verification to unlock a key)
 * fails or is canceled during presentment.
 *
 * @param message detail message.
 * @param cause the cause if any.
 */
class AuthenticationFailedException(
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause)
