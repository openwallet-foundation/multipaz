package org.multipaz.document

/**
 * Thrown when importing a [org.multipaz.mpzpass.MpzPass] into [DocumentStore] fails.
 *
 * @property message the message or `null`.
 * @property cause the cause of `null`.
 */
class ImportMpzPassException(message: String? = null, cause: Throwable? = null) : Exception(message, cause)