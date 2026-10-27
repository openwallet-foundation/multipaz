package org.multipaz.mdoc.transport

import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.multipaz.crypto.Crypto
import org.multipaz.crypto.EcCurve
import org.multipaz.crypto.EcPublicKey
import org.multipaz.mdoc.role.MdocRole
import org.multipaz.util.UUID
import kotlin.math.min
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * Unit and integration tests for [BleCentralManagerImpl] and [BlePeripheralManagerImpl].
 *
 * Whereas [BleTransportTest] tests each transport class in isolation with a single-sided fake HAL,
 * this test suite focuses on the protocol logic inside [BleCentralManagerImpl] and
 * [BlePeripheralManagerImpl]:
 * - **Two-sided end-to-end communication**: Using [PairedBleHalBridge] to connect a real
 *   [BleCentralManagerImpl] and a real [BlePeripheralManagerImpl] directly to each other in memory,
 *   verifying multi-chunk GATT splitting (`0x01` / `0x00` headers) and reassembly, `BLEIdent`
 *   HKDF-SHA-256 derivation and verification, L2CAP PSM discovery via GATT characteristic, and
 *   direct L2CAP connections via engagement.
 * - **Partial L2CAP stream reads**: Using [InMemoryBleL2capChannel] to split incoming byte streams
 *   into small slices (17 bytes at a time) so [readNOctets] and [readFromL2capChannel] must loop to
 *   reassemble 4-byte length prefixes and multi-kilobyte payloads.
 * - **Retry and backoff state machine**: Verifying scan timeout restarts, GATT connection retries,
 *   service discovery retries, and L2CAP exponential backoff retries in [BleCentralManagerImpl].
 * - **Protocol error conditions**: Verifying rejection of mismatched `BLEIdent` values, invalid
 *   negotiated MTU values (`< 22`), malformed GATT chunk headers, and `Ident` reads before
 *   [BlePeripheralManager.setESenderKey] is called.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class BleManagerImplTest {
    private lateinit var eDeviceKey: EcPublicKey
    private lateinit var eReaderKey: EcPublicKey
    private val serviceUuid = UUID.fromString("11111111-2222-3333-4444-555555555555")

    @BeforeTest
    fun setup() = runTest {
        eDeviceKey = Crypto.createEcPrivateKey(EcCurve.P256).use { it.publicKey }
        eReaderKey = Crypto.createEcPrivateKey(EcCurve.P256).use { it.publicKey }
    }

    /**
     * End-to-end test of [BleTransportCentralMdoc] + [BleTransportCentralMdocReader] using the real
     * [BleCentralManagerImpl] and [BlePeripheralManagerImpl] wired together over GATT with chunked
     * messages and `BLEIdent` HKDF verification.
     */
    @Test
    fun endToEndCentralClientModeOverGattWithChunkingAndIdent() = runTest {
        val bridge = PairedBleHalBridge(negotiatedMtu = 103) // maxCharacteristicSize = 100, chunk payload = 99
        val centralManager = BleCentralManagerImpl(
            hal = bridge.centralHal,
            postScanDelay = Duration.ZERO
        )
        val peripheralManager = BlePeripheralManagerImpl(
            hal = bridge.peripheralHal
        )

        val mdoc = BleTransportCentralMdoc(
            role = MdocRole.MDOC,
            options = MdocTransportOptions(bleUseL2CAP = false),
            centralManager = centralManager,
            uuid = serviceUuid,
            psm = null
        )
        val reader = BleTransportCentralMdocReader(
            role = MdocRole.MDOC_READER,
            options = MdocTransportOptions(bleUseL2CAP = false),
            peripheralManager = peripheralManager,
            uuid = serviceUuid
        )

        reader.advertise()
        assertEquals(MdocTransport.State.ADVERTISING, reader.state.value)

        // Open both sides concurrently; central will scan, connect, negotiate MTU 103, discover service/chars,
        // verify reader BLEIdent (using eReaderKey), subscribe, and write 0x01 to state characteristic.
        val readerOpenJob = async(start = CoroutineStart.UNDISPATCHED) { reader.open(eReaderKey) }
        val mdocOpenJob = async { mdoc.open(eReaderKey) }
        readerOpenJob.await()
        mdocOpenJob.await()

        assertEquals(MdocTransport.State.CONNECTED, mdoc.state.value)
        assertEquals(MdocTransport.State.CONNECTED, reader.state.value)
        assertEquals(100, centralManager.maxCharacteristicSize)
        assertEquals(100, peripheralManager.maxCharacteristicSize)

        // Send a 350-byte message from mdoc (Central) -> reader (Peripheral), requiring 4 GATT chunks (99+99+99+53)
        val msgFromMdoc = ByteArray(350) { (it * 3).toByte() }
        mdoc.sendMessage(msgFromMdoc)
        assertContentEquals(msgFromMdoc, reader.waitForMessage())
        assertEquals(4, bridge.centralToPeripheralCharacteristicWrites.size)
        assertEquals(0x01, bridge.centralToPeripheralCharacteristicWrites[0][0].toInt())
        assertEquals(0x01, bridge.centralToPeripheralCharacteristicWrites[1][0].toInt())
        assertEquals(0x01, bridge.centralToPeripheralCharacteristicWrites[2][0].toInt())
        assertEquals(0x00, bridge.centralToPeripheralCharacteristicWrites[3][0].toInt())

        // Send a 250-byte message from reader (Peripheral) -> mdoc (Central), requiring 3 GATT notifications (99+99+52)
        val msgFromReader = ByteArray(250) { (it * 7).toByte() }
        reader.sendMessage(msgFromReader)
        assertContentEquals(msgFromReader, mdoc.waitForMessage())
        assertEquals(3, bridge.peripheralToCentralNotifications.size)
        assertEquals(0x01, bridge.peripheralToCentralNotifications[0][0].toInt())
        assertEquals(0x01, bridge.peripheralToCentralNotifications[1][0].toInt())
        assertEquals(0x00, bridge.peripheralToCentralNotifications[2][0].toInt())

        // Transport-specific termination from reader -> mdoc via state characteristic (0x02)
        reader.sendMessage(byteArrayOf())
        assertContentEquals(byteArrayOf(), mdoc.waitForMessage())

        // Transport-specific termination from mdoc -> reader via state characteristic (0x02)
        mdoc.sendMessage(byteArrayOf())
        assertContentEquals(byteArrayOf(), reader.waitForMessage())

        mdoc.close()
        reader.close()
    }

    /**
     * End-to-end test of [BleTransportPeripheralMdoc] + [BleTransportPeripheralMdocReader] using the real
     * [BlePeripheralManagerImpl] and [BleCentralManagerImpl] wired together over L2CAP discovered via GATT
     * characteristic.
     */
    @Test
    fun endToEndPeripheralServerModeOverL2capViaCharacteristic() = runTest {
        withContext(Dispatchers.Default) {
            val bridge = PairedBleHalBridge(publishedL2capPsm = 192)
            val peripheralManager = BlePeripheralManagerImpl(hal = bridge.peripheralHal)
            val centralManager = BleCentralManagerImpl(
                hal = bridge.centralHal,
                postScanDelay = Duration.ZERO
            )

            val mdoc = BleTransportPeripheralMdoc(
                role = MdocRole.MDOC,
                options = MdocTransportOptions(bleUseL2CAP = true),
                peripheralManager = peripheralManager,
                uuid = serviceUuid
            )
            val reader = BleTransportPeripheralMdocReader(
                role = MdocRole.MDOC_READER,
                options = MdocTransportOptions(bleUseL2CAP = true),
                centralManager = centralManager,
                uuid = serviceUuid,
                psm = null
            )

            mdoc.advertise()
            assertEquals(192, peripheralManager.l2capPsm)

            val mdocOpenJob = async(start = CoroutineStart.UNDISPATCHED) { mdoc.open(eDeviceKey) }
            val readerOpenJob = async { reader.open(eDeviceKey) }
            mdocOpenJob.await()
            readerOpenJob.await()

            assertTrue(centralManager.usingL2cap)
            assertTrue(peripheralManager.usingL2cap)
            assertEquals(192, centralManager.l2capPsm)

            val request = ByteArray(2048) { (it and 0xff).toByte() }
            reader.sendMessage(request)
            assertContentEquals(request, withTimeout(5.seconds) { mdoc.waitForMessage() })

            val response = ByteArray(4096) { ((it * 5) and 0xff).toByte() }
            mdoc.sendMessage(response)
            assertContentEquals(response, withTimeout(5.seconds) { reader.waitForMessage() })

            mdoc.close()
            reader.close()
        }
    }

    /**
     * End-to-end test of direct L2CAP connection when PSM is provided in engagement (`bleUseL2CAPInEngagement = true`),
     * including the iOS path where `requireL2capPeripheralConnectedFirst = true`.
     */
    @Test
    fun endToEndDirectL2capInEngagementConnectsPeripheralFirstWhenConfigured() = runTest {
        withContext(Dispatchers.Default) {
            val bridge = PairedBleHalBridge(publishedL2capPsm = 144)
            val peripheralManager = BlePeripheralManagerImpl(hal = bridge.peripheralHal)
            val centralManager = BleCentralManagerImpl(
                hal = bridge.centralHal,
                postScanDelay = Duration.ZERO,
                requireL2capPeripheralConnectedFirst = true
            )

            val mdoc = BleTransportPeripheralMdoc(
                role = MdocRole.MDOC,
                options = MdocTransportOptions(bleUseL2CAPInEngagement = true),
                peripheralManager = peripheralManager,
                uuid = serviceUuid
            )
            mdoc.advertise()
            val psm = peripheralManager.l2capPsm
            assertEquals(144, psm)

            val reader = BleTransportPeripheralMdocReader(
                role = MdocRole.MDOC_READER,
                options = MdocTransportOptions(bleUseL2CAPInEngagement = true),
                centralManager = centralManager,
                uuid = serviceUuid,
                psm = psm
            )

            val mdocOpenJob = async(start = CoroutineStart.UNDISPATCHED) { mdoc.open(eDeviceKey) }
            val readerOpenJob = async { reader.open(eDeviceKey) }
            mdocOpenJob.await()
            readerOpenJob.await()

            assertTrue(bridge.centralHal.isConnected)
            assertTrue(centralManager.usingL2cap)
            assertTrue(peripheralManager.usingL2cap)

            val msg = byteArrayOf(42, 43, 44)
            reader.sendMessage(msg)
            assertContentEquals(msg, withTimeout(5.seconds) { mdoc.waitForMessage() })

            mdoc.close()
            reader.close()
        }
    }

    /**
     * Verifies that `checkReaderIdentMatches` rejects a mismatched `BLEIdent` characteristic value.
     */
    @Test
    fun centralManagerRejectsMismatchedBleIdent() = runTest {
        val bridge = PairedBleHalBridge()
        val centralManager = BleCentralManagerImpl(
            hal = bridge.centralHal,
            postScanDelay = Duration.ZERO
        )
        val peripheralManager = BlePeripheralManagerImpl(hal = bridge.peripheralHal)

        val mdoc = BleTransportCentralMdoc(
            role = MdocRole.MDOC,
            options = MdocTransportOptions(),
            centralManager = centralManager,
            uuid = serviceUuid,
            psm = null
        )
        val reader = BleTransportCentralMdocReader(
            role = MdocRole.MDOC_READER,
            options = MdocTransportOptions(),
            peripheralManager = peripheralManager,
            uuid = serviceUuid
        )

        reader.advertise()
        // Reader sets eDeviceKey instead of eReaderKey, causing a BLEIdent mismatch!
        val readerOpenJob = async { reader.open(eDeviceKey) }
        testScheduler.runCurrent()
        val error = assertFailsWith<MdocTransportException> {
            mdoc.open(eReaderKey)
        }
        assertTrue(error.cause?.message?.contains("for ident, got") == true)
        assertEquals(MdocTransport.State.FAILED, mdoc.state.value)
        readerOpenJob.cancel()
        reader.close()
    }

    /**
     * Verifies scan timeout restart, connection retry loop, service discovery retry loop, and L2CAP connect retry loop
     * in [BleCentralManagerImpl].
     */
    @Test
    fun centralManagerRetriesScanConnectDiscoverServicesAndL2cap() = runTest {
        var scanStarts = 0
        var connectAttempts = 0
        var discoverServicesAttempts = 0
        var l2capAttempts = 0

        val pipeToCentral = Channel<ByteArray>(Channel.UNLIMITED)
        val pipeToPeripheral = Channel<ByteArray>(Channel.UNLIMITED)
        val fakeL2capChannel = InMemoryBleL2capChannel(incoming = pipeToCentral, outgoing = pipeToPeripheral)

        val hal = object : BleCentralHal {
            lateinit var cb: BleCentralHal.Callback
            override var isConnected: Boolean = false

            override fun setCallback(callback: BleCentralHal.Callback) {
                cb = callback
            }

            override fun waitForPowerOn() {
                cb.onPowerOn(null)
            }

            override fun startScan(serviceUuid: UUID) {
                scanStarts++
                // Only discover on the 2nd scan attempt (1st scan times out)
                if (scanStarts >= 2) {
                    cb.onPeripheralDiscovered()
                }
            }

            override fun stopScan() {}
            override fun onScanStoppedAfterDiscovery() {}

            override fun connectToPeripheral() {
                connectAttempts++
                // Fail first 2 connect attempts via onDisconnected, succeed on 3rd.
                // On Android, a failed BluetoothGatt connection attempt can fire
                // onConnectionStateChange(..., STATE_DISCONNECTED) twice in quick succession
                // (e.g. status=147 followed by status=133 when unregistering). Fire
                // onDisconnected() twice on attempt 1 to verify the duplicate callback
                // is ignored while connectToPeripheral() is retrying.
                if (connectAttempts == 1) {
                    cb.onDisconnected()
                    cb.onDisconnected()
                } else if (connectAttempts == 2) {
                    cb.onDisconnected()
                } else {
                    isConnected = true
                    cb.onConnected()
                }
            }

            override fun requestMtu(mtu: Int) {
                cb.onMtuChanged(250, null)
            }

            override fun discoverServices(serviceUuid: UUID) {
                discoverServicesAttempts++
                if (discoverServicesAttempts == 1) {
                    cb.onServicesDiscovered(hasTargetService = false, error = IllegalStateException("GATT busy"))
                } else if (discoverServicesAttempts == 2) {
                    cb.onServicesDiscovered(hasTargetService = false, error = null)
                } else {
                    cb.onServicesDiscovered(hasTargetService = true, error = null)
                }
            }

            override fun discoverCharacteristics(
                stateCharacteristicUuid: UUID,
                client2ServerCharacteristicUuid: UUID,
                server2ClientCharacteristicUuid: UUID,
                identCharacteristicUuid: UUID?,
                l2capCharacteristicUuid: UUID?
            ) {
                cb.onCharacteristicsDiscovered(maxWriteSize = null, hasL2capCharacteristic = false, error = null)
            }

            override fun readCharacteristic(uuid: UUID) {}
            override fun enableNotifications(characteristicUuid: UUID) {}
            override fun writeCharacteristic(uuid: UUID, value: ByteArray) {}

            override fun connectL2cap(psm: Int) {
                l2capAttempts++
                if (l2capAttempts < 3) {
                    cb.onL2capConnected(null, IllegalStateException("L2CAP connect failed"))
                } else {
                    cb.onL2capConnected(fakeL2capChannel, null)
                }
            }

            override fun closeConnectedL2capAttempt() {}
            override fun close() {
                fakeL2capChannel.close()
            }
        }

        val manager = BleCentralManagerImpl(
            hal = hal,
            scanRestartTimeout = 100.milliseconds,
            postScanDelay = 10.milliseconds,
            maxConnectRetries = 5,
            connectRetryDelay = 10.milliseconds,
            maxDiscoverServicesRetries = 5,
            discoverServicesRetryDelay = 10.milliseconds,
            l2capConnectTimeout = 100.milliseconds,
            maxL2capConnectRetries = 5,
            l2capRetryInitialDelay = 10.milliseconds,
            l2capRetryMaxDelay = 50.milliseconds
        )
        var unexpectedError: Throwable? = null
        manager.setCallbacks(onError = { unexpectedError = it }, onClosed = {})
        manager.setUuids(
            stateCharacteristicUuid = UUID(1UL, 1UL),
            client2ServerCharacteristicUuid = UUID(1UL, 2UL),
            server2ClientCharacteristicUuid = UUID(1UL, 3UL),
            identCharacteristicUuid = null,
            l2capUuid = null
        )

        manager.waitForPowerOn()
        manager.waitForPeripheralWithUuid(serviceUuid)
        assertEquals(2, scanStarts)

        manager.connectToPeripheral()
        assertEquals(3, connectAttempts)
        assertNull(unexpectedError)

        manager.requestMtu()
        assertEquals(247, manager.maxCharacteristicSize)

        manager.peripheralDiscoverServices(serviceUuid)
        assertEquals(3, discoverServicesAttempts)

        manager.peripheralDiscoverCharacteristics()

        manager.connectL2cap(128)
        assertEquals(3, l2capAttempts)
        assertTrue(manager.usingL2cap)

        manager.close()
    }

    /**
     * Verifies error handling in [BleCentralManagerImpl] and [BlePeripheralManagerImpl] for invalid MTU,
     * malformed incoming GATT chunks, and reading `Ident` before `setESenderKey` is called.
     */
    @Test
    fun errorConditionsOnMtuInvalidChunksAndUnsetIdent() = runTest {
        val stateUuid = UUID(1UL, 1UL)
        val c2sUuid = UUID(1UL, 2UL)
        val s2cUuid = UUID(1UL, 3UL)
        val identUuid = UUID(1UL, 4UL)

        val bridge = PairedBleHalBridge(negotiatedMtu = 15) // MTU < 22 is invalid!
        val centralManager = BleCentralManagerImpl(hal = bridge.centralHal, postScanDelay = Duration.ZERO)
        var centralError: Throwable? = null
        centralManager.setCallbacks(onError = { centralError = it }, onClosed = {})
        centralManager.setUuids(stateUuid, c2sUuid, s2cUuid, identUuid, null)

        assertFailsWith<IllegalStateException> {
            centralManager.requestMtu()
        }

        // Malformed empty chunk and invalid header byte (0x05) on central's Server2Client characteristic
        bridge.centralHal.halCallback.onCharacteristicChanged(s2cUuid, byteArrayOf())
        assertNotNull(centralError)
        centralError = null
        bridge.centralHal.halCallback.onCharacteristicChanged(s2cUuid, byteArrayOf(0x05, 0x01))
        assertNotNull(centralError)

        // Peripheral receiving read request for Ident before setESenderKey() is called triggers onError
        val peripheralManager = BlePeripheralManagerImpl(hal = bridge.peripheralHal)
        var peripheralError: Throwable? = null
        peripheralManager.setCallbacks(onError = { peripheralError = it }, onClosed = {})
        peripheralManager.setUuids(stateUuid, c2sUuid, s2cUuid, identUuid, null, false)

        // Default maxCharacteristicSize when MTU is not negotiated is 20 (23 - 3)
        assertEquals(20, peripheralManager.maxCharacteristicSize)

        bridge.peripheralHal.halCallback.onCharacteristicReadRequest(1, identUuid)
        assertNotNull(peripheralError)
        peripheralError = null

        // Malformed chunk on peripheral's Client2Server characteristic triggers onError
        bridge.peripheralHal.halCallback.onCharacteristicWriteRequest(c2sUuid, byteArrayOf(0x09, 0x01), null)
        assertNotNull(peripheralError)

        centralManager.close()
        peripheralManager.close()
    }

    /**
     * Verifies that [BleCentralManagerImpl] throws [IllegalStateException] once `maxConnectRetries`,
     * `maxDiscoverServicesRetries`, or `maxL2capConnectRetries` are exhausted, and that L2CAP connection
     * timeouts invoke [BleCentralHal.closeConnectedL2capAttempt] on each timed-out attempt.
     */
    @Test
    fun retryExhaustionAndL2capConnectTimeout() = runTest {
        var connectAttempts = 0
        var discoverServicesAttempts = 0
        var l2capAttempts = 0
        var closeL2capAttemptCalls = 0

        val hal = object : BleCentralHal {
            lateinit var cb: BleCentralHal.Callback
            override var isConnected: Boolean = false

            override fun setCallback(callback: BleCentralHal.Callback) {
                cb = callback
            }

            override fun waitForPowerOn() {
                cb.onPowerOn(null)
            }

            override fun startScan(serviceUuid: UUID) {
                cb.onPeripheralDiscovered()
            }

            override fun stopScan() {}
            override fun onScanStoppedAfterDiscovery() {}

            override fun connectToPeripheral() {
                connectAttempts++
                cb.onDisconnected()
            }

            override fun requestMtu(mtu: Int) {
                cb.onMtuChanged(250, null)
            }

            override fun discoverServices(serviceUuid: UUID) {
                discoverServicesAttempts++
                cb.onServicesDiscovered(hasTargetService = false, error = null)
            }

            override fun discoverCharacteristics(
                stateCharacteristicUuid: UUID,
                client2ServerCharacteristicUuid: UUID,
                server2ClientCharacteristicUuid: UUID,
                identCharacteristicUuid: UUID?,
                l2capCharacteristicUuid: UUID?
            ) {
                cb.onCharacteristicsDiscovered(maxWriteSize = null, hasL2capCharacteristic = false, error = null)
            }

            override fun readCharacteristic(uuid: UUID) {}
            override fun enableNotifications(characteristicUuid: UUID) {}
            override fun writeCharacteristic(uuid: UUID, value: ByteArray) {}

            override fun connectL2cap(psm: Int) {
                l2capAttempts++
                // Do not call cb.onL2capConnected so that withTimeoutOrNull(l2capConnectTimeout) expires!
            }

            override fun closeConnectedL2capAttempt() {
                closeL2capAttemptCalls++
            }

            override fun close() {}
        }

        val manager = BleCentralManagerImpl(
            hal = hal,
            postScanDelay = Duration.ZERO,
            maxConnectRetries = 2,
            connectRetryDelay = 10.milliseconds,
            maxDiscoverServicesRetries = 2,
            discoverServicesRetryDelay = 10.milliseconds,
            l2capConnectTimeout = 50.milliseconds,
            maxL2capConnectRetries = 3,
            l2capRetryInitialDelay = 10.milliseconds,
            l2capRetryMaxDelay = 30.milliseconds
        )
        manager.setCallbacks(onError = {}, onClosed = {})
        manager.setUuids(
            stateCharacteristicUuid = UUID(1UL, 1UL),
            client2ServerCharacteristicUuid = UUID(1UL, 2UL),
            server2ClientCharacteristicUuid = UUID(1UL, 3UL),
            identCharacteristicUuid = null,
            l2capUuid = null
        )

        // 1 initial attempt + 2 retries = 3 attempts before throwing
        val connectError = assertFailsWith<IllegalStateException> {
            manager.connectToPeripheral()
        }
        assertEquals("Failed connecting to peripheral", connectError.message)
        assertEquals(3, connectAttempts)

        // 1 initial attempt + 2 retries = 3 attempts before throwing
        val discoverError = assertFailsWith<IllegalStateException> {
            manager.peripheralDiscoverServices(serviceUuid)
        }
        assertEquals("No service with the given UUID", discoverError.message)
        assertEquals(3, discoverServicesAttempts)

        // 3 timed-out L2CAP attempts before throwing; each attempt calls closeConnectedL2capAttempt()
        val l2capError = assertFailsWith<IllegalStateException> {
            manager.connectL2cap(128)
        }
        assertEquals("Failed L2CAP connection after 3 attempts", l2capError.message)
        assertEquals(3, l2capAttempts)
        assertEquals(3, closeL2capAttemptCalls)
        assertFalse(manager.usingL2cap)

        manager.close()
    }

    /**
     * Verifies that when an open [BleL2capChannel] hits EOF (`read()` returns `-1`) mid-session,
     * [readFromL2capChannel] invokes `onError` and transitions the transport to [MdocTransport.State.FAILED].
     */
    @Test
    fun l2capMidSessionEofTriggersOnErrorAndFailsTransport() = runTest {
        withContext(Dispatchers.Default) {
            val bridge = PairedBleHalBridge(publishedL2capPsm = 192)
            val peripheralManager = BlePeripheralManagerImpl(hal = bridge.peripheralHal)
            val centralManager = BleCentralManagerImpl(
                hal = bridge.centralHal,
                postScanDelay = Duration.ZERO
            )

            val mdoc = BleTransportPeripheralMdoc(
                role = MdocRole.MDOC,
                options = MdocTransportOptions(bleUseL2CAP = true),
                peripheralManager = peripheralManager,
                uuid = serviceUuid
            )
            val reader = BleTransportPeripheralMdocReader(
                role = MdocRole.MDOC_READER,
                options = MdocTransportOptions(bleUseL2CAP = true),
                centralManager = centralManager,
                uuid = serviceUuid,
                psm = null
            )

            mdoc.advertise()
            val mdocOpenJob = async(start = CoroutineStart.UNDISPATCHED) { mdoc.open(eDeviceKey) }
            val readerOpenJob = async { reader.open(eDeviceKey) }
            mdocOpenJob.await()
            readerOpenJob.await()

            val msg = byteArrayOf(1, 2, 3)
            reader.sendMessage(msg)
            assertContentEquals(msg, withTimeout(5.seconds) { mdoc.waitForMessage() })

            // Simulate remote L2CAP socket closing while mdoc is waiting for the next message
            supervisorScope {
                val waitJob = async(start = CoroutineStart.UNDISPATCHED) { mdoc.waitForMessage() }
                bridge.lastCentralL2capChannel?.close()
                assertFailsWith<MdocTransportException> {
                    withTimeout(5.seconds) { waitJob.await() }
                }
            }
            assertEquals(MdocTransport.State.FAILED, mdoc.state.value)

            // Calling waitForMessage() when already in State.FAILED also throws MdocTransportException
            assertFailsWith<MdocTransportException> {
                mdoc.waitForMessage()
            }

            mdoc.close()
            reader.close()
        }
    }

    /**
     * Verifies that [BleCentralManagerImpl] rejects a non-4-byte L2CAP PSM characteristic payload,
     * and that both [BleCentralManagerImpl] and [BlePeripheralManagerImpl] apply and cap (at 512 bytes)
     * the platform-reported `maxWriteSize` / `maxWriteSizeForCentral` (used on iOS where CoreBluetooth
     * reports maximum write length via `CBPeripheral` / `CBCentral`).
     */
    @Test
    fun malformedL2capPsmAndMaxWriteSizeOverrides() = runTest {
        val stateUuid = UUID(1UL, 1UL)
        val c2sUuid = UUID(1UL, 2UL)
        val s2cUuid = UUID(1UL, 3UL)
        val l2capUuid = UUID(1UL, 5UL)

        var reportedMaxWriteSize: Int? = 180
        var l2capReadPayload: ByteArray = byteArrayOf(0x01, 0x02) // Invalid 2-byte PSM (must be 4 bytes)

        val centralHal = object : BleCentralHal {
            lateinit var cb: BleCentralHal.Callback
            override var isConnected: Boolean = true

            override fun setCallback(callback: BleCentralHal.Callback) {
                cb = callback
            }

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
                l2capCharacteristicUuid: UUID?
            ) {
                cb.onCharacteristicsDiscovered(
                    maxWriteSize = reportedMaxWriteSize,
                    hasL2capCharacteristic = true,
                    error = null
                )
            }

            override fun readCharacteristic(uuid: UUID) {
                cb.onCharacteristicRead(uuid, l2capReadPayload, null)
            }

            override fun enableNotifications(characteristicUuid: UUID) {}
            override fun writeCharacteristic(uuid: UUID, value: ByteArray) {}
            override fun connectL2cap(psm: Int) {}
            override fun closeConnectedL2capAttempt() {}
            override fun close() {}
        }

        val centralManager = BleCentralManagerImpl(hal = centralHal, postScanDelay = Duration.ZERO)
        centralManager.setCallbacks(onError = {}, onClosed = {})
        centralManager.setUuids(stateUuid, c2sUuid, s2cUuid, null, l2capUuid)

        // 1. 2-byte L2CAP PSM payload throws IllegalStateException
        val psmError = assertFailsWith<IllegalStateException> {
            centralManager.peripheralDiscoverCharacteristics()
        }
        assertEquals("onCharacteristicRead: Expected four bytes for PSM, got 2", psmError.message)
        assertEquals(180, centralManager.maxCharacteristicSize)

        // 2. Valid 4-byte PSM and maxWriteSize > 512 is capped at 512
        reportedMaxWriteSize = 1024
        l2capReadPayload = encodeL2capPsm(256)
        centralManager.peripheralDiscoverCharacteristics()
        assertEquals(512, centralManager.maxCharacteristicSize)
        assertEquals(256, centralManager.l2capPsm)

        // 3. Peripheral maxWriteSizeForCentral override and 512-byte cap
        val bridge = PairedBleHalBridge()
        val peripheralManager1 = BlePeripheralManagerImpl(hal = bridge.peripheralHal)
        peripheralManager1.setCallbacks(onError = {}, onClosed = {})
        peripheralManager1.setUuids(stateUuid, c2sUuid, s2cUuid, null, null, false)
        bridge.peripheralHal.halCallback.onCharacteristicWriteRequest(
            uuid = stateUuid,
            value = byteArrayOf(BleTransportConstants.STATE_CHARACTERISTIC_START.toByte()),
            maxWriteSizeForCentral = 220
        )
        assertEquals(220, peripheralManager1.maxCharacteristicSize)

        val peripheralManager2 = BlePeripheralManagerImpl(hal = bridge.peripheralHal)
        peripheralManager2.setCallbacks(onError = {}, onClosed = {})
        peripheralManager2.setUuids(stateUuid, c2sUuid, s2cUuid, null, null, false)
        val waitJob = async { peripheralManager2.waitForStateCharacteristicWriteOrL2CAPClient() }
        testScheduler.runCurrent()
        bridge.peripheralHal.halCallback.onCharacteristicWriteRequest(
            uuid = stateUuid,
            value = byteArrayOf(BleTransportConstants.STATE_CHARACTERISTIC_START.toByte()),
            maxWriteSizeForCentral = 900
        )
        waitJob.await()
        assertEquals(512, peripheralManager2.maxCharacteristicSize)

        centralManager.close()
        peripheralManager1.close()
        peripheralManager2.close()
    }

    /**
     * Verifies that if the OS Bluetooth stack fails to deliver [BlePeripheralHal.Callback.onMtuChanged]
     * to [BlePeripheralManagerImpl] (leaving `negotiatedMtu == -1`), receiving a `Client2Server`
     * write larger than the 20-byte default payload infers the negotiated MTU (`chunk.size + 3`)
     * and updates `maxCharacteristicSize` for both incoming chunk validation and subsequent
     * `Server2Client` notifications.
     */
    @Test
    fun peripheralInfersMtuFromClient2ServerWriteWhenMtuCallbackMissed() = runTest {
        val stateUuid = UUID(1UL, 1UL)
        val c2sUuid = UUID(1UL, 2UL)
        val s2cUuid = UUID(1UL, 3UL)

        val bridge = PairedBleHalBridge()
        val peripheralManager = BlePeripheralManagerImpl(hal = bridge.peripheralHal)
        peripheralManager.setCallbacks(onError = {}, onClosed = {})
        peripheralManager.setUuids(stateUuid, c2sUuid, s2cUuid, null, null, false)

        // Before any MTU callback or large write, maxCharacteristicSize defaults to 20 (MTU 23 - 3)
        assertEquals(20, peripheralManager.maxCharacteristicSize)

        // A 512-byte continuation chunk (0x01 + 511 payload bytes) arrives on Client2Server
        val firstChunk = ByteArray(512) { if (it == 0) 0x01 else 0x42 }
        val finalChunk = ByteArray(100) { if (it == 0) 0x00 else 0x43 }
        bridge.peripheralHal.halCallback.onCharacteristicWriteRequest(
            uuid = c2sUuid,
            value = firstChunk,
            maxWriteSizeForCentral = null
        )
        assertEquals(512, peripheralManager.maxCharacteristicSize)

        bridge.peripheralHal.halCallback.onCharacteristicWriteRequest(
            uuid = c2sUuid,
            value = finalChunk,
            maxWriteSizeForCentral = null
        )
        val received = withTimeout(5.seconds) { peripheralManager.incomingMessages.receive() }
        assertEquals(511 + 99, received.size)

        peripheralManager.close()
    }

    /**
     * In-memory [BleL2capChannel] backed by a pair of coroutine [Channel]s.
     *
     * To test stream reassembly in [readNOctets], [read] returns at most [maxBytesPerRead] bytes per
     * call even when a larger packet is waiting in [incoming], mimicking how real OS L2CAP streams
     * (`BluetoothSocket.inputStream` on Android or `NSInputStream` on iOS) can deliver arbitrary
     * partial chunks across multiple reads.
     */
    private class InMemoryBleL2capChannel(
        private val incoming: Channel<ByteArray>,
        private val outgoing: Channel<ByteArray>,
        private val maxBytesPerRead: Int = 17
    ) : BleL2capChannel {
        private var currentBuffer = ByteArray(0)
        private var currentOffset = 0

        override suspend fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            while (currentOffset >= currentBuffer.size) {
                val next = incoming.receiveCatching().getOrNull() ?: return -1
                currentBuffer = next
                currentOffset = 0
            }
            val toCopy = min(min(length, currentBuffer.size - currentOffset), maxBytesPerRead)
            currentBuffer.copyInto(buffer, destinationOffset = offset, startIndex = currentOffset, endIndex = currentOffset + toCopy)
            currentOffset += toCopy
            return toCopy
        }

        override suspend fun write(data: ByteArray) {
            outgoing.send(data.copyOf())
        }

        override fun close() {
            incoming.close()
            outgoing.close()
        }
    }

    /**
     * Simulated in-memory Bluetooth radio link that connects a [BleCentralHal] ([centralHal]) and a
     * [BlePeripheralHal] ([peripheralHal]) directly to each other:
     * - When the peripheral starts advertising a service UUID, the central's scan for that UUID
     *   discovers it via [BleCentralHal.Callback.onPeripheralDiscovered].
     * - When the central requests an MTU, the bridge clamps it to [negotiatedMtu] and notifies both
     *   the peripheral and central callbacks.
     * - Central characteristic reads (`Ident`, `L2CAP`) are forwarded to the peripheral via
     *   [BlePeripheralHal.Callback.onCharacteristicReadRequest], and the peripheral's response in
     *   [BlePeripheralHal.sendReadResponse] is delivered back to the central via
     *   [BleCentralHal.Callback.onCharacteristicRead].
     * - Central characteristic writes (`State`, `Client2Server`) are forwarded to the peripheral via
     *   [BlePeripheralHal.Callback.onCharacteristicWriteRequest] and acknowledged via
     *   [BleCentralHal.Callback.onCharacteristicWrite].
     * - Peripheral characteristic notifications (`State`, `Server2Client`) are delivered to the
     *   central via [BleCentralHal.Callback.onCharacteristicChanged] and acknowledged via
     *   [BlePeripheralHal.Callback.onNotificationSent].
     * - When the central opens an L2CAP connection, two cross-wired [InMemoryBleL2capChannel]
     *   instances are created and delivered to [BlePeripheralHal.Callback.onL2capClientConnected]
     *   and [BleCentralHal.Callback.onL2capConnected].
     *
     * @param negotiatedMtu The maximum MTU supported by the simulated link.
     * @param publishedL2capPsm The PSM value assigned when the peripheral publishes an L2CAP channel.
     */
    private class PairedBleHalBridge(
        private val negotiatedMtu: Int = 515,
        private val publishedL2capPsm: Int = 128
    ) {
        var advertisedServiceUuid: UUID? = null
        val registeredCharacteristics = mutableSetOf<UUID>()
        /** Raw GATT chunks written by the central to the `Client2Server` data characteristic. */
        val centralToPeripheralCharacteristicWrites = mutableListOf<ByteArray>()
        /** Raw GATT chunks notified by the peripheral on the `Server2Client` data characteristic. */
        val peripheralToCentralNotifications = mutableListOf<ByteArray>()
        var lastCentralL2capChannel: BleL2capChannel? = null
        private var nextReadRequestId = 1

        val centralHal = CentralSideHal()
        val peripheralHal = PeripheralSideHal()

        /** [BleCentralHal] endpoint of the [PairedBleHalBridge]. */
        inner class CentralSideHal : BleCentralHal {
            lateinit var halCallback: BleCentralHal.Callback
            override var isConnected: Boolean = false

            override fun setCallback(callback: BleCentralHal.Callback) {
                this.halCallback = callback
            }

            override fun waitForPowerOn() {
                halCallback.onPowerOn(null)
            }

            override fun startScan(serviceUuid: UUID) {
                if (advertisedServiceUuid == serviceUuid) {
                    halCallback.onPeripheralDiscovered()
                }
            }

            override fun stopScan() {}
            override fun onScanStoppedAfterDiscovery() {}

            override fun connectToPeripheral() {
                isConnected = true
                halCallback.onConnected()
            }

            override fun requestMtu(mtu: Int) {
                val effectiveMtu = min(mtu, negotiatedMtu)
                if (effectiveMtu >= 22) {
                    peripheralHal.halCallback.onMtuChanged(effectiveMtu)
                }
                halCallback.onMtuChanged(effectiveMtu, null)
            }

            override fun discoverServices(serviceUuid: UUID) {
                halCallback.onServicesDiscovered(hasTargetService = (advertisedServiceUuid == serviceUuid), error = null)
            }

            override fun discoverCharacteristics(
                stateCharacteristicUuid: UUID,
                client2ServerCharacteristicUuid: UUID,
                server2ClientCharacteristicUuid: UUID,
                identCharacteristicUuid: UUID?,
                l2capCharacteristicUuid: UUID?
            ) {
                val hasL2cap = l2capCharacteristicUuid != null && registeredCharacteristics.contains(l2capCharacteristicUuid)
                halCallback.onCharacteristicsDiscovered(
                    maxWriteSize = null,
                    hasL2capCharacteristic = hasL2cap,
                    error = null
                )
            }

            override fun readCharacteristic(uuid: UUID) {
                val reqId = nextReadRequestId++
                peripheralHal.pendingReadUuidByRequestId[reqId] = uuid
                peripheralHal.halCallback.onCharacteristicReadRequest(reqId, uuid)
            }

            override fun enableNotifications(characteristicUuid: UUID) {
                halCallback.onDescriptorWrite(characteristicUuid, null)
            }

            override fun writeCharacteristic(uuid: UUID, value: ByteArray) {
                if (uuid != UUID.fromString("00000001-a123-48ce-896b-4c76973373e6") &&
                    uuid != UUID.fromString("00000005-a123-48ce-896b-4c76973373e6")
                ) {
                    centralToPeripheralCharacteristicWrites.add(value.copyOf())
                }
                peripheralHal.halCallback.onCharacteristicWriteRequest(uuid, value.copyOf(), null)
                halCallback.onCharacteristicWrite(uuid, null)
            }

            override fun connectL2cap(psm: Int) {
                val c2p = Channel<ByteArray>(Channel.UNLIMITED)
                val p2c = Channel<ByteArray>(Channel.UNLIMITED)
                val centralChannel = InMemoryBleL2capChannel(incoming = p2c, outgoing = c2p)
                val peripheralChannel = InMemoryBleL2capChannel(incoming = c2p, outgoing = p2c)
                lastCentralL2capChannel = centralChannel
                peripheralHal.halCallback.onL2capClientConnected(peripheralChannel)
                halCallback.onL2capConnected(centralChannel, null)
            }

            override fun closeConnectedL2capAttempt() {}
            override fun close() {
                isConnected = false
            }
        }

        /** [BlePeripheralHal] endpoint of the [PairedBleHalBridge]. */
        inner class PeripheralSideHal : BlePeripheralHal {
            lateinit var halCallback: BlePeripheralHal.Callback
            val pendingReadUuidByRequestId = mutableMapOf<Int, UUID>()

            override fun setCallback(callback: BlePeripheralHal.Callback) {
                this.halCallback = callback
            }

            override fun waitForPowerOn() {
                halCallback.onPowerOn(null)
            }

            override fun publishL2capChannel() {
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
                registeredCharacteristics.clear()
                registeredCharacteristics.add(stateCharacteristicUuid)
                registeredCharacteristics.add(client2ServerCharacteristicUuid)
                registeredCharacteristics.add(server2ClientCharacteristicUuid)
                if (identCharacteristicUuid != null) {
                    registeredCharacteristics.add(identCharacteristicUuid)
                }
                if (l2capCharacteristicUuid != null) {
                    registeredCharacteristics.add(l2capCharacteristicUuid)
                }
                halCallback.onServiceAdded(null)
            }

            override fun startAdvertising(serviceUuid: UUID) {
                advertisedServiceUuid = serviceUuid
                halCallback.onAdvertisingStarted(null)
            }

            override fun stopAdvertising() {
                advertisedServiceUuid = null
            }

            override fun sendReadResponse(requestId: Int, statusSuccess: Boolean, value: ByteArray) {
                val uuid = pendingReadUuidByRequestId.remove(requestId) ?: return
                if (statusSuccess) {
                    centralHal.halCallback.onCharacteristicRead(uuid, value.copyOf(), null)
                } else {
                    centralHal.halCallback.onCharacteristicRead(uuid, null, IllegalStateException("GATT read failed"))
                }
            }

            override fun notifyCharacteristicChanged(uuid: UUID, value: ByteArray) {
                if (uuid != UUID.fromString("00000001-a123-48ce-896b-4c76973373e6") &&
                    uuid != UUID.fromString("00000005-a123-48ce-896b-4c76973373e6")
                ) {
                    peripheralToCentralNotifications.add(value.copyOf())
                }
                centralHal.halCallback.onCharacteristicChanged(uuid, value.copyOf())
                halCallback.onNotificationSent(uuid, null)
            }

            override fun close() {
                advertisedServiceUuid = null
            }
        }
    }
}
