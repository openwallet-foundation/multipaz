package org.multipaz.nfc

import kotlinx.coroutines.test.runTest
import kotlinx.io.bytestring.ByteString
import kotlinx.io.bytestring.buildByteString
import org.multipaz.util.appendUInt16
import org.multipaz.util.getUInt16
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class NfcIsoTagTest {
    private open class FakeNfcIsoTag(
        override val maxTransceiveLength: Int
    ) : NfcIsoTag() {
        val sentCommands = mutableListOf<CommandApdu>()

        // Set to the (zero-based) index of a command which should be answered with an error.
        var failCommandAtIndex = -1

        // Emulates the NDEF file of a Type 4 Tag.
        val file = ByteArray(4096)

        override suspend fun transceive(command: CommandApdu): ResponseApdu {
            sentCommands.add(command)
            val index = sentCommands.size - 1
            if (index == failCommandAtIndex) {
                return ResponseApdu(Nfc.RESPONSE_STATUS_ERROR_NO_PRECISE_DIAGNOSIS)
            }
            return when (command.ins) {
                Nfc.INS_UPDATE_BINARY -> {
                    val offset = command.p1 * 0x100 + command.p2
                    command.payload.toByteArray().copyInto(file, offset)
                    ResponseApdu(Nfc.RESPONSE_STATUS_SUCCESS)
                }
                Nfc.INS_READ_BINARY -> {
                    val offset = command.p1 * 0x100 + command.p2
                    ResponseApdu(
                        status = Nfc.RESPONSE_STATUS_SUCCESS,
                        payload = ByteString(file.copyOfRange(offset, offset + command.le))
                    )
                }
                else -> ResponseApdu(Nfc.RESPONSE_STATUS_ERROR_NO_PRECISE_DIAGNOSIS)
            }
        }

        override suspend fun close() {}

        override suspend fun updateDialogMessage(message: String) {}
    }

    private class FakeLimitedNfcIsoTag(
        maxTransceiveLength: Int,
        private val limit: Int
    ) : FakeNfcIsoTag(maxTransceiveLength) {
        override val maxNdefUpdateBinaryPayloadLength: Int
            get() = limit
    }

    private fun createNdefMessage(payloadSize: Int): NdefMessage {
        return NdefMessage(
            records = listOf(
                NdefRecord(
                    tnf = NdefRecord.Tnf.WELL_KNOWN,
                    type = ByteString("U".encodeToByteArray()),
                    payload = ByteString(ByteArray(payloadSize) { (it.and(0xff)).toByte() })
                )
            )
        )
    }

    @Test
    fun singleUpdateBinaryWhenMessageFits() = runTest {
        val tag = FakeNfcIsoTag(maxTransceiveLength = 255)
        val ndefMessage = createNdefMessage(payloadSize = 20)
        val encoded = ndefMessage.encode()

        val received = tag.ndefTransact(ndefMessage, wtInt = 0, nWait = 0)

        // One merged write, then two READ BINARY commands to read the response message.
        assertEquals(3, tag.sentCommands.size)
        val command = tag.sentCommands[0]
        assertEquals(Nfc.INS_UPDATE_BINARY, command.ins)
        assertEquals(0, command.p1)
        assertEquals(0, command.p2)
        assertContentEquals(
            buildByteString { appendUInt16(encoded.size).append(encoded) }.toByteArray(),
            command.payload.toByteArray()
        )
        assertEquals(encoded.size, received.encode().size)
    }

    @Test
    fun chunkedUpdateBinarySequence() = runTest {
        val tag = FakeLimitedNfcIsoTag(maxTransceiveLength = 255, limit = 16)
        val ndefMessage = createNdefMessage(payloadSize = 64)
        val encoded = ndefMessage.encode()
        assertTrue(encoded.size > 16 - 2)

        val received = tag.ndefTransact(ndefMessage, wtInt = 0, nWait = 0)

        // First command resets NLEN, then chunks of at most 16 bytes, final command writes NLEN,
        // then two READ BINARY commands to read the response message.
        val expectedNumChunks = (encoded.size + 15) / 16
        assertEquals(2 + expectedNumChunks + 2, tag.sentCommands.size)
        assertContentEquals(byteArrayOf(0, 0), tag.sentCommands[0].payload.toByteArray())
        assertEquals(0, tag.sentCommands[0].p1)
        assertEquals(0, tag.sentCommands[0].p2)
        for (n in 0 until expectedNumChunks) {
            val command = tag.sentCommands[1 + n]
            assertEquals(Nfc.INS_UPDATE_BINARY, command.ins)
            val expectedOffset = 2 + n * 16
            assertEquals(expectedOffset / 0x100, command.p1)
            assertEquals(expectedOffset.and(0xff), command.p2)
            assertTrue(command.payload.size in 1..16)
        }
        val finalCommand = tag.sentCommands[expectedNumChunks + 1]
        assertEquals(Nfc.INS_UPDATE_BINARY, finalCommand.ins)
        assertEquals(0, finalCommand.p1)
        assertEquals(0, finalCommand.p2)
        assertEquals(2, finalCommand.payload.size)
        assertEquals(Nfc.INS_READ_BINARY, tag.sentCommands[tag.sentCommands.size - 2].ins)
        assertEquals(Nfc.INS_READ_BINARY, tag.sentCommands[tag.sentCommands.size - 1].ins)

        // The round-trip through the emulated NDEF file yields the original message.
        assertEquals(ndefMessage, received)
    }

    @Test
    fun updateBinaryPayloadLengthCappedByMaxTransceiveLength() = runTest {
        val tag = FakeLimitedNfcIsoTag(maxTransceiveLength = 40, limit = 255)
        val ndefMessage = createNdefMessage(payloadSize = 96)
        val encoded = ndefMessage.encode()

        tag.ndefTransact(ndefMessage, wtInt = 0, nWait = 0)

        // Effective limit is maxTransceiveLength minus the five-byte APDU header.
        val maxPayload = 40 - 5
        for (command in tag.sentCommands) {
            assertTrue(command.payload.size <= maxPayload)
        }
        assertTrue(encoded.size > maxPayload - 2)
        assertEquals(encoded.size, tag.file.getUInt16(0).toInt())
    }

    @Test
    fun rejectMaxNdefUpdateBinaryPayloadLengthBelowTwo() = runTest {
        val tag = FakeLimitedNfcIsoTag(maxTransceiveLength = 255, limit = 1)

        val error = assertFailsWith<IllegalArgumentException> {
            tag.ndefTransact(createNdefMessage(payloadSize = 4), wtInt = 0, nWait = 0)
        }
        assertEquals(
            "maxNdefUpdateBinaryPayloadLength must be in 2..255, not 1",
            error.message
        )
        assertEquals(0, tag.sentCommands.size)
    }

    @Test
    fun rejectMaxNdefUpdateBinaryPayloadLengthAbove255() = runTest {
        val tag = FakeLimitedNfcIsoTag(maxTransceiveLength = 65535, limit = 256)

        val error = assertFailsWith<IllegalArgumentException> {
            tag.ndefTransact(createNdefMessage(payloadSize = 4), wtInt = 0, nWait = 0)
        }
        assertEquals(
            "maxNdefUpdateBinaryPayloadLength must be in 2..255, not 256",
            error.message
        )
        assertEquals(0, tag.sentCommands.size)
    }

    @Test
    fun defaultLimitChunksLargeMessage() = runTest {
        val tag = FakeNfcIsoTag(maxTransceiveLength = 65535)
        assertEquals(128, tag.maxNdefUpdateBinaryPayloadLength)
        val ndefMessage = createNdefMessage(payloadSize = 160)
        val encoded = ndefMessage.encode()
        assertTrue(encoded.size > 128 - 2)

        tag.ndefTransact(ndefMessage, wtInt = 0, nWait = 0)

        // NLEN write, chunks of at most the default 128 bytes, final NLEN write, then reads.
        val expectedNumChunks = (encoded.size + 127) / 128
        assertEquals(2 + expectedNumChunks + 2, tag.sentCommands.size)
        assertEquals(128, tag.sentCommands[1].payload.size)
        assertEquals(encoded.size - 128, tag.sentCommands[2].payload.size)
        assertEquals(2, tag.sentCommands[expectedNumChunks + 1].payload.size)
    }

    @Test
    fun failedIntermediateUpdateBinaryAbortsSequence() = runTest {
        val tag = FakeLimitedNfcIsoTag(maxTransceiveLength = 255, limit = 16)
        tag.failCommandAtIndex = 2 // The second chunk write.

        val error = assertFailsWith<NfcCommandFailedException> {
            tag.ndefTransact(createNdefMessage(payloadSize = 64), wtInt = 0, nWait = 0)
        }

        assertEquals(Nfc.RESPONSE_STATUS_ERROR_NO_PRECISE_DIAGNOSIS, error.status)
        // No commands are sent after the failed one.
        assertEquals(3, tag.sentCommands.size)
        assertEquals(0, tag.file.getUInt16(0).toInt())
    }
}
