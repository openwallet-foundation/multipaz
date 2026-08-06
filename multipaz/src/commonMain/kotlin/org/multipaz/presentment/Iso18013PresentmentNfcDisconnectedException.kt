package org.multipaz.presentment

/**
 * Exception thrown when the device is removed from the reader before presentment can be completed.
 */
class Iso18013PresentmentNfcDisconnectedException(
    message: String = "Device was removed from reader before sharing completed"
) : Exception(message)
