package org.multipaz.trustmanagement

/**
 * Thrown by [TrustManager.addX509Cert] if there is already [TrustEntry] with the same
 * Subject Key Identifier.
 *
 * @param message the message
 */
class TrustEntryAlreadyExistsException(message: String): Exception(message)