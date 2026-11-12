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
import org.multipaz.mdoc.nfc.nfcCceTransact
import org.multipaz.mdoc.role.MdocRole
import org.multipaz.nfc.NfcIsoTag
import org.multipaz.util.Logger
import kotlin.concurrent.Volatile
import kotlin.math.min
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

/**
 * Transport for ISO/IEC 18013-5 Second Edition NFC Concurrent Channel Engagement (CCE) on the reader side.
 *
 * @param nfcTag the [NfcIsoTag] that was used for NFC CCE engagement and which is used to send data over.
 * @param negotiatedTransport the [MdocTransport] that was negotiated between reader and holder or `null` if only NFC
 * data exchange is supported.
 * @param apduCommandMaxSize the maximum command APDU data length indicated by the mdoc during CCE handover.
 * @param apduResponseMaxSize the maximum response APDU data length indicated by the mdoc during CCE handover.
 */
class NfcCceTransportMdocReader(
    private val nfcTag: NfcIsoTag,
    private val negotiatedTransport: MdocTransport?,
    private val apduCommandMaxSize: Int = 65535,
    private val apduResponseMaxSize: Int = 65536,
): MdocTransport() {
    companion object {
        private const val TAG = "NfcCceTransportMdocReader"
    }

    /**
     * Whether this CCE reader transport is operating in NFC-only mode without a secondary transport.
     */
    val isNfcOnly: Boolean
        get() = negotiatedTransport == null

    private var numSent = 0
    private var numSentViaNfc = 0
    private var numSentViaTransport = 0
    private var bytesSent = 0L
    private var bytesReceived = 0L

    @Volatile
    private var disconnectNfcSent = false

    @Volatile
    private var disconnectNfcReceived = false

    private fun resolveTransportType(secondaryStats: MdocTransportStats?): MdocTransportType {
        val transport = negotiatedTransport ?: return MdocTransportType.NFC_CCE_NFC_ONLY
        when (secondaryStats?.transportType) {
            MdocTransportType.MDOC_CENTRAL_CLIENT_MODE -> return MdocTransportType.NFC_CCE_MDOC_CENTRAL_CLIENT_MODE
            MdocTransportType.MDOC_PERIPHERAL_SERVER_MODE -> return MdocTransportType.NFC_CCE_MDOC_PERIPHERAL_SERVER_MODE
            else -> {}
        }
        val cm = transport.connectionMethod
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
     * Statistics about the [NfcCceTransportMdocReader].
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

    override val connectionMethod: MdocConnectionMethod?
        get() = negotiatedTransport?.connectionMethod

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
    private val receivedMessagesBySeq = mutableMapOf<Int, ByteString>()

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
        val info = parseCceSessionMessageInfo(message)
        val shouldConveyMessage = mutex.withLock {
            if (info.disconnectNfc) {
                disconnectNfcReceived = true
                nfcNotAvailable = true
            }
            if (info.seq != null) {
                // Confirm receipt of any queued messages with a lower sequence number (Clause 9.5.5 Note 1)
                negotiatedTransportPendingOutgoingMessages.removeAll { queued ->
                    val queuedSeq = parseCceSessionMessageInfo(queued).seq
                    queuedSeq != null && queuedSeq < info.seq
                }
                val existing = receivedMessagesBySeq[info.seq]
                if (existing != null) {
                    if (fromNfc) {
                        incomingMessagesNumViaNfc += 1
                    } else {
                        incomingMessagesNumViaTransport += 1
                    }
                    if (existing != message) {
                        val err = MdocTransportException(
                            "Received non-identical messages with seq=${info.seq} on NFC and secondary transport"
                        )
                        failTransport(err)
                        throw err
                    }
                    false
                } else {
                    receivedMessagesBySeq[info.seq] = message
                    if (fromNfc) {
                        incomingMessagesNumViaNfc += 1
                        incomingMessagesNumConveyedWasViaNfc += 1
                    } else {
                        incomingMessagesNumViaTransport += 1
                        incomingMessagesNumConveyedWasViaTransport += 1
                    }
                    incomingMessagesNumConveyed += 1
                    if (message.size > 0) {
                        bytesReceived += message.size
                    }
                    true
                }
            } else {
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
        if (info.disconnectNfc) {
            if (!fromNfc) {
                ioJob?.cancel()
            }
            closeTag()
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
                    val pendingMessages = mutex.withLock {
                        val list = negotiatedTransportPendingOutgoingMessages.toList()
                        negotiatedTransportPendingOutgoingMessages.clear()
                        list
                    }
                    Logger.i(
                        TAG, "Connected to negotiated transport, sending " +
                                "${pendingMessages.size} pending messages"
                    )
                    pendingMessages.forEachIndexed { idx, message ->
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
                    val maxCmdLen = min(nfcTag.maxTransceiveLength, min(apduCommandMaxSize, 65530))
                    val maxRespLen = min(nfcTag.maxTransceiveLength, min(apduResponseMaxSize, 65530))
                    val responseMessage = nfcTag.nfcCceTransact(
                        message = messageToSend,
                        commandDataFieldMaxLength = maxCmdLen,
                        responseDataFieldMaxLength = maxRespLen,
                        onMessageSent = {
                            sentMessagesNumViaNfc += 1
                        }
                    )
                    Logger.i(TAG, "Received message over NFC of length ${responseMessage.size}")
                    conveyMessage(responseMessage, true)
                    if (disconnectNfcSent || disconnectNfcReceived) {
                        nfcNotAvailable = true
                        closeTag()
                        break
                    }
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
        val messageBs = ByteString(message)
        val info = parseCceSessionMessageInfo(messageBs)
        val canSendViaNfc = !nfcNotAvailable && !disconnectNfcSent && !disconnectNfcReceived
        if (info.disconnectNfc) {
            disconnectNfcSent = true
        }
        if (canSendViaNfc) {
            writingQueue.send(messageBs)
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
                mutex.withLock {
                    negotiatedTransportPendingOutgoingMessages.add(messageBs)
                }
            }
        }
        if (message.isNotEmpty()) {
            bytesSent += message.size
        }
        numSent += 1
    }

    override suspend fun waitForMessage(): ByteArray {
        mutex.withLock {
            if (_state.value == State.FAILED) {
                throw MdocTransportException("Transport already failed")
            }
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
            if (_state.value == State.CLOSED || _state.value == State.FAILED) {
                return
            }
            // Wait 500ms with canceling the I/O job to give the last sent message a chance to be sent. This
            // is very common because a mdoc reader typically receives a response, then sends a session termination
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
