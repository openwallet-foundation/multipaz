package org.multipaz.mdoc.transport

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.io.bytestring.ByteString
import org.multipaz.crypto.EcPublicKey
import org.multipaz.mdoc.connectionmethod.MdocConnectionMethod
import org.multipaz.mdoc.connectionmethod.MdocConnectionMethodBle
import org.multipaz.mdoc.role.MdocRole
import org.multipaz.nfc.NfcDeactivationReason
import org.multipaz.util.Logger
import kotlin.concurrent.Volatile
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
    private var expectedConnectionMethod: MdocConnectionMethod? = null

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
     * @param connectionMethod optional expected [MdocConnectionMethod] for the secondary transport.
     */
    fun setExpectTransport(
        expectTransport: Boolean,
        connectionMethod: MdocConnectionMethod? = null,
    ) {
        this.expectTransport = expectTransport
        if (connectionMethod != null) {
            this.expectedConnectionMethod = connectionMethod
        }
    }

    /**
     * Whether this hybrid transport is operating in NFC-only mode without alternative transports.
     */
    val isNfcOnly: Boolean
        get() = !expectTransport && transport == null && closedSecondaryStats == null

    private var responsePending = false

    @Volatile
    internal var numNfcReTaps: Int = 0
        private set

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
        if (!_isNfcConnected.value) {
            numNfcReTaps += 1
            addWarning(TransportWarning(TransportWarningCode.NFC_DISCONNECTED_WAITING_FOR_RETAP))
        }
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
    fun onNfcDeactivated(reason: NfcDeactivationReason) {
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
        val backlog = mutex.withLock {
            if (_state.value == State.CLOSED || _state.value == State.FAILED) {
                null
            } else {
                this.transport = transport
                transportMessageBacklog.map { it.toByteArray() }
            }
        }
        if (backlog == null) {
            transport.close()
            return
        }
        Logger.i(TAG, "Connected to transport, sending backlog of ${backlog.size} messages")
        backlog.forEachIndexed { idx, message ->
            transport.sendMessage(message)
            Logger.i(TAG, "Sending message over Transport of length ${message.size} (backlog index $idx)")
            numSentViaTransport += 1
        }

        val listenJob = CoroutineScope(Dispatchers.Default).launch {
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
        val shouldClose = mutex.withLock {
            if (_state.value == State.CLOSED || _state.value == State.FAILED) {
                this.transport = null
                true
            } else {
                transportListenMessageJob = listenJob
                false
            }
        }
        if (shouldClose) {
            listenJob.cancel()
            transport.close()
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
    private var incomingMessagesNumConveyedWasViaNfc = 0
    private var incomingMessagesNumConveyedWasViaTransport = 0

    private var numSent = 0
    private var numSentViaNfc = 0
    private var numSentViaTransport = 0
    private var bytesSent = 0L
    private var bytesReceived = 0L
    @Volatile
    private var nfcDisconnected = false
    private val _warnings = MutableStateFlow<List<TransportWarning>>(emptyList())
    @Volatile
    private var closedSecondaryStats: MdocTransportStats? = null
    @Volatile
    private var closedSecondaryConnectionMethod: MdocConnectionMethod? = null

    internal fun addWarning(warning: TransportWarning) {
        _warnings.update { it + warning }
    }

    private fun resolveTransportType(secondaryStats: MdocTransportStats?): MdocTransportType {
        if (isNfcOnly) {
            return MdocTransportType.NFC_CCE_NFC_ONLY
        }
        when (secondaryStats?.transportType) {
            MdocTransportType.MDOC_CENTRAL_CLIENT_MODE -> return MdocTransportType.NFC_CCE_MDOC_CENTRAL_CLIENT_MODE
            MdocTransportType.MDOC_PERIPHERAL_SERVER_MODE -> return MdocTransportType.NFC_CCE_MDOC_PERIPHERAL_SERVER_MODE
            else -> {}
        }
        val cm = transport?.let { runCatching { it.connectionMethod }.getOrNull() }
            ?: closedSecondaryConnectionMethod
            ?: expectedConnectionMethod
        if (cm is MdocConnectionMethodBle) {
            if (cm.supportsCentralClientMode) {
                return MdocTransportType.NFC_CCE_MDOC_CENTRAL_CLIENT_MODE
            }
            if (cm.supportsPeripheralServerMode) {
                return MdocTransportType.NFC_CCE_MDOC_PERIPHERAL_SERVER_MODE
            }
        }
        return MdocTransportType.OTHER
    }

    /**
     * Statistics about the [NfcHybridTransportMdoc].
     */
    override val stats: MdocTransportStats
        get() {
            val secondaryStats = transport?.stats ?: closedSecondaryStats
            return MdocTransportStats(
                transportType = resolveTransportType(secondaryStats),
                role = role,
                scanningTime = secondaryStats?.scanningTime,
                connectingTime = secondaryStats?.connectingTime,
                bytesSent = bytesSent,
                bytesReceived = bytesReceived,
                numMessagesSent = numSent,
                numMessagesReceived = incomingMessagesNumConveyed,
                warnings = (_warnings.value + (secondaryStats?.warnings ?: emptyList())).distinct(),
                bleUsingL2cap = secondaryStats?.bleUsingL2cap ?: false,
                bleL2capPsmInEngagement = secondaryStats?.bleL2capPsmInEngagement ?: false,
                bleNegotiatedMtu = secondaryStats?.bleNegotiatedMtu,
                bleMtuInferredFromWrite = secondaryStats?.bleMtuInferredFromWrite ?: false,
                bleNumScanRestarts = secondaryStats?.bleNumScanRestarts ?: 0,
                bleNumGattConnectRetries = secondaryStats?.bleNumGattConnectRetries ?: 0,
                bleNumServiceDiscoveryRetries = secondaryStats?.bleNumServiceDiscoveryRetries ?: 0,
                bleNumL2capConnectRetries = secondaryStats?.bleNumL2capConnectRetries ?: 0,
                bleNumChunksSent = secondaryStats?.bleNumChunksSent ?: 0,
                bleNumChunksReceived = secondaryStats?.bleNumChunksReceived ?: 0,
                nfcHybridNumSentViaNfc = numSentViaNfc,
                nfcHybridNumSentViaTransport = numSentViaTransport,
                nfcHybridNumReceivedFirstOnNfc = incomingMessagesNumConveyedWasViaNfc,
                nfcHybridNumReceivedFirstOnTransport = incomingMessagesNumConveyedWasViaTransport,
                nfcDisconnectedDuringTransaction = nfcDisconnected,
            )
        }

    private suspend fun conveyMessage(message: ByteString, fromNfc: Boolean) {
        val shouldConveyMessage = mutex.withLock {
            val shouldConvey = if (fromNfc) {
                incomingMessagesNumViaNfc++ == incomingMessagesNumConveyed
            } else {
                incomingMessagesNumViaTransport++ == incomingMessagesNumConveyed
            }
            if (shouldConvey) {
                incomingMessagesNumConveyed += 1
                if (fromNfc) {
                    incomingMessagesNumConveyedWasViaNfc += 1
                } else {
                    incomingMessagesNumConveyedWasViaTransport += 1
                }
                if (message.size > 0) {
                    bytesReceived += message.size
                }
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
        if (message.isNotEmpty()) {
            numSent += 1
            bytesSent += message.size
        }
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
                addWarning(TransportWarning(TransportWarningCode.NFC_DISCONNECTED_WAITING_FOR_RETAP))
                _isNfcConnected.value = false
                _isNfcConnected.first { it }
                if (sendMessageViaNfc(messageBs)) {
                    Logger.i(TAG, "Successfully sent message over NFC after re-tap of length ${messageBs.size}")
                    numSentViaNfc += 1
                } else {
                    val e = MdocTransportException("Error sending on NFC after re-tap and no non-NFC transport is expected")
                    mutex.withLock {
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
            writingQueue.close()
            transportListenMessageJob?.cancel()
            transportListenMessageJob = null
            transport?.let {
                closedSecondaryStats = it.stats
                closedSecondaryConnectionMethod = runCatching { it.connectionMethod }.getOrNull()
                it.close()
            }
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