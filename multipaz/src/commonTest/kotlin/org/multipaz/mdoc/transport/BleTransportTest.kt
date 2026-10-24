package org.multipaz.mdoc.transport

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.io.bytestring.ByteStringBuilder
import org.multipaz.crypto.Crypto
import org.multipaz.crypto.EcCurve
import org.multipaz.crypto.EcPublicKey
import org.multipaz.mdoc.connectionmethod.MdocConnectionMethodBle
import org.multipaz.mdoc.role.MdocRole
import org.multipaz.util.UUID
import org.multipaz.util.getUInt32
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration

/**
 * Unit tests for the four ISO 18013-5 BLE transport implementations:
 * - [BleTransportPeripheralMdoc] (mdoc in BLE peripheral server mode)
 * - [BleTransportCentralMdoc] (mdoc in BLE central client mode)
 * - [BleTransportCentralMdocReader] (mdoc reader in BLE peripheral server mode)
 * - [BleTransportPeripheralMdocReader] (mdoc reader in BLE central client mode)
 *
 * ### How the BLE stack is structured and tested here
 * The BLE transport layer has three parts:
 * 1. **High-level transport** (`BleTransport*`): manages [MdocTransport.State] transitions (`IDLE`,
 *    `ADVERTISING`, `SCANNING`, `CONNECTING`, `CONNECTED`, `FAILED`, `CLOSED`), coroutine cancellation,
 *    and characteristic UUID assignment according to the role and mode.
 * 2. **Shared BLE protocol manager** ([BlePeripheralManagerImpl] and [BleCentralManagerImpl]): implements
 *    [BlePeripheralManager] / [BleCentralManager] in `commonMain`, including the coroutine continuation
 *    state machine, `BLEIdent` HKDF-SHA-256 derivation and verification, GATT `0x01`/`0x00` message
 *    chunking and reassembly, and 4-byte length-prefixed L2CAP framing.
 * 3. **Platform BLE HAL** ([BlePeripheralHal] and [BleCentralHal]): thin interface over OS-specific
 *    Bluetooth APIs (`android.bluetooth.*` on Android, `CoreBluetooth` on iOS).
 *
 * In these tests, [FakeBlePeripheralManager] and [FakeBleCentralManager] delegate all [BlePeripheralManager]
 * and [BleCentralManager] calls to the real [BlePeripheralManagerImpl] and [BleCentralManagerImpl], while
 * providing fake HAL implementations ([FakeBlePeripheralHal] and [FakeBleCentralHal]) underneath to simulate
 * OS Bluetooth callbacks in memory and record operations for assertions.
 *
 * (For two-sided end-to-end tests that wire a [BleCentralManagerImpl] and a [BlePeripheralManagerImpl]
 * directly to each other over a simulated radio link, see [BleManagerImplTest].)
 */
@OptIn(ExperimentalCoroutinesApi::class)
class BleTransportTest {
    private lateinit var senderKey: EcPublicKey
    private lateinit var senderIdent: ByteArray
    private val testUuid = UUID.fromString("b3d52ac4-a1b6-4b51-a22e-78ee55ef6eb6")

    @BeforeTest
    fun setup() = runTest {
        senderKey = Crypto.createEcPrivateKey(EcCurve.P256).use { it.publicKey }
        senderIdent = deriveBleIdent(senderKey)
    }

    /**
     * Verifies that cancelling a coroutine suspended inside [BleTransportPeripheralMdoc.advertise]
     * (while waiting for Bluetooth power-on / advertising start) closes the underlying
     * [BlePeripheralManager] and transitions the transport to [MdocTransport.State.CLOSED].
     */
    @Test
    fun peripheralMdocCancelAdvertiseClosesPeripheralManager() = runTest {
        val manager = FakeBlePeripheralManager(autoPowerOn = false)
        val transport = BleTransportPeripheralMdoc(
            role = MdocRole.MDOC,
            options = MdocTransportOptions(),
            peripheralManager = manager,
            uuid = testUuid,
        )

        val advertiseDeferred = async { transport.advertise() }
        runCurrent()
        assertFalse(advertiseDeferred.isCompleted)
        assertEquals(0, manager.closeCalls)

        advertiseDeferred.cancel()
        runCurrent()

        assertTrue(advertiseDeferred.isCancelled)
        assertTrue(manager.closeCalls > 0)
        assertEquals(MdocTransport.State.CLOSED, transport.state.value)
    }

    /**
     * Verifies that cancelling a coroutine suspended inside [BleTransportPeripheralMdoc.open]
     * (while in [MdocTransport.State.ADVERTISING] waiting for a GATT central client to connect)
     * closes the underlying [BlePeripheralManager] and transitions the transport to
     * [MdocTransport.State.CLOSED].
     */
    @Test
    fun peripheralMdocCancelOpenClosesPeripheralManager() = runTest {
        val manager = FakeBlePeripheralManager(autoConnectGattClient = false)
        val transport = BleTransportPeripheralMdoc(
            role = MdocRole.MDOC,
            options = MdocTransportOptions(),
            peripheralManager = manager,
            uuid = testUuid,
        )

        val openDeferred = async { transport.open(senderKey) }
        runCurrent()
        assertEquals(MdocTransport.State.ADVERTISING, transport.state.value)
        assertFalse(openDeferred.isCompleted)
        assertEquals(0, manager.closeCalls)

        openDeferred.cancel()
        runCurrent()

        assertTrue(openDeferred.isCancelled)
        assertTrue(manager.closeCalls > 0)
        assertEquals(MdocTransport.State.CLOSED, transport.state.value)
    }

    /**
     * Verifies that calling [BleTransportPeripheralMdoc.close] from another coroutine while
     * [BleTransportPeripheralMdoc.open] is suspended waiting for a connection cancels and joins
     * the in-flight `open()` call, closes the [BlePeripheralManager], and transitions the
     * transport to [MdocTransport.State.CLOSED].
     */
    @Test
    fun peripheralMdocCloseCancelsAndAwaitsInFlightOpen() = runTest {
        val manager = FakeBlePeripheralManager(autoConnectGattClient = false)
        val transport = BleTransportPeripheralMdoc(
            role = MdocRole.MDOC,
            options = MdocTransportOptions(),
            peripheralManager = manager,
            uuid = testUuid,
        )

        val openDeferred = async { transport.open(senderKey) }
        runCurrent()
        assertEquals(MdocTransport.State.ADVERTISING, transport.state.value)

        transport.close()
        runCurrent()

        assertTrue(openDeferred.isCancelled)
        assertTrue(manager.closeCalls > 0)
        assertEquals(MdocTransport.State.CLOSED, transport.state.value)
    }

    /**
     * Verifies that cancelling a coroutine suspended inside [BleTransportCentralMdoc.open]
     * (while in [MdocTransport.State.SCANNING] waiting to discover the reader's BLE peripheral)
     * closes the underlying [BleCentralManager] and transitions the transport to
     * [MdocTransport.State.CLOSED].
     */
    @Test
    fun centralMdocCancelOpenClosesCentralManager() = runTest {
        val manager = FakeBleCentralManager(autoDiscoverPeripheral = false)
        val transport = BleTransportCentralMdoc(
            role = MdocRole.MDOC,
            options = MdocTransportOptions(),
            centralManager = manager,
            uuid = testUuid,
            psm = null,
        )

        val openDeferred = async { transport.open(senderKey) }
        runCurrent()
        assertEquals(MdocTransport.State.SCANNING, transport.state.value)
        assertFalse(openDeferred.isCompleted)
        assertEquals(0, manager.closeCalls)

        openDeferred.cancel()
        runCurrent()

        assertTrue(openDeferred.isCancelled)
        assertTrue(manager.closeCalls > 0)
        assertEquals(MdocTransport.State.CLOSED, transport.state.value)
    }

    /**
     * Verifies that cancelling a coroutine suspended inside [BleTransportCentralMdocReader.open]
     * (when the reader is acting as the BLE peripheral server in Central Client Mode and waiting
     * for the mdoc to connect) closes the underlying [BlePeripheralManager] and transitions the
     * transport to [MdocTransport.State.CLOSED].
     */
    @Test
    fun centralMdocReaderCancelOpenClosesPeripheralManager() = runTest {
        val manager = FakeBlePeripheralManager(autoConnectGattClient = false)
        val transport = BleTransportCentralMdocReader(
            role = MdocRole.MDOC_READER,
            options = MdocTransportOptions(),
            peripheralManager = manager,
            uuid = testUuid,
        )

        val openDeferred = async { transport.open(senderKey) }
        runCurrent()
        assertEquals(MdocTransport.State.ADVERTISING, transport.state.value)
        assertFalse(openDeferred.isCompleted)
        assertEquals(0, manager.closeCalls)

        openDeferred.cancel()
        runCurrent()

        assertTrue(openDeferred.isCancelled)
        assertTrue(manager.closeCalls > 0)
        assertEquals(MdocTransport.State.CLOSED, transport.state.value)
    }

    /**
     * Verifies that cancelling a coroutine suspended inside [BleTransportPeripheralMdocReader.open]
     * (when the reader is acting as the BLE central client in Peripheral Server Mode and scanning
     * for the mdoc's peripheral) closes the underlying [BleCentralManager] and transitions the
     * transport to [MdocTransport.State.CLOSED].
     */
    @Test
    fun peripheralMdocReaderCancelOpenClosesCentralManager() = runTest {
        val manager = FakeBleCentralManager(autoDiscoverPeripheral = false)
        val transport = BleTransportPeripheralMdocReader(
            role = MdocRole.MDOC_READER,
            options = MdocTransportOptions(),
            centralManager = manager,
            uuid = testUuid,
            psm = null,
        )

        val openDeferred = async { transport.open(senderKey) }
        runCurrent()
        assertEquals(MdocTransport.State.SCANNING, transport.state.value)
        assertFalse(openDeferred.isCompleted)
        assertEquals(0, manager.closeCalls)

        openDeferred.cancel()
        runCurrent()

        assertTrue(openDeferred.isCancelled)
        assertTrue(manager.closeCalls > 0)
        assertEquals(MdocTransport.State.CLOSED, transport.state.value)
    }

    /**
     * Verifies that if the remote BLE central disconnects unexpectedly while
     * [BleTransportPeripheralMdoc.waitForMessage] is suspended waiting for a message,
     * `waitForMessage()` throws [MdocTransportException], the underlying [BlePeripheralManager] is
     * closed, and the transport transitions to [MdocTransport.State.FAILED].
     */
    @Test
    fun peripheralMdocFailsWaitForMessageWhenCentralDisconnects() = runTest {
        val manager = FakeBlePeripheralManager()
        val transport = BleTransportPeripheralMdoc(
            role = MdocRole.MDOC,
            options = MdocTransportOptions(),
            peripheralManager = manager,
            uuid = testUuid,
        )

        transport.open(senderKey)
        assertEquals(MdocTransport.State.CONNECTED, transport.state.value)

        supervisorScope {
            val waitDeferred = async { transport.waitForMessage() }
            runCurrent()
            assertFalse(waitDeferred.isCompleted)

            manager.simulateCentralDisconnected()

            assertFailsWith<MdocTransportException> {
                waitDeferred.await()
            }
        }
        assertTrue(manager.closeCalls > 0)
        assertEquals(MdocTransport.State.FAILED, transport.state.value)
    }

    /**
     * Verifies that if the remote BLE central disconnects unexpectedly while
     * [BleTransportCentralMdocReader.waitForMessage] is suspended waiting for a message,
     * `waitForMessage()` throws [MdocTransportException], the underlying [BlePeripheralManager] is
     * closed, and the transport transitions to [MdocTransport.State.FAILED].
     */
    @Test
    fun centralMdocReaderFailsWaitForMessageWhenCentralDisconnects() = runTest {
        val manager = FakeBlePeripheralManager()
        val transport = BleTransportCentralMdocReader(
            role = MdocRole.MDOC_READER,
            options = MdocTransportOptions(),
            peripheralManager = manager,
            uuid = testUuid,
        )

        transport.open(senderKey)
        assertEquals(MdocTransport.State.CONNECTED, transport.state.value)

        supervisorScope {
            val waitDeferred = async { transport.waitForMessage() }
            runCurrent()
            assertFalse(waitDeferred.isCompleted)

            manager.simulateCentralDisconnected()

            assertFailsWith<MdocTransportException> {
                waitDeferred.await()
            }
        }
        assertTrue(manager.closeCalls > 0)
        assertEquals(MdocTransport.State.FAILED, transport.state.value)
    }

    /**
     * Verifies the full GATT lifecycle of [BleTransportPeripheralMdoc] (mdoc in BLE Peripheral
     * Server Mode):
     * 1. [BleTransportPeripheralMdoc.connectionMethod] reports `supportsPeripheralServerMode = true`
     *    with `peripheralServerModeUuid = testUuid`, and no `Ident` characteristic is exposed
     *    (`identCharacteristicUuidValue == null`, as ISO 18013-5 only defines `Ident` in Central
     *    Client Mode).
     * 2. Calling [BleTransportPeripheralMdoc.advertise] followed by [BleTransportPeripheralMdoc.open]
     *    reuses the active advertisement rather than calling `waitForPowerOn()` / `advertiseService()`
     *    a second time.
     * 3. Incoming and outgoing messages are exchanged over GATT, and sending an empty message
     *    (`byteArrayOf()`) writes `0x02` ([BleTransportConstants.STATE_CHARACTERISTIC_END]) to the
     *    GATT `State` characteristic for transport-specific session termination.
     * 4. Calling [BleTransportPeripheralMdoc.close] is idempotent.
     */
    @Test
    fun peripheralMdocAdvertiseThenOpenSkipsSecondAdvertiseAndExchangesMessages() = runTest {
        val manager = FakeBlePeripheralManager()
        val transport = BleTransportPeripheralMdoc(
            role = MdocRole.MDOC,
            options = MdocTransportOptions(),
            peripheralManager = manager,
            uuid = testUuid,
        )

        assertNull(transport.scanningTime)
        val cm = transport.connectionMethod as MdocConnectionMethodBle
        assertTrue(cm.supportsPeripheralServerMode)
        assertFalse(cm.supportsCentralClientMode)
        assertEquals(testUuid, cm.peripheralServerModeUuid)
        assertNull(cm.centralClientModeUuid)
        assertNull(cm.peripheralServerModePsm)

        transport.advertise()
        assertEquals(MdocTransport.State.ADVERTISING, transport.state.value)
        assertEquals(1, manager.waitForPowerOnCalls)
        assertEquals(1, manager.advertiseServiceCalls)

        // Calling open() when already ADVERTISING must not call waitForPowerOn / advertiseService again.
        transport.open(senderKey)
        assertEquals(MdocTransport.State.CONNECTED, transport.state.value)
        assertEquals(1, manager.waitForPowerOnCalls)
        assertEquals(1, manager.advertiseServiceCalls)
        assertNull(manager.identCharacteristicUuidValue)

        // Exchange messages over GATT via BlePeripheralManagerImpl chunking/reassembly
        val incoming = byteArrayOf(1, 2, 3, 4)
        manager.simulateIncomingGattMessage(incoming)
        assertContentEquals(incoming, transport.waitForMessage())

        val outgoing = byteArrayOf(5, 6, 7, 8)
        transport.sendMessage(outgoing)
        assertEquals(1, manager.sentMessages.size)
        assertContentEquals(outgoing, manager.sentMessages[0])

        // Empty message sends transport-specific termination over GATT state characteristic
        transport.sendMessage(byteArrayOf())
        assertEquals(listOf(BleTransportConstants.STATE_CHARACTERISTIC_END), manager.stateCharacteristicWrites)

        transport.close()
        assertEquals(MdocTransport.State.CLOSED, transport.state.value)
        assertEquals(1, manager.closeCalls)

        // Calling close() again is a no-op
        transport.close()
        assertEquals(1, manager.closeCalls)
    }

    /**
     * Verifies [BleTransportCentralMdocReader] (reader acting as BLE peripheral server in BLE Central
     * Client Mode) when both `bleUseL2CAP` and `bleUseL2CAPInEngagement` are enabled:
     * 1. [BleTransportCentralMdocReader.advertise] starts the L2CAP server, exposes the Central Client
     *    Mode `L2CAP` (`0000000b-...`) and `Ident` (`00000008-...`) characteristic UUIDs, and populates
     *    `peripheralServerModePsm` on [BleTransportCentralMdocReader.connectionMethod] so it can be
     *    conveyed in `ReaderEngagement` (e.g. during NFC Negotiated Handover).
     * 2. [BleTransportCentralMdocReader.open] sets the sender key so reading the `Ident` characteristic
     *    returns the expected `BLEIdent` value (`deriveBleIdent(senderKey)`), and connects directly
     *    over the L2CAP channel.
     * 3. Attempting to send an empty transport-specific termination message (`byteArrayOf()`) over
     *    L2CAP is rejected with [MdocTransportException] wrapping [MdocTransportTerminationException],
     *    since ISO 18013-5 transport-specific termination (`State = 0x02`) only exists on GATT.
     */
    @Test
    fun centralMdocReaderAdvertiseThenOpenAndL2capInEngagement() = runTest {
        val manager = FakeBlePeripheralManager(
            publishedL2capPsm = 128,
            autoConnectGattClient = false,
            autoConnectL2capClient = true
        )
        val transport = BleTransportCentralMdocReader(
            role = MdocRole.MDOC_READER,
            options = MdocTransportOptions(bleUseL2CAP = true, bleUseL2CAPInEngagement = true),
            peripheralManager = manager,
            uuid = testUuid,
        )

        transport.advertise()
        assertEquals(MdocTransport.State.ADVERTISING, transport.state.value)
        assertTrue(manager.startL2capServerValue)
        assertEquals(
            UUID.fromString("0000000b-a123-48ce-896b-4c76973373e6"),
            manager.l2capCharacteristicUuidValue
        )
        assertEquals(
            UUID.fromString("00000008-a123-48ce-896b-4c76973373e6"),
            manager.identCharacteristicUuidValue
        )

        val cm = transport.connectionMethod as MdocConnectionMethodBle
        assertFalse(cm.supportsPeripheralServerMode)
        assertTrue(cm.supportsCentralClientMode)
        assertEquals(testUuid, cm.centralClientModeUuid)
        assertEquals(128, cm.peripheralServerModePsm)

        transport.open(senderKey)
        assertEquals(MdocTransport.State.CONNECTED, transport.state.value)
        assertEquals(1, manager.advertiseServiceCalls)
        assertContentEquals(senderIdent, manager.readIdentCharacteristic())

        val outgoing = byteArrayOf(9, 10)
        transport.sendMessage(outgoing)
        assertEquals(1, manager.sentMessages.size)
        assertContentEquals(outgoing, manager.sentMessages[0])

        // Empty message over L2CAP throws MdocTransportException wrapping MdocTransportTerminationException
        val terminationError = assertFailsWith<MdocTransportException> {
            transport.sendMessage(byteArrayOf())
        }
        assertIs<MdocTransportTerminationException>(terminationError.cause)
        assertEquals(MdocTransport.State.FAILED, transport.state.value)
    }

    /**
     * Verifies the GATT connection sequence of [BleTransportCentralMdoc] (mdoc acting as BLE central
     * client in BLE Central Client Mode):
     * 1. Calling [BleTransportCentralMdoc.advertise] is a no-op (`State.IDLE`) because a BLE central
     *    scans rather than advertises.
     * 2. Calling [BleTransportCentralMdoc.open] scans for `testUuid`, records [MdocTransport.scanningTime],
     *    connects to the peripheral, negotiates MTU, discovers services and characteristics, verifies
     *    the reader's `Ident` characteristic (`checkReaderIdentMatches`), subscribes to GATT
     *    notifications, and writes `0x01` ([BleTransportConstants.STATE_CHARACTERISTIC_START]) to the
     *    `State` characteristic.
     * 3. Messages are exchanged over GATT and terminated by writing `0x02`
     *    ([BleTransportConstants.STATE_CHARACTERISTIC_END]) to the `State` characteristic.
     */
    @Test
    fun centralMdocGattOpenMessageExchangeAndTermination() = runTest {
        val manager = FakeBleCentralManager(expectedIdent = senderIdent)
        val transport = BleTransportCentralMdoc(
            role = MdocRole.MDOC,
            options = MdocTransportOptions(),
            centralManager = manager,
            uuid = testUuid,
            psm = null,
        )

        assertNull(transport.scanningTime)
        val cm = transport.connectionMethod as MdocConnectionMethodBle
        assertFalse(cm.supportsPeripheralServerMode)
        assertTrue(cm.supportsCentralClientMode)
        assertEquals(testUuid, cm.centralClientModeUuid)

        transport.advertise()
        assertEquals(MdocTransport.State.IDLE, transport.state.value)

        transport.open(senderKey)
        assertEquals(MdocTransport.State.CONNECTED, transport.state.value)
        assertNotNull(transport.scanningTime)
        assertEquals(1, manager.connectToPeripheralCalls)
        assertEquals(1, manager.requestMtuCalls)
        assertEquals(1, manager.discoverServicesCalls)
        assertEquals(1, manager.discoverCharacteristicsCalls)
        assertEquals(1, manager.checkReaderIdentCalls)
        assertEquals(1, manager.subscribeToCharacteristicsCalls)
        assertEquals(listOf(BleTransportConstants.STATE_CHARACTERISTIC_START), manager.stateCharacteristicWrites)
        assertTrue(manager.connectedL2capPsms.isEmpty())

        val incoming = byteArrayOf(11, 22, 33)
        manager.simulateIncomingGattMessage(incoming)
        assertContentEquals(incoming, transport.waitForMessage())

        val outgoing = byteArrayOf(44, 55, 66)
        transport.sendMessage(outgoing)
        assertEquals(1, manager.sentMessages.size)
        assertContentEquals(outgoing, manager.sentMessages[0])

        // Empty message writes STATE_CHARACTERISTIC_END over GATT
        transport.sendMessage(byteArrayOf())
        assertEquals(
            listOf(BleTransportConstants.STATE_CHARACTERISTIC_START, BleTransportConstants.STATE_CHARACTERISTIC_END),
            manager.stateCharacteristicWrites
        )

        transport.close()
        assertEquals(MdocTransport.State.CLOSED, transport.state.value)
    }

    /**
     * Compares the three central-side connection flows across [BleTransportCentralMdoc] and
     * [BleTransportPeripheralMdocReader]:
     * 1. **L2CAP via GATT characteristic** (`psm == null`, `bleUseL2CAP = true` on
     *    [BleTransportCentralMdoc]): connects over GATT, discovers characteristics (reading the remote
     *    L2CAP PSM `192` from the `L2CAP` characteristic `0000000b-...`), verifies the reader `Ident`
     *    characteristic, and then opens the L2CAP channel to PSM `192` instead of subscribing to GATT
     *    data characteristics.
     * 2. **Direct L2CAP via engagement** (`psm = 200` on [BleTransportPeripheralMdocReader]): scans for
     *    the peripheral and connects directly to L2CAP PSM `200`, bypassing GATT connection, service
     *    discovery, and `Ident` verification entirely.
     * 3. **GATT on [BleTransportPeripheralMdocReader]** (`psm == null`, `bleUseL2CAP = false`): connects
     *    over GATT and subscribes to characteristics without calling `checkReaderIdentMatches` (since
     *    in Peripheral Server Mode the mdoc is the peripheral and does not expose an `Ident`
     *    characteristic).
     */
    @Test
    fun centralMdocAndPeripheralMdocReaderL2capViaCharacteristicAndViaEngagement() = runTest {
        // 1. L2CAP via characteristic (psm == null, centralManager.l2capPsm != null)
        val manager1 = FakeBleCentralManager(
            expectedIdent = senderIdent,
            remoteL2capPsm = 192
        )
        val centralMdoc = BleTransportCentralMdoc(
            role = MdocRole.MDOC,
            options = MdocTransportOptions(bleUseL2CAP = true),
            centralManager = manager1,
            uuid = testUuid,
            psm = null,
        )
        centralMdoc.open(senderKey)
        assertEquals(
            UUID.fromString("0000000b-a123-48ce-896b-4c76973373e6"),
            manager1.l2capUuidValue
        )
        assertEquals(MdocTransport.State.CONNECTED, centralMdoc.state.value)
        assertEquals(1, manager1.connectToPeripheralCalls)
        assertEquals(1, manager1.checkReaderIdentCalls)
        assertEquals(0, manager1.subscribeToCharacteristicsCalls)
        assertEquals(listOf(192), manager1.connectedL2capPsms)

        val termErr1 = assertFailsWith<MdocTransportException> {
            centralMdoc.sendMessage(byteArrayOf())
        }
        assertIs<MdocTransportTerminationException>(termErr1.cause)
        assertEquals(MdocTransport.State.FAILED, centralMdoc.state.value)

        // 2. Direct L2CAP via engagement (psm != null) on BleTransportPeripheralMdocReader
        val manager2 = FakeBleCentralManager()
        val peripheralMdocReader = BleTransportPeripheralMdocReader(
            role = MdocRole.MDOC_READER,
            options = MdocTransportOptions(bleUseL2CAPInEngagement = true),
            centralManager = manager2,
            uuid = testUuid,
            psm = 200,
        )
        peripheralMdocReader.open(senderKey)
        assertEquals(MdocTransport.State.CONNECTED, peripheralMdocReader.state.value)
        // Direct L2CAP bypasses GATT connection & discovery completely
        assertEquals(0, manager2.connectToPeripheralCalls)
        assertEquals(0, manager2.discoverServicesCalls)
        assertEquals(0, manager2.checkReaderIdentCalls)
        assertEquals(listOf(200), manager2.connectedL2capPsms)

        val termErr2 = assertFailsWith<MdocTransportException> {
            peripheralMdocReader.sendMessage(byteArrayOf())
        }
        assertIs<MdocTransportTerminationException>(termErr2.cause)
        assertEquals(MdocTransport.State.FAILED, peripheralMdocReader.state.value)

        // 3. GATT path on BleTransportPeripheralMdocReader (does not call checkReaderIdentMatches)
        val manager3 = FakeBleCentralManager()
        val gattPeripheralMdocReader = BleTransportPeripheralMdocReader(
            role = MdocRole.MDOC_READER,
            options = MdocTransportOptions(),
            centralManager = manager3,
            uuid = testUuid,
            psm = null,
        )
        gattPeripheralMdocReader.open(senderKey)
        assertEquals(1, manager3.connectToPeripheralCalls)
        assertEquals(0, manager3.checkReaderIdentCalls)
        assertEquals(1, manager3.subscribeToCharacteristicsCalls)
        assertEquals(listOf(BleTransportConstants.STATE_CHARACTERISTIC_START), manager3.stateCharacteristicWrites)
        gattPeripheralMdocReader.close()
    }

    /**
     * Distinguishes between unexpected remote disconnection vs local [MdocTransport.close] while a
     * caller is suspended in [MdocTransport.waitForMessage]:
     * 1. When the remote peripheral disconnects unexpectedly on [BleTransportCentralMdoc] or
     *    [BleTransportPeripheralMdocReader], `onError` fires, `waitForMessage()` throws
     *    [MdocTransportException], and the transport transitions to [MdocTransport.State.FAILED].
     * 2. When [MdocTransport.close] is called locally while `waitForMessage()` is suspended,
     *    `waitForMessage()` throws [MdocTransportClosedException] and the transport transitions to
     *    [MdocTransport.State.CLOSED].
     */
    @Test
    fun centralManagerOnErrorAndOnClosedCallbacks() = runTest {
        // 1. onError on BleTransportCentralMdoc while waiting for message
        val manager1 = FakeBleCentralManager(expectedIdent = senderIdent)
        val centralMdoc = BleTransportCentralMdoc(
            role = MdocRole.MDOC,
            options = MdocTransportOptions(),
            centralManager = manager1,
            uuid = testUuid,
            psm = null,
        )
        centralMdoc.open(senderKey)
        supervisorScope {
            val waitDeferred = async { centralMdoc.waitForMessage() }
            runCurrent()
            manager1.simulateDisconnected()
            assertFailsWith<MdocTransportException> {
                waitDeferred.await()
            }
        }
        assertEquals(MdocTransport.State.FAILED, centralMdoc.state.value)
        assertTrue(manager1.closeCalls > 0)

        // 2. onError on BleTransportPeripheralMdocReader while waiting for message
        val manager2 = FakeBleCentralManager()
        val reader = BleTransportPeripheralMdocReader(
            role = MdocRole.MDOC_READER,
            options = MdocTransportOptions(),
            centralManager = manager2,
            uuid = testUuid,
            psm = null,
        )
        reader.open(senderKey)
        supervisorScope {
            val waitDeferred = async { reader.waitForMessage() }
            runCurrent()
            manager2.simulateDisconnected()
            assertFailsWith<MdocTransportException> {
                waitDeferred.await()
            }
        }
        assertEquals(MdocTransport.State.FAILED, reader.state.value)
        assertTrue(manager2.closeCalls > 0)

        // 3. close() while waiting for message throws MdocTransportClosedException
        val manager3 = FakeBlePeripheralManager()
        val peripheralMdoc = BleTransportPeripheralMdoc(
            role = MdocRole.MDOC,
            options = MdocTransportOptions(),
            peripheralManager = manager3,
            uuid = testUuid,
        )
        peripheralMdoc.open(senderKey)
        supervisorScope {
            val waitDeferred = async { peripheralMdoc.waitForMessage() }
            runCurrent()
            peripheralMdoc.close()
            assertFailsWith<MdocTransportClosedException> {
                waitDeferred.await()
            }
        }
        assertEquals(MdocTransport.State.CLOSED, peripheralMdoc.state.value)
    }

    /**
     * Verifies that underlying HAL/manager exceptions thrown during [MdocTransport.advertise],
     * [MdocTransport.open], or [MdocTransport.sendMessage] are wrapped in [MdocTransportException],
     * close the underlying BLE manager, and transition the transport to [MdocTransport.State.FAILED].
     */
    @Test
    fun failuresInAdvertiseOpenSendMessageTransitionToFailed() = runTest {
        // 1. Failure during advertise()
        val manager1 = FakeBlePeripheralManager(
            powerOnError = IllegalStateException("Bluetooth off")
        )
        val peripheralMdoc = BleTransportPeripheralMdoc(
            role = MdocRole.MDOC,
            options = MdocTransportOptions(),
            peripheralManager = manager1,
            uuid = testUuid,
        )
        assertFailsWith<MdocTransportException> {
            peripheralMdoc.advertise()
        }
        assertEquals(MdocTransport.State.FAILED, peripheralMdoc.state.value)
        assertEquals(1, manager1.closeCalls)

        // 2. Failure during open()
        val manager2 = FakeBleCentralManager(
            scanError = IllegalStateException("Scan failed")
        )
        val centralMdoc = BleTransportCentralMdoc(
            role = MdocRole.MDOC,
            options = MdocTransportOptions(),
            centralManager = manager2,
            uuid = testUuid,
            psm = null,
        )
        assertFailsWith<MdocTransportException> {
            centralMdoc.open(senderKey)
        }
        assertEquals(MdocTransport.State.FAILED, centralMdoc.state.value)
        assertEquals(1, manager2.closeCalls)

        // 3. Failure during sendMessage()
        val manager3 = FakeBlePeripheralManager(
            failSendMessage = IllegalStateException("Write failed")
        )
        val peripheralReader = BleTransportCentralMdocReader(
            role = MdocRole.MDOC_READER,
            options = MdocTransportOptions(),
            peripheralManager = manager3,
            uuid = testUuid,
        )
        peripheralReader.open(senderKey)
        assertFailsWith<MdocTransportException> {
            peripheralReader.sendMessage(byteArrayOf(1, 2, 3))
        }
        assertEquals(MdocTransport.State.FAILED, peripheralReader.state.value)
        assertEquals(1, manager3.closeCalls)
    }

    /**
     * Verifies state precondition checks on [BleTransportPeripheralMdoc]:
     * - Calling [MdocTransport.waitForMessage] while in [MdocTransport.State.IDLE] throws
     *   [IllegalStateException] without failing the transport.
     * - Calling [MdocTransport.sendMessage] while in [MdocTransport.State.IDLE] transitions the
     *   transport to [MdocTransport.State.FAILED] and throws [MdocTransportException] caused by
     *   [IllegalStateException].
     * - Calling [MdocTransport.advertise] or [MdocTransport.open] once the transport is already
     *   in [MdocTransport.State.FAILED] throws [MdocTransportException] caused by
     *   [IllegalStateException].
     */
    @Test
    fun invalidStateCallsThrowTransportOrIllegalStateException() = runTest {
        val manager = FakeBlePeripheralManager()
        val transport = BleTransportPeripheralMdoc(
            role = MdocRole.MDOC,
            options = MdocTransportOptions(),
            peripheralManager = manager,
            uuid = testUuid,
        )

        // waitForMessage while IDLE throws IllegalStateException directly without failing transport
        assertFailsWith<IllegalStateException> {
            transport.waitForMessage()
        }
        assertEquals(MdocTransport.State.IDLE, transport.state.value)

        // sendMessage while IDLE fails transport and throws MdocTransportException wrapping IllegalStateException
        val sendErr = assertFailsWith<MdocTransportException> {
            transport.sendMessage(byteArrayOf(1))
        }
        assertIs<IllegalStateException>(sendErr.cause)
        assertEquals(MdocTransport.State.FAILED, transport.state.value)

        // Cannot advertise or open once FAILED / CLOSED
        val advErr = assertFailsWith<MdocTransportException> {
            transport.advertise()
        }
        assertIs<IllegalStateException>(advErr.cause)

        val openErr = assertFailsWith<MdocTransportException> {
            transport.open(senderKey)
        }
        assertIs<IllegalStateException>(openErr.cause)
    }

    /**
     * Wraps a real [BlePeripheralManagerImpl] with a [FakeBlePeripheralHal] so tests can exercise the
     * full [BlePeripheralManagerImpl] state machine while inspecting recorded HAL calls and injecting
     * simulated Bluetooth events (incoming GATT writes, central disconnections, etc.).
     *
     * @param autoPowerOn If `true`, [BlePeripheralHal.waitForPowerOn] immediately invokes
     *   [BlePeripheralHal.Callback.onPowerOn]; if `false`, `waitForPowerOn()` stays suspended so
     *   cancellation during startup can be tested.
     * @param powerOnError If non-null, [BlePeripheralHal.waitForPowerOn] fails with this error.
     * @param publishedL2capPsm The PSM value reported when [BlePeripheralHal.publishL2capChannel] is called.
     * @param autoConnectGattClient If `true`, starting advertising immediately simulates a remote central
     *   writing [BleTransportConstants.STATE_CHARACTERISTIC_START] (`0x01`) to the state characteristic.
     *   If `false` (and [autoConnectL2capClient] is `false`), `open()` stays suspended in `ADVERTISING`
     *   waiting for a client to connect.
     * @param autoConnectL2capClient If `true`, starting advertising immediately simulates a remote central
     *   opening an L2CAP connection.
     * @param failSendMessage If non-null, outgoing GATT notifications fail with this error.
     */
    private class FakeBlePeripheralManager(
        autoPowerOn: Boolean = true,
        powerOnError: Throwable? = null,
        publishedL2capPsm: Int = 128,
        autoConnectGattClient: Boolean = true,
        autoConnectL2capClient: Boolean = false,
        failSendMessage: Throwable? = null,
        private val hal: FakeBlePeripheralHal = FakeBlePeripheralHal(
            autoPowerOn = autoPowerOn,
            powerOnError = powerOnError,
            publishedL2capPsm = publishedL2capPsm,
            autoConnectGattClient = autoConnectGattClient,
            autoConnectL2capClient = autoConnectL2capClient,
            failSendMessage = failSendMessage,
        ),
        private val impl: BlePeripheralManagerImpl = BlePeripheralManagerImpl(hal)
    ) : BlePeripheralManager by impl {
        val waitForPowerOnCalls: Int get() = hal.waitForPowerOnCalls
        val advertiseServiceCalls: Int get() = hal.advertiseServiceCalls
        val closeCalls: Int get() = hal.closeCalls
        val identCharacteristicUuidValue: UUID? get() = hal.identCharacteristicUuid
        val l2capCharacteristicUuidValue: UUID? get() = hal.l2capCharacteristicUuid
        val startL2capServerValue: Boolean get() = hal.publishL2capCalled
        val stateCharacteristicWrites: List<Int> get() = hal.stateCharacteristicWrites
        val sentMessages: List<ByteArray> get() = hal.sentMessages

        /** Simulates the remote BLE central unexpectedly disconnecting from our GATT server. */
        fun simulateCentralDisconnected() {
            hal.halCallback.onCentralDisconnected()
        }

        /**
         * Simulates the remote BLE central writing a single-chunk (`0x00`-prefixed) GATT message to the
         * `Client2Server` characteristic.
         */
        fun simulateIncomingGattMessage(message: ByteArray) {
            val uuid = checkNotNull(hal.client2ServerCharacteristicUuid)
            hal.halCallback.onCharacteristicWriteRequest(uuid, byteArrayOf(0x00) + message, null)
        }

        /**
         * Simulates the remote BLE central reading the `Ident` characteristic and returns the response
         * payload produced by [BlePeripheralManagerImpl].
         */
        fun readIdentCharacteristic(): ByteArray? {
            val uuid = checkNotNull(hal.identCharacteristicUuid)
            hal.lastReadResponse = null
            hal.halCallback.onCharacteristicReadRequest(requestId = 1, uuid = uuid)
            return hal.lastReadResponse
        }
    }

    /**
     * Fake [BlePeripheralHal] implementation that simulates OS BLE peripheral callbacks in memory
     * and records outgoing GATT notifications and L2CAP messages for test assertions.
     */
    private class FakeBlePeripheralHal(
        private val autoPowerOn: Boolean = true,
        private val powerOnError: Throwable? = null,
        private val publishedL2capPsm: Int = 128,
        private val autoConnectGattClient: Boolean = true,
        private val autoConnectL2capClient: Boolean = false,
        private val failSendMessage: Throwable? = null,
    ) : BlePeripheralHal {
        lateinit var halCallback: BlePeripheralHal.Callback
        var waitForPowerOnCalls = 0
        var advertiseServiceCalls = 0
        var closeCalls = 0
        var publishL2capCalled = false
        var stateCharacteristicUuid: UUID? = null
        var client2ServerCharacteristicUuid: UUID? = null
        var server2ClientCharacteristicUuid: UUID? = null
        var identCharacteristicUuid: UUID? = null
        var l2capCharacteristicUuid: UUID? = null
        var lastReadResponse: ByteArray? = null
        val stateCharacteristicWrites = mutableListOf<Int>()
        val sentMessages = mutableListOf<ByteArray>()
        private var outgoingGattBuffer = ByteStringBuilder()

        override fun setCallback(callback: BlePeripheralHal.Callback) {
            halCallback = callback
        }

        override fun waitForPowerOn() {
            waitForPowerOnCalls += 1
            if (powerOnError != null) {
                halCallback.onPowerOn(powerOnError)
            } else if (autoPowerOn) {
                halCallback.onPowerOn(null)
            }
        }

        override fun publishL2capChannel() {
            publishL2capCalled = true
            halCallback.onL2capPublished(publishedL2capPsm, null)
        }

        override fun closeL2capServer() {}

        override fun setupGattServerAndAddService(
            serviceUuid: UUID,
            stateCharacteristicUuid: UUID,
            client2ServerCharacteristicUuid: UUID,
            server2ClientCharacteristicUuid: UUID,
            identCharacteristicUuid: UUID?,
            l2capCharacteristicUuid: UUID?,
            encodedL2capPsm: ByteArray?
        ) {
            this.stateCharacteristicUuid = stateCharacteristicUuid
            this.client2ServerCharacteristicUuid = client2ServerCharacteristicUuid
            this.server2ClientCharacteristicUuid = server2ClientCharacteristicUuid
            this.identCharacteristicUuid = identCharacteristicUuid
            this.l2capCharacteristicUuid = l2capCharacteristicUuid
            halCallback.onServiceAdded(null)
        }

        override fun startAdvertising(serviceUuid: UUID) {
            advertiseServiceCalls += 1
            halCallback.onAdvertisingStarted(null)
            if (autoConnectL2capClient) {
                halCallback.onL2capClientConnected(RecordingBleL2capChannel(sentMessages))
            } else if (autoConnectGattClient) {
                halCallback.onCharacteristicWriteRequest(
                    uuid = checkNotNull(stateCharacteristicUuid),
                    value = byteArrayOf(BleTransportConstants.STATE_CHARACTERISTIC_START.toByte()),
                    maxWriteSizeForCentral = 512
                )
            }
        }

        override fun stopAdvertising() {}

        override fun sendReadResponse(requestId: Int, statusSuccess: Boolean, value: ByteArray) {
            if (statusSuccess) {
                lastReadResponse = value.copyOf()
            }
        }

        override fun notifyCharacteristicChanged(uuid: UUID, value: ByteArray) {
            if (failSendMessage != null) {
                halCallback.onNotificationSent(uuid, failSendMessage)
                return
            }
            if (uuid == stateCharacteristicUuid && value.size == 1) {
                stateCharacteristicWrites.add(value[0].toInt())
            } else if (uuid == server2ClientCharacteristicUuid && value.isNotEmpty()) {
                outgoingGattBuffer.append(value, 1, value.size)
                if (value[0].toInt() == 0x00) {
                    sentMessages.add(outgoingGattBuffer.toByteString().toByteArray())
                    outgoingGattBuffer = ByteStringBuilder()
                }
            }
            halCallback.onNotificationSent(uuid, null)
        }

        override fun close() {
            closeCalls += 1
        }
    }

    /**
     * Wraps a real [BleCentralManagerImpl] with a [FakeBleCentralHal] so tests can exercise the
     * full [BleCentralManagerImpl] state machine while inspecting recorded HAL calls and injecting
     * simulated Bluetooth events (incoming GATT notifications, peripheral disconnections, etc.).
     *
     * @param autoDiscoverPeripheral If `true`, [BleCentralHal.startScan] immediately reports a discovered
     *   peripheral via [BleCentralHal.Callback.onPeripheralDiscovered]; if `false`, `waitForPeripheralWithUuid()`
     *   stays suspended in `SCANNING` so cancellation during scanning can be tested.
     * @param scanError If non-null, [BleCentralHal.startScan] fails with this error via
     *   [BleCentralHal.Callback.onScanFailed].
     * @param expectedIdent The 16-byte `BLEIdent` value returned when [BleCentralManagerImpl] reads the
     *   remote peripheral's `Ident` characteristic during [BleCentralManager.checkReaderIdentMatches].
     * @param remoteL2capPsm If non-null, the fake peripheral reports that it has an L2CAP characteristic
     *   during characteristic discovery and returns this PSM (encoded as a 4-byte big-endian integer)
     *   when read.
     */
    private class FakeBleCentralManager(
        autoDiscoverPeripheral: Boolean = true,
        scanError: Throwable? = null,
        expectedIdent: ByteArray = ByteArray(16),
        remoteL2capPsm: Int? = null,
        private val hal: FakeBleCentralHal = FakeBleCentralHal(
            autoDiscoverPeripheral = autoDiscoverPeripheral,
            scanError = scanError,
            expectedIdent = expectedIdent,
            remoteL2capPsm = remoteL2capPsm,
        ),
        private val impl: BleCentralManagerImpl = BleCentralManagerImpl(
            hal = hal,
            postScanDelay = Duration.ZERO
        )
    ) : BleCentralManager by impl {
        val closeCalls: Int get() = hal.closeCalls
        val connectToPeripheralCalls: Int get() = hal.connectToPeripheralCalls
        val requestMtuCalls: Int get() = hal.requestMtuCalls
        val discoverServicesCalls: Int get() = hal.discoverServicesCalls
        val discoverCharacteristicsCalls: Int get() = hal.discoverCharacteristicsCalls
        val checkReaderIdentCalls: Int get() = hal.checkReaderIdentCalls
        val subscribeToCharacteristicsCalls: Int get() = hal.enableNotificationsCalls / 2
        val l2capUuidValue: UUID? get() = hal.l2capCharacteristicUuid
        val stateCharacteristicWrites: List<Int> get() = hal.stateCharacteristicWrites
        val sentMessages: List<ByteArray> get() = hal.sentMessages
        val connectedL2capPsms: List<Int> get() = hal.connectedL2capPsms

        /** Simulates the remote BLE peripheral unexpectedly disconnecting from our GATT client. */
        fun simulateDisconnected() {
            hal.halCallback.onDisconnected()
        }

        /**
         * Simulates the remote BLE peripheral sending a single-chunk (`0x00`-prefixed) GATT notification
         * on the `Server2Client` characteristic.
         */
        fun simulateIncomingGattMessage(message: ByteArray) {
            val uuid = checkNotNull(hal.server2ClientCharacteristicUuid)
            hal.halCallback.onCharacteristicChanged(uuid, byteArrayOf(0x00) + message)
        }
    }

    /**
     * Fake [BleCentralHal] implementation that simulates OS BLE central callbacks in memory
     * and records outgoing GATT writes and L2CAP messages for test assertions.
     */
    private class FakeBleCentralHal(
        private val autoDiscoverPeripheral: Boolean = true,
        private val scanError: Throwable? = null,
        private val expectedIdent: ByteArray = ByteArray(16),
        private val remoteL2capPsm: Int? = null,
    ) : BleCentralHal {
        lateinit var halCallback: BleCentralHal.Callback
        override var isConnected: Boolean = false

        var closeCalls = 0
        var connectToPeripheralCalls = 0
        var requestMtuCalls = 0
        var discoverServicesCalls = 0
        var discoverCharacteristicsCalls = 0
        var checkReaderIdentCalls = 0
        var enableNotificationsCalls = 0
        var stateCharacteristicUuid: UUID? = null
        var client2ServerCharacteristicUuid: UUID? = null
        var server2ClientCharacteristicUuid: UUID? = null
        var identCharacteristicUuid: UUID? = null
        var l2capCharacteristicUuid: UUID? = null
        val stateCharacteristicWrites = mutableListOf<Int>()
        val sentMessages = mutableListOf<ByteArray>()
        val connectedL2capPsms = mutableListOf<Int>()
        private var outgoingGattBuffer = ByteStringBuilder()

        override fun setCallback(callback: BleCentralHal.Callback) {
            halCallback = callback
        }

        override fun waitForPowerOn() {
            halCallback.onPowerOn(null)
        }

        override fun startScan(serviceUuid: UUID) {
            if (scanError != null) {
                halCallback.onScanFailed(scanError)
            } else if (autoDiscoverPeripheral) {
                halCallback.onPeripheralDiscovered()
            }
        }

        override fun stopScan() {}
        override fun onScanStoppedAfterDiscovery() {}

        override fun connectToPeripheral() {
            connectToPeripheralCalls += 1
            isConnected = true
            halCallback.onConnected()
        }

        override fun requestMtu(mtu: Int) {
            requestMtuCalls += 1
            halCallback.onMtuChanged(mtu, null)
        }

        override fun discoverServices(serviceUuid: UUID) {
            discoverServicesCalls += 1
            halCallback.onServicesDiscovered(hasTargetService = true, error = null)
        }

        override fun discoverCharacteristics(
            stateCharacteristicUuid: UUID,
            client2ServerCharacteristicUuid: UUID,
            server2ClientCharacteristicUuid: UUID,
            identCharacteristicUuid: UUID?,
            l2capCharacteristicUuid: UUID?
        ) {
            discoverCharacteristicsCalls += 1
            this.stateCharacteristicUuid = stateCharacteristicUuid
            this.client2ServerCharacteristicUuid = client2ServerCharacteristicUuid
            this.server2ClientCharacteristicUuid = server2ClientCharacteristicUuid
            this.identCharacteristicUuid = identCharacteristicUuid
            this.l2capCharacteristicUuid = l2capCharacteristicUuid
            halCallback.onCharacteristicsDiscovered(
                maxWriteSize = null,
                hasL2capCharacteristic = (l2capCharacteristicUuid != null && remoteL2capPsm != null),
                error = null
            )
        }

        override fun readCharacteristic(uuid: UUID) {
            when (uuid) {
                identCharacteristicUuid -> {
                    checkReaderIdentCalls += 1
                    halCallback.onCharacteristicRead(uuid, expectedIdent.copyOf(), null)
                }
                l2capCharacteristicUuid -> {
                    val psm = checkNotNull(remoteL2capPsm)
                    halCallback.onCharacteristicRead(uuid, encodeL2capPsm(psm), null)
                }
                else -> {
                    halCallback.onCharacteristicRead(uuid, null, IllegalStateException("Unexpected read $uuid"))
                }
            }
        }

        override fun enableNotifications(characteristicUuid: UUID) {
            enableNotificationsCalls += 1
            halCallback.onDescriptorWrite(characteristicUuid, null)
        }

        override fun writeCharacteristic(uuid: UUID, value: ByteArray) {
            if (uuid == stateCharacteristicUuid && value.size == 1) {
                stateCharacteristicWrites.add(value[0].toInt())
            } else if (uuid == client2ServerCharacteristicUuid && value.isNotEmpty()) {
                outgoingGattBuffer.append(value, 1, value.size)
                if (value[0].toInt() == 0x00) {
                    sentMessages.add(outgoingGattBuffer.toByteString().toByteArray())
                    outgoingGattBuffer = ByteStringBuilder()
                }
            }
            halCallback.onCharacteristicWrite(uuid, null)
        }

        override fun connectL2cap(psm: Int) {
            connectedL2capPsms.add(psm)
            halCallback.onL2capConnected(RecordingBleL2capChannel(sentMessages), null)
        }

        override fun closeConnectedL2capAttempt() {}

        override fun close() {
            isConnected = false
            closeCalls += 1
        }
    }

    /**
     * [BleL2capChannel] that suspends reads until closed and unframes 4-byte length-prefixed writes
     * into [sentMessages].
     */
    private class RecordingBleL2capChannel(
        private val sentMessages: MutableList<ByteArray>
    ) : BleL2capChannel {
        private val incoming = Channel<ByteArray>(Channel.UNLIMITED)

        override suspend fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            incoming.receiveCatching().getOrNull() ?: return -1
            return 0
        }

        override suspend fun write(data: ByteArray) {
            val payloadLength = data.getUInt32(0).toInt()
            sentMessages.add(data.copyOfRange(4, 4 + payloadLength))
        }

        override fun close() {
            incoming.close()
        }
    }
}
