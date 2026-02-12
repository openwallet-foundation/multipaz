package org.multipaz.presentment

/**
 * Thrown when presentment was cancelled.
 *
 * @property message message to display.
 */
class PresentmentCanceled(message: String): Exception(message)