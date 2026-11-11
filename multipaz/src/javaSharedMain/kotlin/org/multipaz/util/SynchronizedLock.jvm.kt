package org.multipaz.util

internal actual typealias SynchronizedLock = Any

internal actual inline fun <T> SynchronizedLock.withLock(block: () -> T): T = synchronized(this, block)
