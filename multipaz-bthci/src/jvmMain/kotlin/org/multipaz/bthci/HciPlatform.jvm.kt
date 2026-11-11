package org.multipaz.bthci

internal actual class HciLock {
    private val monitor = Any()

    actual fun <T> withLock(block: () -> T): T = synchronized(monitor, block)
}

internal actual suspend fun openDefaultHciPacketIo(): HciPacketIo =
    LinuxHciSocketPacketIo().also { it.open() }
