package org.multipaz.nci

import kotlinx.coroutines.CancellationException
import kotlinx.io.IOException

/**
 * Abstraction for reading and writing raw NFC Controller Interface (NCI) packets
 * (3-byte header `[MT|PBF|GID_or_ConnID, OID_or_RFU, PayloadLen]` + payload) and
 * controlling the hardware reset / enable (`VEN`) pin.
 */
interface NciPacketIo {
    /**
     * Performs a hardware power-cycle / reset of the NFC controller (for example, toggling the
     * `VEN` GPIO pin low and then high).
     *
     * @throws IOException if resetting the controller hardware fails.
     * @throws CancellationException if the coroutine is cancelled.
     */
    @Throws(IOException::class, CancellationException::class)
    suspend fun resetHardware()

    /**
     * Reads the next complete NCI packet from the controller, or returns `null` if closed.
     *
     * @throws IOException if reading from the controller transport fails.
     * @throws CancellationException if the coroutine is cancelled.
     */
    @Throws(IOException::class, CancellationException::class)
    suspend fun readPacket(): ByteArray?

    /**
     * Writes a complete NCI packet to the controller.
     *
     * @throws IOException if writing to the controller transport fails.
     * @throws CancellationException if the coroutine is cancelled.
     */
    @Throws(IOException::class, CancellationException::class)
    suspend fun writePacket(packet: ByteArray)

    /**
     * Closes the underlying transport, powers down the controller if applicable, and unblocks
     * any pending [readPacket] call.
     */
    fun close()
}
