package org.multipaz.bthci

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.io.IOException
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

class AndroidUsbHciPacketIoTest {

    @Test
    fun eventAssemblerReassemblesFragmentedAndExactMultipleEvents() {
        val assembler = UsbHciEventAssembler()

        // 1. Fragmented 35-byte LE Advertising Report across 16-byte Full-Speed Interrupt IN packets
        val eventBody = ByteArray(35) { idx ->
            when (idx) {
                0 -> 0x3E // HCI_EV_LE_META
                1 -> 33   // paramLen = 33
                else -> (idx + 0x10).toByte()
            }
        }
        assertEquals(emptyList(), assembler.onChunk(eventBody.copyOfRange(0, 16), isShortPacket = false))
        assertEquals(emptyList(), assembler.onChunk(eventBody.copyOfRange(16, 32), isShortPacket = false))
        val reassembled = assembler.onChunk(eventBody.copyOfRange(32, 35), isShortPacket = true)
        assertEquals(1, reassembled.size)
        assertContentEquals(byteArrayOf(0x04) + eventBody, reassembled.single())

        // 2. Exact 16-byte event without ZLP, followed by optional ZLP
        val exact16 = ByteArray(16) { idx ->
            when (idx) {
                0 -> 0x0E // Command Complete
                1 -> 14   // paramLen = 14 -> total 16 bytes
                else -> idx.toByte()
            }
        }
        val exactOut = assembler.onChunk(exact16, isShortPacket = false)
        assertEquals(1, exactOut.size)
        assertContentEquals(byteArrayOf(0x04) + exact16, exactOut.single())
        assertEquals(emptyList(), assembler.onChunk(byteArrayOf(), isShortPacket = true))

        // 3. Premature short packet discards truncated fragment and resyncs cleanly
        val truncated = byteArrayOf(0x0E, 0x10, 0x01, 0x02) // claims paramLen=16, but short packet of 4 bytes
        assertEquals(emptyList(), assembler.onChunk(truncated, isShortPacket = true))

        val resetComplete = byteArrayOf(0x0E, 0x04, 0x01, 0x03, 0x0C, 0x00)
        val afterResync = assembler.onChunk(resetComplete, isShortPacket = true)
        assertEquals(1, afterResync.size)
        assertContentEquals(byteArrayOf(0x04) + resetComplete, afterResync.single())
    }

    @Test
    fun aclAssemblerReassemblesFragmentedAndCoalescedFrames() {
        val assembler = UsbHciAclAssembler()

        // 150-byte ACL payload -> 154-byte USB ACL body split across 64-byte Bulk IN packets
        val payload = ByteArray(150) { (it and 0xFF).toByte() }
        val aclBody = ByteArray(4 + payload.size)
        aclBody[0] = 0x40
        aclBody[1] = 0x20
        aclBody[2] = (payload.size and 0xFF).toByte()
        aclBody[3] = ((payload.size ushr 8) and 0xFF).toByte()
        payload.copyInto(aclBody, destinationOffset = 4)

        assertEquals(emptyList(), assembler.onChunk(aclBody.copyOfRange(0, 64), isShortPacket = false))
        assertEquals(emptyList(), assembler.onChunk(aclBody.copyOfRange(64, 128), isShortPacket = false))
        val out = assembler.onChunk(aclBody.copyOfRange(128, aclBody.size), isShortPacket = true)
        assertEquals(1, out.size)
        assertContentEquals(byteArrayOf(0x02) + aclBody, out.single())

        // Coalesced frames in one chunk
        val smallA = byteArrayOf(0x01, 0x00, 0x02, 0x00, 0xAA.toByte(), 0xBB.toByte())
        val smallB = byteArrayOf(0x01, 0x00, 0x01, 0x00, 0xCC.toByte())
        val coalesced = assembler.onChunk(smallA + smallB, isShortPacket = true)
        assertEquals(2, coalesced.size)
        assertContentEquals(byteArrayOf(0x02) + smallA, coalesced[0])
        assertContentEquals(byteArrayOf(0x02) + smallB, coalesced[1])
    }

    @Test
    fun outboundPayloadExtractionValidatesFraming() {
        val cmd = byteArrayOf(0x01, 0x03, 0x0C, 0x00)
        assertContentEquals(byteArrayOf(0x03, 0x0C, 0x00), extractUsbHciCommandPayload(cmd))
        assertFailsWith<IOException> {
            extractUsbHciCommandPayload(byteArrayOf(0x01, 0x03, 0x0C, 0x02, 0xFF.toByte()))
        }

        val acl = byteArrayOf(0x02, 0x01, 0x00, 0x02, 0x00, 0x11, 0x22)
        assertContentEquals(byteArrayOf(0x01, 0x00, 0x02, 0x00, 0x11, 0x22), extractUsbHciAclPayload(acl))
        assertFailsWith<IOException> {
            extractUsbHciAclPayload(byteArrayOf(0x02, 0x01, 0x00, 0x05, 0x00, 0x11))
        }
    }

    private class FakeUsbHciConnection(
        override val interfaceId: Int = 0,
        override val interruptMaxPacketSize: Int = 16,
        override val bulkInMaxPacketSize: Int = 64,
        private val autoRespondToControllerInit: Boolean = false,
    ) : UsbHciConnection {
        val sentCommands = CopyOnWriteArrayList<ByteArray>()
        val sentAcl = CopyOnWriteArrayList<ByteArray>()
        var closedCount = 0
        var readerStopped = false

        var onEventChunk: ((ByteArray, Int, Boolean) -> Unit)? = null
        var onAclChunk: ((ByteArray, Int, Boolean) -> Unit)? = null
        var onReaderError: ((IOException) -> Unit)? = null

        override fun open(
            onEventChunk: (ByteArray, Int, Boolean) -> Unit,
            onAclChunk: (ByteArray, Int, Boolean) -> Unit,
            onReaderError: (IOException) -> Unit,
        ) {
            this.onEventChunk = onEventChunk
            this.onAclChunk = onAclChunk
            this.onReaderError = onReaderError
        }

        fun emitUsbEvent(rawEventWithoutH4: ByteArray) {
            var offset = 0
            while (offset < rawEventWithoutH4.size) {
                val len = minOf(interruptMaxPacketSize, rawEventWithoutH4.size - offset)
                val slice = rawEventWithoutH4.copyOfRange(offset, offset + len)
                offset += len
                onEventChunk?.invoke(slice, slice.size, slice.size < interruptMaxPacketSize)
            }
        }

        fun emitUsbAcl(rawAclWithoutH4: ByteArray) {
            var offset = 0
            while (offset < rawAclWithoutH4.size) {
                val len = minOf(bulkInMaxPacketSize, rawAclWithoutH4.size - offset)
                val slice = rawAclWithoutH4.copyOfRange(offset, offset + len)
                offset += len
                onAclChunk?.invoke(slice, slice.size, slice.size < bulkInMaxPacketSize)
            }
        }

        override fun sendCommand(payload: ByteArray, timeoutMs: Int) {
            sentCommands.add(payload.copyOf())
            if (autoRespondToControllerInit && !readerStopped && payload.size >= 2) {
                val opcode = (payload[0].toInt() and 0xFF) or ((payload[1].toInt() and 0xFF) shl 8)
                val returnParams = when (opcode) {
                    BleHciConstants.HCI_OP_LE_READ_BUFFER_SIZE ->
                        byteArrayOf(0x00, 0xFB.toByte(), 0x00, 0x08) // status=0, pktLen=251, numPkts=8
                    else ->
                        byteArrayOf(0x00) // status=0
                }
                val evt = ByteArray(5 + returnParams.size)
                evt[0] = 0x0E // Command Complete
                evt[1] = (3 + returnParams.size).toByte()
                evt[2] = 0x01 // numCmdPkts = 1
                evt[3] = payload[0]
                evt[4] = payload[1]
                returnParams.copyInto(evt, destinationOffset = 5)
                emitUsbEvent(evt)
            }
        }

        override fun sendAcl(payload: ByteArray, timeoutMs: Int) {
            sentAcl.add(payload.copyOf())
        }

        override fun stopReader() {
            readerStopped = true
        }

        override fun drainInterruptIn(buffer: ByteArray, timeoutMs: Int): Int {
            // Return Command Complete for HCI_Reset so fallback drain exits immediately
            val resetEvt = byteArrayOf(0x0E, 0x04, 0x01, 0x03, 0x0C, 0x00)
            resetEvt.copyInto(buffer, 0)
            return resetEvt.size
        }

        override fun close() {
            closedCount++
        }
    }

    @Test
    fun packetIoOpenReadWriteCloseAndFallbackReset() = runBlocking {
        val fakeConn = FakeUsbHciConnection()
        val io = AndroidUsbHciPacketIo(
            deviceKey = "/dev/bus/usb/001/042",
            connectionFactory = { fakeConn },
            transferTimeout = 1.seconds,
        )
        io.open()

        // Duplicate open of the same deviceKey while active must fail cleanly
        val duplicate = AndroidUsbHciPacketIo(
            deviceKey = "/dev/bus/usb/001/042",
            connectionFactory = { FakeUsbHciConnection() },
        )
        assertFailsWith<IOException> {
            duplicate.open()
        }

        // Outbound command + ACL
        io.writePacket(byteArrayOf(0x01, 0x01, 0x10, 0x00))
        io.writePacket(byteArrayOf(0x02, 0x40, 0x00, 0x02, 0x00, 0xDE.toByte(), 0xAD.toByte()))
        assertEquals(1, fakeConn.sentCommands.size)
        assertContentEquals(byteArrayOf(0x01, 0x10, 0x00), fakeConn.sentCommands[0])
        assertEquals(1, fakeConn.sentAcl.size)
        assertContentEquals(
            byteArrayOf(0x40, 0x00, 0x02, 0x00, 0xDE.toByte(), 0xAD.toByte()),
            fakeConn.sentAcl[0]
        )

        // Inbound event + ACL
        fakeConn.emitUsbEvent(byteArrayOf(0x0E, 0x04, 0x01, 0x01, 0x10, 0x00))
        fakeConn.emitUsbAcl(byteArrayOf(0x40, 0x20, 0x01, 0x00, 0x55))
        assertContentEquals(
            byteArrayOf(0x04, 0x0E, 0x04, 0x01, 0x01, 0x10, 0x00),
            io.readPacket()
        )
        assertContentEquals(
            byteArrayOf(0x02, 0x40, 0x20, 0x01, 0x00, 0x55),
            io.readPacket()
        )

        // Suspended readPacket() unblocks with null when close() is called, and fallback HCI_Reset is sent
        val readStarted = CountDownLatch(1)
        val pendingRead = async(Dispatchers.Default) {
            readStarted.countDown()
            io.readPacket()
        }
        assertTrue(readStarted.await(2, TimeUnit.SECONDS))
        delay(20.milliseconds)
        io.close()

        assertNull(withTimeout(2.seconds) { pendingRead.await() })
        assertEquals(2, fakeConn.sentCommands.size)
        assertContentEquals(byteArrayOf(0x03, 0x0C, 0x00), fakeConn.sentCommands[1])
        assertEquals(1, fakeConn.closedCount)
    }

    @Test
    fun bleHciEngineInitializesAndFlushesCleanResetWithoutDuplicateFallbackReset() = runBlocking {
        val fakeConn = FakeUsbHciConnection(autoRespondToControllerInit = true)
        val io = AndroidUsbHciPacketIo(
            deviceKey = "/dev/bus/usb/001/043",
            connectionFactory = { fakeConn },
        )
        io.open()
        val engine = BleHciEngine(packetIo = io)
        try {
            engine.initializeController()
            val cmdCountBeforeFlush = fakeConn.sentCommands.size
            engine.flushAndDisconnect()
            // flushAndDisconnect() issued HCI_Reset via BleHciEngine because requiresControllerResetOnClose == true
            assertEquals(cmdCountBeforeFlush + 1, fakeConn.sentCommands.size)
            assertContentEquals(byteArrayOf(0x03, 0x0C, 0x00), fakeConn.sentCommands.last())
        } finally {
            engine.close()
        }
        // close() must NOT send a second fallback HCI_Reset because markControllerResetCompleted() was called
        assertContentEquals(byteArrayOf(0x03, 0x0C, 0x00), fakeConn.sentCommands.last())
        assertEquals(1, fakeConn.closedCount)
    }

    @Test
    fun usbDisconnectPropagatesIoExceptionToReader() = runBlocking {
        val fakeConn = FakeUsbHciConnection()
        val io = AndroidUsbHciPacketIo(
            deviceKey = "/dev/bus/usb/001/044",
            connectionFactory = { fakeConn },
        )
        io.open()
        try {
            fakeConn.onReaderError?.invoke(IOException("USB device unplugged"))
            val err = assertFailsWith<IOException> {
                io.readPacket()
            }
            assertTrue(err.message!!.contains("unplugged"))
        } finally {
            io.close()
        }
    }
}
