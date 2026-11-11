package org.multipaz.util

/**
 * A reentrant mutual-exclusion lock for short synchronous critical sections in multiplatform code.
 */
internal expect class SynchronizedLock()

/**
 * Executes [block] while holding this [SynchronizedLock].
 */
internal expect inline fun <T> SynchronizedLock.withLock(block: () -> T): T
