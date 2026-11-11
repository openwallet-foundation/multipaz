package org.multipaz.bthci

import platform.Foundation.NSRecursiveLock

internal actual class HciLock {
    private val lock = NSRecursiveLock()

    actual fun <T> withLock(block: () -> T): T {
        lock.lock()
        try {
            return block()
        } finally {
            lock.unlock()
        }
    }
}

internal actual suspend fun openDefaultHciPacketIo(): HciPacketIo {
    throw UnsupportedOperationException(
        "No default HciPacketIo on iOS; pass packetIoFactory explicitly to BtHciMdocTransportFactory"
    )
}
