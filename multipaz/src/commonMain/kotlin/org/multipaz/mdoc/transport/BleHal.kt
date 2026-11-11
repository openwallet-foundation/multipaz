package org.multipaz.mdoc.transport

import org.multipaz.mdoc.connectionmethod.MdocConnectionMethodBle
import org.multipaz.mdoc.role.MdocRole
import org.multipaz.util.UUID
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * Abstraction for an L2CAP channel used by BLE central and peripheral mdoc transports.
 */
interface BleL2capChannel {
    /**
     * Reads up to [length] bytes into [buffer] starting at [offset].
     *
     * @return the number of bytes read, or `-1` if the stream has reached end-of-file.
     */
    suspend fun read(buffer: ByteArray, offset: Int, length: Int): Int

    /**
     * Writes all bytes in [data] to the L2CAP channel and flushes it.
     */
    suspend fun write(data: ByteArray)

    /**
     * Closes the L2CAP channel.
     */
    fun close()
}

/**
 * Low-level BLE Central Hardware Abstraction Layer (HAL) used to back BLE mdoc transports.
 *
 * All ISO/IEC 18013-5 protocol state machines, continuation management, retry/backoff loops,
 * `BLEIdent` HKDF verification, GATT message chunking/reassembly, and L2CAP 4-byte length-prefixed
 * framing are handled by Multipaz's shared BLE central manager. Implementations of this interface
 * perform low-level Bluetooth central operations and forward asynchronous events to [Callback].
 */
interface BleCentralHal {
    /**
     * Asynchronous callbacks invoked by a [BleCentralHal] implementation.
     */
    interface Callback {
        /** Invoked when Bluetooth central power-on check completes (or fails if [error] is non-null). */
        fun onPowerOn(error: Throwable?)

        /** Invoked when a peripheral advertising the target service UUID has been discovered. */
        fun onPeripheralDiscovered()

        /** Invoked when BLE scanning fails with [error]. */
        fun onScanFailed(error: Throwable)

        /** Invoked when a BLE connection to the discovered peripheral has been established. */
        fun onConnected()

        /** Invoked when the BLE connection to the peripheral is lost or fails to establish. */
        fun onDisconnected()

        /** Invoked when GATT ATT MTU negotiation completes (or fails if [error] is non-null). */
        fun onMtuChanged(mtu: Int, error: Throwable?)

        /** Invoked when GATT primary service discovery completes. */
        fun onServicesDiscovered(hasTargetService: Boolean, error: Throwable?)

        /** Invoked when GATT characteristic discovery completes. */
        fun onCharacteristicsDiscovered(
            maxWriteSize: Int?,
            hasL2capCharacteristic: Boolean,
            error: Throwable?
        )

        /** Invoked when a GATT characteristic read completes. */
        fun onCharacteristicRead(uuid: UUID, value: ByteArray?, error: Throwable?)

        /** Invoked when a GATT characteristic write completes. */
        fun onCharacteristicWrite(uuid: UUID, error: Throwable?)

        /** Invoked when a GATT descriptor (CCCD) write completes. */
        fun onDescriptorWrite(characteristicUuid: UUID, error: Throwable?)

        /** Invoked when a GATT notification or indication is received for [uuid]. */
        fun onCharacteristicChanged(uuid: UUID, value: ByteArray)

        /** Invoked when an L2CAP Connection-Oriented Channel connection attempt completes. */
        fun onL2capConnected(channel: BleL2capChannel?, error: Throwable?)

        /** Invoked when an unrecoverable asynchronous transport error occurs. */
        fun onError(error: Throwable)
    }

    /**
     * Whether a connection to the peripheral has already been established.
     */
    val isConnected: Boolean

    /** Registers the [Callback] receiver for asynchronous BLE central events. */
    fun setCallback(callback: Callback)

    /** Checks or waits for the BLE central radio/stack to power on, reporting via [Callback.onPowerOn]. */
    fun waitForPowerOn()

    /** Starts scanning for peripherals advertising [serviceUuid]. */
    fun startScan(serviceUuid: UUID)

    /** Stops an active BLE scan. */
    fun stopScan()

    /** Notifies the HAL that scanning has been stopped after discovering the target peripheral. */
    fun onScanStoppedAfterDiscovery()

    /** Initiates a BLE connection to the discovered peripheral. */
    fun connectToPeripheral()

    /** Requests a GATT ATT MTU of [mtu] bytes. */
    fun requestMtu(mtu: Int)

    /** Discovers GATT services on the connected peripheral, checking for [serviceUuid]. */
    fun discoverServices(serviceUuid: UUID)

    /** Discovers the ISO/IEC 18013-5 GATT characteristics on the connected peripheral. */
    fun discoverCharacteristics(
        stateCharacteristicUuid: UUID,
        client2ServerCharacteristicUuid: UUID,
        server2ClientCharacteristicUuid: UUID,
        identCharacteristicUuid: UUID?,
        l2capCharacteristicUuid: UUID?
    )

    /** Reads the GATT characteristic with [uuid]. */
    fun readCharacteristic(uuid: UUID)

    /** Enables GATT notifications on [characteristicUuid] by writing its CCCD. */
    fun enableNotifications(characteristicUuid: UUID)

    /** Writes [value] (write without response) to the GATT characteristic with [uuid]. */
    fun writeCharacteristic(uuid: UUID, value: ByteArray)

    /** Initiates an L2CAP Connection-Oriented Channel to [psm] on the peripheral. */
    fun connectL2cap(psm: Int)

    /** Cleans up any partially established L2CAP channel attempt before retrying. */
    fun closeConnectedL2capAttempt()

    /** Gracefully shuts down active BLE central connections/controllers before [close]. */
    suspend fun shutdown() {}

    /** Immediately closes the HAL and releases any underlying resources. */
    fun close()
}

/**
 * Low-level BLE Peripheral Hardware Abstraction Layer (HAL) used to back BLE mdoc transports.
 *
 * All ISO/IEC 18013-5 protocol state machines, continuation management, `BLEIdent` HKDF calculation,
 * L2CAP PSM 4-byte big-endian encoding, GATT message chunking/reassembly, and L2CAP 4-byte
 * length-prefixed framing are handled by Multipaz's shared BLE peripheral manager. Implementations
 * of this interface perform low-level Bluetooth peripheral operations and forward asynchronous
 * events to [Callback].
 */
interface BlePeripheralHal {
    /**
     * Asynchronous callbacks invoked by a [BlePeripheralHal] implementation.
     */
    interface Callback {
        /** Invoked when Bluetooth peripheral power-on check completes (or fails if [error] is non-null). */
        fun onPowerOn(error: Throwable?)

        /** Invoked when an L2CAP server channel has been published with [psm] (or failed if [error] is non-null). */
        fun onL2capPublished(psm: Int?, error: Throwable?)

        /** Invoked when the GATT server service has been registered. */
        fun onServiceAdded(error: Throwable?)

        /** Invoked when BLE advertising has started. */
        fun onAdvertisingStarted(error: Throwable?)

        /** Invoked when the connected central negotiates a GATT ATT [mtu]. */
        fun onMtuChanged(mtu: Int)

        /** Invoked when a connected central requests to read characteristic [uuid]. */
        fun onCharacteristicReadRequest(requestId: Int, uuid: UUID)

        /** Invoked when a connected central writes [value] to characteristic [uuid]. */
        fun onCharacteristicWriteRequest(
            uuid: UUID,
            value: ByteArray,
            maxWriteSizeForCentral: Int?
        )

        /** Invoked when a GATT notification for [uuid] has been sent (or failed if [error] is non-null). */
        fun onNotificationSent(uuid: UUID, error: Throwable?)

        /** Invoked when a central connects to the published L2CAP [channel]. */
        fun onL2capClientConnected(channel: BleL2capChannel)

        /** Invoked when the connected central disconnects. */
        fun onCentralDisconnected()

        /** Invoked when an unrecoverable asynchronous transport error occurs. */
        fun onError(error: Throwable)
    }

    /** Registers the [Callback] receiver for asynchronous BLE peripheral events. */
    fun setCallback(callback: Callback)

    /** Checks or waits for the BLE peripheral radio/stack to power on, reporting via [Callback.onPowerOn]. */
    fun waitForPowerOn()

    /** Publishes an insecure L2CAP server channel, reporting its PSM via [Callback.onL2capPublished]. */
    fun publishL2capChannel()

    /** Closes any published L2CAP server channel and active L2CAP client channel. */
    fun closeL2capServer()

    /** Sets up the GATT server and registers the ISO/IEC 18013-5 service and characteristics. */
    fun setupGattServerAndAddService(
        serviceUuid: UUID,
        stateCharacteristicUuid: UUID,
        client2ServerCharacteristicUuid: UUID,
        server2ClientCharacteristicUuid: UUID,
        identCharacteristicUuid: UUID?,
        l2capCharacteristicUuid: UUID?,
        encodedL2capPsm: ByteArray?
    )

    /** Starts BLE advertising for [serviceUuid]. */
    fun startAdvertising(serviceUuid: UUID)

    /** Stops BLE advertising. */
    fun stopAdvertising()

    /** Sends a GATT read response for [requestId]. */
    fun sendReadResponse(requestId: Int, statusSuccess: Boolean, value: ByteArray)

    /** Sends a GATT notification with [value] for characteristic [uuid]. */
    fun notifyCharacteristicChanged(uuid: UUID, value: ByteArray)

    /** Gracefully shuts down active BLE peripheral connections/controllers before [close]. */
    suspend fun shutdown() {}

    /** Immediately closes the HAL and releases any underlying resources. */
    fun close()
}

/**
 * Timing, retry, and connection parameters for BLE Central operations when constructing a BLE
 * [MdocTransport] via [createBleMdocTransport].
 *
 * @property scanRestartTimeout if non-null, periodically stops and restarts BLE scanning after this
 *   duration while waiting for a peripheral, working around OS or controller scan stalls.
 * @property maxScanFailureRetries maximum number of additional scan attempts after [BleCentralHal.Callback.onScanFailed].
 * @property scanFailureRetryDelay delay before restarting a BLE scan after [BleCentralHal.Callback.onScanFailed].
 * @property postScanDelay delay inserted after stopping a BLE scan before initiating a connection.
 * @property maxConnectRetries maximum number of additional BLE connection attempts after an initial failure.
 * @property connectRetryDelay delay between BLE connection retry attempts.
 * @property maxDiscoverServicesRetries maximum number of additional GATT discovery/read/CCCD-write attempts.
 * @property discoverServicesRetryDelay delay between GATT retry attempts.
 * @property requireL2capPeripheralConnectedFirst if `true`, establishes a peripheral-level BLE connection
 *   before calling [BleCentralHal.connectL2cap] when the L2CAP PSM is provided in engagement.
 * @property l2capConnectTimeout per-attempt timeout for opening an L2CAP channel, or `null` for no timeout.
 * @property maxL2capConnectRetries maximum number of attempts to open the L2CAP channel before failing.
 * @property l2capRetryInitialDelay initial backoff delay before the second L2CAP connection attempt.
 * @property l2capRetryMaxDelay maximum backoff delay between L2CAP connection retry attempts.
 */
data class BleCentralConfig(
    val scanRestartTimeout: Duration? = 10.seconds,
    val maxScanFailureRetries: Int = 10,
    val scanFailureRetryDelay: Duration = 500.milliseconds,
    val postScanDelay: Duration = 150.milliseconds,
    val maxConnectRetries: Int = 10,
    val connectRetryDelay: Duration = 500.milliseconds,
    val maxDiscoverServicesRetries: Int = 10,
    val discoverServicesRetryDelay: Duration = 500.milliseconds,
    val requireL2capPeripheralConnectedFirst: Boolean = false,
    val l2capConnectTimeout: Duration? = 3.seconds,
    val maxL2capConnectRetries: Int = 10,
    val l2capRetryInitialDelay: Duration = 500.milliseconds,
    val l2capRetryMaxDelay: Duration = 4.seconds,
)

/**
 * Creates a BLE [MdocTransport] for [connectionMethod] and [role] backed by the given
 * [centralHalFactory] and [peripheralHalFactory].
 *
 * @param connectionMethod the BLE connection method (`CentralClientMode` or `PeripheralServerMode`).
 * @param role the mdoc role ([MdocRole.MDOC] or [MdocRole.MDOC_READER]).
 * @param options options for the [MdocTransport].
 * @param centralConfig retry, scan-restart, and L2CAP parameters used when this endpoint acts as BLE Central.
 * @param centralHalFactory factory invoked to create a [BleCentralHal] when this endpoint acts as BLE Central.
 * @param peripheralHalFactory factory invoked to create a [BlePeripheralHal] when this endpoint acts as BLE Peripheral.
 * @return an [MdocTransport] ready to advertise or open.
 * @throws IllegalArgumentException if [connectionMethod] enables both or neither of Central Client and
 *   Peripheral Server modes, or is missing its mode UUID.
 */
fun createBleMdocTransport(
    connectionMethod: MdocConnectionMethodBle,
    role: MdocRole,
    options: MdocTransportOptions = MdocTransportOptions(),
    centralConfig: BleCentralConfig = BleCentralConfig(),
    centralHalFactory: () -> BleCentralHal,
    peripheralHalFactory: () -> BlePeripheralHal,
): MdocTransport {
    if (connectionMethod.supportsCentralClientMode && connectionMethod.supportsPeripheralServerMode) {
        throw IllegalArgumentException(
            "Only Central Client or Peripheral Server mode is supported at one time, not both"
        )
    }
    if (!connectionMethod.supportsCentralClientMode && !connectionMethod.supportsPeripheralServerMode) {
        throw IllegalArgumentException(
            "Either Central Client or Peripheral Server mode must be enabled"
        )
    }
    fun buildCentralManager(): BleCentralManager = BleCentralManagerImpl(
        hal = centralHalFactory(),
        scanRestartTimeout = centralConfig.scanRestartTimeout,
        maxScanFailureRetries = centralConfig.maxScanFailureRetries,
        scanFailureRetryDelay = centralConfig.scanFailureRetryDelay,
        postScanDelay = centralConfig.postScanDelay,
        maxConnectRetries = centralConfig.maxConnectRetries,
        connectRetryDelay = centralConfig.connectRetryDelay,
        maxDiscoverServicesRetries = centralConfig.maxDiscoverServicesRetries,
        discoverServicesRetryDelay = centralConfig.discoverServicesRetryDelay,
        requireL2capPeripheralConnectedFirst = centralConfig.requireL2capPeripheralConnectedFirst,
        l2capConnectTimeout = centralConfig.l2capConnectTimeout,
        maxL2capConnectRetries = centralConfig.maxL2capConnectRetries,
        l2capRetryInitialDelay = centralConfig.l2capRetryInitialDelay,
        l2capRetryMaxDelay = centralConfig.l2capRetryMaxDelay,
    )
    fun buildPeripheralManager(): BlePeripheralManager = BlePeripheralManagerImpl(
        hal = peripheralHalFactory()
    )
    return if (connectionMethod.supportsCentralClientMode) {
        val uuid = requireNotNull(connectionMethod.centralClientModeUuid) {
            "centralClientModeUuid is required when supportsCentralClientMode is true"
        }
        when (role) {
            MdocRole.MDOC -> BleTransportCentralMdoc(
                role,
                options,
                buildCentralManager(),
                uuid,
                connectionMethod.peripheralServerModePsm
            )
            MdocRole.MDOC_READER -> BleTransportCentralMdocReader(
                role,
                options,
                buildPeripheralManager(),
                uuid
            )
        }
    } else {
        val uuid = requireNotNull(connectionMethod.peripheralServerModeUuid) {
            "peripheralServerModeUuid is required when supportsPeripheralServerMode is true"
        }
        when (role) {
            MdocRole.MDOC -> BleTransportPeripheralMdoc(
                role,
                options,
                buildPeripheralManager(),
                uuid
            )
            MdocRole.MDOC_READER -> BleTransportPeripheralMdocReader(
                role,
                options,
                buildCentralManager(),
                uuid,
                connectionMethod.peripheralServerModePsm
            )
        }
    }
}
