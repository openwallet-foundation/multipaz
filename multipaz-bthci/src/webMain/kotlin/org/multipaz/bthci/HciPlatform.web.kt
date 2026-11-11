package org.multipaz.bthci

internal actual class HciLock {
    actual fun <T> withLock(block: () -> T): T = block()
}

internal actual suspend fun openDefaultHciPacketIo(): HciPacketIo {
    throw UnsupportedOperationException(
        "No default HciPacketIo on Web; pass packetIoFactory explicitly to BtHciMdocTransportFactory"
    )
}
