package org.multipaz.nfc

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.io.IOException
import kotlinx.io.bytestring.ByteString
import org.multipaz.storage.ephemeral.EphemeralStorage
import org.multipaz.util.SynchronizedLock
import org.multipaz.util.fromHex
import org.multipaz.util.withLock
import kotlin.concurrent.Volatile
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * Common multiplatform unit tests for [NfcTagReaderUsb], [CcidDriver], [CcidUsbTransport],
 * and [ExternalNfcReaderStore] USB CCID reader discovery.
 */
class NfcTagReaderUsbTest {

    private class FakeCcidDriver(
        val id: Int,
        private val eventsLock: SynchronizedLock = SynchronizedLock(),
        val events: MutableList<String> = mutableListOf(),
        initialStatus: CardStatus = CardStatus.PRESENT_ACTIVE,
    ) : CcidDriverOps {
        override val maxCommandLength: Int = 65535

        @Volatile
        var connected: Boolean = false
            private set

        @Volatile
        var status: CardStatus = initialStatus

        @Volatile
        var currentListener: CcidDriverListener? = null
            private set

        override fun connect() {
            connected = true
            eventsLock.withLock { events.add("connect#$id") }
        }

        override fun disconnect() {
            if (connected) {
                connected = false
                eventsLock.withLock { events.add("disconnect#$id") }
            }
        }

        override fun setListener(listener: CcidDriverListener?) {
            currentListener = listener
        }

        override fun getCardStatus(): CardStatus {
            if (!connected) {
                throw IOException("Driver is not connected.")
            }
            return status
        }

        override suspend fun powerCycleCard(fieldOffDelay: Duration): Boolean {
            if (!connected) return false
            return status == CardStatus.PRESENT_ACTIVE || status == CardStatus.PRESENT_INACTIVE
        }

        override fun transceive(commandApdu: ByteArray): ByteArray {
            if (!connected) {
                throw IOException("Driver is not connected.")
            }
            return byteArrayOf(0x90.toByte(), 0x00)
        }

        fun simulateCardInserted() {
            status = CardStatus.PRESENT_ACTIVE
            currentListener?.onCardInserted()
        }

        fun simulateCardRemoved() {
            status = CardStatus.ABSENT
            currentListener?.onCardRemoved()
        }
    }

    @Test
    fun usbWaitUntilRemovedBeforeAnyScanReturnsImmediately() = runTest {
        val reader = NfcTagReaderUsb(
            deviceIdentity = null,
            interfaceIndex = 0,
            createDriver = { FakeCcidDriver(1) }
        )
        reader.waitUntilRemoved()
    }

    @Test
    fun usbWaitUntilRemovedSuspendsPastDisconnectDelayUntilCardRemoved() = runTest {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val driver = FakeCcidDriver(1, initialStatus = CardStatus.PRESENT_ACTIVE)
            val reader = NfcTagReaderUsb(
                deviceIdentity = null,
                interfaceIndex = 0,
                disconnectDelay = 50.milliseconds,
                pollInterval = 10.milliseconds,
                backgroundScope = scope,
                createDriver = { driver }
            )

            val scanResult = reader.scan(
                message = null,
                tagInteractionFunc = { tag ->
                    tag.close()
                    "ok"
                }
            )
            assertEquals("ok", scanResult)
            assertTrue(driver.connected)

            val waitDeferred = scope.async {
                reader.waitUntilRemoved()
            }

            withContextRealTime {
                delay(160.milliseconds)
                assertFalse(waitDeferred.isCompleted)
                assertTrue(driver.connected)

                driver.status = CardStatus.ABSENT
                waitDeferred.await()
                assertFalse(driver.connected)
            }
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun usbWaitUntilRemovedCompletesOnInterruptCardRemoved() = runTest {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val driver = FakeCcidDriver(1, initialStatus = CardStatus.PRESENT_ACTIVE)
            val reader = NfcTagReaderUsb(
                deviceIdentity = null,
                interfaceIndex = 0,
                disconnectDelay = 5.seconds,
                pollInterval = 20.milliseconds,
                backgroundScope = scope,
                createDriver = { driver }
            )

            reader.scan(
                message = null,
                tagInteractionFunc = { tag ->
                    tag.close()
                    "done"
                }
            )

            val waitDeferred = scope.async {
                reader.waitUntilRemoved()
            }
            withContextRealTime {
                delay(40.milliseconds)
                assertFalse(waitDeferred.isCompleted)

                driver.simulateCardRemoved()
                waitDeferred.await()
                assertFalse(driver.connected)
            }
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun usbPostScanCardRemovalWithoutWaitUntilRemovedDisconnectsImmediately() = runTest {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val driver = FakeCcidDriver(1, initialStatus = CardStatus.PRESENT_ACTIVE)
            val reader = NfcTagReaderUsb(
                deviceIdentity = null,
                interfaceIndex = 0,
                disconnectDelay = 10.seconds,
                pollInterval = 10.milliseconds,
                backgroundScope = scope,
                createDriver = { driver }
            )

            reader.scan(
                message = null,
                tagInteractionFunc = { tag ->
                    tag.close()
                    "done"
                }
            )
            assertTrue(driver.connected)

            driver.simulateCardRemoved()
            assertFalse(driver.connected)

            reader.waitUntilRemoved()
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun usbPostScanCardBounceDoesNotReinvokeTagInteractionFunc() = runTest {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val driver = FakeCcidDriver(1, initialStatus = CardStatus.PRESENT_ACTIVE)
            var invocations = 0
            val reader = NfcTagReaderUsb(
                deviceIdentity = null,
                interfaceIndex = 0,
                disconnectDelay = 200.milliseconds,
                pollInterval = 10.milliseconds,
                backgroundScope = scope,
                createDriver = { driver }
            )

            reader.scan(
                message = null,
                tagInteractionFunc = { tag ->
                    invocations++
                    tag.close()
                    "done"
                }
            )

            val waitDeferred = scope.async {
                reader.waitUntilRemoved()
            }
            withContextRealTime {
                delay(25.milliseconds)
                driver.simulateCardInserted()
                delay(25.milliseconds)
                assertEquals(1, invocations)

                driver.simulateCardRemoved()
                waitDeferred.await()
                assertEquals(1, invocations)
                assertFalse(driver.connected)
            }
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun usbOpenTagStaysConnectedUntilClosedThenWaitsForRemoval() = runTest {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val driver = FakeCcidDriver(1, initialStatus = CardStatus.PRESENT_ACTIVE)
            val reader = NfcTagReaderUsb(
                deviceIdentity = null,
                interfaceIndex = 0,
                disconnectDelay = 60.milliseconds,
                openTagDisconnectDelay = 5.seconds,
                pollInterval = 10.milliseconds,
                backgroundScope = scope,
                createDriver = { driver }
            )

            val deferredTag = reader.scan(
                message = null,
                tagInteractionFunc = { tag -> tag }
            )
            assertTrue(driver.connected)

            deferredTag.close()
            assertTrue(driver.connected)

            val waitDeferred = scope.async {
                reader.waitUntilRemoved()
            }
            withContextRealTime {
                delay(120.milliseconds)
                assertFalse(waitDeferred.isCompleted)
                assertTrue(driver.connected)

                driver.simulateCardRemoved()
                waitDeferred.await()
                assertFalse(driver.connected)
            }
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun usbScanExceptionStillAllowsWaitUntilRemoved() = runTest {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val driver = FakeCcidDriver(1, initialStatus = CardStatus.PRESENT_ACTIVE)
            val reader = NfcTagReaderUsb(
                deviceIdentity = null,
                interfaceIndex = 0,
                disconnectDelay = 60.milliseconds,
                pollInterval = 10.milliseconds,
                backgroundScope = scope,
                createDriver = { driver }
            )

            assertFailsWith<IllegalStateException> {
                reader.scan<String>(
                    message = null,
                    tagInteractionFunc = {
                        throw IllegalStateException("Unsupported tag")
                    }
                )
            }
            assertTrue(driver.connected)

            val waitDeferred = scope.async {
                reader.waitUntilRemoved()
            }
            withContextRealTime {
                delay(120.milliseconds)
                assertFalse(waitDeferred.isCompleted)

                driver.simulateCardRemoved()
                waitDeferred.await()
                assertFalse(driver.connected)
            }
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun usbBackToBackScansOrderingAndNoInterference() = runTest {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val eventsLock = SynchronizedLock()
            val events = mutableListOf<String>()
            var nextId = 1
            val drivers = mutableListOf<FakeCcidDriver>()
            val reader = NfcTagReaderUsb(
                deviceIdentity = null,
                interfaceIndex = 0,
                disconnectDelay = 50.milliseconds,
                pollInterval = 10.milliseconds,
                backgroundScope = scope,
                createDriver = {
                    eventsLock.withLock {
                        FakeCcidDriver(nextId++, eventsLock, events).also { drivers.add(it) }
                    }
                }
            )

            reader.scan(
                message = null,
                tagInteractionFunc = { tag ->
                    tag.close()
                    "scan1"
                }
            )

            withContextRealTime {
                delay(15.milliseconds)
                reader.scan(
                    message = null,
                    tagInteractionFunc = { tag ->
                        delay(80.milliseconds)
                        assertTrue(drivers[1].connected)
                        tag.close()
                        "scan2"
                    }
                )

                val waitJob = scope.launch {
                    reader.waitUntilRemoved()
                }
                delay(25.milliseconds)
                drivers[1].simulateCardRemoved()
                waitJob.join()
            }

            assertEquals(
                listOf("connect#1", "disconnect#1", "connect#2", "disconnect#2"),
                eventsLock.withLock { events.toList() }
            )
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun usbCancelledScanDisconnectsImmediately() = runTest {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val driver = FakeCcidDriver(1, initialStatus = CardStatus.ABSENT)
            val reader = NfcTagReaderUsb(
                deviceIdentity = null,
                interfaceIndex = 0,
                disconnectDelay = 5.seconds,
                pollInterval = 10.milliseconds,
                backgroundScope = scope,
                createDriver = { driver }
            )

            val scanJob = scope.async {
                reader.scan(
                    message = null,
                    tagInteractionFunc = { "never" }
                )
            }
            withContextRealTime {
                delay(30.milliseconds)
                assertTrue(driver.connected)

                scanJob.cancel()
                assertFailsWith<CancellationException> {
                    scanJob.await()
                }
                assertFalse(driver.connected)

                reader.waitUntilRemoved()
            }
        } finally {
            scope.cancel()
        }
    }

    /**
     * Verifies [CcidDriver] with a fake [CcidUsbTransport] simulating an ACS WalletMate II reader:
     * 1. Parses the CCID descriptor (`0x21`) to detect extended APDU chaining (`dwFeatures = 0x000404BA`).
     * 2. Sends the `E0 00 00 3B` ECP payload escape command and `E0 00 01 20 02 83 00` PICC polling command
     *    when `pollingFrameData` starts with `0x6A`.
     * 3. Sends `PC_to_RDR_IccPowerOn` followed by `PC_to_RDR_XfrBlock` on [CcidDriver.transceive].
     * 4. Restores the default ECP payload and disables PICC polling on [CcidDriver.disconnect].
     */
    @Test
    fun ccidDriverConfiguresWalletMateEcpAndTransceives() = runTest {
        val sentCommands = mutableListOf<ByteArray>()
        // Interface 0 descriptor (0x04) + 54-byte CCID descriptor (0x21) with dwFeatures = 0x000404BA (extended APDU)
        val rawDesc = buildWalletMateDescriptors(dwMechanical = 0, dwFeatures = 0x000404BA, maxMsgLen = 271)
        val transport = object : CcidUsbTransport {
            override val deviceName: String = "FakeWalletMateII"
            override val vendorId: Int = 0x072F // ACS
            override val productId: Int = 0x2266
            override val interfaceId: Int = 0
            override val rawDescriptors: ByteArray = rawDesc
            override val hasInterruptEndpoint: Boolean = false
            override val interruptMaxPacketSize: Int = 0
            var isOpen = false

            override fun open() {
                isOpen = true
            }

            override fun close() {
                isOpen = false
            }

            override fun bulkTransferOut(data: ByteArray, timeout: Duration): Int {
                sentCommands.add(data.copyOf())
                return data.size
            }

            override fun bulkTransferIn(buffer: ByteArray, timeout: Duration): Int {
                val lastCmd = sentCommands.last()
                val msgType = lastCmd[0]
                val seq = lastCmd[6]
                val respPayload: ByteArray
                val respType: Byte
                when (msgType) {
                    0x6B.toByte() -> { // PC_to_RDR_Escape
                        respType = 0x83.toByte() // RDR_to_PC_Escape
                        val esc = lastCmd.copyOfRange(10, lastCmd.size)
                        respPayload = if (esc.size >= 4 && esc[3] == 0x3B.toByte()) {
                            byteArrayOf(0x90.toByte(), 0x00)
                        } else if (esc.size >= 6 && esc[3] == 0x20.toByte()) {
                            byteArrayOf(0xE1.toByte(), 0x00, 0x00, 0x00, 0x02, esc[5], 0x00)
                        } else {
                            byteArrayOf(0x90.toByte(), 0x00)
                        }
                    }
                    0x62.toByte() -> { // PC_to_RDR_IccPowerOn
                        respType = 0x80.toByte() // RDR_to_PC_DataBlock (ATR)
                        respPayload = "3b8f8001804f0ca000000306030001000000006a".fromHex()
                    }
                    0x6F.toByte() -> { // PC_to_RDR_XfrBlock
                        respType = 0x80.toByte()
                        respPayload = byteArrayOf(0x90.toByte(), 0x00)
                    }
                    else -> {
                        respType = 0x81.toByte()
                        respPayload = ByteArray(0)
                    }
                }
                val pkt = ByteArray(10 + respPayload.size)
                pkt[0] = respType
                CcidDriver.writeInt32Le(pkt, 1, respPayload.size)
                pkt[5] = 0
                pkt[6] = seq
                pkt[7] = 0
                respPayload.copyInto(pkt, destinationOffset = 10)
                pkt.copyInto(buffer)
                return pkt.size
            }

            override fun interruptTransferIn(buffer: ByteArray, timeout: Duration): Int = 0
        }

        val ecpFrame = "6a0281030000".fromHex()
        val driver = CcidDriver(
            transport = transport,
            pollingFrameData = ecpFrame
        )
        driver.connect()
        assertTrue(transport.isOpen)
        assertEquals(65535, driver.maxCommandLength)

        // 1st command: E0 00 00 3B 05 02 81 03 00 00
        assertEquals(2, sentCommands.size)
        assertContentEquals(
            "e000003b050281030000".fromHex(),
            sentCommands[0].copyOfRange(10, sentCommands[0].size)
        )
        // 2nd command: E0 00 01 20 02 83 00
        assertContentEquals(
            "e0000120028300".fromHex(),
            sentCommands[1].copyOfRange(10, sentCommands[1].size)
        )

        val apduResp = driver.transceive("00a4040007a000000248040000".fromHex())
        assertContentEquals(byteArrayOf(0x90.toByte(), 0x00), apduResp)

        driver.disconnect()
        assertFalse(transport.isOpen)
        // Disconnect sends default ECP payload reset + disable PICC polling
        assertEquals(6, sentCommands.size)
        assertContentEquals(
            "e000003b0401000002".fromHex(),
            sentCommands[4].copyOfRange(10, sentCommands[4].size)
        )
        assertContentEquals(
            "e0000120020000".fromHex(),
            sentCommands[5].copyOfRange(10, sentCommands[5].size)
        )
    }

    /**
     * Verifies USB descriptor parsing and [ExternalNfcReaderStore.handleUsbDeviceAttached]
     * filtering out SAM/contact interfaces while retaining the contactless NFC CCID interface.
     */
    @Test
    fun usbDescriptorParsingAndStoreFiltering() = runTest {
        val rawDesc = buildWalletMateDescriptors(dwMechanical = 0, dwFeatures = 0x000404BA, maxMsgLen = 271)
        val endpoints = CcidUsbTransport.findCcidInterfaceEndpoints(rawDesc, requestedInterfaceIndex = 0)
        assertNotNull(endpoints)
        assertEquals(0, endpoints.interfaceId)
        assertEquals(0x82.toByte(), endpoints.bulkInAddress)
        assertEquals(0x02.toByte(), endpoints.bulkOutAddress)
        assertEquals(0x81.toByte(), endpoints.interruptInAddress)
        assertEquals(8, endpoints.interruptMaxPacketSize)

        val storage = EphemeralStorage()
        val store = ExternalNfcReaderStore.create(storage)
        val deviceInfo = UsbDeviceInfo(
            vendorId = 0x072F,
            productId = 0x2266,
            manufacturerName = "ACS",
            productName = "WalletMate II",
            interfaces = listOf(
                UsbInterfaceInfo(id = 0, interfaceClass = CcidUsbTransport.USB_CLASS_CSCID, name = "WalletMate II PICC"),
                UsbInterfaceInfo(id = 1, interfaceClass = CcidUsbTransport.USB_CLASS_CSCID, name = "WalletMate II SAM")
            ),
            rawDescriptors = rawDesc
        )
        val added = store.handleUsbDeviceAttached(deviceInfo)
        assertEquals("ACS WalletMate II", added.displayName)
        assertEquals(0, added.interfaceIndex)
        assertEquals(1, store.readers.value.size)
        assertEquals(ByteString("6a0281030000".fromHex()).size, 6)
    }

    private suspend fun <T> withContextRealTime(block: suspend CoroutineScope.() -> T): T {
        return withContext(Dispatchers.Default, block)
    }

    @Test
    fun ccidDriverReassemblesChainedDataBlockResponsesAndValidatesSequence() = runTest {
        val sentCommands = mutableListOf<ByteArray>()
        val rawDesc = buildWalletMateDescriptors(dwMechanical = 0, dwFeatures = 0x000404BA, maxMsgLen = 271)
        val chunk1 = ByteArray(100) { 0x11 }
        val chunk2 = ByteArray(100) { 0x22 }
        val chunk3 = ByteArray(50) { 0x33 } + byteArrayOf(0x90.toByte(), 0x00)
        var xfrStep = 0
        var corruptSeq = false

        val transport = object : CcidUsbTransport {
            override val deviceName: String = "FakeChainedReader"
            override val vendorId: Int = 0x1234
            override val productId: Int = 0x5678
            override val interfaceId: Int = 0
            override val rawDescriptors: ByteArray = rawDesc
            override val hasInterruptEndpoint: Boolean = false
            override val interruptMaxPacketSize: Int = 0

            override fun open() {}
            override fun close() {}

            override fun bulkTransferOut(data: ByteArray, timeout: Duration): Int {
                sentCommands.add(data.copyOf())
                return data.size
            }

            override fun bulkTransferIn(buffer: ByteArray, timeout: Duration): Int {
                val cmd = sentCommands.last()
                val seq = if (corruptSeq) (cmd[6] + 1).toByte() else cmd[6]
                val payload: ByteArray
                val chainParam: Byte
                if (cmd[0] == 0x62.toByte()) {
                    payload = "3b8f8001804f0ca000000306030001000000006a".fromHex()
                    chainParam = 0x00
                } else {
                    when (xfrStep++) {
                        0 -> {
                            payload = chunk1
                            chainParam = 0x01 // First chained block
                        }
                        1 -> {
                            payload = chunk2
                            chainParam = 0x03 // Intermediate chained block
                        }
                        else -> {
                            payload = chunk3
                            chainParam = 0x02 // Final chained block
                        }
                    }
                }
                val pkt = ByteArray(10 + payload.size)
                pkt[0] = 0x80.toByte()
                CcidDriver.writeInt32Le(pkt, 1, payload.size)
                pkt[5] = 0
                pkt[6] = seq
                pkt[7] = 0
                pkt[9] = chainParam
                payload.copyInto(pkt, destinationOffset = 10)
                pkt.copyInto(buffer)
                return pkt.size
            }

            override fun interruptTransferIn(buffer: ByteArray, timeout: Duration): Int = 0
        }

        val driver = CcidDriver(transport = transport)
        driver.connect()
        try {
            val full = driver.transceive("00a4040007a000000248040000".fromHex())
            assertContentEquals(chunk1 + chunk2 + chunk3, full)

            corruptSeq = true
            assertFailsWith<IOException> {
                driver.transceive("00a4040007a000000248040000".fromHex())
            }
        } finally {
            driver.disconnect()
        }
    }

    @Test
    fun autoInterfaceSelectionSkipsSamAndContactSlotsAndCloseRejectsFurtherScans() = runTest {
        // Interface 0: SAM slot (iInterface = 1 -> "Reader SAM")
        val iface0 = byteArrayOf(
            9, 0x04, 0x00, 0x00, 0x02, 0x0B, 0x00, 0x00, 0x01
        )
        val ccid0 = ByteArray(54).also {
            it[0] = 54
            it[1] = 0x21
        }
        val ep0Out = byteArrayOf(7, 0x05, 0x01, 0x02, 0x40, 0x00, 0x00)
        val ep0In = byteArrayOf(7, 0x05, 0x81.toByte(), 0x02, 0x40, 0x00, 0x00)

        // Interface 1: Contactless NFC slot (iInterface = 2 -> "Reader PICC")
        val iface1 = byteArrayOf(
            9, 0x04, 0x01, 0x00, 0x02, 0x0B, 0x00, 0x00, 0x02
        )
        val ccid1 = ByteArray(54).also {
            it[0] = 54
            it[1] = 0x21
        }
        val ep1Out = byteArrayOf(7, 0x05, 0x02, 0x02, 0x40, 0x00, 0x00)
        val ep1In = byteArrayOf(7, 0x05, 0x82.toByte(), 0x02, 0x40, 0x00, 0x00)

        val multiDesc = iface0 + ccid0 + ep0Out + ep0In + iface1 + ccid1 + ep1Out + ep1In
        val resolved = CcidUsbTransport.findCcidInterfaceEndpoints(
            rawDescriptors = multiDesc,
            requestedInterfaceIndex = null,
            interfaceNameResolver = { _, iIface ->
                when (iIface) {
                    1 -> "ACR1252 SAM Slot"
                    2 -> "ACR1252 Contactless PICC"
                    else -> null
                }
            }
        )
        assertNotNull(resolved)
        assertEquals(1, resolved.interfaceId)
        assertEquals(0x82.toByte(), resolved.bulkInAddress)
        assertEquals(0x02.toByte(), resolved.bulkOutAddress)

        val fakeDriver = FakeCcidDriver(id = 1, initialStatus = CardStatus.ABSENT)
        val reader = NfcTagReaderUsb(
            interfaceIndex = null,
            createDriver = { fakeDriver }
        )
        reader.close()
        assertFailsWith<IllegalStateException> {
            reader.scan<String>(message = null, tagInteractionFunc = { "done" })
        }
    }

    private fun buildWalletMateDescriptors(
        dwMechanical: Int,
        dwFeatures: Int,
        maxMsgLen: Int,
    ): ByteArray {
        // 9-byte Interface descriptor (0x04) + 54-byte CCID descriptor (0x21) + 3 * 7-byte Endpoint descriptors (0x05)
        val ifaceDesc = byteArrayOf(
            9, 0x04,
            0x00, // bInterfaceNumber = 0
            0x00, // bAlternateSetting = 0
            0x03, // bNumEndpoints = 3
            0x0B, // bInterfaceClass = CCID
            0x00, 0x00, 0x00
        )
        val ccidDesc = ByteArray(54)
        ccidDesc[0] = 54
        ccidDesc[1] = 0x21
        CcidDriver.writeInt32Le(ccidDesc, 36, dwMechanical)
        CcidDriver.writeInt32Le(ccidDesc, 40, dwFeatures)
        CcidDriver.writeInt32Le(ccidDesc, 44, maxMsgLen)

        val epIntIn = byteArrayOf(7, 0x05, 0x81.toByte(), 0x03, 0x08, 0x00, 0x10)
        val epBulkOut = byteArrayOf(7, 0x05, 0x02, 0x02, 0x40, 0x00, 0x00)
        val epBulkIn = byteArrayOf(7, 0x05, 0x82.toByte(), 0x02, 0x40, 0x00, 0x00)
        return ifaceDesc + ccidDesc + epIntIn + epBulkOut + epBulkIn
    }
}
