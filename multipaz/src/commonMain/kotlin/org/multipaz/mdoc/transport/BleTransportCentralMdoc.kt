package org.multipaz.mdoc.transport

import io.ktor.client.utils.unwrapCancellationException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.time.Clock
import kotlin.time.Instant
import org.multipaz.crypto.EcPublicKey
import org.multipaz.mdoc.connectionmethod.MdocConnectionMethod
import org.multipaz.mdoc.connectionmethod.MdocConnectionMethodBle
import org.multipaz.mdoc.role.MdocRole
import org.multipaz.util.Logger
import org.multipaz.util.UUID
import kotlin.time.Duration

internal class BleTransportCentralMdoc(
    override val role: MdocRole,
    private val options: MdocTransportOptions,
    private val centralManager: BleCentralManager,
    private val uuid: UUID,
    private val psm: Int?
) : MdocTransport() {
    companion object {
        private const val TAG = "BleTransportCentralMdoc"
    }

    private val mutex = Mutex()
    private var currentJob: Job? = null

    private val _state = MutableStateFlow<State>(State.IDLE)
    override val state: StateFlow<State> = _state.asStateFlow()

    override val connectionMethod: MdocConnectionMethod
        get() = MdocConnectionMethodBle(
            supportsPeripheralServerMode = false,
            supportsCentralClientMode = true,
            peripheralServerModeUuid = null,
            centralClientModeUuid = uuid
        )

    init {
        centralManager.setUuids(
            stateCharacteristicUuid = UUID.fromString("00000005-a123-48ce-896b-4c76973373e6"),
            client2ServerCharacteristicUuid = UUID.fromString("00000006-a123-48ce-896b-4c76973373e6"),
            server2ClientCharacteristicUuid = UUID.fromString("00000007-a123-48ce-896b-4c76973373e6"),
            identCharacteristicUuid = UUID.fromString("00000008-a123-48ce-896b-4c76973373e6"),
            l2capUuid = if (options.bleUseL2CAP) {
                UUID.fromString("0000000b-a123-48ce-896b-4c76973373e6")
            } else {
                null
            }
        )
        centralManager.setCallbacks(
            onError = { error ->
                runBlocking {
                    currentJob?.cancel("onError was called", error)
                    mutex.withLock {
                        failTransport(error)
                    }
                }
            },
            onClosed = {
                runBlocking {
                    mutex.withLock {
                        closeWithoutDelay()
                    }
                }
            }
        )
    }

    override suspend fun advertise() {
        // Nothing to do here.
    }

    private var _scanningTime: Duration? = null
    override val scanningTime: Duration?
        get() = _scanningTime

    override suspend fun open(eSenderKey: EcPublicKey) {
        var timeScanningStarted: Instant
        mutex.withLock {
            check(_state.value == State.IDLE) { "Expected state IDLE, got ${_state.value}" }
            try {
                coroutineScope {
                    currentJob = launch {
                        _state.value = State.SCANNING
                        centralManager.waitForPowerOn()
                        timeScanningStarted = Clock.System.now()
                        centralManager.waitForPeripheralWithUuid(uuid)
                        _scanningTime = Clock.System.now() - timeScanningStarted
                        _state.value = State.CONNECTING
                        if (psm != null) {
                            // If the PSM is known at engagement-time we can bypass the entire GATT server
                            // and just connect directly.
                            Logger.i(TAG, "Connecting directly to PSM $psm")
                            centralManager.connectL2cap(psm)
                        } else {
                            centralManager.connectToPeripheral()
                            centralManager.requestMtu()
                            centralManager.peripheralDiscoverServices(uuid)
                            centralManager.peripheralDiscoverCharacteristics()
                            centralManager.checkReaderIdentMatches(eSenderKey)
                            if (centralManager.l2capPsm != null) {
                                centralManager.connectL2cap(centralManager.l2capPsm!!)
                            } else {
                                centralManager.subscribeToCharacteristics()
                                centralManager.writeToStateCharacteristic(BleTransportConstants.STATE_CHARACTERISTIC_START)
                            }
                        }
                        _state.value = State.CONNECTED
                    }
                }
            } catch (error: Throwable) {
                error.unwrapCancellationException().let {
                    failTransport(it)
                    throw it.wrapUnlessCancellationException("Failed while opening transport")
                }
            } finally {
                currentJob = null
            }
        }
    }

    override suspend fun waitForMessage(): ByteArray {
        mutex.withLock {
            check(_state.value == State.CONNECTED) { "Expected state CONNECTED, got ${_state.value}" }
        }
        try {
            return centralManager.incomingMessages.receive()
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
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
        mutex.withLock {
            check(_state.value == State.CONNECTED) { "Expected state CONNECTED, got ${_state.value}" }
            if (message.isEmpty() && centralManager.usingL2cap) {
                throw MdocTransportTerminationException("Transport-specific termination not available with L2CAP")
            }
            try {
                coroutineScope {
                    currentJob = launch {
                        if (message.isEmpty()) {
                            centralManager.writeToStateCharacteristic(BleTransportConstants.STATE_CHARACTERISTIC_END)
                        } else {
                            centralManager.sendMessage(message)
                        }
                    }
                }
            } catch (error: Throwable) {
                throw error.unwrapCancellationException().let {
                    failTransport(it)
                    it.wrapUnlessCancellationException("Failed while sending message")
                }
            } finally {
                currentJob = null
            }
        }
    }

    private fun failTransport(error: Throwable) {
        check(mutex.isLocked) { "failTransport called without holding lock" }
        if (_state.value == State.FAILED || _state.value == State.CLOSED) {
            return
        }
        Logger.w(TAG, "Failing transport with error", error)
        centralManager.close()
        _state.value = State.FAILED
    }

    private fun closeWithoutDelay() {
        check(mutex.isLocked) { "closeWithoutDelay called without holding lock" }
        centralManager.close()
        _state.value = State.CLOSED
    }

    override suspend fun close() {
        currentJob?.cancel("close() was called")
        mutex.withLock {
            if (_state.value == State.FAILED || _state.value == State.CLOSED) {
                return
            }
            closeWithoutDelay()
        }
    }
}
