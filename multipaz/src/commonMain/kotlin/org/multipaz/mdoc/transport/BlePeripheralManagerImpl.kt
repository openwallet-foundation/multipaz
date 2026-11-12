package org.multipaz.mdoc.transport

import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.io.bytestring.ByteStringBuilder
import org.multipaz.crypto.EcPublicKey
import org.multipaz.util.Logger
import org.multipaz.util.UUID
import org.multipaz.util.toHex
import kotlin.concurrent.Volatile
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.math.min

/**
 * Shared multiplatform implementation of [BlePeripheralManager] backed by a platform-specific [BlePeripheralHal].
 *
 * @param hal The platform-specific [BlePeripheralHal] implementation.
 */
internal class BlePeripheralManagerImpl(
    private val hal: BlePeripheralHal,
) : BlePeripheralManager {
    companion object {
        private const val TAG = "BlePeripheralManagerImpl"
        private const val DEFAULT_MTU = 23
        private const val MAX_CHARACTERISTIC_SIZE_CAP = 512
    }

    private lateinit var stateCharacteristicUuid: UUID
    private lateinit var client2ServerCharacteristicUuid: UUID
    private lateinit var server2ClientCharacteristicUuid: UUID
    private var identCharacteristicUuid: UUID? = null
    private var l2capCharacteristicUuid: UUID? = null
    private var startL2capServer = false

    @Volatile
    private var _negotiatedMtu = -1
    override val negotiatedMtu: Int?
        get() = if (_negotiatedMtu != -1) _negotiatedMtu else null

    @Volatile
    private var _mtuInferredFromWrite = false
    override val mtuInferredFromWrite: Boolean
        get() = _mtuInferredFromWrite

    @Volatile
    private var _numChunksSent = 0
    override val numChunksSent: Int
        get() = _numChunksSent

    @Volatile
    private var _numChunksReceived = 0
    override val numChunksReceived: Int
        get() = _numChunksReceived

    private val _warnings = MutableStateFlow<List<TransportWarning>>(emptyList())
    override val warnings: List<TransportWarning>
        get() = _warnings.value

    private fun addWarning(warning: TransportWarning) {
        _warnings.update { it + warning }
    }

    private var maxCharacteristicSizeMemoized = 0
    internal val maxCharacteristicSize: Int
        get() {
            if (maxCharacteristicSizeMemoized > 0) {
                return maxCharacteristicSizeMemoized
            }
            var mtuSize = _negotiatedMtu
            if (mtuSize == -1) {
                Logger.w(TAG, "MTU not negotiated, defaulting to $DEFAULT_MTU. Performance will suffer.")
                addWarning(TransportWarning(TransportWarningCode.BLE_MTU_NOT_NEGOTIATED))
                mtuSize = DEFAULT_MTU
            }
            maxCharacteristicSizeMemoized = min(MAX_CHARACTERISTIC_SIZE_CAP, mtuSize - 3)
            Logger.i(TAG, "Using maxCharacteristicSize $maxCharacteristicSizeMemoized")
            return maxCharacteristicSizeMemoized
        }

    override fun setUuids(
        stateCharacteristicUuid: UUID,
        client2ServerCharacteristicUuid: UUID,
        server2ClientCharacteristicUuid: UUID,
        identCharacteristicUuid: UUID?,
        l2capCharacteristicUuid: UUID?,
        startL2capServer: Boolean
    ) {
        this.stateCharacteristicUuid = stateCharacteristicUuid
        this.client2ServerCharacteristicUuid = client2ServerCharacteristicUuid
        this.server2ClientCharacteristicUuid = server2ClientCharacteristicUuid
        this.identCharacteristicUuid = identCharacteristicUuid
        this.l2capCharacteristicUuid = l2capCharacteristicUuid
        this.startL2capServer = startL2capServer
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
        PUBLISH_L2CAP_CHANNEL,
        SERVICE_ADDED,
        START_ADVERTISING,
        STATE_CHARACTERISTIC_WRITTEN_OR_L2CAP_CLIENT,
        CHARACTERISTIC_WRITE_COMPLETED,
    }

    private data class WaitFor(
        val state: WaitState,
        val continuation: CancellableContinuation<Boolean>,
    )

    private val waitFor = MutableStateFlow<WaitFor?>(null)

    private fun setWaitCondition(
        state: WaitState,
        continuation: CancellableContinuation<Boolean>,
    ) {
        val entry = WaitFor(state, continuation)
        check(waitFor.compareAndSet(null, entry)) {
            "waitFor is already set to ${waitFor.value?.state}"
        }
        continuation.invokeOnCancellation {
            waitFor.compareAndSet(entry, null)
        }
    }

    private fun claimWaitCondition(expectedState: WaitState): CancellableContinuation<Boolean>? {
        while (true) {
            val current = waitFor.value
            if (current == null || current.state != expectedState) {
                return null
            }
            if (waitFor.compareAndSet(current, null)) {
                return current.continuation
            }
        }
    }

    private fun resumeWait(expectedState: WaitState): Boolean {
        val continuation = claimWaitCondition(expectedState) ?: return false
        continuation.resume(true)
        return true
    }

    private fun resumeWaitWithException(expectedState: WaitState, exception: Throwable): Boolean {
        val continuation = claimWaitCondition(expectedState) ?: return false
        continuation.resumeWithException(exception)
        return true
    }

    override val incomingMessages = Channel<ByteArray>(Channel.UNLIMITED)

    private var incomingMessage = ByteStringBuilder()
    @Volatile
    private var identValue: ByteArray? = null
    @Volatile
    private var _l2capPsm: Int? = null
    @Volatile
    private var l2capChannel: BleL2capChannel? = null
    @Volatile
    private var clientConnected = false

    override val l2capPsm: Int?
        get() = _l2capPsm

    override val usingL2cap: Boolean
        get() = l2capChannel != null

    private val halCallback = object : BlePeripheralHal.Callback {
        override fun onPowerOn(error: Throwable?) {
            val resumed = if (error != null) {
                resumeWaitWithException(WaitState.POWER_ON, error)
            } else {
                resumeWait(WaitState.POWER_ON)
            }
            if (!resumed) {
                Logger.w(TAG, "onPowerOn callback but not waiting")
            }
        }

        override fun onL2capPublished(psm: Int?, error: Throwable?) {
            if (error != null) {
                if (!resumeWaitWithException(WaitState.PUBLISH_L2CAP_CHANNEL, error)) {
                    Logger.w(TAG, "onL2capPublished but not waiting")
                }
            } else {
                _l2capPsm = psm
                if (!resumeWait(WaitState.PUBLISH_L2CAP_CHANNEL)) {
                    Logger.w(TAG, "onL2capPublished but not waiting")
                }
            }
        }

        override fun onServiceAdded(error: Throwable?) {
            val resumed = if (error != null) {
                resumeWaitWithException(WaitState.SERVICE_ADDED, error)
            } else {
                resumeWait(WaitState.SERVICE_ADDED)
            }
            if (!resumed) {
                Logger.w(TAG, "onServiceAdded but not waiting")
            }
        }

        override fun onAdvertisingStarted(error: Throwable?) {
            val resumed = if (error != null) {
                resumeWaitWithException(WaitState.START_ADVERTISING, error)
            } else {
                resumeWait(WaitState.START_ADVERTISING)
            }
            if (!resumed) {
                Logger.w(TAG, "onAdvertisingStarted but not waiting")
            }
        }

        override fun onMtuChanged(mtu: Int) {
            _negotiatedMtu = mtu
            _mtuInferredFromWrite = false
            maxCharacteristicSizeMemoized = 0
            Logger.d(TAG, "Negotiated MTU $mtu")
        }

        override fun onCharacteristicReadRequest(requestId: Int, uuid: UUID) {
            if (uuid == identCharacteristicUuid) {
                val ident = identValue
                if (ident == null) {
                    onError(IllegalStateException("Received request for ident before it's set"))
                } else {
                    hal.sendReadResponse(requestId, statusSuccess = true, value = ident)
                }
            } else if (uuid == l2capCharacteristicUuid) {
                val psm = _l2capPsm
                val encodedPsm = if (psm != null) encodeL2capPsm(psm) else byteArrayOf()
                hal.sendReadResponse(requestId, statusSuccess = true, value = encodedPsm)
            } else {
                Logger.w(TAG, "Read on unexpected characteristic with UUID $uuid")
                addWarning(TransportWarning(TransportWarningCode.BLE_UNEXPECTED_CALLBACK))
            }
        }

        override fun onCharacteristicWriteRequest(
            uuid: UUID,
            value: ByteArray,
            maxWriteSizeForCentral: Int?
        ) {
            if (uuid == stateCharacteristicUuid) {
                if (value contentEquals byteArrayOf(BleTransportConstants.STATE_CHARACTERISTIC_START.toByte())) {
                    if (!clientConnected || waitFor.value?.state == WaitState.STATE_CHARACTERISTIC_WRITTEN_OR_L2CAP_CLIENT) {
                        if (maxWriteSizeForCentral != null) {
                            maxCharacteristicSizeMemoized = min(maxWriteSizeForCentral, MAX_CHARACTERISTIC_SIZE_CAP)
                            if (_negotiatedMtu == -1) {
                                _negotiatedMtu = maxWriteSizeForCentral + 3
                            }
                        }
                        clientConnected = true
                        hal.stopAdvertising()
                        hal.closeL2capServer()
                        resumeWait(WaitState.STATE_CHARACTERISTIC_WRITTEN_OR_L2CAP_CLIENT)
                    }
                } else if (value contentEquals byteArrayOf(BleTransportConstants.STATE_CHARACTERISTIC_END.toByte())) {
                    Logger.i(TAG, "Received transport-specific termination message")
                    incomingMessages.trySend(byteArrayOf())
                } else {
                    if (waitFor.value?.state == WaitState.STATE_CHARACTERISTIC_WRITTEN_OR_L2CAP_CLIENT) {
                        Logger.w(TAG, "Ignoring unexpected write ${value.toHex()} to state characteristic")
                    } else {
                        Logger.w(TAG, "Ignoring unexpected write to state characteristic")
                    }
                    addWarning(TransportWarning(TransportWarningCode.BLE_UNEXPECTED_CALLBACK))
                }
            } else if (uuid == client2ServerCharacteristicUuid) {
                try {
                    handleIncomingData(value)
                } catch (e: Exception) {
                    if (e is CancellationException) throw e
                    onError(IllegalStateException("Error processing incoming data", e))
                }
            } else {
                Logger.w(TAG, "Unexpected write to characteristic with UUID $uuid")
                addWarning(TransportWarning(TransportWarningCode.BLE_UNEXPECTED_CALLBACK))
            }
        }

        override fun onNotificationSent(uuid: UUID, error: Throwable?) {
            val resumed = if (error != null) {
                resumeWaitWithException(WaitState.CHARACTERISTIC_WRITE_COMPLETED, error)
            } else {
                resumeWait(WaitState.CHARACTERISTIC_WRITE_COMPLETED)
            }
            if (!resumed) {
                Logger.w(TAG, "onNotificationSent but not waiting")
                addWarning(TransportWarning(TransportWarningCode.BLE_UNEXPECTED_CALLBACK))
            }
        }

        override fun onL2capClientConnected(channel: BleL2capChannel) {
            l2capChannel = channel
            clientConnected = true
            hal.stopAdvertising()
            resumeWait(WaitState.STATE_CHARACTERISTIC_WRITTEN_OR_L2CAP_CLIENT)
            CoroutineScope(Dispatchers.Default).launch {
                readFromL2capChannel(channel, incomingMessages) { error ->
                    onError(IllegalStateException("Accepting/reading from L2CAP socket failed", error))
                }
            }
        }

        override fun onCentralDisconnected() {
            onError(IllegalStateException("Central unexpectedly disconnected"))
        }

        override fun onError(error: Throwable) {
            this@BlePeripheralManagerImpl.onError(error)
        }
    }

    init {
        hal.setCallback(halCallback)
    }

    private fun handleIncomingData(chunk: ByteArray) {
        if (chunk.isEmpty()) {
            throw IllegalStateException("Invalid data length ${chunk.size} for Client2Server characteristic")
        }
        _numChunksReceived += 1
        // In Android's Bluetooth stack (com.android.bluetooth), ATT MTU is shared per LE ACL link,
        // and BluetoothGattServerCallback.onMtuChanged() is only invoked when the remote peer's
        // GATT client initiates the ATT_EXCHANGE_MTU_REQ against the local GATT server. If the
        // local device's own GATT client stack (e.g. from a system service or a leaked clientIf)
        // already exchanged the MTU on the ACL link before the peer's requestMtu() arrived,
        // onMtuChanged() may never be delivered to the BluetoothGattServer even though the link's
        // ATT MTU was negotiated above the 23-byte default.
        //
        // Receiving a chunk larger than DEFAULT_MTU - 3 (20 bytes) on Client2Server proves that
        // the link's ATT MTU is at least chunk.size + 3, so we infer the negotiated MTU here to
        // avoid false chunk-size warnings and to allow subsequent Server2Client notifications
        // (such as DeviceResponse in peripheral server mode) to use full-sized chunks.
        if ((_negotiatedMtu == -1 || _mtuInferredFromWrite) &&
            chunk.size > DEFAULT_MTU - 3 &&
            chunk.size + 3 > _negotiatedMtu
        ) {
            _negotiatedMtu = chunk.size + 3
            _mtuInferredFromWrite = true
            maxCharacteristicSizeMemoized = 0
            addWarning(TransportWarning(TransportWarningCode.BLE_MTU_INFERRED_FROM_WRITE))
            Logger.i(
                TAG,
                "Inferred negotiated MTU of at least $_negotiatedMtu from ${chunk.size}-byte Client2Server write"
            )
        }
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
                        "Client2Server received ${chunk.size} bytes which is not the " +
                                "expected $maxCharacteristicSize bytes"
                    )
                    addWarning(TransportWarning(TransportWarningCode.BLE_UNEXPECTED_CHUNK_SIZE))
                }
            }
            else -> {
                throw IllegalStateException(
                    "Invalid first byte ${chunk[0]} in Client2Server data chunk, expected 0 or 1"
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

    override suspend fun advertiseService(uuid: UUID) {
        if (l2capCharacteristicUuid != null || startL2capServer) {
            suspendCancellableCoroutine { continuation ->
                setWaitCondition(WaitState.PUBLISH_L2CAP_CHANNEL, continuation)
                hal.publishL2capChannel()
            }
        }
        val encodedPsm = _l2capPsm?.let { encodeL2capPsm(it) }
        suspendCancellableCoroutine { continuation ->
            setWaitCondition(WaitState.SERVICE_ADDED, continuation)
            hal.setupGattServerAndAddService(
                serviceUuid = uuid,
                stateCharacteristicUuid = stateCharacteristicUuid,
                client2ServerCharacteristicUuid = client2ServerCharacteristicUuid,
                server2ClientCharacteristicUuid = server2ClientCharacteristicUuid,
                identCharacteristicUuid = identCharacteristicUuid,
                l2capCharacteristicUuid = l2capCharacteristicUuid,
                encodedL2capPsm = encodedPsm
            )
        }
        suspendCancellableCoroutine { continuation ->
            setWaitCondition(WaitState.START_ADVERTISING, continuation)
            hal.startAdvertising(uuid)
        }
    }

    override suspend fun setESenderKey(eSenderKey: EcPublicKey) {
        identValue = deriveBleIdent(eSenderKey)
    }

    override suspend fun waitForStateCharacteristicWriteOrL2CAPClient() {
        if (clientConnected) {
            Logger.i(TAG, "The device is already connected, no need to wait")
            return
        }
        suspendCancellableCoroutine { continuation ->
            setWaitCondition(WaitState.STATE_CHARACTERISTIC_WRITTEN_OR_L2CAP_CLIENT, continuation)
            if (clientConnected) {
                resumeWait(WaitState.STATE_CHARACTERISTIC_WRITTEN_OR_L2CAP_CLIENT)
            }
        }
    }

    private suspend fun writeToCharacteristic(characteristicUuid: UUID, value: ByteArray) {
        suspendCancellableCoroutine { continuation ->
            setWaitCondition(WaitState.CHARACTERISTIC_WRITE_COMPLETED, continuation)
            hal.notifyCharacteristicChanged(characteristicUuid, value)
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
            writeToCharacteristic(server2ClientCharacteristicUuid, chunk)
            _numChunksSent += 1
        }
        Logger.i(TAG, "sendMessage completed")
    }

    override fun close() {
        clientConnected = false
        hal.close()
        l2capChannel = null
        incomingMessages.close()
    }
}
