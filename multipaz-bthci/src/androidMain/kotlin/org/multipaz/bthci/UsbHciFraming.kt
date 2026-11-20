package org.multipaz.bthci

import kotlinx.io.IOException
import org.multipaz.util.Logger

/**
 * Reassembles USB Interrupt IN transfers into H4-framed HCI Event packets (`0x04` indicator +
 * `[eventCode, paramLen, params...]`) per Bluetooth Core Specification Vol 4, Part B, Section 2.2.
 */
internal class UsbHciEventAssembler {
    companion object {
        private const val TAG = "UsbHciEventAssembler"
        private const val MAX_BUFFER_SIZE = 4096
    }

    private var buffer = ByteArray(512)
    private var buffered = 0

    fun reset() {
        buffered = 0
    }

    fun onChunk(
        chunk: ByteArray,
        length: Int = chunk.size,
        isShortPacket: Boolean,
    ): List<ByteArray> {
        require(length >= 0 && length <= chunk.size) {
            "Invalid chunk length $length for array of size ${chunk.size}"
        }
        if (length == 0) {
            if (buffered > 0) {
                Logger.w(TAG, "Discarding $buffered-byte incomplete HCI event after zero-length USB packet")
                buffered = 0
            }
            return emptyList()
        }
        if (buffered + length > MAX_BUFFER_SIZE) {
            buffered = 0
            throw IOException("USB HCI event reassembly buffer overflow")
        }
        ensureCapacity(buffered + length)
        chunk.copyInto(buffer, destinationOffset = buffered, startIndex = 0, endIndex = length)
        buffered += length

        val completed = mutableListOf<ByteArray>()
        while (buffered >= 2) {
            val paramLen = buffer[1].toInt() and 0xFF
            val expectedLen = 2 + paramLen
            if (buffered < expectedLen) {
                break
            }
            val h4Packet = ByteArray(1 + expectedLen)
            h4Packet[0] = BleHciConstants.HCI_EVENT_PKT
            buffer.copyInto(h4Packet, destinationOffset = 1, startIndex = 0, endIndex = expectedLen)
            completed.add(h4Packet)

            val remaining = buffered - expectedLen
            if (remaining > 0) {
                buffer.copyInto(buffer, destinationOffset = 0, startIndex = expectedLen, endIndex = buffered)
            }
            buffered = remaining
        }

        if (buffered > 0 && isShortPacket) {
            Logger.w(TAG, "Discarding $buffered-byte truncated HCI event after premature short USB packet")
            buffered = 0
        }
        return completed
    }

    private fun ensureCapacity(required: Int) {
        if (buffer.size >= required) return
        var newSize = buffer.size
        while (newSize < required) {
            newSize *= 2
        }
        buffer = buffer.copyOf(newSize)
    }
}

/**
 * Reassembles USB Bulk IN transfers into H4-framed HCI ACL Data packets (`0x02` indicator +
 * `[handleFlagsLo, handleFlagsHi, dataLenLo, dataLenHi, data...]`) per Bluetooth Core Specification
 * Vol 4, Part B, Section 2.2.
 */
internal class UsbHciAclAssembler {
    companion object {
        private const val TAG = "UsbHciAclAssembler"
        private const val MAX_BUFFER_SIZE = 4 + 65535 + 4096
    }

    private var buffer = ByteArray(1024)
    private var buffered = 0

    fun reset() {
        buffered = 0
    }

    fun onChunk(
        chunk: ByteArray,
        length: Int = chunk.size,
        isShortPacket: Boolean,
    ): List<ByteArray> {
        require(length >= 0 && length <= chunk.size) {
            "Invalid chunk length $length for array of size ${chunk.size}"
        }
        if (length == 0) {
            if (buffered > 0) {
                Logger.w(TAG, "Discarding $buffered-byte incomplete HCI ACL packet after zero-length USB packet")
                buffered = 0
            }
            return emptyList()
        }
        if (buffered + length > MAX_BUFFER_SIZE) {
            buffered = 0
            throw IOException("USB HCI ACL reassembly buffer overflow")
        }
        ensureCapacity(buffered + length)
        chunk.copyInto(buffer, destinationOffset = buffered, startIndex = 0, endIndex = length)
        buffered += length

        val completed = mutableListOf<ByteArray>()
        while (buffered >= 4) {
            val dataLen = u16le(buffer, 2)
            val expectedLen = 4 + dataLen
            if (buffered < expectedLen) {
                break
            }
            val h4Packet = ByteArray(1 + expectedLen)
            h4Packet[0] = BleHciConstants.HCI_ACL_DATA_PKT
            buffer.copyInto(h4Packet, destinationOffset = 1, startIndex = 0, endIndex = expectedLen)
            completed.add(h4Packet)

            val remaining = buffered - expectedLen
            if (remaining > 0) {
                buffer.copyInto(buffer, destinationOffset = 0, startIndex = expectedLen, endIndex = buffered)
            }
            buffered = remaining
        }

        if (buffered > 0 && isShortPacket) {
            Logger.w(TAG, "Discarding $buffered-byte truncated HCI ACL packet after premature short USB packet")
            buffered = 0
        }
        return completed
    }

    private fun ensureCapacity(required: Int) {
        if (buffer.size >= required) return
        var newSize = buffer.size
        while (newSize < required) {
            newSize *= 2
        }
        buffer = buffer.copyOf(newSize.coerceAtMost(MAX_BUFFER_SIZE))
    }
}

internal fun extractUsbHciCommandPayload(packet: ByteArray): ByteArray {
    if (packet.size < 4 || packet[0] != BleHciConstants.HCI_COMMAND_PKT) {
        throw IOException("Malformed H4 HCI Command packet (size=${packet.size})")
    }
    val paramLen = packet[3].toInt() and 0xFF
    if (packet.size != 4 + paramLen) {
        throw IOException(
            "H4 HCI Command length mismatch: expected ${4 + paramLen} bytes, got ${packet.size}"
        )
    }
    return packet.copyOfRange(1, packet.size)
}

internal fun extractUsbHciAclPayload(packet: ByteArray): ByteArray {
    if (packet.size < 5 || packet[0] != BleHciConstants.HCI_ACL_DATA_PKT) {
        throw IOException("Malformed H4 HCI ACL Data packet (size=${packet.size})")
    }
    val dataLen = u16le(packet, 3)
    if (packet.size != 5 + dataLen) {
        throw IOException(
            "H4 HCI ACL Data length mismatch: expected ${5 + dataLen} bytes, got ${packet.size}"
        )
    }
    return packet.copyOfRange(1, packet.size)
}
