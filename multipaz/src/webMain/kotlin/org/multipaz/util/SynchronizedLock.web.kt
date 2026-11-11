package org.multipaz.util

internal actual class SynchronizedLock actual constructor()

internal actual inline fun <T> SynchronizedLock.withLock(block: () -> T): T = block()
