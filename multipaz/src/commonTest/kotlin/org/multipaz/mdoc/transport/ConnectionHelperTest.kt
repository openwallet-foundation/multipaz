package org.multipaz.mdoc.transport

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import org.multipaz.crypto.Crypto
import org.multipaz.crypto.EcCurve
import org.multipaz.crypto.EcPublicKey
import org.multipaz.mdoc.connectionmethod.MdocConnectionMethod
import org.multipaz.mdoc.role.MdocRole
import org.multipaz.nfc.CommandApdu
import org.multipaz.nfc.Nfc
import org.multipaz.util.UUID
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.time.Duration

@OptIn(ExperimentalCoroutinesApi::class)
class ConnectionHelperTest {
    private lateinit var senderKey: EcPublicKey

    @BeforeTest
    fun setup() = runTest {
        senderKey = Crypto.createEcPrivateKey(EcCurve.P256).use { it.publicKey }
    }

    @Test
    fun waitsUntilSelectedTransportIsConnected() = runTest {
        val transport = TestTransport()
        val deferred = async { listOf(transport).waitForConnection(senderKey) }
        runCurrent()
        assertFalse(deferred.isCompleted)

        transport.state.value = MdocTransport.State.CONNECTING
        runCurrent()
        assertFalse(deferred.isCompleted)

        transport.finishOpen.complete(Unit)
        runCurrent()
        assertTrue(deferred.isCompleted)
        assertSame(transport, deferred.await())
        assertEquals(MdocTransport.State.CONNECTED, transport.state.value)
        assertEquals(0, transport.closeCalls)
    }

    @Test
    fun closesLosingTransportsWhenWinnerConnects() = runTest {
        val winner = TestTransport()
        val loser = TestTransport()
        val deferred = async { listOf(winner, loser).waitForConnection(senderKey) }
        runCurrent()

        winner.finishOpen.complete(Unit)
        runCurrent()

        assertTrue(deferred.isCompleted)
        assertSame(winner, deferred.await())
        assertEquals(MdocTransport.State.CONNECTED, winner.state.value)
        assertEquals(0, winner.closeCalls)
        assertEquals(MdocTransport.State.CLOSED, loser.state.value)
        assertTrue(loser.openCancelled)
        assertTrue(loser.closeCalls > 0)
    }

    @Test
    fun transportFailingDuringConnectingDoesNotPreventOtherTransportFromConnecting() = runTest {
        val failing = TestTransport()
        val winner = TestTransport()
        val deferred = async { listOf(failing, winner).waitForConnection(senderKey) }
        runCurrent()

        failing.state.value = MdocTransport.State.CONNECTING
        runCurrent()
        assertFalse(deferred.isCompleted)
        assertEquals(0, winner.closeCalls)

        failing.failOpen()
        runCurrent()
        assertFalse(deferred.isCompleted)

        winner.finishOpen.complete(Unit)
        runCurrent()
        assertTrue(deferred.isCompleted)
        assertSame(winner, deferred.await())
        assertEquals(MdocTransport.State.CONNECTED, winner.state.value)
        assertEquals(0, winner.closeCalls)
        assertTrue(failing.closeCalls > 0)
    }

    @Test
    fun cancellationClosesAllTransports() = runTest {
        val first = TestTransport()
        val second = TestTransport()
        val deferred = async { listOf(first, second).waitForConnection(senderKey) }
        runCurrent()

        deferred.cancel()
        runCurrent()

        assertTrue(deferred.isCancelled)
        for (transport in listOf(first, second)) {
            assertTrue(transport.openCancelled)
            assertEquals(1, transport.closeCalls)
            assertEquals(MdocTransport.State.CLOSED, transport.state.value)
        }
    }

    @Test
    fun allFailedTransportsCloseAndThrow() = runTest {
        val first = TestTransport()
        val second = TestTransport()
        val result = CompletableDeferred<MdocTransport>()
        val job = launch {
            try {
                result.complete(listOf(first, second).waitForConnection(senderKey))
            } catch (e: Exception) {
                result.completeExceptionally(e)
            }
        }
        runCurrent()

        first.failOpen()
        second.failOpen()
        runCurrent()
        job.join()

        assertFailsWith<IllegalStateException> { result.await() }
        assertTrue(first.closeCalls > 0)
        assertTrue(second.closeCalls > 0)
    }

    @Test
    fun nfcOpenReturningIdleWaitsForSelectApdu() = runTest {
        val nfc = NfcTransportMdoc(
            role = MdocRole.MDOC,
            options = MdocTransportOptions(),
            connectionMethod = MdocConnectionMethod.Nfc(0xffff, 0x10000)
        )
        val other = TestTransport()
        try {
            val deferred = async { listOf(nfc, other).waitForConnection(senderKey) }
            runCurrent()
            assertFalse(deferred.isCompleted)
            assertEquals(MdocTransport.State.IDLE, nfc.state.value)

            val response = nfc.processApdu(
                CommandApdu(
                    0,
                    Nfc.INS_SELECT,
                    Nfc.INS_SELECT_P1_APPLICATION,
                    0,
                    Nfc.ISO_MDOC_NFC_DATA_TRANSFER_APPLICATION_ID,
                    0
                )
            )
            assertEquals(Nfc.RESPONSE_STATUS_SUCCESS, response.status)
            runCurrent()

            assertTrue(deferred.isCompleted)
            assertSame(nfc, deferred.await())
            assertEquals(MdocTransport.State.CONNECTED, nfc.state.value)
            assertTrue(other.openCancelled)
            assertTrue(other.closeCalls > 0)
        } finally {
            nfc.close()
        }
    }

    @Test
    fun invalidInitialStateThrowsWithoutOpening() = runTest {
        val first = TestTransport()
        val invalid = TestTransport().apply { state.value = MdocTransport.State.CONNECTED }
        assertFailsWith<IllegalStateException> {
            listOf(first, invalid).waitForConnection(senderKey)
        }
        assertFalse(first.openStarted)
        assertFalse(invalid.openStarted)
    }

    @Test
    fun duplicateTransportsThrowWithoutOpening() = runTest {
        val first = TestTransport()
        val second = TestTransport()
        assertFailsWith<IllegalArgumentException> {
            listOf(first, second, first).waitForConnection(senderKey)
        }
        assertFalse(first.openStarted)
        assertFalse(second.openStarted)
    }

    @Test
    fun advertiseFailureClosesPreviouslyCreatedTransports() = runTest {
        val first = TestTransport()
        val second = TestTransport().apply {
            advertiseError = IllegalStateException("BLE advertising failed")
        }
        val transports = ArrayDeque(listOf(first, second))
        val factory = object : MdocTransportFactory {
            override fun createTransport(
                connectionMethod: MdocConnectionMethod,
                role: MdocRole,
                options: MdocTransportOptions,
            ): MdocTransport = transports.removeFirst()
        }
        val methods = listOf(
            MdocConnectionMethod.Nfc(0xffff, 0x10000),
            MdocConnectionMethod.Nfc(0xffff, 0x10000),
        )

        assertFailsWith<IllegalStateException> {
            methods.advertise(
                role = MdocRole.MDOC,
                transportFactory = factory,
                options = MdocTransportOptions(),
            )
        }

        assertEquals(1, first.closeCalls)
        assertEquals(MdocTransport.State.CLOSED, first.state.value)
        assertEquals(1, second.closeCalls)
    }

    @Test
    fun advertiseCancellationClosesCreatedTransports() = runTest {
        val first = TestTransport()
        val secondWait = CompletableDeferred<Unit>()
        val second = TestTransport().apply {
            finishAdvertise = secondWait
        }
        val transports = ArrayDeque(listOf(first, second))
        val factory = object : MdocTransportFactory {
            override fun createTransport(
                connectionMethod: MdocConnectionMethod,
                role: MdocRole,
                options: MdocTransportOptions,
            ): MdocTransport = transports.removeFirst()
        }
        val methods = listOf(
            MdocConnectionMethod.Nfc(0xffff, 0x10000),
            MdocConnectionMethod.Nfc(0xffff, 0x10000),
        )

        val deferred = async {
            methods.advertise(
                role = MdocRole.MDOC,
                transportFactory = factory,
                options = MdocTransportOptions(),
            )
        }
        runCurrent()
        assertEquals(MdocTransport.State.ADVERTISING, first.state.value)
        assertFalse(deferred.isCompleted)

        deferred.cancel()
        runCurrent()

        assertTrue(deferred.isCancelled)
        assertEquals(1, first.closeCalls)
        assertEquals(MdocTransport.State.CLOSED, first.state.value)
        assertEquals(1, second.closeCalls)
        assertEquals(MdocTransport.State.CLOSED, second.state.value)
    }

    @Test
    fun createMdocTransportsCoalescesCoHostedBlePeripheralModes() = runTest {
        val sharedUuid = UUID.fromString("b3d52ac4-a1b6-4b51-a22e-78ee55ef6eb6")
        val otherUuid = UUID.fromString("11111111-2222-3333-4444-555555555555")

        val factory = object : MdocTransportFactory {
            override fun createTransport(
                connectionMethod: MdocConnectionMethod,
                role: MdocRole,
                options: MdocTransportOptions,
            ): MdocTransport = createTransports(listOf(connectionMethod), role, options).single()

            override fun createTransports(
                connectionMethods: List<MdocConnectionMethod>,
                role: MdocRole,
                options: MdocTransportOptions,
            ): List<MdocTransport> = createMdocTransports(
                connectionMethods = connectionMethods,
                role = role,
                options = options,
                centralHalFactory = { NoOpBleCentralHal() },
                peripheralHalFactory = { NoOpBlePeripheralHal(allocatedPsm = 129) },
                nonBleTransportFactory = { TestTransport() },
            )
        }

        // 0. Empty list returns emptyList().
        assertEquals(
            emptyList(),
            emptyList<MdocConnectionMethod>().advertise(
                role = MdocRole.MDOC,
                transportFactory = factory,
                options = MdocTransportOptions(),
            )
        )

        // 1. MDOC role coalesces DirectL2capPeripheral + GattPeripheral sharing the same non-null UUID
        //    onto a single dual-mode BLE peripheral transport, leaving GattCentral and Non-BLE methods
        //    as separate transports in their original encounter order.
        val mdocDirect = MdocConnectionMethod.Ble.DirectL2capPeripheral(uuid = sharedUuid)
        val mdocGatt = MdocConnectionMethod.Ble.GattPeripheral(uuid = sharedUuid)
        val mdocCentral = MdocConnectionMethod.Ble.GattCentral(uuid = sharedUuid)
        val nfc = MdocConnectionMethod.Nfc(0xffff, 0x10000)
        val mdocTransports = listOf(mdocGatt, nfc, mdocCentral, mdocDirect).advertise(
            role = MdocRole.MDOC,
            transportFactory = factory,
            options = MdocTransportOptions(bleUseL2CAP = true),
        )
        assertEquals(3, mdocTransports.size)
        assertEquals(
            listOf<MdocConnectionMethod>(
                MdocConnectionMethod.Ble.DirectL2capPeripheral(uuid = sharedUuid, psm = 129),
                mdocGatt,
            ),
            mdocTransports[0].connectionMethods
        )
        assertEquals(listOf<MdocConnectionMethod>(nfc), mdocTransports[1].connectionMethods)
        assertEquals(listOf<MdocConnectionMethod>(mdocCentral), mdocTransports[2].connectionMethods)
        mdocTransports.forEach { it.close() }

        // 2. MDOC_READER role coalesces DirectL2capCentral + GattCentral sharing the same non-null UUID,
        //    leaving methods with a different UUID as separate transports.
        val readerDirect = MdocConnectionMethod.Ble.DirectL2capCentral(uuid = sharedUuid)
        val readerGattSameUuid = MdocConnectionMethod.Ble.GattCentral(uuid = sharedUuid)
        val readerGattOtherUuid = MdocConnectionMethod.Ble.GattCentral(uuid = otherUuid)
        val readerTransports = listOf(readerDirect, readerGattOtherUuid, readerGattSameUuid).advertise(
            role = MdocRole.MDOC_READER,
            transportFactory = factory,
            options = MdocTransportOptions(bleUseL2CAP = true),
        )
        assertEquals(2, readerTransports.size)
        assertEquals(
            listOf<MdocConnectionMethod>(
                MdocConnectionMethod.Ble.DirectL2capCentral(uuid = sharedUuid, psm = 129),
                readerGattSameUuid,
            ),
            readerTransports[0].connectionMethods
        )
        assertEquals(listOf<MdocConnectionMethod>(readerGattOtherUuid), readerTransports[1].connectionMethods)
        readerTransports.forEach { it.close() }
    }

    private class TestTransport : MdocTransport() {
        override val state = MutableStateFlow(State.IDLE)
        override val role = MdocRole.MDOC
        override val connectionMethod = MdocConnectionMethod.Nfc(0xffff, 0x10000)
        override val scanningTime: Duration? = null
        val finishOpen = CompletableDeferred<Unit>()
        var finishAdvertise: CompletableDeferred<Unit>? = null
        var advertiseError: Exception? = null
        var openStarted = false
        var openCancelled = false
        var closeCalls = 0

        fun failOpen() {
            state.value = State.FAILED
            finishOpen.completeExceptionally(IllegalStateException("Synthetic connection failure"))
        }

        override suspend fun advertise() {
            advertiseError?.let {
                state.value = State.FAILED
                throw it
            }
            finishAdvertise?.await()
            state.value = State.ADVERTISING
        }

        override suspend fun open(eSenderKey: EcPublicKey) {
            openStarted = true
            try {
                finishOpen.await()
                state.value = State.CONNECTED
            } catch (error: CancellationException) {
                openCancelled = true
                throw error
            }
        }

        override suspend fun close() {
            yield()
            closeCalls += 1
            if (state.value != State.FAILED) {
                state.value = State.CLOSED
            }
        }

        override suspend fun sendMessage(message: ByteArray) {
            throw UnsupportedOperationException()
        }

        override suspend fun waitForMessage(): ByteArray {
            throw UnsupportedOperationException()
        }
    }

    private class NoOpBleCentralHal : BleCentralHal {
        override val isConnected: Boolean = false
        override fun setCallback(callback: BleCentralHal.Callback) {}
        override fun waitForPowerOn() {}
        override fun startScan(serviceUuid: UUID) {}
        override fun stopScan() {}
        override fun onScanStoppedAfterDiscovery() {}
        override fun connectToPeripheral() {}
        override fun requestMtu(mtu: Int) {}
        override fun discoverServices(serviceUuid: UUID) {}
        override fun discoverCharacteristics(
            stateCharacteristicUuid: UUID,
            client2ServerCharacteristicUuid: UUID,
            server2ClientCharacteristicUuid: UUID,
            identCharacteristicUuid: UUID?,
            l2capCharacteristicUuid: UUID?,
        ) {}
        override fun readCharacteristic(uuid: UUID) {}
        override fun enableNotifications(characteristicUuid: UUID) {}
        override fun writeCharacteristic(uuid: UUID, value: ByteArray) {}
        override fun connectL2cap(psm: Int) {}
        override fun closeConnectedL2capAttempt() {}
        override fun close() {}
    }

    private class NoOpBlePeripheralHal(
        private val allocatedPsm: Int?,
    ) : BlePeripheralHal {
        private lateinit var callback: BlePeripheralHal.Callback

        override fun setCallback(callback: BlePeripheralHal.Callback) {
            this.callback = callback
        }

        override fun waitForPowerOn() {
            callback.onPowerOn(null)
        }

        override fun publishL2capChannel() {
            callback.onL2capPublished(allocatedPsm, null)
        }

        override fun closeL2capServer() {}

        override fun setupGattServerAndAddService(
            serviceUuid: UUID,
            stateCharacteristicUuid: UUID,
            client2ServerCharacteristicUuid: UUID,
            server2ClientCharacteristicUuid: UUID,
            identCharacteristicUuid: UUID?,
            l2capCharacteristicUuid: UUID?,
            encodedL2capPsm: ByteArray?,
        ) {
            callback.onServiceAdded(null)
        }

        override fun startAdvertising(serviceUuid: UUID) {
            callback.onAdvertisingStarted(null)
        }

        override fun stopAdvertising() {}
        override fun sendReadResponse(requestId: Int, statusSuccess: Boolean, value: ByteArray) {}
        override fun notifyCharacteristicChanged(uuid: UUID, value: ByteArray) {}
        override fun close() {}
    }
}
