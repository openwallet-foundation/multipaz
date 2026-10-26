package org.multipaz.mdoc.transport

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.io.bytestring.ByteString
import org.multipaz.crypto.EcPublicKey
import org.multipaz.mdoc.connectionmethod.MdocConnectionMethod
import org.multipaz.mdoc.connectionmethod.MdocConnectionMethodBle
import org.multipaz.mdoc.connectionmethod.MdocConnectionMethodNfcV2
import org.multipaz.mdoc.nfc.nfcV2Transact
import org.multipaz.mdoc.role.MdocRole
import org.multipaz.nfc.NfcIsoTag
import org.multipaz.util.Logger
import kotlin.math.min
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds


/**
 * Hybrid transport for ISO 18013-5 Second Edition NFCv2 engagement on the reader side.
 *
 * @param nfcTag the [NfcIsoTag] that was used for NFCv2 engagement and which is used to send data over.
 * @param negotiatedTransport the [MdocTransport] that was negotiated between reader and holder or `null` if only NFC
 * data exchange is supported.
 */
class NfcHybridTransportMdocReader(
    private val nfcTag: NfcIsoTag,
    private val negotiatedTransport: MdocTransport?
): MdocTransport() {
    companion object {
        private const val TAG = "NfcHybridTransportMdocReader"
    }

    private var numSent = 0
    private var numSentViaNfc = 0
    private var numSentViaTransport = 0
    private var bytesSent = 0L
    private var bytesReceived = 0L

    private fun resolveTransportType(secondaryStats: MdocTransportStats?): MdocTransportType {
        val transport = negotiatedTransport ?: return MdocTransportType.NFC_CCE_NFC_ONLY
        when (secondaryStats?.transportType) {
            MdocTransportType.MDOC_CENTRAL_CLIENT_MODE -> return MdocTransportType.NFC_CCE_MDOC_CENTRAL_CLIENT_MODE
            MdocTransportType.MDOC_PERIPHERAL_SERVER_MODE -> return MdocTransportType.NFC_CCE_MDOC_PERIPHERAL_SERVER_MODE
            else -> {}
        }
        val cm = runCatching { transport.connectionMethod }.getOrNull()
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
     * Statistics about the [NfcHybridTransportMdocReader].
     */
    override val stats: MdocTransportStats
        get() {
            val secondaryStats = negotiatedTransport?.stats
            return MdocTransportStats(
                transportType = resolveTransportType(secondaryStats),
                role = role,
                scanningTime = secondaryStats?.scanningTime,
                connectingTime = secondaryStats?.connectingTime,
                bytesSent = bytesSent,
                bytesReceived = bytesReceived,
                numMessagesSent = numSent,
                numMessagesReceived = incomingMessagesNumConveyed,
                warnings = secondaryStats?.warnings ?: emptyList(),
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
                nfcDisconnectedDuringTransaction = nfcNotAvailable,
            )
        }

    private val _state = MutableStateFlow<State>(State.IDLE)
    override val state: StateFlow<State> = _state.asStateFlow()

    override val role: MdocRole
        get() = MdocRole.MDOC_READER

    override val connectionMethod: MdocConnectionMethod
        get() = negotiatedTransport?.connectionMethod
            ?: MdocConnectionMethodNfcV2()

    override val scanningTime: Duration?
        get() = negotiatedTransport?.scanningTime

    override suspend fun advertise() {
    }

    private val mutex = Mutex()
    private val tagMutex = Mutex()
    private var tagClosed = false
    private var ioJob: Job? = null

    private suspend fun closeTag() {
        tagMutex.withLock {
            if (!tagClosed) {
                tagClosed = true
                withContext(NonCancellable) {
                    nfcTag.close()
                }
            }
        }
    }
    private val writingQueue = Channel<ByteString>(Channel.UNLIMITED)
    private val incomingMessages = Channel<ByteString>(Channel.UNLIMITED)

    // Counts how many messages was sent via NFC
    private var sentMessagesNumViaNfc: Int = 0

    // Counts how many total messages we received via NFC
    private var incomingMessagesNumViaNfc = 0

    // Counts how many total messages we received via Transport
    private var incomingMessagesNumViaTransport = 0

    // Counts how many total messages received via either NFC or Transport
    private var incomingMessagesNumConveyed = 0

    // Number of messages where we received them first on NFC
    private var incomingMessagesNumConveyedWasViaNfc = 0

    // Number of messages where we received them first on Transport
    private var incomingMessagesNumConveyedWasViaTransport = 0

    private var nfcNotAvailable = false

    private var negotiatedTransportOpenJob: Job? = null
    private val negotiatedTransportPendingOutgoingMessages = mutableListOf<ByteString>()

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


    override suspend fun open(eSenderKey: EcPublicKey) {
        mutex.withLock {
            check(_state.value == State.IDLE) { "Expected state IDLE, got ${_state.value}" }
            _state.value = State.CONNECTED
        }

        if (negotiatedTransport != null) {
            negotiatedTransportOpenJob = CoroutineScope(Dispatchers.Default).launch {
                try {
                    negotiatedTransport.open(eSenderKey)
                    negotiatedTransportOpenJob = null
                    Logger.i(
                        TAG, "Connected to negotiated transport, sending " +
                                "${negotiatedTransportPendingOutgoingMessages.size} pending messages"
                    )
                    negotiatedTransportPendingOutgoingMessages.forEachIndexed { idx, message ->
                        negotiatedTransport.sendMessage(message.toByteArray())
                        Logger.i(TAG, "Sending message over Transport of length ${message.size} (backlog index $idx)")
                        numSentViaTransport += 1
                    }

                    while (true) {
                        val message = negotiatedTransport.waitForMessage()
                        Logger.i(TAG, "Received message over Transport of length ${message.size}")
                        conveyMessage(ByteString(message), false)
                    }
                } catch (e: Throwable) {
                    mutex.withLock {
                        failTransport(e)
                    }
                }
            }
        }

        // This job is canceled in close()
        ioJob = CoroutineScope(Dispatchers.Default).launch {
            try {
                while (true) {
                    Logger.i(TAG, "Waiting for message to send over NFC")
                    val messageToSend = writingQueue.receive()
                    numSentViaNfc += 1
                    Logger.i(TAG, "Sending message over NFC of length ${messageToSend.size}")
                    val maxLen = min(nfcTag.maxTransceiveLength, 65530)
                    val responseMessage = nfcTag.nfcV2Transact(
                        message = messageToSend,
                        commandDataFieldMaxLength = maxLen,
                        responseDataFieldMaxLength = maxLen,
                        onMessageSent = {
                            sentMessagesNumViaNfc += 1
                        }
                    )
                    Logger.i(TAG, "Received message over NFC of length ${responseMessage.size}")
                    conveyMessage(responseMessage, true)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                if (negotiatedTransport != null) {
                    Logger.w(TAG, "Error while transacting on NFC - continuing on non-NFC transport", e)
                    nfcNotAvailable = true
                    closeTag()
                } else {
                    mutex.withLock {
                        failTransport(e)
                    }
                }
            }
        }
    }

    override suspend fun sendMessage(message: ByteArray) {
        mutex.withLock {
            check(_state.value == State.CONNECTED) { "Expected state CONNECTED, got ${_state.value}" }
        }
        if (!nfcNotAvailable) {
            writingQueue.send(ByteString(message))
        }

        if (negotiatedTransport != null) {
            if (negotiatedTransport.state.value == State.CONNECTED) {
                try {
                    negotiatedTransport.sendMessage(message)
                    Logger.i(TAG, "Sending message over Transport of length ${message.size}")
                    numSentViaTransport += 1
                } catch (e: Throwable) {
                    mutex.withLock {
                        failTransport(e)
                    }
                    throw e
                }
            } else {
                negotiatedTransportPendingOutgoingMessages.add(ByteString(message))
            }
        }
        if (message.isNotEmpty()) {
            bytesSent += message.size
        }
        numSent += 1
    }

    override suspend fun waitForMessage(): ByteArray {
        mutex.withLock {
            check(_state.value == State.CONNECTED || _state.value == State.FAILED) {
                "Expected state CONNECTED or FAILED, got ${_state.value}"
            }
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
            if (_state.value == State.CLOSED || _state.value == State.FAILED) {
                return
            }
            // Wait 500ms with canceling the I/O job to give the last sent message a chance to be sent. This
            // is very common because a mdoc reader typically receives a response, then sends session a termination
            // message, and then immediately closes the connection.
            val job = ioJob
            if (job != null && !nfcNotAvailable) {
                CoroutineScope(Dispatchers.Default).launch {
                    delay(500.milliseconds)
                    job.cancel()
                    closeTag()
                }
            } else {
                job?.cancel()
                closeTag()
            }
            ioJob = null
            incomingMessages.close()
            negotiatedTransportOpenJob?.cancel()
            negotiatedTransportOpenJob = null
            negotiatedTransport?.close()
            _state.value = State.CLOSED
        }
    }

    private var inError = false

    private suspend fun failTransport(error: Throwable) {
        check(mutex.isLocked) { "failTransport called without holding lock" }
        inError = true
        if (_state.value == State.FAILED || _state.value == State.CLOSED) {
            return
        }
        Logger.w(TAG, "Failing transport with error", error)
        incomingMessages.close(error)
        ioJob?.cancel()
        ioJob = null
        closeTag()
        incomingMessages.close()
        _state.value = State.FAILED
    }
}