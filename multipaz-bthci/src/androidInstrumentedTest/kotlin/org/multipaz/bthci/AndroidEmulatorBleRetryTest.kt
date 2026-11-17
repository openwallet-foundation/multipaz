package org.multipaz.bthci

import android.Manifest
import android.bluetooth.BluetoothManager
import android.os.Build
import android.os.ParcelFileDescriptor
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assume
import org.multipaz.context.applicationContext
import org.multipaz.context.initializeApplication
import org.multipaz.crypto.Crypto
import org.multipaz.crypto.EcCurve
import org.multipaz.mdoc.connectionmethod.BleAddressType
import org.multipaz.mdoc.connectionmethod.MdocConnectionMethod
import org.multipaz.mdoc.engagement.buildDeviceEngagement
import org.multipaz.mdoc.role.MdocRole
import org.multipaz.mdoc.transport.AndroidMdocTransportFactory
import org.multipaz.mdoc.transport.BleCentralConfig
import org.multipaz.mdoc.transport.MdocTransport
import org.multipaz.mdoc.transport.MdocTransportOptions
import org.multipaz.mdoc.transport.MdocTransportStats
import org.multipaz.mdoc.transport.TransportWarningCode
import org.multipaz.util.UUID
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * End-to-end integration and fault-injection stress tests combining the real Android OS Bluetooth
 * stack (`BleCentralHalAndroid` / `BlePeripheralHalAndroid`, `BluetoothGatt`, `BluetoothLeScanner`,
 * `BluetoothGattServer`, `BluetoothLeAdvertiser`, and `BluetoothSocket` L2CAP CoC) with
 * `multipaz-bthci` connected to the Android Emulator's `netsimd` / Rootcanal virtual controller
 * over TCP (`10.0.2.2:6402`).
 */
class AndroidEmulatorBleRetryTest {

    @BeforeTest
    fun setUp() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        initializeApplication(context)

        val permissions = buildList {
            add(Manifest.permission.ACCESS_FINE_LOCATION)
            add(Manifest.permission.ACCESS_COARSE_LOCATION)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                add(Manifest.permission.BLUETOOTH_SCAN)
                add(Manifest.permission.BLUETOOTH_ADVERTISE)
                add(Manifest.permission.BLUETOOTH_CONNECT)
            }
        }
        for (perm in permissions) {
            try {
                instrumentation.uiAutomation.grantRuntimePermission(context.packageName, perm)
            } catch (_: Exception) {
            }
        }

        val btManager = context.getSystemService(BluetoothManager::class.java)
        Assume.assumeTrue("BluetoothAdapter not present on device", btManager?.adapter != null)
        if (btManager.adapter.isEnabled != true) {
            execShellCommand("cmd bluetooth_manager enable")
            execShellCommand("cmd bluetooth_manager wait-for-state:STATE_ON")
        }
        Assume.assumeTrue("BluetoothAdapter is not enabled", btManager.adapter.isEnabled)

        val hciReachable = TcpHciPacketIo.isReachable(
            host = TcpHciPacketIo.EMULATOR_HOST_LOOPBACK,
            port = TcpHciPacketIo.DEFAULT_NETSIM_HCI_PORT,
        )
        Assume.assumeTrue(
            "netsimd HCI port ${TcpHciPacketIo.EMULATOR_HOST_LOOPBACK}:${TcpHciPacketIo.DEFAULT_NETSIM_HCI_PORT} " +
                "is not reachable; start emulator with -netsim-args \"--hci-port=6402\"",
            hciReachable
        )
    }

    private fun execShellCommand(cmd: String): String {
        val pfd = InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(cmd)
        return ParcelFileDescriptor.AutoCloseInputStream(pfd).bufferedReader().use { it.readText() }
    }

    private fun createNetsimHciFactory(
        faultConfig: BleHciFaultConfig = BleHciFaultConfig(),
        centralConfig: BleCentralConfig = BleCentralConfig(
            scanRestartTimeout = 1.seconds,
            postScanDelay = 10.milliseconds,
            connectRetryDelay = 150.milliseconds,
            discoverServicesRetryDelay = 150.milliseconds,
            l2capRetryInitialDelay = 150.milliseconds,
            l2capRetryMaxDelay = 600.milliseconds,
            requireL2capPeripheralConnectedFirst = true,
        )
    ): BtHciMdocTransportFactory = BtHciMdocTransportFactory(
        centralConfig = centralConfig,
        faultConfig = faultConfig,
        packetIoFactory = {
            TcpHciPacketIo.connect(
                host = TcpHciPacketIo.EMULATOR_HOST_LOOPBACK,
                port = TcpHciPacketIo.DEFAULT_NETSIM_HCI_PORT,
            )
        }
    )

    private suspend fun runAndroidCentralAgainstHciPeripheral(
        useL2cap: Boolean,
        centralClientMode: Boolean = false,
        usePeripheralAddress: Boolean = false,
        directL2capInEngagement: Boolean = false,
        faultConfig: BleHciFaultConfig = BleHciFaultConfig(),
        centralConfig: BleCentralConfig = BleCentralConfig(
            scanRestartTimeout = 700.milliseconds,
            connectRetryDelay = 150.milliseconds,
            discoverServicesRetryDelay = 150.milliseconds,
            l2capRetryInitialDelay = 150.milliseconds,
            l2capRetryMaxDelay = 600.milliseconds,
        ),
    ): Pair<MdocTransportStats, MdocTransportStats> = coroutineScope {
        val serviceUuid = UUID.randomUUID()
        val initialPeripheralMethod = when {
            centralClientMode && directL2capInEngagement ->
                MdocConnectionMethod.Ble.DirectL2capCentral(uuid = serviceUuid)
            centralClientMode ->
                MdocConnectionMethod.Ble.GattCentral(uuid = serviceUuid)
            directL2capInEngagement ->
                MdocConnectionMethod.Ble.DirectL2capPeripheral(uuid = serviceUuid)
            else ->
                MdocConnectionMethod.Ble.GattPeripheral(uuid = serviceUuid)
        }
        val eSenderKey = Crypto.createEcPrivateKey(EcCurve.P256).use {
            buildDeviceEngagement(it.publicKey) {}.eDeviceKeyBytes
        }

        val hciFactory = createNetsimHciFactory(faultConfig = faultConfig)
        val androidFactory = AndroidMdocTransportFactory(centralConfig = centralConfig)

        val hciPeripheral = hciFactory.createTransport(
            connectionMethod = initialPeripheralMethod,
            role = if (centralClientMode) MdocRole.MDOC_READER else MdocRole.MDOC,
            options = MdocTransportOptions(
                bleUseL2CAP = useL2cap,
                bleExportPeripheralAddress = usePeripheralAddress,
            ),
        )
        var androidCentral: MdocTransport? = null

        val requestPayload = ByteArray(1024) { (it and 0xFF).toByte() }
        val responsePayload = ByteArray(2048) { ((it * 7) and 0xFF).toByte() }

        try {
            hciPeripheral.advertise()
            val advertisedMethod = hciPeripheral.connectionMethod as MdocConnectionMethod.Ble
            if (usePeripheralAddress) {
                val addr = assertNotNull(advertisedMethod.peripheralAddress)
                assertEquals(BleAddressType.RANDOM, addr.addressType)
            } else {
                assertNull(advertisedMethod.peripheralAddress)
            }
            val createdAndroidCentral = androidFactory.createTransport(
                connectionMethod = advertisedMethod,
                role = if (centralClientMode) MdocRole.MDOC else MdocRole.MDOC_READER,
                options = MdocTransportOptions(
                    bleUseL2CAP = useL2cap,
                    bleUsePeripheralAddress = usePeripheralAddress,
                ),
            )
            androidCentral = createdAndroidCentral

            val peripheralJob = async(Dispatchers.Default) {
                hciPeripheral.open(eSenderKey)
                if (centralClientMode) {
                    hciPeripheral.sendMessage(requestPayload)
                    val rsp = hciPeripheral.waitForMessage()
                    assertContentEquals(responsePayload, rsp)
                } else {
                    val req = hciPeripheral.waitForMessage()
                    assertContentEquals(requestPayload, req)
                    hciPeripheral.sendMessage(responsePayload)
                }
            }
            val centralJob = async(Dispatchers.Default) {
                createdAndroidCentral.open(eSenderKey)
                if (centralClientMode) {
                    val req = createdAndroidCentral.waitForMessage()
                    assertContentEquals(requestPayload, req)
                    createdAndroidCentral.sendMessage(responsePayload)
                } else {
                    createdAndroidCentral.sendMessage(requestPayload)
                    val rsp = createdAndroidCentral.waitForMessage()
                    assertContentEquals(responsePayload, rsp)
                }
            }
            peripheralJob.await()
            centralJob.await()
            Pair(createdAndroidCentral.stats, hciPeripheral.stats)
        } finally {
            androidCentral?.close()
            hciPeripheral.close()
        }
    }

    private suspend fun runAndroidPeripheralAgainstHciCentral(
        useL2cap: Boolean,
        centralClientMode: Boolean = false,
    ): Pair<MdocTransportStats, MdocTransportStats> = coroutineScope {
        val serviceUuid = UUID.randomUUID()
        val connectionMethod = if (centralClientMode) {
            MdocConnectionMethod.Ble.GattCentral(uuid = serviceUuid)
        } else {
            MdocConnectionMethod.Ble.GattPeripheral(uuid = serviceUuid)
        }
        val eSenderKey = Crypto.createEcPrivateKey(EcCurve.P256).use {
            buildDeviceEngagement(it.publicKey) {}.eDeviceKeyBytes
        }

        val hciFactory = createNetsimHciFactory()
        val androidFactory = AndroidMdocTransportFactory()

        val androidPeripheral = androidFactory.createTransport(
            connectionMethod = connectionMethod,
            role = if (centralClientMode) MdocRole.MDOC_READER else MdocRole.MDOC,
            options = MdocTransportOptions(bleUseL2CAP = useL2cap),
        )
        val hciCentral = hciFactory.createTransport(
            connectionMethod = connectionMethod,
            role = if (centralClientMode) MdocRole.MDOC else MdocRole.MDOC_READER,
            options = MdocTransportOptions(bleUseL2CAP = useL2cap),
        )

        val requestPayload = ByteArray(1024) { ((it * 3) and 0xFF).toByte() }
        val responsePayload = ByteArray(2048) { ((it * 11) and 0xFF).toByte() }

        try {
            androidPeripheral.advertise()
            val peripheralJob = async(Dispatchers.Default) {
                androidPeripheral.open(eSenderKey)
                if (centralClientMode) {
                    androidPeripheral.sendMessage(requestPayload)
                    val rsp = androidPeripheral.waitForMessage()
                    assertContentEquals(responsePayload, rsp)
                } else {
                    val req = androidPeripheral.waitForMessage()
                    assertContentEquals(requestPayload, req)
                    androidPeripheral.sendMessage(responsePayload)
                }
            }
            val centralJob = async(Dispatchers.Default) {
                hciCentral.open(eSenderKey)
                if (centralClientMode) {
                    val req = hciCentral.waitForMessage()
                    assertContentEquals(requestPayload, req)
                    hciCentral.sendMessage(responsePayload)
                } else {
                    hciCentral.sendMessage(requestPayload)
                    val rsp = hciCentral.waitForMessage()
                    assertContentEquals(responsePayload, rsp)
                }
            }
            peripheralJob.await()
            centralJob.await()
            Pair(androidPeripheral.stats, hciCentral.stats)
        } finally {
            hciCentral.close()
            androidPeripheral.close()
        }
    }

    @Test
    fun androidCentralRetriesAgainstHciPeripheralOverL2cap() = runBlocking {
        withTimeout(60.seconds) {
            val (centralStats, _) = runAndroidCentralAgainstHciPeripheral(
                useL2cap = true,
                faultConfig = BleHciFaultConfig(
                    initialAdvertisingDelay = 950.milliseconds,
                    disconnectMidHandshakeAttempts = 1,
                    failCharacteristicReadAttempts = 1,
                    failL2capConnectAttempts = 2,
                ),
            )
            assertEquals(true, centralStats.bleUsingL2cap)
            val scanRestarts = centralStats.bleNumScanRestarts ?: 0
            assertTrue(
                scanRestarts >= 1,
                "Expected >= 1 scan restart, got $scanRestarts"
            )
            assertEquals(1, centralStats.bleNumGattConnectRetries)
            assertEquals(1, centralStats.bleNumCharacteristicReadRetries)
            assertEquals(2, centralStats.bleNumL2capConnectRetries)
        }
    }

    @Test
    fun androidCentralRetriesAgainstHciPeripheralOverGatt() = runBlocking {
        withTimeout(60.seconds) {
            val (centralStats, _) = runAndroidCentralAgainstHciPeripheral(
                useL2cap = false,
                faultConfig = BleHciFaultConfig(
                    disconnectMidHandshakeAttempts = 1,
                    failServiceDiscoveryAttempts = 2,
                    failCccdWriteAttempts = 2,
                ),
            )
            assertEquals(false, centralStats.bleUsingL2cap)
            assertEquals(1, centralStats.bleNumGattConnectRetries)
            assertEquals(2, centralStats.bleNumServiceDiscoveryRetries)
            assertEquals(2, centralStats.bleNumDescriptorWriteRetries)
        }
    }

    @Test
    fun androidCentralClientModeRetriesAgainstHciPeripheralOverGatt() = runBlocking {
        withTimeout(60.seconds) {
            val (centralStats, _) = runAndroidCentralAgainstHciPeripheral(
                useL2cap = false,
                centralClientMode = true,
                faultConfig = BleHciFaultConfig(
                    disconnectMidHandshakeAttempts = 1,
                    failServiceDiscoveryAttempts = 1,
                    failCccdWriteAttempts = 1,
                ),
            )
            assertEquals(false, centralStats.bleUsingL2cap)
            assertEquals(1, centralStats.bleNumGattConnectRetries)
            assertEquals(1, centralStats.bleNumServiceDiscoveryRetries)
            assertEquals(1, centralStats.bleNumDescriptorWriteRetries)
        }
    }

    @Test
    fun androidPeripheralAgainstHciCentralOverL2cap() = runBlocking {
        withTimeout(60.seconds) {
            val (peripheralStats, centralStats) = runAndroidPeripheralAgainstHciCentral(
                useL2cap = true,
                centralClientMode = false,
            )
            assertEquals(true, peripheralStats.bleUsingL2cap)
            assertEquals(true, centralStats.bleUsingL2cap)
        }
    }

    @Test
    fun androidPeripheralAgainstHciCentralOverGatt() = runBlocking {
        withTimeout(60.seconds) {
            val (peripheralStats, centralStats) = runAndroidPeripheralAgainstHciCentral(
                useL2cap = false,
                centralClientMode = true,
            )
            assertEquals(false, peripheralStats.bleUsingL2cap)
            assertEquals(false, centralStats.bleUsingL2cap)
        }
    }

    @Test
    fun androidCentralWithPeripheralAddressSkipsScanOverGatt() = runBlocking {
        Assume.assumeTrue(
            "BluetoothAdapter.getRemoteLeDevice requires Android API 33+",
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
        )
        withTimeout(60.seconds) {
            for (centralClientMode in listOf(false, true)) {
                val (centralStats, peripheralStats) = runAndroidCentralAgainstHciPeripheral(
                    useL2cap = false,
                    centralClientMode = centralClientMode,
                    usePeripheralAddress = true,
                    directL2capInEngagement = false,
                )
                assertNull(centralStats.bleScanningTime)
                assertEquals(true, centralStats.bleUsedPeripheralAddress)
                assertNull(peripheralStats.bleUsedPeripheralAddress)
                assertEquals(0, centralStats.bleNumScanRestarts ?: 0)
                assertEquals(false, centralStats.bleUsingL2cap)
                assertEquals(false, peripheralStats.bleUsingL2cap)
            }
        }
    }

    @Test
    fun androidCentralWithPeripheralAddressSkipsScanOverDirectL2cap() = runBlocking {
        Assume.assumeTrue(
            "BluetoothAdapter.getRemoteLeDevice requires Android API 33+",
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
        )
        withTimeout(60.seconds) {
            for (centralClientMode in listOf(false, true)) {
                val (centralStats, peripheralStats) = runAndroidCentralAgainstHciPeripheral(
                    useL2cap = true,
                    centralClientMode = centralClientMode,
                    usePeripheralAddress = true,
                    directL2capInEngagement = true,
                )
                assertNull(centralStats.bleScanningTime)
                assertEquals(true, centralStats.bleUsedPeripheralAddress)
                assertNull(peripheralStats.bleUsedPeripheralAddress)
                assertEquals(0, centralStats.bleNumScanRestarts ?: 0)
                assertEquals(true, centralStats.bleL2capPsmInEngagement)
                assertEquals(true, peripheralStats.bleL2capPsmInEngagement)
                assertEquals(true, centralStats.bleUsingL2cap)
                assertEquals(true, peripheralStats.bleUsingL2cap)
            }
        }
    }
}
