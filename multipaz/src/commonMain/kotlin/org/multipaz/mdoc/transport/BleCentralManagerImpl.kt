package org.multipaz.mdoc.transport

import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.io.bytestring.ByteStringBuilder
import kotlinx.io.bytestring.buildByteString
import org.multipaz.cbor.Bstr
import org.multipaz.cbor.Cbor
import org.multipaz.cbor.Tagged
import org.multipaz.crypto.Algorithm
import org.multipaz.crypto.EcPublicKey
import org.multipaz.crypto.Hkdf
import org.multipaz.crypto.SecretKey
import org.multipaz.util.Logger
import org.multipaz.util.UUID
import org.multipaz.util.appendByteArray
import org.multipaz.util.appendUInt32
import org.multipaz.util.getUInt32
import org.multipaz.util.toHex
import kotlin.concurrent.Volatile
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.math.min
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * Shared multiplatform implementation of [BleCentralManager] backed by a platform-specific [BleCentralHal].
 *
 * @param hal The platform-specific [BleCentralHal] implementation.
 * @param scanRestartTimeout If non-null, [waitForPeripheralWithUuid] stops and restarts the BLE scan
 *   each time this duration elapses without discovering the peripheral, working around OS scan-frequency
 *   throttling (such as Android's 5-scans-per-30-seconds limit). If `null`, a single continuous scan is
 *   performed until the peripheral is discovered or the coroutine is cancelled.
 * @param postScanDelay Delay inserted after stopping the BLE scan in [waitForPeripheralWithUuid] to give
 *   the Bluetooth radio time to transition out of the scanning state before initiating a connection.
 * @param maxConnectRetries Maximum number of additional connection attempts in [connectToPeripheral] if
 *   an initial connection attempt fails via [BleCentralHal.Callback.onDisconnected] (`0` disables retries).
 * @param connectRetryDelay Delay between connection retry attempts in [connectToPeripheral].
 * @param maxDiscoverServicesRetries Maximum number of additional service discovery attempts in
 *   [peripheralDiscoverServices] if the target service is not immediately found or discovery fails
 *   (`0` disables retries).
 * @param discoverServicesRetryDelay Delay between service discovery retry attempts in
 *   [peripheralDiscoverServices].
 * @param requireL2capPeripheralConnectedFirst If `true`, [connectL2cap] ensures a peripheral-level BLE
 *   connection is established via [connectToPeripheral] before calling [BleCentralHal.connectL2cap]
 *   (required by iOS `CBPeripheral.openL2CAPChannel`, whereas Android's
 *   `BluetoothDevice.createInsecureL2capChannel` connects directly without calling
 *   `BluetoothDevice.connectGatt` first when given the PSM in engagement).
 * @param l2capConnectTimeout Per-attempt timeout for opening an L2CAP channel in [connectL2cap], or
 *   `null` to rely solely on the OS L2CAP connection callback.
 * @param maxL2capConnectRetries Maximum number of attempts to open the L2CAP channel in [connectL2cap]
 *   before failing.
 * @param l2capRetryInitialDelay Initial backoff delay before the second L2CAP connection attempt in
 *   [connectL2cap]; doubles after each failed attempt up to [l2capRetryMaxDelay].
 * @param l2capRetryMaxDelay Maximum backoff delay between L2CAP connection retry attempts in
 *   [connectL2cap].
 */
internal class BleCentralManagerImpl(
    private val hal: BleCentralHal,
    private val scanRestartTimeout: Duration? = 10.seconds,
    private val postScanDelay: Duration = 150.milliseconds,
    private val maxConnectRetries: Int = 10,
    private val connectRetryDelay: Duration = 500.milliseconds,
    private val maxDiscoverServicesRetries: Int = 10,
    private val discoverServicesRetryDelay: Duration = 500.milliseconds,
    private val requireL2capPeripheralConnectedFirst: Boolean = false,
    private val l2capConnectTimeout: Duration? = 3.seconds,
    private val maxL2capConnectRetries: Int = 10,
    private val l2capRetryInitialDelay: Duration = 500.milliseconds,
    private val l2capRetryMaxDelay: Duration = 4.seconds,
) : BleCentralManager {
    companion object {
        private const val TAG = "BleCentralManagerImpl"
        private const val DEFAULT_REQUESTED_MTU = 515
        private const val MIN_ACCEPTABLE_MTU = 22
        private const val MAX_CHARACTERISTIC_SIZE_CAP = 512
    }

    private lateinit var stateCharacteristicUuid: UUID
    private lateinit var client2ServerCharacteristicUuid: UUID
    private lateinit var server2ClientCharacteristicUuid: UUID
    private var identCharacteristicUuid: UUID? = null
    private var l2capCharacteristicUuid: UUID? = null

    override fun setUuids(
        stateCharacteristicUuid: UUID,
        client2ServerCharacteristicUuid: UUID,
        server2ClientCharacteristicUuid: UUID,
        identCharacteristicUuid: UUID?,
        l2capUuid: UUID?
    ) {
        this.stateCharacteristicUuid = stateCharacteristicUuid
        this.client2ServerCharacteristicUuid = client2ServerCharacteristicUuid
        this.server2ClientCharacteristicUuid = server2ClientCharacteristicUuid
        this.identCharacteristicUuid = identCharacteristicUuid
        this.l2capCharacteristicUuid = l2capUuid
    }

    private lateinit var onError: (error: Throwable) -> Unit
    private lateinit var onClosed: () -> Unit

    override fun setCallbacks(
        onError: (error: Throwable) -> Unit,
        onClosed: () -> Unit
    ) {
        this.onError = onError
        this.onClosed = onClosed
    }

    internal enum class WaitState {
        POWER_ON,
        PERIPHERAL_DISCOVERED,
        CONNECT_TO_PERIPHERAL,
        REQUEST_MTU,
        PERIPHERAL_DISCOVER_SERVICES,
        PERIPHERAL_DISCOVER_CHARACTERISTICS,
        GET_READER_IDENT,
        GET_L2CAP_PSM,
        WRITE_TO_DESCRIPTOR,
        CHARACTERISTIC_WRITE_COMPLETED,
        OPEN_L2CAP_CHANNEL,
    }

    private data class WaitFor(
        val state: WaitState,
        val continuation: CancellableContinuation<Boolean>,
        val characteristicUuid: UUID? = null,
    )

    private var waitFor: WaitFor? = null

    private fun setWaitCondition(
        state: WaitState,
        continuation: CancellableContinuation<Boolean>,
        characteristicUuid: UUID? = null,
    ) {
        check(waitFor == null) { "waitFor is already set to ${waitFor?.state}" }
        waitFor = WaitFor(state, continuation, characteristicUuid)
        continuation.invokeOnCancellation {
            if (waitFor?.continuation === continuation) {
                waitFor = null
            }
        }
    }

    private fun clearWaitCondition() {
        waitFor = null
    }

    private fun resumeWait() {
        val continuation = waitFor!!.continuation
        waitFor = null
        continuation.resume(true)
    }

    private fun resumeWaitWithException(exception: Throwable) {
        val continuation = waitFor!!.continuation
        waitFor = null
        continuation.resumeWithException(exception)
    }

    override val incomingMessages = Channel<ByteArray>(Channel.UNLIMITED)

    private var incomingMessage = ByteStringBuilder()
    internal var maxCharacteristicSize = -1
    private var hasL2capCharacteristic = false
    private var expectedIdentValue: ByteArray? = null
    private var _l2capPsm: Int? = null
    private var l2capChannel: BleL2capChannel? = null
    private var isConnectingToPeripheral = false

    override val l2capPsm: Int?
        get() = _l2capPsm

    override val usingL2cap: Boolean
        get() = l2capChannel != null

    @Volatile
    private var _negotiatedMtu: Int? = null
    override val negotiatedMtu: Int?
        get() = _negotiatedMtu

    @Volatile
    private var _numScanRestarts: Int = 0
    override val numScanRestarts: Int
        get() = _numScanRestarts

    @Volatile
    private var _numGattConnectRetries: Int = 0
    override val numGattConnectRetries: Int
        get() = _numGattConnectRetries

    @Volatile
    private var _numServiceDiscoveryRetries: Int = 0
    override val numServiceDiscoveryRetries: Int
        get() = _numServiceDiscoveryRetries

    @Volatile
    private var _numL2capConnectRetries: Int = 0
    override val numL2capConnectRetries: Int
        get() = _numL2capConnectRetries

    @Volatile
    private var _numChunksSent: Int = 0
    override val numChunksSent: Int
        get() = _numChunksSent

    @Volatile
    private var _numChunksReceived: Int = 0
    override val numChunksReceived: Int
        get() = _numChunksReceived

    private val _warnings = MutableStateFlow<List<TransportWarning>>(emptyList())
    override val warnings: List<TransportWarning>
        get() = _warnings.value

    private fun addWarning(warning: TransportWarning) {
        _warnings.update { it + warning }
    }

    private class ConnectionFailedException(message: String, cause: Throwable? = null) : Exception(message, cause)
    private class ServiceDiscoveryFailedException(message: String, cause: Throwable? = null) : Exception(message, cause)

    private val halCallback = object : BleCentralHal.Callback {
        override fun onPowerOn(error: Throwable?) {
            if (waitFor?.state == WaitState.POWER_ON) {
                if (error != null) {
                    resumeWaitWithException(error)
                } else {
                    resumeWait()
                }
            } else {
                Logger.w(TAG, "onPowerOn callback but not waiting")
            }
        }

        override fun onPeripheralDiscovered() {
            try {
                if (waitFor?.state == WaitState.PERIPHERAL_DISCOVERED) {
                    resumeWait()
                } else {
                    Logger.w(TAG, "onPeripheralDiscovered but not waiting")
                }
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                onError(IllegalStateException("onPeripheralDiscovered failed", error))
            }
        }

        override fun onScanFailed(error: Throwable) {
            try {
                if (waitFor?.state == WaitState.PERIPHERAL_DISCOVERED) {
                    resumeWaitWithException(error)
                } else {
                    Logger.w(TAG, "onScanFailed but not waiting")
                }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                onError(IllegalStateException("onScanFailed failed", e))
            }
        }

        override fun onConnected() {
            try {
                if (waitFor?.state == WaitState.CONNECT_TO_PERIPHERAL) {
                    resumeWait()
                } else {
                    Logger.w(TAG, "onConnected but not waiting")
                }
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                onError(IllegalStateException("onConnected failed", error))
            }
        }

        override fun onDisconnected() {
            try {
                if (waitFor?.state == WaitState.CONNECT_TO_PERIPHERAL) {
                    resumeWaitWithException(ConnectionFailedException("Failed to connect to peripheral"))
                } else if (isConnectingToPeripheral) {
                    // On Android, a failed BluetoothGatt connection attempt can fire
                    // onConnectionStateChange(..., STATE_DISCONNECTED) twice in quick succession
                    // (e.g. status=147 followed a few milliseconds later by status=133 when
                    // BluetoothGatt internally calls unregisterApp()). By the time the second
                    // callback arrives, the first callback has already resumed
                    // WaitState.CONNECT_TO_PERIPHERAL and connectToPeripheral() is sleeping in
                    // delay(connectRetryDelay) before the next retry attempt. Ignore the duplicate
                    // callback so the retry loop can proceed instead of failing the transport.
                    Logger.w(TAG, "Ignoring duplicate onDisconnected callback while retrying connection")
                } else {
                    throw IllegalStateException("Peripheral unexpectedly disconnected")
                }
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                onError(IllegalStateException("onDisconnected failed", error))
            }
        }

        override fun onMtuChanged(mtu: Int, error: Throwable?) {
            try {
                if (waitFor?.state == WaitState.REQUEST_MTU) {
                    if (error != null) {
                        resumeWaitWithException(error)
                    } else if (mtu < MIN_ACCEPTABLE_MTU) {
                        resumeWaitWithException(IllegalStateException("Unexpected MTU size $mtu"))
                    } else {
                        _negotiatedMtu = mtu
                        if (mtu <= 23) {
                            addWarning(TransportWarning(TransportWarningCode.BLE_MTU_NOT_NEGOTIATED))
                        }
                        maxCharacteristicSize = min(mtu - 3, MAX_CHARACTERISTIC_SIZE_CAP)
                        Logger.i(TAG, "MTU is $mtu, using $maxCharacteristicSize as maximum data size for characteristics")
                        resumeWait()
                    }
                } else {
                    Logger.w(TAG, "onMtuChanged but not waiting")
                    addWarning(TransportWarning(TransportWarningCode.BLE_UNEXPECTED_CALLBACK))
                }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                onError(IllegalStateException("onMtuChanged failed", e))
            }
        }

        override fun onServicesDiscovered(hasTargetService: Boolean, error: Throwable?) {
            try {
                if (waitFor?.state == WaitState.PERIPHERAL_DISCOVER_SERVICES) {
                    if (error != null) {
                        resumeWaitWithException(ServiceDiscoveryFailedException(error.message ?: "Discover services failed", error))
                    } else if (!hasTargetService) {
                        resumeWaitWithException(ServiceDiscoveryFailedException("No service with the given UUID"))
                    } else {
                        resumeWait()
                    }
                } else {
                    Logger.w(TAG, "onServicesDiscovered but not waiting")
                    addWarning(TransportWarning(TransportWarningCode.BLE_UNEXPECTED_CALLBACK))
                }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                onError(IllegalStateException("onServicesDiscovered failed", e))
            }
        }

        override fun onCharacteristicsDiscovered(
            maxWriteSize: Int?,
            hasL2capCharacteristic: Boolean,
            error: Throwable?
        ) {
            if (waitFor?.state == WaitState.PERIPHERAL_DISCOVER_CHARACTERISTICS) {
                if (error != null) {
                    resumeWaitWithException(error)
                } else {
                    if (maxWriteSize != null) {
                        maxCharacteristicSize = min(maxWriteSize, MAX_CHARACTERISTIC_SIZE_CAP)
                        if (_negotiatedMtu == null) {
                            _negotiatedMtu = maxWriteSize + 3
                        }
                    }
                    this@BleCentralManagerImpl.hasL2capCharacteristic = hasL2capCharacteristic
                    resumeWait()
                }
            } else {
                Logger.w(TAG, "onCharacteristicsDiscovered but not waiting")
                addWarning(TransportWarning(TransportWarningCode.BLE_UNEXPECTED_CALLBACK))
            }
        }

        override fun onCharacteristicRead(uuid: UUID, value: ByteArray?, error: Throwable?) {
            try {
                if (uuid == l2capCharacteristicUuid) {
                    if (waitFor?.state == WaitState.GET_L2CAP_PSM) {
                        if (error != null) {
                            resumeWaitWithException(error)
                        } else if (value == null || value.size != 4) {
                            resumeWaitWithException(
                                IllegalStateException("onCharacteristicRead: Expected four bytes for PSM, got ${value?.size}")
                            )
                        } else {
                            _l2capPsm = value.getUInt32(0).toInt()
                            Logger.i(TAG, "L2CAP PSM is $_l2capPsm")
                            resumeWait()
                        }
                    } else {
                        Logger.w(TAG, "onCharacteristicRead for L2CAP PSM but not waiting")
                        addWarning(TransportWarning(TransportWarningCode.BLE_UNEXPECTED_CALLBACK))
                    }
                } else if (uuid == identCharacteristicUuid) {
                    if (waitFor?.state == WaitState.GET_READER_IDENT) {
                        if (error != null) {
                            resumeWaitWithException(error)
                        } else if (value != null && expectedIdentValue contentEquals value) {
                            resumeWait()
                        } else {
                            resumeWaitWithException(
                                IllegalStateException(
                                    "onCharacteristicRead: Expected ${expectedIdentValue?.toHex()} " +
                                            "for ident, got ${value?.toHex()} instead"
                                )
                            )
                        }
                    } else {
                        Logger.w(TAG, "onCharacteristicRead for ident but not waiting")
                        addWarning(TransportWarning(TransportWarningCode.BLE_UNEXPECTED_CALLBACK))
                    }
                }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                onError(IllegalStateException("onCharacteristicRead failed", e))
            }
        }

        override fun onCharacteristicWrite(uuid: UUID, error: Throwable?) {
            if (waitFor?.state == WaitState.CHARACTERISTIC_WRITE_COMPLETED) {
                if (error != null) {
                    resumeWaitWithException(error)
                } else {
                    resumeWait()
                }
            } else {
                Logger.w(TAG, "onCharacteristicWrite for characteristic $uuid but not waiting")
                addWarning(TransportWarning(TransportWarningCode.BLE_UNEXPECTED_CALLBACK))
            }
        }

        override fun onDescriptorWrite(characteristicUuid: UUID, error: Throwable?) {
            if (waitFor?.state == WaitState.WRITE_TO_DESCRIPTOR) {
                if (error != null) {
                    resumeWaitWithException(error)
                } else {
                    resumeWait()
                }
            } else {
                Logger.w(TAG, "onDescriptorWrite but not waiting")
                addWarning(TransportWarning(TransportWarningCode.BLE_UNEXPECTED_CALLBACK))
            }
        }

        override fun onCharacteristicChanged(uuid: UUID, value: ByteArray) {
            try {
                if (uuid == server2ClientCharacteristicUuid) {
                    handleIncomingData(value)
                } else if (uuid == stateCharacteristicUuid) {
                    if (value contentEquals byteArrayOf(BleTransportConstants.STATE_CHARACTERISTIC_END.toByte())) {
                        Logger.i(TAG, "Received transport-specific termination message")
                        incomingMessages.trySend(byteArrayOf())
                    } else {
                        Logger.w(TAG, "Ignoring unexpected write to state characteristic")
                        addWarning(TransportWarning(TransportWarningCode.BLE_UNEXPECTED_CALLBACK))
                    }
                }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                onError(IllegalStateException("onCharacteristicChanged failed", e))
            }
        }

        override fun onL2capConnected(channel: BleL2capChannel?, error: Throwable?) {
            if (waitFor?.state == WaitState.OPEN_L2CAP_CHANNEL) {
                if (error != null || channel == null) {
                    resumeWaitWithException(error ?: IllegalStateException("L2CAP channel is null"))
                } else {
                    l2capChannel = channel
                    resumeWait()
                }
            } else {
                Logger.w(TAG, "onL2capConnected but not waiting")
                addWarning(TransportWarning(TransportWarningCode.BLE_UNEXPECTED_CALLBACK))
            }
        }

        override fun onError(error: Throwable) {
            this@BleCentralManagerImpl.onError(error)
        }
    }

    init {
        hal.setCallback(halCallback)
    }

    private fun handleIncomingData(chunk: ByteArray) {
        if (chunk.isEmpty()) {
            throw IllegalStateException("Invalid data length ${chunk.size} for Server2Client characteristic")
        }
        _numChunksReceived += 1
        incomingMessage.append(chunk, 1, chunk.size)
        when (chunk[0].toInt()) {
            0x00 -> {
                val newMessage = incomingMessage.toByteString().toByteArray()
                incomingMessage = ByteStringBuilder()
                incomingMessages.trySend(newMessage)
            }
            0x01 -> {
                if (chunk.size != maxCharacteristicSize) {
                    Logger.w(
                        TAG,
                        "Server2Client received ${chunk.size} bytes which is not the " +
                                "expected $maxCharacteristicSize bytes"
                    )
                    addWarning(TransportWarning(TransportWarningCode.BLE_UNEXPECTED_CHUNK_SIZE))
                }
            }
            else -> {
                throw IllegalStateException(
                    "Invalid first byte ${chunk[0]} in Server2Client data chunk, expected 0 or 1"
                )
            }
        }
    }

    override suspend fun waitForPowerOn() {
        suspendCancellableCoroutine { continuation ->
            setWaitCondition(WaitState.POWER_ON, continuation)
            hal.waitForPowerOn()
        }
    }

    override suspend fun waitForPeripheralWithUuid(uuid: UUID) {
        // Some OS Bluetooth stacks (for example Android) have built-in protection against
        // applications scanning too frequently. On Android, the stack will simply not report
        // any scan results back to the app and instead print
        //
        //   App 'com.example.appname' is scanning too frequently
        //
        // to logcat. When [scanRestartTimeout] is configured (10 seconds by default), we work
        // around this by periodically stopping and restarting the scan if we haven't discovered
        // the peripheral yet.
        var retryCount = 0
        while (true) {
            val discovered = try {
                if (scanRestartTimeout != null) {
                    withTimeoutOrNull(scanRestartTimeout) {
                        suspendCancellableCoroutine { continuation ->
                            setWaitCondition(WaitState.PERIPHERAL_DISCOVERED, continuation)
                            hal.startScan(uuid)
                        }
                        true
                    }
                } else {
                    suspendCancellableCoroutine { continuation ->
                        setWaitCondition(WaitState.PERIPHERAL_DISCOVERED, continuation)
                        hal.startScan(uuid)
                    }
                    true
                }
            } finally {
                hal.stopScan()
            }
            if (discovered != null) {
                break
            }
            clearWaitCondition()
            retryCount++
            _numScanRestarts = retryCount
            // Note: we never give up because it's possible this is used by a wallet app which is
            // simply sitting at the "Show QR code" dialog.
            Logger.i(TAG, "Failed to find peripheral after $retryCount attempt(s). Restarting scan.")
        }
        hal.onScanStoppedAfterDiscovery()
        // Give the radio time to actually transition out of the scanning state.
        if (postScanDelay > Duration.ZERO) {
            delay(postScanDelay)
        }
    }

    override suspend fun connectToPeripheral() {
        var retryCount = 0
        isConnectingToPeripheral = true
        try {
            while (true) {
                try {
                    suspendCancellableCoroutine { continuation ->
                        setWaitCondition(WaitState.CONNECT_TO_PERIPHERAL, continuation)
                        hal.connectToPeripheral()
                    }
                    break
                } catch (error: ConnectionFailedException) {
                    if (retryCount < maxConnectRetries) {
                        retryCount++
                        _numGattConnectRetries = retryCount
                        Logger.w(TAG, "Failed connecting to peripheral after $retryCount attempt(s), retrying in $connectRetryDelay")
                        if (connectRetryDelay > Duration.ZERO) {
                            delay(connectRetryDelay)
                        }
                    } else {
                        Logger.w(TAG, "Failed connecting to peripheral after $retryCount attempts. Giving up.")
                        throw IllegalStateException("Failed connecting to peripheral", error)
                    }
                }
            }
        } finally {
            isConnectingToPeripheral = false
        }
    }

    override suspend fun requestMtu() {
        suspendCancellableCoroutine { continuation ->
            setWaitCondition(WaitState.REQUEST_MTU, continuation)
            hal.requestMtu(DEFAULT_REQUESTED_MTU)
        }
    }

    override suspend fun peripheralDiscoverServices(uuid: UUID) {
        var retryCount = 0
        while (true) {
            try {
                suspendCancellableCoroutine { continuation ->
                    setWaitCondition(WaitState.PERIPHERAL_DISCOVER_SERVICES, continuation)
                    hal.discoverServices(uuid)
                }
                break
            } catch (error: ServiceDiscoveryFailedException) {
                if (retryCount < maxDiscoverServicesRetries) {
                    retryCount++
                    _numServiceDiscoveryRetries = retryCount
                    Logger.w(
                        TAG,
                        "Failed discovering service with UUID $uuid after $retryCount attempt(s), " +
                                "retrying in $discoverServicesRetryDelay: ${error.message}"
                    )
                    if (discoverServicesRetryDelay > Duration.ZERO) {
                        delay(discoverServicesRetryDelay)
                    }
                } else {
                    Logger.w(
                        TAG,
                        "Failed discovering service with UUID $uuid after $retryCount attempts. Giving up."
                    )
                    throw IllegalStateException("No service with the given UUID", error)
                }
            }
        }
    }

    override suspend fun peripheralDiscoverCharacteristics() {
        suspendCancellableCoroutine { continuation ->
            setWaitCondition(WaitState.PERIPHERAL_DISCOVER_CHARACTERISTICS, continuation)
            hal.discoverCharacteristics(
                stateCharacteristicUuid = stateCharacteristicUuid,
                client2ServerCharacteristicUuid = client2ServerCharacteristicUuid,
                server2ClientCharacteristicUuid = server2ClientCharacteristicUuid,
                identCharacteristicUuid = identCharacteristicUuid,
                l2capCharacteristicUuid = l2capCharacteristicUuid,
            )
        }
        if (l2capCharacteristicUuid != null && hasL2capCharacteristic) {
            suspendCancellableCoroutine { continuation ->
                setWaitCondition(WaitState.GET_L2CAP_PSM, continuation, l2capCharacteristicUuid)
                hal.readCharacteristic(l2capCharacteristicUuid!!)
            }
        }
    }

    override suspend fun checkReaderIdentMatches(eSenderKey: EcPublicKey) {
        val identUuid = checkNotNull(identCharacteristicUuid) { "identCharacteristicUuid is null" }
        expectedIdentValue = deriveBleIdent(eSenderKey)
        suspendCancellableCoroutine { continuation ->
            setWaitCondition(WaitState.GET_READER_IDENT, continuation, identUuid)
            hal.readCharacteristic(identUuid)
        }
    }

    override suspend fun subscribeToCharacteristics() {
        enableNotifications(stateCharacteristicUuid)
        enableNotifications(server2ClientCharacteristicUuid)
    }

    private suspend fun enableNotifications(characteristicUuid: UUID) {
        suspendCancellableCoroutine { continuation ->
            setWaitCondition(WaitState.WRITE_TO_DESCRIPTOR, continuation, characteristicUuid)
            hal.enableNotifications(characteristicUuid)
        }
    }

    private suspend fun writeToCharacteristic(characteristicUuid: UUID, value: ByteArray) {
        suspendCancellableCoroutine { continuation ->
            setWaitCondition(WaitState.CHARACTERISTIC_WRITE_COMPLETED, continuation, characteristicUuid)
            hal.writeCharacteristic(characteristicUuid, value)
        }
    }

    override suspend fun writeToStateCharacteristic(value: Int) {
        writeToCharacteristic(stateCharacteristicUuid, byteArrayOf(value.toByte()))
    }

    override suspend fun sendMessage(message: ByteArray) {
        val channel = l2capChannel
        if (channel != null) {
            l2capSendMessage(channel, message)
            return
        }
        Logger.i(TAG, "sendMessage ${message.size} length")
        val maxChunkSize = maxCharacteristicSize - 1
        check(maxChunkSize > 0) { "Invalid maxCharacteristicSize $maxCharacteristicSize" }
        val offsets = 0 until message.size step maxChunkSize
        for (offset in offsets) {
            val moreDataComing = (offset != offsets.last)
            val size = min(maxChunkSize, message.size - offset)
            val builder = ByteStringBuilder(size + 1)
            builder.append(if (moreDataComing) 0x01 else 0x00)
            builder.append(message, offset, offset + size)
            val chunk = builder.toByteString().toByteArray()
            writeToCharacteristic(client2ServerCharacteristicUuid, chunk)
            _numChunksSent += 1
        }
        Logger.i(TAG, "sendMessage completed")
    }

    override suspend fun connectL2cap(psm: Int) {
        if (requireL2capPeripheralConnectedFirst && !hal.isConnected) {
            connectToPeripheral()
        }
        var currentDelay = l2capRetryInitialDelay
        var numTries = 0
        while (true) {
            try {
                numTries++
                val connected = if (l2capConnectTimeout != null) {
                    withTimeoutOrNull(l2capConnectTimeout) {
                        suspendCancellableCoroutine { continuation ->
                            setWaitCondition(WaitState.OPEN_L2CAP_CHANNEL, continuation)
                            hal.connectL2cap(psm)
                        }
                        true
                    }
                } else {
                    suspendCancellableCoroutine { continuation ->
                        setWaitCondition(WaitState.OPEN_L2CAP_CHANNEL, continuation)
                        hal.connectL2cap(psm)
                    }
                    true
                }
                if (connected == null) {
                    clearWaitCondition()
                    throw ConnectionFailedException("L2CAP connection timed out")
                }
                break
            } catch (e: Exception) {
                hal.closeConnectedL2capAttempt()
                l2capChannel = null
                if (e is CancellationException) throw e
                if (numTries >= maxL2capConnectRetries) {
                    throw IllegalStateException("Failed L2CAP connection after $maxL2capConnectRetries attempts", e)
                }
                _numL2capConnectRetries += 1
                Logger.i(TAG, "Failed L2CAP connection (Attempt $numTries), trying again in $currentDelay: ${e.message}")
                if (currentDelay > Duration.ZERO) {
                    delay(currentDelay)
                }
                currentDelay = (currentDelay.inWholeMilliseconds * 2).milliseconds
                if (currentDelay > l2capRetryMaxDelay) {
                    currentDelay = l2capRetryMaxDelay
                }
            }
        }

        val channel = checkNotNull(l2capChannel)
        CoroutineScope(Dispatchers.Default).launch {
            readFromL2capChannel(channel, incomingMessages) { error ->
                onError(IllegalStateException("Reading from L2CAP socket failed", error))
            }
        }
    }

    override fun close() {
        hal.close()
        l2capChannel = null
        incomingMessages.close()
    }
}

internal suspend fun deriveBleIdent(eSenderKey: EcPublicKey): ByteArray {
    val ikm = Cbor.encode(Tagged(24, Bstr(Cbor.encode(eSenderKey.toCoseKey().toDataItem()))))
    val info = "BLEIdent".encodeToByteArray()
    return SecretKey(ikm).use {
        Hkdf.deriveKey(Algorithm.HMAC_SHA256, it, null, info, 16).use { key -> key.encoded }
    }
}

internal fun encodeL2capPsm(psm: Int): ByteArray =
    buildByteString {
        appendUInt32(psm)
    }.toByteArray()

internal suspend fun l2capSendMessage(channel: BleL2capChannel, message: ByteArray) {
    val framed = buildByteString {
        appendUInt32(message.size)
        appendByteArray(message)
    }.toByteArray()
    channel.write(framed)
}

internal suspend fun BleL2capChannel.readNOctets(len: UInt): ByteArray {
    val bsb = ByteStringBuilder()
    var remaining = len.toInt()
    while (remaining > 0) {
        val buf = ByteArray(remaining)
        val numBytesRead = read(buf, 0, remaining)
        if (numBytesRead == -1) {
            throw IllegalStateException("Failed reading from L2CAP channel (EOF)")
        }
        bsb.append(buf, 0, numBytesRead)
        remaining -= numBytesRead
    }
    return bsb.toByteString().toByteArray()
}

internal suspend fun readFromL2capChannel(
    channel: BleL2capChannel,
    incomingMessages: Channel<ByteArray>,
    onError: (Throwable) -> Unit
) {
    try {
        while (true) {
            val encodedLength = channel.readNOctets(4U)
            val length = encodedLength.getUInt32(0)
            val message = channel.readNOctets(length)
            incomingMessages.send(message)
        }
    } catch (e: Exception) {
        if (e is CancellationException) throw e
        onError(e)
    }
}
