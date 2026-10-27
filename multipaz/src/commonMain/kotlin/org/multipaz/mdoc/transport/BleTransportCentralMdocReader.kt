package org.multipaz.mdoc.transport

import kotlinx.coroutines.CancellationException
import org.multipaz.util.unwrapCancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.concurrent.Volatile
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Instant
import org.multipaz.crypto.EcPublicKey
import org.multipaz.mdoc.connectionmethod.MdocConnectionMethod
import org.multipaz.mdoc.connectionmethod.MdocConnectionMethodBle
import org.multipaz.mdoc.role.MdocRole
import org.multipaz.util.Logger
import org.multipaz.util.UUID

internal class BleTransportCentralMdocReader(
    override val role: MdocRole,
    private val options: MdocTransportOptions,
    private val peripheralManager: BlePeripheralManager,
    private val uuid: UUID
) : MdocTransport() {
    companion object {
        private const val TAG = "BleTransportCentralMdocReader"
    }

    private val transportScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private val mutex = Mutex()
    @Volatile
    private var currentJob: Job? = null

    private val _state = MutableStateFlow<State>(State.IDLE)
    override val state: StateFlow<State> = _state.asStateFlow()

    override val connectionMethod: MdocConnectionMethod
        get() {
            return MdocConnectionMethodBle(
                supportsPeripheralServerMode = false,
                supportsCentralClientMode = true,
                peripheralServerModeUuid = null,
                centralClientModeUuid = uuid,
                peripheralServerModePsm = if (options.bleUseL2CAPInEngagement) peripheralManager.l2capPsm else null,
                peripheralServerModeMacAddress = null
            )
        }

    init {
        peripheralManager.setUuids(
            stateCharacteristicUuid = UUID.fromString("00000005-a123-48ce-896b-4c76973373e6"),
            client2ServerCharacteristicUuid = UUID.fromString("00000006-a123-48ce-896b-4c76973373e6"),
            server2ClientCharacteristicUuid = UUID.fromString("00000007-a123-48ce-896b-4c76973373e6"),
            identCharacteristicUuid = UUID.fromString("00000008-a123-48ce-896b-4c76973373e6"),
            l2capCharacteristicUuid = if (options.bleUseL2CAP) {
                UUID.fromString("0000000b-a123-48ce-896b-4c76973373e6")
            } else {
                null
            },
            startL2capServer = options.bleUseL2CAPInEngagement
        )
        peripheralManager.setCallbacks(
            onError = { error ->
                transportScope.launch {
                    currentJob?.cancel("onError was called", error)
                    mutex.withLock {
                        failTransport(error)
                    }
                }
            },
            onClosed = {
                transportScope.launch {
                    mutex.withLock {
                        closeWithoutDelay()
                    }
                }
            }
        )
    }

    override suspend fun advertise() {
        try {
            coroutineScope {
                mutex.withLock {
                    check(_state.value == State.IDLE) { "Expected state IDLE, got ${_state.value}" }
                    _state.value = State.ADVERTISING
                    currentJob = coroutineContext[Job]
                }
                peripheralManager.waitForPowerOn()
                peripheralManager.advertiseService(uuid)
            }
        } catch (error: Exception) {
            val unwrapped = error.unwrapCancellationException()
            if (unwrapped is CancellationException) {
                withContext(NonCancellable) {
                    mutex.withLock {
                        if (_state.value != State.FAILED && _state.value != State.CLOSED) {
                            closeWithoutDelay()
                        }
                    }
                }
                throw unwrapped
            } else {
                mutex.withLock { failTransport(unwrapped) }
                throw unwrapped.wrapUnlessCancellationException("Failed while advertising")
            }
        } finally {
            currentJob = null
        }
    }

    override val scanningTime: Duration?
        get() = null

    private var _connectingTime: Duration? = null
    private var _bytesSent: Long = 0
    private var _bytesReceived: Long = 0
    private var _numMessagesSent: Int = 0
    private var _numMessagesReceived: Int = 0

    override val stats: MdocTransportStats
        get() = MdocTransportStats(
            transportType = MdocTransportType.MDOC_CENTRAL_CLIENT_MODE,
            role = role,
            scanningTime = null,
            connectingTime = _connectingTime,
            bytesSent = _bytesSent,
            bytesReceived = _bytesReceived,
            numMessagesSent = _numMessagesSent,
            numMessagesReceived = _numMessagesReceived,
            warnings = peripheralManager.warnings,
            bleUsingL2cap = peripheralManager.usingL2cap,
            bleL2capPsmInEngagement = options.bleUseL2CAPInEngagement && peripheralManager.l2capPsm != null,
            bleNegotiatedMtu = peripheralManager.negotiatedMtu,
            bleMtuInferredFromWrite = peripheralManager.mtuInferredFromWrite,
            bleNumChunksSent = peripheralManager.numChunksSent,
            bleNumChunksReceived = peripheralManager.numChunksReceived,
        )

    override suspend fun open(eSenderKey: EcPublicKey) {
        var timeConnectingStarted: Instant? = null
        try {
            coroutineScope {
                val wasAdvertising = mutex.withLock {
                    check(_state.value == State.IDLE || _state.value == State.ADVERTISING) {
                        "Expected state IDLE or ADVERTISING, got ${_state.value}"
                    }
                    val advertising = _state.value == State.ADVERTISING
                    _state.value = State.ADVERTISING
                    currentJob = coroutineContext[Job]
                    advertising
                }
                if (!wasAdvertising) {
                    // Start advertising if we aren't already...
                    peripheralManager.waitForPowerOn()
                    peripheralManager.advertiseService(uuid)
                }
                peripheralManager.setESenderKey(eSenderKey)
                timeConnectingStarted = Clock.System.now()
                // Note: It's not really possible to know someone is connecting to use until they're _actually_
                // connected. I mean, for all we know, someone could be BLE scanning us. So not really possible
                // to go into State.CONNECTING...
                peripheralManager.waitForStateCharacteristicWriteOrL2CAPClient()
                _connectingTime = Clock.System.now() - timeConnectingStarted!!
                mutex.withLock { _state.value = State.CONNECTED }
            }
        } catch (error: Exception) {
            timeConnectingStarted?.let { _connectingTime = Clock.System.now() - it }
            val unwrapped = error.unwrapCancellationException()
            if (unwrapped is CancellationException) {
                withContext(NonCancellable) {
                    mutex.withLock {
                        if (_state.value != State.FAILED && _state.value != State.CLOSED) {
                            closeWithoutDelay()
                        }
                    }
                }
                throw unwrapped
            } else {
                mutex.withLock { failTransport(unwrapped) }
                throw unwrapped.wrapUnlessCancellationException("Failed while opening transport")
            }
        } finally {
            currentJob = null
        }
    }

    override suspend fun waitForMessage(): ByteArray {
        mutex.withLock {
            if (_state.value == State.FAILED) {
                throw MdocTransportException("Transport already failed")
            }
            check(_state.value == State.CONNECTED) { "Expected state CONNECTED, got ${_state.value}" }
        }
        try {
            val msg = peripheralManager.incomingMessages.receive()
            if (msg.isNotEmpty()) {
                _bytesReceived += msg.size
                _numMessagesReceived += 1
            }
            return msg
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            if (_state.value == State.CLOSED) {
                throw MdocTransportClosedException("Transport was closed while waiting for message")
            } else {
                mutex.withLock {
                    failTransport(error)
                }
                throw MdocTransportException("Failed while waiting for message", error)
            }
        }
    }

    override suspend fun sendMessage(message: ByteArray) {
        try {
            coroutineScope {
                mutex.withLock {
                    check(_state.value == State.CONNECTED) { "Expected state CONNECTED, got ${_state.value}" }
                    if (message.isEmpty() && peripheralManager.usingL2cap) {
                        throw MdocTransportTerminationException("Transport-specific termination not available with L2CAP")
                    }
                    currentJob = coroutineContext[Job]
                }
                if (message.isEmpty()) {
                    peripheralManager.writeToStateCharacteristic(BleTransportConstants.STATE_CHARACTERISTIC_END)
                } else {
                    peripheralManager.sendMessage(message)
                    _bytesSent += message.size
                    _numMessagesSent += 1
                }
            }
        } catch (error: Exception) {
            val unwrapped = error.unwrapCancellationException()
            if (unwrapped is CancellationException) {
                throw unwrapped
            } else {
                mutex.withLock { failTransport(unwrapped) }
                throw unwrapped.wrapUnlessCancellationException("Failed while sending message")
            }
        } finally {
            currentJob = null
        }
    }

    private fun failTransport(error: Throwable) {
        check(mutex.isLocked) { "failTransport called without holding lock" }
        if (_state.value == State.FAILED || _state.value == State.CLOSED) {
            return
        }
        Logger.w(TAG, "Failing transport with error", error)
        peripheralManager.close()
        _state.value = State.FAILED
    }

    private fun closeWithoutDelay() {
        check(mutex.isLocked) { "closeWithoutDelay called without holding lock" }
        peripheralManager.close()
        _state.value = State.CLOSED
    }

    override suspend fun close() = withContext(NonCancellable) {
        val job = mutex.withLock { currentJob }
        job?.cancel("close() was called")
        job?.join()
        transportScope.cancel("Transport closed")
        mutex.withLock {
            if (_state.value == State.FAILED || _state.value == State.CLOSED) {
                return@withContext
            }
            closeWithoutDelay()
        }
    }
}