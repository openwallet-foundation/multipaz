package org.multipaz.nci

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.io.bytestring.ByteString
import org.multipaz.nfc.NfcTagLostException
import org.multipaz.util.Logger
import kotlin.concurrent.Volatile
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

private const val TAG = "NciEngine"

/**
 * Details of an activated RF interface reported by `RF_INTF_ACTIVATED_NTF`.
 *
 * @property rfDiscoveryId RF Discovery ID assigned to the activated target.
 * @property rfInterface RF Interface type (e.g. `RF_INTERFACE_ISO_DEP`).
 * @property rfProtocol RF Protocol (e.g. `PROTOCOL_ISO_DEP`).
 * @property activationRfTechAndMode RF Technology and Mode of the data exchange.
 * @property maxDataPacketPayloadSize maximum payload size of an NCI Data packet on the Static RF Connection.
 * @property initialCredits initial number of data credits for the Static RF Connection.
 * @property historicalBytes ISO-DEP Type A RATS historical bytes, if present.
 * @property applicationData ISO-DEP Type B ATTRIB higher layer response / application data, if present.
 */
data class NciRfActivatedEvent(
    val rfDiscoveryId: Int,
    val rfInterface: Int,
    val rfProtocol: Int,
    val activationRfTechAndMode: Int,
    val maxDataPacketPayloadSize: Int,
    val initialCredits: Int,
    val historicalBytes: ByteString?,
    val applicationData: ByteString?,
) {
    /**
     * Whether the RF interface was activated in Listen Mode (card emulation).
     */
    val isListenMode: Boolean
        get() = NciConstants.isListenMode(activationRfTechAndMode)

    /**
     * Whether the RF interface was activated in Poll Mode (reader/writer).
     */
    val isPollMode: Boolean
        get() = NciConstants.isPollMode(activationRfTechAndMode)
}

/**
 * Listener for asynchronous NCI events emitted by [NciEngine].
 */
internal interface NciEngineListener {
    fun onRfActivated(event: NciRfActivatedEvent)
    fun onRfDeactivated(deactivationType: Int, deactivationReason: Int)
    fun onDataPacketReceived(data: ByteArray)
    fun onEngineError(error: Throwable)
}

/**
 * Core NFC Forum NCI 2.0 protocol engine.
 *
 * Manages controller initialization (`CORE_RESET`, `CORE_INIT`, `NXP_PROPRIETARY_ACT`,
 * `RF_DISCOVER_MAP`, `CORE_SET_CONFIG`, `RF_SET_LISTEN_MODE_ROUTING`), RF discovery state
 * transitions (`RF_DISCOVER_CMD`, `RF_DEACTIVATE_CMD`), ISO-DEP data segmentation/reassembly
 * across the `PBF` bit, and Static RF Connection (`0x00`) credit flow control.
 */
internal class NciEngine(
    private val packetIo: NciPacketIo,
    private val listener: NciEngineListener,
    private val commandTimeout: Duration = 3.seconds,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
) {
    private val commandMutex = Mutex()
    private val dataSendMutex = Mutex()
    private val stateMutex = Mutex()

    @Volatile
    private var closed = false

    @Volatile
    var nciVersion: Int = NciConstants.NCI_VERSION_2_0
        private set

    @Volatile
    var maxDataPacketPayloadSize: Int = 251
        private set

    @Volatile
    private var availableCredits: Int = 1

    @Volatile
    private var creditWaiter: CompletableDeferred<Unit>? = null

    @Volatile
    private var pendingCommandGid: Int = -1

    @Volatile
    private var pendingCommandOid: Int = -1

    @Volatile
    private var pendingCommandResponse: CompletableDeferred<ByteArray>? = null

    @Volatile
    private var pendingResetNotification: CompletableDeferred<ByteArray>? = null

    @Volatile
    private var lastResetNotification: ByteArray? = null

    @Volatile
    private var pendingDeactivateNotification: CompletableDeferred<Pair<Int, Int>>? = null

    @Volatile
    private var pendingPresenceCheckNotification: CompletableDeferred<Int>? = null

    @Volatile
    private var pendingResponseApdu: CompletableDeferred<ByteArray>? = null

    @Volatile
    var activeRfEvent: NciRfActivatedEvent? = null
        private set

    private var readerJob: Job? = null

    // Buffers for segmented NCI packets (PBF = 1)
    private var segmentedControlHeader0: Int = 0
    private var segmentedControlHeader1: Int = 0
    private var segmentedControlBuffer = ByteArray(0)

    private var segmentedDataBuffer = ByteArray(0)

    private val incomingEventChannel = Channel<suspend () -> Unit>(Channel.UNLIMITED)
    private var eventDispatcherJob: Job? = null

    /**
     * Starts the packet reader loop and initializes the NCI controller into `RFST_IDLE`.
     */
    suspend fun initializeController() {
        startReaderLoopIfNeeded()
        stateMutex.withLock {
            lastResetNotification = null
            pendingResetNotification = CompletableDeferred()
        }

        packetIo.resetHardware()

        // 1. CORE_RESET_CMD (Reset Configuration)
        val resetRsp = sendCommand(
            gid = NciConstants.GID_CORE,
            oid = NciConstants.OID_CORE_RESET,
            payload = byteArrayOf(NciConstants.RESET_TYPE_RESET_CONFIG.toByte())
        )
        checkStatus("CORE_RESET_RSP", resetRsp)

        if (resetRsp.size >= 3) {
            // NCI 1.x format: [Status, NciVersion, ConfigStatus]
            nciVersion = resetRsp[1].toInt() and 0xFF
        } else {
            // NCI 2.0 format: [Status] followed by CORE_RESET_NTF: [ResetTrigger, ConfigStatus, NciVersion, ...]
            val resetNtf = withRealTimeTimeout(commandTimeout) {
                val (existing, waiter) = stateMutex.withLock {
                    lastResetNotification to pendingResetNotification
                }
                existing ?: waiter!!.await()
            }
            if (resetNtf.size >= 3) {
                nciVersion = resetNtf[2].toInt() and 0xFF
            }
        }
        Logger.d(TAG, "NCI controller reset complete (NCI version 0x${nciVersion.toString(16)})")

        // 2. CORE_INIT_CMD
        val initPayload = if (nciVersion >= NciConstants.NCI_VERSION_2_0) {
            byteArrayOf(0x00, 0x00)
        } else {
            ByteArray(0)
        }
        val initRsp = sendCommand(
            gid = NciConstants.GID_CORE,
            oid = NciConstants.OID_CORE_INIT,
            payload = initPayload
        )
        checkStatus("CORE_INIT_RSP", initRsp)
        if (nciVersion < NciConstants.NCI_VERSION_2_0 && initRsp.size >= 9) {
            // In NCI 1.x, bytes 7 and 8 are MaxDataPacketPayloadSize and InitialCredits for Static RF Conn.
            // In NCI 2.0, these are provided per-activation in RF_INTF_ACTIVATED_NTF bytes 4 and 5.
            val maxPayload = initRsp[7].toInt() and 0xFF
            if (maxPayload in 1..255) {
                maxDataPacketPayloadSize = maxPayload
            }
            val initCredits = initRsp[8].toInt() and 0xFF
            if (initCredits > 0) {
                stateMutex.withLock {
                    availableCredits = initCredits
                }
            }
        }

        // 3. NXP_PROPRIETARY_ACT_CMD (2F 02 00) — enables PN7160 proprietary extensions.
        // Non-NXP controllers may reject this with SYNTAX_ERROR/SEMANTIC_ERROR, which is safe to ignore.
        val propRsp = sendCommand(
            gid = NciConstants.GID_PROPRIETARY,
            oid = NciConstants.OID_NXP_PROPRIETARY_ACT,
            payload = ByteArray(0)
        )
        if (propRsp.isNotEmpty() && (propRsp[0].toInt() and 0xFF) == NciConstants.STATUS_OK) {
            Logger.d(TAG, "NXP proprietary extensions activated")
        }

        // 4. RF_DISCOVER_MAP_CMD: Map PROTOCOL_ISO_DEP to RF_INTERFACE_ISO_DEP for both Poll & Listen,
        // and PROTOCOL_T2T to RF_INTERFACE_FRAME for Poll.
        val discoverMapPayload = byteArrayOf(
            0x02, // 2 mapping entries
            NciConstants.PROTOCOL_ISO_DEP.toByte(),
            (NciConstants.DISCOVER_MAP_MODE_POLL or NciConstants.DISCOVER_MAP_MODE_LISTEN).toByte(),
            NciConstants.RF_INTERFACE_ISO_DEP.toByte(),
            NciConstants.PROTOCOL_T2T.toByte(),
            NciConstants.DISCOVER_MAP_MODE_POLL.toByte(),
            NciConstants.RF_INTERFACE_FRAME.toByte()
        )
        val mapRsp = sendCommand(
            gid = NciConstants.GID_RF_MGMT,
            oid = NciConstants.OID_RF_DISCOVER_MAP,
            payload = discoverMapPayload
        )
        checkStatus("RF_DISCOVER_MAP_RSP", mapRsp)

        // 5. CORE_SET_CONFIG_CMD:
        // - TOTAL_DURATION (0x00) = 500 ms (0x01F4 LE)
        // - LA_SEL_INFO (0x32) = 0x20 (ISO-DEP bit set in NFC-A SAK for Listen Mode)
        // - LF_PROTOCOL_TYPE (0x50) = 0x00 (disable NFC-DEP P2P in Listen Mode)
        val setConfigPayload = byteArrayOf(
            0x03, // 3 parameters
            NciConstants.PARAM_TOTAL_DURATION.toByte(), 0x02, 0xF4.toByte(), 0x01,
            NciConstants.PARAM_LA_SEL_INFO.toByte(), 0x01, 0x20,
            NciConstants.PARAM_LF_PROTOCOL_TYPE.toByte(), 0x01, 0x00
        )
        val configRsp = sendCommand(
            gid = NciConstants.GID_CORE,
            oid = NciConstants.OID_CORE_SET_CONFIG,
            payload = setConfigPayload
        )
        checkStatus("CORE_SET_CONFIG_RSP", configRsp)

        // 5b. High-speed ISO-DEP parameters (848 kbps Poll & Listen, FWI=7 / SFGI=0 in Listen Mode):
        // Sent as a separate optional CORE_SET_CONFIG_CMD so controllers that do not support 848 kbps
        // or specific optional ISO-DEP parameters still keep the base configuration above.
        val highSpeedConfigPayload = byteArrayOf(
            0x03, // 3 parameters
            NciConstants.PARAM_PI_BIT_RATE.toByte(), 0x01, NciConstants.BIT_RATE_848_KBPS.toByte(),
            NciConstants.PARAM_LI_A_BIT_RATE.toByte(), 0x01, NciConstants.BIT_RATE_848_KBPS.toByte(),
            NciConstants.PARAM_LI_A_RATS_TB1.toByte(), 0x01, 0x70
        )
        val highSpeedRsp = sendCommand(
            gid = NciConstants.GID_CORE,
            oid = NciConstants.OID_CORE_SET_CONFIG,
            payload = highSpeedConfigPayload
        )
        if (highSpeedRsp.isNotEmpty() && (highSpeedRsp[0].toInt() and 0xFF) == NciConstants.STATUS_OK) {
            Logger.d(TAG, "Configured ISO-DEP up to 848 kbps (FWI=7)")
        }

        // 6. RF_SET_LISTEN_MODE_ROUTING_CMD:
        // Route PROTOCOL_ISO_DEP and RF_TECH_A in Listen Mode to Device Host (DH = 0x00).
        val routingPayload = byteArrayOf(
            0x00, // More = 0 (complete routing table)
            0x02, // 2 routing entries
            // Entry 1: Protocol-based routing for PROTOCOL_ISO_DEP -> DH (0x00)
            NciConstants.ROUTE_ENTRY_TYPE_PROTOCOL.toByte(),
            0x03,
            NciConstants.DH_NFCEE_ID.toByte(),
            NciConstants.POWER_STATE_SWITCHED_ON.toByte(),
            NciConstants.PROTOCOL_ISO_DEP.toByte(),
            // Entry 2: Technology-based routing for RF_TECH_A -> DH (0x00)
            NciConstants.ROUTE_ENTRY_TYPE_TECH.toByte(),
            0x03,
            NciConstants.DH_NFCEE_ID.toByte(),
            NciConstants.POWER_STATE_SWITCHED_ON.toByte(),
            NciConstants.RF_TECH_A.toByte()
        )
        val routeRsp = sendCommand(
            gid = NciConstants.GID_RF_MGMT,
            oid = NciConstants.OID_RF_SET_LISTEN_MODE_ROUTING,
            payload = routingPayload
        )
        checkStatus("RF_SET_LISTEN_MODE_ROUTING_RSP", routeRsp)
    }

    /**
     * Starts RF discovery for the requested Poll and/or Listen modes.
     */
    suspend fun startDiscovery(
        enablePoll: Boolean,
        enableListen: Boolean
    ) {
        val configs = mutableListOf<Pair<Int, Int>>()
        if (enablePoll) {
            configs.add(NciConstants.NFC_A_PASSIVE_POLL_MODE to 1)
            configs.add(NciConstants.NFC_B_PASSIVE_POLL_MODE to 1)
        }
        if (enableListen) {
            configs.add(NciConstants.NFC_A_PASSIVE_LISTEN_MODE to 1)
        }
        if (configs.isEmpty()) {
            return
        }
        val payload = ByteArray(1 + configs.size * 2)
        payload[0] = configs.size.toByte()
        configs.forEachIndexed { index, (techAndMode, freq) ->
            payload[1 + index * 2] = techAndMode.toByte()
            payload[2 + index * 2] = freq.toByte()
        }
        val rsp = sendCommand(
            gid = NciConstants.GID_RF_MGMT,
            oid = NciConstants.OID_RF_DISCOVER,
            payload = payload
        )
        val status = rsp.firstOrNull()?.toInt()?.and(0xFF) ?: NciConstants.STATUS_FAILED
        if (status != NciConstants.STATUS_OK && status != NciConstants.STATUS_DISCOVERY_ALREADY_STARTED) {
            throw IllegalStateException("RF_DISCOVER_CMD failed with status 0x${status.toString(16)}")
        }
    }

    /**
     * Sends `RF_DEACTIVATE_CMD` with [deactivationType] (`DEACTIVATE_TYPE_IDLE`,
     * `DEACTIVATE_TYPE_SLEEP`, or `DEACTIVATE_TYPE_DISCOVERY`).
     */
    suspend fun deactivate(deactivationType: Int) {
        val wasActivated = activeRfEvent != null
        val deactivateNtfDeferred = if (wasActivated) {
            CompletableDeferred<Pair<Int, Int>>().also {
                stateMutex.withLock {
                    pendingDeactivateNotification = it
                }
            }
        } else {
            null
        }
        val rsp = sendCommand(
            gid = NciConstants.GID_RF_MGMT,
            oid = NciConstants.OID_RF_DEACTIVATE,
            payload = byteArrayOf(deactivationType.toByte())
        )
        val status = rsp.firstOrNull()?.toInt()?.and(0xFF) ?: NciConstants.STATUS_FAILED
        if (status == NciConstants.STATUS_OK && deactivateNtfDeferred != null) {
            try {
                withRealTimeTimeout(500.milliseconds) {
                    deactivateNtfDeferred.await()
                }
            } catch (_: Exception) {
                // In RFST_DISCOVERY, RF_DEACTIVATE_CMD(IDLE) transitions directly without RF_DEACTIVATE_NTF
            }
        }
        if (deactivationType == NciConstants.DEACTIVATE_TYPE_IDLE ||
            deactivationType == NciConstants.DEACTIVATE_TYPE_DISCOVERY
        ) {
            stateMutex.withLock {
                activeRfEvent = null
                pendingDeactivateNotification = null
            }
        }
    }

    /**
     * Transceives an ISO 7816-4 APDU in Poll Mode over Static RF Connection `0x00` and waits
     * for the remote tag's response APDU.
     *
     * @throws NfcTagLostException if the RF link is lost or times out.
     */
    suspend fun transceiveApdu(commandApdu: ByteArray, timeout: Duration = 20.seconds): ByteArray {
        val responseDeferred = CompletableDeferred<ByteArray>()
        stateMutex.withLock {
            if (activeRfEvent == null) {
                throw NfcTagLostException("No NFC tag is currently activated")
            }
            pendingResponseApdu = responseDeferred
        }
        try {
            sendDataMessage(commandApdu)
            return withRealTimeTimeout(timeout) {
                responseDeferred.await()
            }
        } catch (e: NfcTagLostException) {
            throw e
        } catch (e: CancellationException) {
            if (activeRfEvent == null) {
                throw NfcTagLostException("NFC tag lost during transceive", e)
            }
            throw e
        } finally {
            stateMutex.withLock {
                if (pendingResponseApdu === responseDeferred) {
                    pendingResponseApdu = null
                }
            }
        }
    }

    /**
     * Sends a complete payload (Command APDU in Poll Mode or Response APDU in Listen Mode) over
     * Static RF Connection `0x00`, automatically segmenting into NCI Data packets of at most
     * [maxDataPacketPayloadSize] bytes and respecting `CORE_CONN_CREDITS_NTF` flow control.
     */
    suspend fun sendDataMessage(payload: ByteArray) {
        dataSendMutex.withLock {
            val chunkLimit = maxDataPacketPayloadSize.coerceIn(1, 255)
            if (payload.isEmpty()) {
                acquireCredit()
                packetIo.writePacket(
                    NciConstants.buildDataPacket(
                        connId = NciConstants.STATIC_RF_CONN_ID,
                        payload = payload,
                        offset = 0,
                        length = 0,
                        isComplete = true
                    )
                )
                return
            }

            var offset = 0
            while (offset < payload.size) {
                val remaining = payload.size - offset
                val chunkLength = minOf(remaining, chunkLimit)
                val isLast = (offset + chunkLength) >= payload.size
                acquireCredit()
                val packet = NciConstants.buildDataPacket(
                    connId = NciConstants.STATIC_RF_CONN_ID,
                    payload = payload,
                    offset = offset,
                    length = chunkLength,
                    isComplete = isLast
                )
                packetIo.writePacket(packet)
                offset += chunkLength
            }
        }
    }

    /**
     * Checks if the currently activated Poll Mode ISO-DEP tag is still in the RF field.
     */
    suspend fun checkTagPresence(): Boolean {
        val currentEvent = activeRfEvent ?: return false
        if (!currentEvent.isPollMode) return false

        val presenceDeferred = CompletableDeferred<Int>()
        val isActive = stateMutex.withLock {
            if (activeRfEvent == null) {
                false
            } else {
                pendingPresenceCheckNotification = presenceDeferred
                true
            }
        }
        if (!isActive) return false

        return try {
            val rsp = sendCommand(
                gid = NciConstants.GID_PROPRIETARY,
                oid = NciConstants.OID_NXP_PRESENCE_CHECK,
                payload = ByteArray(0)
            )
            val rspStatus = rsp.firstOrNull()?.toInt()?.and(0xFF) ?: NciConstants.STATUS_FAILED
            if (rspStatus != NciConstants.STATUS_OK) {
                // Controller does not support NXP proprietary presence check; fall back to activeRfEvent state
                activeRfEvent != null
            } else {
                val ntfStatus = withRealTimeTimeout(500.milliseconds) {
                    presenceDeferred.await()
                }
                if (ntfStatus != NciConstants.STATUS_OK) {
                    stateMutex.withLock {
                        activeRfEvent = null
                    }
                    false
                } else {
                    activeRfEvent != null
                }
            }
        } catch (_: Exception) {
            activeRfEvent != null
        } finally {
            stateMutex.withLock {
                if (pendingPresenceCheckNotification === presenceDeferred) {
                    pendingPresenceCheckNotification = null
                }
            }
        }
    }

    /**
     * Closes the engine and the underlying [NciPacketIo].
     */
    fun close() {
        closed = true
        activeRfEvent = null
        creditWaiter?.completeExceptionally(NfcTagLostException("NCI engine closed"))
        creditWaiter = null
        pendingResponseApdu?.completeExceptionally(NfcTagLostException("NCI engine closed"))
        pendingResponseApdu = null
        pendingCommandResponse?.completeExceptionally(CancellationException("NCI engine closed"))
        pendingCommandResponse = null
        pendingResetNotification?.completeExceptionally(CancellationException("NCI engine closed"))
        pendingResetNotification = null
        pendingDeactivateNotification?.completeExceptionally(CancellationException("NCI engine closed"))
        pendingDeactivateNotification = null
        pendingPresenceCheckNotification?.completeExceptionally(CancellationException("NCI engine closed"))
        pendingPresenceCheckNotification = null
        packetIo.close()
        incomingEventChannel.close()
        readerJob?.cancel()
        eventDispatcherJob?.cancel()
        scope.cancel()
    }

    private suspend fun acquireCredit() {
        while (true) {
            val waiter: CompletableDeferred<Unit>? = stateMutex.withLock {
                if (closed || activeRfEvent == null) {
                    throw NfcTagLostException("RF link deactivated while waiting for NCI data credit")
                }
                if (availableCredits > 0) {
                    availableCredits -= 1
                    null
                } else {
                    val w = creditWaiter ?: CompletableDeferred<Unit>().also { creditWaiter = it }
                    w
                }
            }
            if (waiter == null) {
                return
            }
            withRealTimeTimeout(commandTimeout) {
                waiter.await()
            }
        }
    }

    private suspend fun sendCommand(
        gid: Int,
        oid: Int,
        payload: ByteArray
    ): ByteArray = commandMutex.withLock {
        val deferred = CompletableDeferred<ByteArray>()
        stateMutex.withLock {
            if (closed) {
                throw CancellationException("NCI engine is closed")
            }
            pendingCommandGid = gid
            pendingCommandOid = oid
            pendingCommandResponse = deferred
        }
        try {
            val packet = NciConstants.buildCommand(gid, oid, payload)
            packetIo.writePacket(packet)
            withRealTimeTimeout(commandTimeout) {
                deferred.await()
            }
        } finally {
            stateMutex.withLock {
                if (pendingCommandResponse === deferred) {
                    pendingCommandResponse = null
                    pendingCommandGid = -1
                    pendingCommandOid = -1
                }
            }
        }
    }

    private suspend fun <T> withRealTimeTimeout(
        timeout: Duration,
        block: suspend CoroutineScope.() -> T
    ): T = withContext(Dispatchers.Default) {
        withTimeout(timeout, block)
    }

    private fun checkStatus(commandName: String, responsePayload: ByteArray) {
        val status = responsePayload.firstOrNull()?.toInt()?.and(0xFF) ?: NciConstants.STATUS_FAILED
        if (status != NciConstants.STATUS_OK) {
            throw IllegalStateException("$commandName failed with NCI status 0x${status.toString(16)}")
        }
    }

    private fun startReaderLoopIfNeeded() {
        if (readerJob != null) return
        eventDispatcherJob = scope.launch {
            for (action in incomingEventChannel) {
                try {
                    action()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Throwable) {
                    Logger.w(TAG, "Error handling NCI event", e)
                }
            }
        }
        readerJob = scope.launch {
            try {
                while (!closed) {
                    val packet = packetIo.readPacket() ?: break
                    if (packet.size < 3) {
                        continue
                    }
                    handleIncomingPacket(packet)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                if (!closed) {
                    Logger.e(TAG, "NCI packet reader failed", e)
                    listener.onEngineError(e)
                }
            }
        }
    }

    private suspend fun handleIncomingPacket(packet: ByteArray) {
        val header0 = packet[0].toInt() and 0xFF
        val header1 = packet[1].toInt() and 0xFF
        val payloadLen = (packet[2].toInt() and 0xFF).coerceAtMost(packet.size - 3)
        val payload = packet.copyOfRange(3, 3 + payloadLen)

        val mt = header0 and NciConstants.MT_MASK
        val pbf = header0 and NciConstants.PBF_MASK

        when (mt) {
            NciConstants.MT_RSP, NciConstants.MT_NTF -> {
                if (pbf == NciConstants.PBF_SEGMENT) {
                    if (segmentedControlBuffer.isEmpty()) {
                        segmentedControlHeader0 = header0 and (NciConstants.PBF_MASK.inv())
                        segmentedControlHeader1 = header1
                    }
                    segmentedControlBuffer += payload
                    return
                }
                val fullPayload = if (segmentedControlBuffer.isNotEmpty()) {
                    val combined = segmentedControlBuffer + payload
                    segmentedControlBuffer = ByteArray(0)
                    combined
                } else {
                    payload
                }
                val effectiveHeader0 = if (segmentedControlHeader0 != 0) {
                    val h0 = segmentedControlHeader0
                    segmentedControlHeader0 = 0
                    h0
                } else {
                    header0
                }
                val effectiveHeader1 = if (segmentedControlHeader1 != 0) {
                    val h1 = segmentedControlHeader1
                    segmentedControlHeader1 = 0
                    h1
                } else {
                    header1
                }
                val gid = effectiveHeader0 and NciConstants.GID_MASK
                val oid = effectiveHeader1 and NciConstants.OID_MASK
                if ((effectiveHeader0 and NciConstants.MT_MASK) == NciConstants.MT_RSP) {
                    handleControlResponse(gid, oid, fullPayload)
                } else {
                    handleControlNotification(gid, oid, fullPayload)
                }
            }

            NciConstants.MT_DATA -> {
                val connId = header0 and NciConstants.CONN_ID_MASK
                if (connId != NciConstants.STATIC_RF_CONN_ID) {
                    return
                }
                if (pbf == NciConstants.PBF_SEGMENT) {
                    segmentedDataBuffer += payload
                    return
                }
                val fullData = if (segmentedDataBuffer.isNotEmpty()) {
                    val combined = segmentedDataBuffer + payload
                    segmentedDataBuffer = ByteArray(0)
                    combined
                } else {
                    payload
                }
                handleCompleteDataMessage(fullData)
            }
        }
    }

    private suspend fun handleControlResponse(gid: Int, oid: Int, payload: ByteArray) {
        val deferred = stateMutex.withLock {
            if (pendingCommandGid == gid && pendingCommandOid == oid) {
                pendingCommandResponse
            } else {
                null
            }
        }
        deferred?.complete(payload)
    }

    private suspend fun handleControlNotification(gid: Int, oid: Int, payload: ByteArray) {
        when (gid) {
            NciConstants.GID_CORE -> {
                when (oid) {
                    NciConstants.OID_CORE_RESET -> {
                        val deferred = stateMutex.withLock {
                            lastResetNotification = payload
                            pendingResetNotification
                        }
                        deferred?.complete(payload)
                    }

                    NciConstants.OID_CORE_CONN_CREDITS -> {
                        if (payload.isEmpty()) return
                        val numEntries = payload[0].toInt() and 0xFF
                        var offset = 1
                        repeat(numEntries) {
                            if (offset + 1 < payload.size) {
                                val connId = payload[offset].toInt() and 0xFF
                                val credits = payload[offset + 1].toInt() and 0xFF
                                offset += 2
                                if (connId == NciConstants.STATIC_RF_CONN_ID && credits > 0) {
                                    val waiter = stateMutex.withLock {
                                        availableCredits += credits
                                        val w = creditWaiter
                                        creditWaiter = null
                                        w
                                    }
                                    waiter?.complete(Unit)
                                }
                            }
                        }
                    }

                    NciConstants.OID_CORE_INTERFACE_ERROR -> {
                        val status = payload.firstOrNull()?.toInt()?.and(0xFF) ?: NciConstants.STATUS_FAILED
                        Logger.w(TAG, "CORE_INTERFACE_ERROR_NTF status=0x${status.toString(16)}")
                        val responseWaiter = stateMutex.withLock {
                            val w = pendingResponseApdu
                            pendingResponseApdu = null
                            w
                        }
                        responseWaiter?.completeExceptionally(
                            NfcTagLostException("NCI interface error 0x${status.toString(16)}")
                        )
                    }
                }
            }

            NciConstants.GID_RF_MGMT -> {
                when (oid) {
                    NciConstants.OID_RF_DISCOVER -> {
                        // Multi-target discovery notification (RF_DISCOVER_NTF):
                        // [RfDiscoveryId, RfProtocol, RfTechAndMode, RfTechParamsLen, ...More]
                        if (payload.size >= 3) {
                            val discoveryId = payload[0].toInt() and 0xFF
                            val protocol = payload[1].toInt() and 0xFF
                            val more = payload.last().toInt() and 0xFF
                            if (protocol == NciConstants.PROTOCOL_ISO_DEP && more == 0) {
                                incomingEventChannel.trySend {
                                    try {
                                        sendCommand(
                                            gid = NciConstants.GID_RF_MGMT,
                                            oid = NciConstants.OID_RF_DISCOVER_SELECT,
                                            payload = byteArrayOf(
                                                discoveryId.toByte(),
                                                NciConstants.PROTOCOL_ISO_DEP.toByte(),
                                                NciConstants.RF_INTERFACE_ISO_DEP.toByte()
                                            )
                                        )
                                    } catch (e: Throwable) {
                                        Logger.w(TAG, "RF_DISCOVER_SELECT_CMD failed", e)
                                    }
                                }
                            }
                        }
                    }

                    NciConstants.OID_RF_INTF_ACTIVATED -> {
                        val event = parseRfIntfActivatedNtf(payload) ?: return
                        stateMutex.withLock {
                            segmentedDataBuffer = ByteArray(0)
                            if (event.maxDataPacketPayloadSize in 1..255) {
                                maxDataPacketPayloadSize = event.maxDataPacketPayloadSize
                            }
                            availableCredits = event.initialCredits.coerceAtLeast(1)
                            activeRfEvent = event
                            val w = creditWaiter
                            creditWaiter = null
                            w?.complete(Unit)
                        }
                        incomingEventChannel.trySend {
                            listener.onRfActivated(event)
                        }
                    }

                    NciConstants.OID_RF_DEACTIVATE -> {
                        val deactType = payload.getOrNull(0)?.toInt()?.and(0xFF)
                            ?: NciConstants.DEACTIVATE_TYPE_IDLE
                        val deactReason = payload.getOrNull(1)?.toInt()?.and(0xFF)
                            ?: NciConstants.DEACTIVATE_REASON_RF_LINK_LOSS

                        val (responseWaiter, creditW, deactWaiter) = stateMutex.withLock {
                            segmentedDataBuffer = ByteArray(0)
                            activeRfEvent = null
                            val rw = pendingResponseApdu
                            pendingResponseApdu = null
                            val cw = creditWaiter
                            creditWaiter = null
                            val dw = pendingDeactivateNotification
                            pendingDeactivateNotification = null
                            Triple(rw, cw, dw)
                        }
                        val lostException = NfcTagLostException(
                            "RF deactivated (type=$deactType, reason=$deactReason)"
                        )
                        responseWaiter?.completeExceptionally(lostException)
                        creditW?.completeExceptionally(lostException)
                        deactWaiter?.complete(deactType to deactReason)

                        incomingEventChannel.trySend {
                            listener.onRfDeactivated(deactType, deactReason)
                        }
                    }
                }
            }

            NciConstants.GID_PROPRIETARY -> {
                if (oid == NciConstants.OID_NXP_PRESENCE_CHECK) {
                    val status = payload.firstOrNull()?.toInt()?.and(0xFF) ?: NciConstants.STATUS_FAILED
                    val waiter = stateMutex.withLock {
                        val w = pendingPresenceCheckNotification
                        pendingPresenceCheckNotification = null
                        w
                    }
                    waiter?.complete(status)
                }
            }
        }
    }

    private suspend fun handleCompleteDataMessage(data: ByteArray) {
        val (pollResponseWaiter, isListenMode) = stateMutex.withLock {
            val rw = pendingResponseApdu
            if (rw != null) {
                pendingResponseApdu = null
            }
            val listen = activeRfEvent?.isListenMode == true
            rw to listen
        }
        if (pollResponseWaiter != null) {
            pollResponseWaiter.complete(data)
        } else if (isListenMode) {
            incomingEventChannel.trySend {
                listener.onDataPacketReceived(data)
            }
        }
    }

    companion object {
        /**
         * Parses an NCI 2.0 `RF_INTF_ACTIVATED_NTF` payload into an [NciRfActivatedEvent].
         */
        internal fun parseRfIntfActivatedNtf(payload: ByteArray): NciRfActivatedEvent? {
            if (payload.size < 11) {
                return null
            }
            val rfDiscoveryId = payload[0].toInt() and 0xFF
            val rfInterface = payload[1].toInt() and 0xFF
            val rfProtocol = payload[2].toInt() and 0xFF
            val activationTechAndMode = payload[3].toInt() and 0xFF
            val maxDataSize = (payload[4].toInt() and 0xFF).let { if (it in 1..255) it else 251 }
            val initialCredits = (payload[5].toInt() and 0xFF).coerceAtLeast(1)
            val techParamsLen = payload[6].toInt() and 0xFF

            val techParamsStart = 7
            val techParamsEnd = (techParamsStart + techParamsLen).coerceAtMost(payload.size)
            val techParams = payload.copyOfRange(techParamsStart, techParamsEnd)

            var applicationData: ByteString? = null
            if (activationTechAndMode == NciConstants.NFC_B_PASSIVE_POLL_MODE && techParams.size >= 9) {
                // SENSB_RES length at techParams[0], SENSB_RES at techParams[1..]:
                // Bytes 0..3 = NFCID0, Bytes 4..7 = Application Data, Bytes 8..10 = Protocol Info
                val sensbLen = techParams[0].toInt() and 0xFF
                if (sensbLen >= 8 && techParams.size >= 1 + 8) {
                    applicationData = ByteString(techParams.copyOfRange(1 + 4, 1 + 8))
                }
            }

            var historicalBytes: ByteString? = null
            val actParamsLenIndex = techParamsEnd + 3
            if (actParamsLenIndex < payload.size) {
                val actParamsLen = payload[actParamsLenIndex].toInt() and 0xFF
                val actParamsStart = actParamsLenIndex + 1
                val actParamsEnd = (actParamsStart + actParamsLen).coerceAtMost(payload.size)
                val actParams = payload.copyOfRange(actParamsStart, actParamsEnd)

                if (rfInterface == NciConstants.RF_INTERFACE_ISO_DEP &&
                    activationTechAndMode == NciConstants.NFC_A_PASSIVE_POLL_MODE &&
                    actParams.isNotEmpty()
                ) {
                    // actParams[0] = RATS response length (ATS bytes after TL), actParams[1..] = T0 [TA1] [TB1] [TC1] HistBytes
                    val ratsLen = (actParams[0].toInt() and 0xFF).coerceAtMost(actParams.size - 1)
                    if (ratsLen >= 1) {
                        val t0 = actParams[1].toInt() and 0xFF
                        var histOffset = 2
                        if ((t0 and 0x10) != 0) histOffset += 1 // TA1 present
                        if ((t0 and 0x20) != 0) histOffset += 1 // TB1 present
                        if ((t0 and 0x40) != 0) histOffset += 1 // TC1 present
                        val atsEnd = 1 + ratsLen
                        if (histOffset <= atsEnd) {
                            historicalBytes = ByteString(actParams.copyOfRange(histOffset, atsEnd))
                        }
                    }
                }
            }

            return NciRfActivatedEvent(
                rfDiscoveryId = rfDiscoveryId,
                rfInterface = rfInterface,
                rfProtocol = rfProtocol,
                activationRfTechAndMode = activationTechAndMode,
                maxDataPacketPayloadSize = maxDataSize,
                initialCredits = initialCredits,
                historicalBytes = historicalBytes,
                applicationData = applicationData
            )
        }
    }
}
