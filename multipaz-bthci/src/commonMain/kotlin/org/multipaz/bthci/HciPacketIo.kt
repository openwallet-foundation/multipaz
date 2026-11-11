package org.multipaz.bthci

/**
 * Abstraction for reading and writing H4-framed Bluetooth HCI packets (`0x01` Command,
 * `0x02` ACL Data, `0x04` Event).
 */
interface HciPacketIo {
    /**
     * Reads the next H4-framed HCI packet from the controller, or returns `null` if closed.
     */
    suspend fun readPacket(): ByteArray?

    /**
     * Writes an H4-framed HCI packet to the controller.
     */
    suspend fun writePacket(packet: ByteArray)

    /**
     * Whether the underlying transport keeps its descriptor open across [close] calls for reuse.
     */
    val keepsDescriptorOpenOnClose: Boolean
        get() = false

    /**
     * Notifies the transport that `HCI_Reset` has already been executed and acknowledged prior to [close].
     */
    fun markControllerResetCompleted() {}

    /**
     * Closes the underlying HCI transport and unblocks any pending [readPacket] call.
     */
    fun close()
}
