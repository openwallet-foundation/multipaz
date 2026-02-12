package org.multipaz.presentment

/**
 * Thrown if timed out waiting for a message from the remote reader.
 */
class Iso18013PresentmentTimeoutException : Exception {
    /**
     * Construct a new exception.
     */
    constructor()

    /**
     * Construct a new exception.
     *
     */
    constructor(message: String) : super(message)

    /**
     * Construct a new exception.
     *
     * @param message the message.
     * @param cause the cause.
     */
    constructor(
        message: String,
        cause: Throwable
    ) : super(message, cause)

    /**
     * Construct a new exception.
     *
     * @param cause the cause.
     */
    constructor(cause: Throwable) : super(cause)
}