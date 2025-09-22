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
import org.multipaz.crypto.EcPublicKey
import org.multipaz.mdoc.connectionmethod.MdocConnectionMethod
import org.multipaz.mdoc.connectionmethod.MdocConnectionMethodBle
import org.multipaz.mdoc.role.MdocRole
import org.multipaz.util.Logger
import org.multipaz.util.UUID
import kotlin.time.Duration

internal class BleTransportPeripheralMdoc(
    override val role: MdocRole,
    private val options: MdocTransportOptions,
    private val peripheralManager: BlePeripheralManager,
    private val uuid: UUID
) : MdocTransport() {
    companion object {
        private const val TAG = "BleTransportPeripheralMdoc"
    }

    private val mutex = Mutex()
    private var currentJob: Job? = null

    private val _state = MutableStateFlow<State>(State.IDLE)
    override val state: StateFlow<State> = _state.asStateFlow()

    override val connectionMethod: MdocConnectionMethod
        get() {
            return MdocConnectionMethodBle(
                supportsPeripheralServerMode = true,
                supportsCentralClientMode = false,
                peripheralServerModeUuid = uuid,
                centralClientModeUuid = null,
                peripheralServerModePsm = if (options.bleUseL2CAPInEngagement) peripheralManager.l2capPsm else null,
                peripheralServerModeMacAddress = null
            )
        }

    init {
        peripheralManager.setUuids(
            stateCharacteristicUuid = UUID.fromString("00000001-a123-48ce-896b-4c76973373e6"),
            client2ServerCharacteristicUuid = UUID.fromString("00000002-a123-48ce-896b-4c76973373e6"),
            server2ClientCharacteristicUuid = UUID.fromString("00000003-a123-48ce-896b-4c76973373e6"),
            identCharacteristicUuid = null,
            l2capCharacteristicUuid = if (options.bleUseL2CAP) {
                UUID.fromString("0000000a-a123-48ce-896b-4c76973373e6")
            } else {
                null
            },
            startL2capServer = options.bleUseL2CAPInEngagement
        )
        peripheralManager.setCallbacks(
            onError = { error ->
                runBlocking {
                    currentJob?.cancel("onError was called", error)
                    mutex.withLock {
                        failTransport(error)
                    }
                }
            },
            onClosed = {
                Logger.w(TAG, "BlePeripheralManager close")
                runBlocking {
                    mutex.withLock {
                        closeWithoutDelay()
                    }
                }
            }
        )
    }

    override suspend fun advertise() {
        mutex.withLock {
            check(_state.value == State.IDLE) { "Expected state IDLE, got ${_state.value}" }
            try {
                coroutineScope {
                    currentJob = launch {
                        peripheralManager.waitForPowerOn()
                        peripheralManager.advertiseService(uuid)
                        _state.value = State.ADVERTISING
                    }
                }
            } catch (error: Throwable) {
                throw error.unwrapCancellationException().let {
                    failTransport(it)
                    it.wrapUnlessCancellationException("Failed while advertising")
                }
            } finally {
                currentJob = null
            }
        }
    }

    override val scanningTime: Duration?
        get() = null

    override suspend fun open(eSenderKey: EcPublicKey) {
        mutex.withLock {
            check(_state.value == State.IDLE || _state.value == State.ADVERTISING) {
                "Expected state IDLE or ADVERTISING, got ${_state.value}"
            }
            try {
                coroutineScope {
                    currentJob = launch {
                        if (_state.value != State.ADVERTISING) {
                            // Start advertising if we aren't already...
                            _state.value = State.ADVERTISING
                            peripheralManager.waitForPowerOn()
                            peripheralManager.advertiseService(uuid)
                        }
                        peripheralManager.setESenderKey(eSenderKey)
                        // Note: It's not really possible to know someone is connecting to us until they're _actually_
                        // connected. I mean, for all we know, someone could be BLE scanning us. So not really possible
                        // to go into State.CONNECTING...
                        peripheralManager.waitForStateCharacteristicWriteOrL2CAPClient()
                        _state.value = State.CONNECTED
                    }
                }
            } catch (error: Throwable) {
                throw error.unwrapCancellationException().let {
                    failTransport(it)
                    it.wrapUnlessCancellationException("Failed while opening transport")
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
            return peripheralManager.incomingMessages.receive()
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
            if (message.isEmpty() && peripheralManager.usingL2cap) {
                throw MdocTransportTerminationException("Transport-specific termination not available with L2CAP")
            }
            try {
                coroutineScope {
                    currentJob = launch {
                        if (message.isEmpty()) {
                            peripheralManager.writeToStateCharacteristic(BleTransportConstants.STATE_CHARACTERISTIC_END)
                        } else {
                            peripheralManager.sendMessage(message)
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
        peripheralManager.close()
        _state.value = State.FAILED
    }

    private fun closeWithoutDelay() {
        check(mutex.isLocked) { "closeWithoutDelay called without holding lock" }
        peripheralManager.close()
        _state.value = State.CLOSED
    }

    override suspend fun close() {
        currentJob?.cancel("close() was called")
        mutex.withLock {
            if (_state.value == State.FAILED || _state.value == State.CLOSED) {
                return
            }
            peripheralManager.close()
            _state.value = State.CLOSED
        }
    }
}
