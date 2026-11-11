package org.multipaz.util

import platform.Foundation.NSRecursiveLock

internal actual class SynchronizedLock actual constructor() {
    @PublishedApi
    internal val nsLock = NSRecursiveLock()
}

internal actual inline fun <T> SynchronizedLock.withLock(block: () -> T): T {
    nsLock.lock()
    try {
        return block()
    } finally {
        nsLock.unlock()
    }
}
