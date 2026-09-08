package org.multipaz.mdoc.transport

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.io.bytestring.ByteString
import org.multipaz.crypto.EcPublicKey
import org.multipaz.mdoc.connectionmethod.MdocConnectionMethod
import org.multipaz.mdoc.role.MdocRole
import org.multipaz.nfc.CommandApdu
import org.multipaz.nfc.ResponseApdu
import org.multipaz.util.Logger
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Duration

/**
 * Hybrid transport for using NFCv2.
 *
 * @param sendMessageViaNfc a function to send a message via NFC, returns false if the NFC connection is no longer up.
 * be on NFC
 */
class NfcHybridTransportMdoc(
    private val sendMessageViaNfc: suspend (message: ByteString) -> Boolean,
): MdocTransport() {
    companion object {
        private const val TAG = "NfcHybridTransportMdoc"
    }

    private val _state = MutableStateFlow<State>(State.IDLE)
    override val state: StateFlow<State> = _state.asStateFlow()

    private val _isNfcConnected = MutableStateFlow(true)

    /**
     * Whether the NFC connection is currently active.
     */
    val isNfcConnected: StateFlow<Boolean> = _isNfcConnected.asStateFlow()

    private var expectTransport: Boolean = false

    override val role: MdocRole
        get() = MdocRole.MDOC

    override val connectionMethod: MdocConnectionMethod
        get() {
            throw IllegalStateException("Should not be called")
        }

    override val scanningTime: Duration?
        get() = null

    override suspend fun advertise() {}

    private var transport: MdocTransport? = null
    private var transportMessageBacklog = mutableListOf<ByteString>()
    private var transportListenMessageJob: Job? = null

    /**
     * Sets whether a [setTransport] is expected to be called.
     *
     * @param expectTransport `true` if it's expected that [setTransport] will be called, `false` if not.
     */
    fun setExpectTransport(expectTransport: Boolean) {
        this.expectTransport = expectTransport
    }

    /**
     * Whether this hybrid transport is operating in NFC-only mode without alternative transports.
     */
    val isNfcOnly: Boolean
        get() = !expectTransport && transport == null

    private var responsePending = false

    /**
     * Marks that a response is pending transmission for the current request.
     */
    fun markResponsePending() {
        responsePending = true
    }

    /**
     * Called when a message has been received via NFC.
     *
     * @param message the message that was received.
     */
    suspend fun onMessageReceivedViaNfc(message: ByteString) {
        Logger.i(TAG, "Received message over NFC of length ${message.size}")
        _isNfcConnected.value = true
        if (responsePending) {
            Logger.i(TAG, "Ignoring reader message of ${message.size} bytes received on re-tap while response is pending")
            return
        }
        conveyMessage(message, true)
    }

    /**
     * Called when a deactivation event on NFC has occurred.
     *
     * @param reason the reason for the deactivation event.
     */
    suspend fun onNfcDeactivated(reason: Int) {
        _isNfcConnected.value = false
        // For NFC-only connections, leaving the field is expected during multi-tap presentment.
        // We do not fail the transport so the presentment session can resume when re-tapped.
    }

    /**
     * Called when the negotiated transport is connected.
     *
     * @param transport the negotiated transport which connected.
     */
    suspend fun setTransport(transport: MdocTransport) {
        this.transport = transport
        val backlog = mutex.withLock { transportMessageBacklog.map { it.toByteArray() } }
        Logger.i(TAG, "Connected to transport, sending backlog of ${backlog.size} messages")
        backlog.forEachIndexed { idx, message ->
            transport.sendMessage(message)
            Logger.i(TAG, "Sending message over Transport of length ${message.size} (backlog index $idx)")
            numSentViaTransport += 1
        }

        transportListenMessageJob = CoroutineScope(Dispatchers.Default).launch {
            try {
                while (true) {
                        val message = transport.waitForMessage()
                        Logger.i(TAG, "Received message over Transport of length ${message.size}")
                        conveyMessage(ByteString(message), false)
                }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                Logger.e(TAG, "Caught exception while waiting for message on transport", e)
            }
        }
    }

    override suspend fun open(eSenderKey: EcPublicKey) {
        mutex.withLock {
            check(_state.value == State.IDLE) { "Expected state IDLE, got ${_state.value}" }
            _state.value = State.CONNECTED
        }
    }

    private val mutex = Mutex()
    private val writingQueue = Channel<ByteString>(Channel.UNLIMITED)
    private val incomingMessages = Channel<ByteString>(Channel.UNLIMITED)
    private var incomingMessagesNumViaNfc = 0
    private var incomingMessagesNumViaTransport = 0
    private var incomingMessagesNumConveyed = 0

    private var numSent = 0
    private var numSentViaNfc = 0
    private var numSentViaTransport = 0
    private var nfcDisconnected = false

    /**
     * Statistics about the [NfcHybridTransportMdoc].
     */
    val stats: NfcHybridTransportStats
        get() = NfcHybridTransportStats(
            numSent = numSent,
            numSentViaNfc = numSentViaNfc,
            numSentViaTransport = numSentViaTransport,
            numReceived = incomingMessagesNumConveyed,
            numReceivedFirstOnNfc = incomingMessagesNumViaNfc,
            numReceivedFirstOnTransport = incomingMessagesNumViaTransport,
            nfcDisconnectedDuringTransaction = nfcDisconnected
        )

    private suspend fun conveyMessage(message: ByteString, fromNfc: Boolean) {
        val shouldConveyMessage = mutex.withLock {
            val shouldConvey = if (fromNfc) {
                incomingMessagesNumViaNfc++ == incomingMessagesNumConveyed
            } else {
                incomingMessagesNumViaTransport++ == incomingMessagesNumConveyed
            }
            if (shouldConvey) {
                incomingMessagesNumConveyed += 1
            }
            shouldConvey
        }
        val (conveyanceName, otherName) = if (fromNfc) Pair("NFC", "Transport") else Pair("Transport", "NFC")
        if (shouldConveyMessage) {
            Logger.i(TAG, "Conveying message of ${message.size} bytes received via " +
                    "$conveyanceName (not yet received via $otherName)")
            incomingMessages.send(message)
        } else {
            Logger.i(TAG, "Not conveying message of ${message.size} bytes received via " +
                    "$conveyanceName (already received via $otherName)")
        }
    }

    override suspend fun sendMessage(message: ByteArray) {
        responsePending = false
        mutex.withLock {
            check(_state.value == State.CONNECTED) { "Expected state CONNECTED, got ${_state.value}" }
        }
        val messageBs = ByteString(message)
        writingQueue.send(messageBs)
        if (sendMessageViaNfc(messageBs)) {
            Logger.i(TAG, "Sending message over NFC of length ${messageBs.size}")
            numSentViaNfc += 1
        } else {
            if (expectTransport) {
                Logger.i(TAG, "Error sending message over NFC of length ${messageBs.size} - " +
                        "NFC is disconnected, continuing on non-NFC transport")
                nfcDisconnected = true
            } else {
                Logger.i(TAG, "NFC disconnected while sending message. Waiting for re-tap...")
                _isNfcConnected.value = false
                _isNfcConnected.first { it }
                if (sendMessageViaNfc(messageBs)) {
                    Logger.i(TAG, "Successfully sent message over NFC after re-tap of length ${messageBs.size}")
                    numSentViaNfc += 1
                } else {
                    val e = MdocTransportException("Error sending on NFC after re-tap and no non-NFC transport is expected")
                    mutex.lock {
                        failTransport(e)
                    }
                    throw e
                }
            }
        }

        val toSend = mutex.withLock {
            if (transport == null) {
                transportMessageBacklog.add(messageBs)
                null
            } else {
                message
            }
        }
        toSend?.let {
            transport?.sendMessage(it)
            Logger.i(TAG, "Sending message over Transport of length ${toSend.size}")
            numSentViaTransport += 1
        }
    }

    override suspend fun waitForMessage(): ByteArray {
        mutex.withLock {
            check(_state.value == State.CONNECTED) { "Expected state CONNECTED, got ${_state.value}" }
        }
        try {
            return incomingMessages.receive().toByteArray()
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

    override suspend fun close() {
        mutex.withLock {
            if (_state.value == State.CLOSED) {
                return
            }
            if (_isNfcConnected.value) {
                try {
                    sendMessageViaNfc(ByteString())
                } catch (e: Throwable) {
                    // Ignore
                }
            }
            incomingMessages.close()
            transportListenMessageJob?.cancel()
            transportListenMessageJob = null
            transport?.close()
            transport = null
            if (_state.value != State.FAILED) {
                _state.value = State.CLOSED
            }
        }
    }

    private var inError = false

    private fun failTransport(error: Throwable) {
        check(mutex.isLocked) { "failTransport called without holding lock" }
        inError = true
        if (_state.value == State.FAILED || _state.value == State.CLOSED) {
            return
        }
        Logger.w(TAG, "Failing transport with error", error)
        incomingMessages.close(error)
        _state.value = State.FAILED
    }
}