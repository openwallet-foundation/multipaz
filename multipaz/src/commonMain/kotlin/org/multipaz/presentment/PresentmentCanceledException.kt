package org.multipaz.presentment

/**
 * Thrown when presentment was cancelled.
 *
 * @property message message to display or `null`.
 */
class PresentmentCanceledException(message: String?): Exception(message)