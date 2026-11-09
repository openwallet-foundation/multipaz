package org.multipaz.mdoc.transport

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
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import org.multipaz.crypto.Crypto
import org.multipaz.util.Logger
import org.multipaz.util.UUID
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.Volatile
import kotlin.math.min
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

internal object BleHciConstants {
    // H4 Packet Indicators
    const val HCI_COMMAND_PKT: Byte = 0x01
    const val HCI_ACL_DATA_PKT: Byte = 0x02
    const val HCI_EVENT_PKT: Byte = 0x04

    // HCI Command Opcodes ((OGF shl 10) or OCF)
    const val HCI_OP_DISCONNECT = 0x0406
    const val HCI_OP_SET_EVENT_MASK = 0x0C01
    const val HCI_OP_RESET = 0x0C03
    const val HCI_OP_READ_BUFFER_SIZE = 0x1005
    const val HCI_OP_LE_SET_EVENT_MASK = 0x2001
    const val HCI_OP_LE_READ_BUFFER_SIZE = 0x2002
    const val HCI_OP_LE_SET_RANDOM_ADDRESS = 0x2005
    const val HCI_OP_LE_SET_ADVERTISING_PARAMETERS = 0x2006
    const val HCI_OP_LE_SET_ADVERTISING_DATA = 0x2008
    const val HCI_OP_LE_SET_SCAN_RESPONSE_DATA = 0x2009
    const val HCI_OP_LE_SET_ADVERTISE_ENABLE = 0x200A
    const val HCI_OP_LE_SET_SCAN_PARAMETERS = 0x200B
    const val HCI_OP_LE_SET_SCAN_ENABLE = 0x200C
    const val HCI_OP_LE_CREATE_CONNECTION = 0x200D
    const val HCI_OP_LE_CREATE_CONNECTION_CANCEL = 0x200E

    // HCI Event Codes
    const val HCI_EV_DISCONN_COMPLETE = 0x05
    const val HCI_EV_CMD_COMPLETE = 0x0E
    const val HCI_EV_CMD_STATUS = 0x0F
    const val HCI_EV_NUM_COMP_PKTS = 0x13
    const val HCI_EV_LE_META = 0x3E

    // HCI LE Meta Subevent Codes
    const val HCI_EV_LE_CONN_COMPLETE = 0x01
    const val HCI_EV_LE_ADVERTISING_REPORT = 0x02
    const val HCI_EV_LE_ENHANCED_CONN_COMPLETE = 0x0A
    const val HCI_EV_LE_EXT_ADVERTISING_REPORT = 0x0D

    // L2CAP Fixed CIDs
    const val L2CAP_CID_ATT = 0x0004
    const val L2CAP_CID_LE_SIGNALING = 0x0005
    const val L2CAP_CID_SMP = 0x0006
    const val L2CAP_DYNAMIC_CID_DEFAULT = 0x0040

    // L2CAP LE Signaling Codes (CID 0x0005)
    const val L2CAP_CMD_REJECT = 0x01
    const val L2CAP_DISCONN_REQ = 0x06
    const val L2CAP_DISCONN_RSP = 0x07
    const val L2CAP_CONN_PARAM_UPDATE_REQ = 0x12
    const val L2CAP_CONN_PARAM_UPDATE_RSP = 0x13
    const val L2CAP_LE_CREDIT_CONN_REQ = 0x14
    const val L2CAP_LE_CREDIT_CONN_RSP = 0x15
    const val L2CAP_FLOW_CONTROL_CREDIT_IND = 0x16

    // L2CAP CoC Defaults
    const val L2CAP_DEFAULT_MTU = 65535
    const val L2CAP_DEFAULT_MPS = 247
    const val L2CAP_INITIAL_CREDITS = 256
    const val L2CAP_CREDIT_REPLENISH_THRESHOLD = 64

    // ATT Opcodes (CID 0x0004)
    const val ATT_OP_ERROR_RSP = 0x01
    const val ATT_OP_EXCHANGE_MTU_REQ = 0x02
    const val ATT_OP_EXCHANGE_MTU_RSP = 0x03
    const val ATT_OP_FIND_INFO_REQ = 0x04
    const val ATT_OP_FIND_INFO_RSP = 0x05
    const val ATT_OP_FIND_BY_TYPE_VALUE_REQ = 0x06
    const val ATT_OP_FIND_BY_TYPE_VALUE_RSP = 0x07
    const val ATT_OP_READ_BY_TYPE_REQ = 0x08
    const val ATT_OP_READ_BY_TYPE_RSP = 0x09
    const val ATT_OP_READ_REQ = 0x0A
    const val ATT_OP_READ_RSP = 0x0B
    const val ATT_OP_READ_BY_GROUP_TYPE_REQ = 0x10
    const val ATT_OP_READ_BY_GROUP_TYPE_RSP = 0x11
    const val ATT_OP_WRITE_REQ = 0x12
    const val ATT_OP_WRITE_RSP = 0x13
    const val ATT_OP_HANDLE_VALUE_NTF = 0x1B
    const val ATT_OP_HANDLE_VALUE_IND = 0x1D
    const val ATT_OP_HANDLE_VALUE_CFM = 0x1E
    const val ATT_OP_WRITE_CMD = 0x52

    // ATT Error Codes
    const val ATT_ERR_INVALID_HANDLE = 0x01
    const val ATT_ERR_READ_NOT_PERMITTED = 0x02
    const val ATT_ERR_WRITE_NOT_PERMITTED = 0x03
    const val ATT_ERR_INVALID_PDU = 0x04
    const val ATT_ERR_REQUEST_NOT_SUPPORTED = 0x06
    const val ATT_ERR_ATTRIBUTE_NOT_FOUND = 0x0A
    const val ATT_ERR_UNLIKELY_ERROR = 0x0E
    const val ATT_ERR_UNSUPPORTED_GROUP_TYPE = 0x10

    // GATT 16-bit UUIDs
    const val GATT_UUID_PRIMARY_SERVICE = 0x2800
    const val GATT_UUID_CHARACTERISTIC = 0x2803
    const val GATT_UUID_CCCD = 0x2902

    // GATT Characteristic Property Bits
    const val GATT_PROP_READ = 0x02
    const val GATT_PROP_WRITE_NO_RSP = 0x04
    const val GATT_PROP_WRITE = 0x08
    const val GATT_PROP_NOTIFY = 0x10
    const val GATT_PROP_INDICATE = 0x20

    // AD Types
    const val AD_TYPE_FLAGS = 0x01
    const val AD_TYPE_INCOMPLETE_UUID128_LIST = 0x06
    const val AD_TYPE_COMPLETE_UUID128_LIST = 0x07
}

internal fun u16le(buf: ByteArray, offset: Int): Int =
    (buf[offset].toInt() and 0xFF) or ((buf[offset + 1].toInt() and 0xFF) shl 8)

internal fun putU16le(buf: ByteArray, offset: Int, value: Int) {
    buf[offset] = (value and 0xFF).toByte()
    buf[offset + 1] = ((value ushr 8) and 0xFF).toByte()
}

internal fun u16leBytes(value: Int): ByteArray = byteArrayOf(
    (value and 0xFF).toByte(),
    ((value ushr 8) and 0xFF).toByte()
)

internal fun UUID.toLittleEndianBytes(): ByteArray = toByteArray().reversedArray()

internal fun uuidFromLittleEndianBytes(bytes: ByteArray, offset: Int = 0): UUID {
    val be = ByteArray(16)
    for (i in 0 until 16) {
        be[i] = bytes[offset + 15 - i]
    }
    return UUID.fromByteArray(be)
}

/**
 * Checks whether an Advertising Data (AD) payload contains a 128-bit Service UUID (`0x06` or `0x07`)
 * matching [targetUuid].
 */
internal fun adDataContainsServiceUuid(adData: ByteArray, offset: Int, length: Int, targetUuid: UUID): Boolean {
    val targetLe = targetUuid.toLittleEndianBytes()
    var pos = offset
    val end = min(adData.size, offset + length)
    while (pos < end) {
        val fieldLen = adData[pos].toInt() and 0xFF
        if (fieldLen == 0) break
        val fieldEnd = pos + 1 + fieldLen
        if (fieldEnd > end) break
        val adType = adData[pos + 1].toInt() and 0xFF
        if (adType == BleHciConstants.AD_TYPE_INCOMPLETE_UUID128_LIST ||
            adType == BleHciConstants.AD_TYPE_COMPLETE_UUID128_LIST
        ) {
            var uuidPos = pos + 2
            while (uuidPos + 16 <= fieldEnd) {
                var matches = true
                for (i in 0 until 16) {
                    if (adData[uuidPos + i] != targetLe[i]) {
                        matches = false
                        break
                    }
                }
                if (matches) return true
                uuidPos += 16
            }
        }
        pos = fieldEnd
    }
    return false
}

/**
 * Direct HCI engine managing H4 packet multiplexing, sequential HCI command execution,
 * ACL fragmentation/reassembly, controller buffer credit flow control, and L2CAP CID dispatch.
 */
internal class BleHciEngine(
    private val packetIo: HciPacketIo,
    private val onAdvertisingReport: (addrType: Int, addr: ByteArray, adData: ByteArray) -> Unit = { _, _, _ -> },
    private val onConnected: (connectionHandle: Int, peerAddrType: Int, peerAddr: ByteArray) -> Unit = { _, _, _ -> },
    private val onDisconnected: (connectionHandle: Int, reason: Int) -> Unit = { _, _ -> },
    private val onAttPdu: (connectionHandle: Int, pdu: ByteArray) -> Unit = { _, _ -> },
    private val onL2capSignalingPdu: (connectionHandle: Int, pdu: ByteArray) -> Unit = { _, _ -> },
    private val onL2capCoCPdu: (connectionHandle: Int, cid: Int, pdu: ByteArray) -> Unit = { _, _, _ -> },
    private val onFatalError: (Throwable) -> Unit = {}
) {
    companion object {
        private const val TAG = "BleHciEngine"
    }

    val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    private val commandMutex = Mutex()
    private val pendingCommandLock = Any()
    private var pendingCommandOpcode: Int = -1
    private var pendingCommandDeferred: CompletableDeferred<ByteArray>? = null

    @Volatile
    var leAclDataPacketLength: Int = 27
        private set

    @Volatile
    var totalNumLeAclDataPackets: Int = 8
        private set

    private var aclCreditSemaphore = Semaphore(8)
    private val inFlightAclPackets = AtomicInteger(0)
    private val aclSendMutex = Mutex()

    private class AclReassemblyState(
        val cid: Int,
        val expectedLength: Int,
        val buffer: ByteArrayOutputStream = ByteArrayOutputStream(expectedLength)
    )

    private val reassemblyByHandle = mutableMapOf<Int, AclReassemblyState>()
    private var readerJob: Job? = null

    @Volatile
    private var closed = false

    fun startReaderLoop() {
        if (readerJob != null) return
        readerJob = scope.launch {
            try {
                while (!closed) {
                    val packet = packetIo.readPacket() ?: break
                    if (packet.isEmpty()) continue
                    when (packet[0]) {
                        BleHciConstants.HCI_EVENT_PKT -> handleHciEvent(packet)
                        BleHciConstants.HCI_ACL_DATA_PKT -> handleAclPacket(packet)
                        else -> Logger.w(TAG, "Ignoring unexpected H4 packet type 0x${(packet[0].toInt() and 0xFF).toString(16)}")
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                if (!closed) {
                    Logger.w(TAG, "HCI reader loop failed", t)
                    onFatalError(t)
                }
            }
        }
    }

    suspend fun initializeController(randomAddress: ByteArray? = null) {
        startReaderLoop()

        // 1. HCI_Reset
        val resetRsp = sendCommand(BleHciConstants.HCI_OP_RESET)
        checkCommandStatus("HCI_Reset", resetRsp)

        // 2. HCI_Set_Event_Mask (enable Disconnection Complete, Command Complete/Status, Num Completed Packets, LE Meta)
        val eventMask = byteArrayOf(
            0xFF.toByte(),
            0xFF.toByte(),
            0xFB.toByte(),
            0xFF.toByte(),
            0x07.toByte(),
            0xF8.toByte(),
            0xBF.toByte(),
            0x3D.toByte()
        )
        val maskRsp = sendCommand(BleHciConstants.HCI_OP_SET_EVENT_MASK, eventMask)
        checkCommandStatus("HCI_Set_Event_Mask", maskRsp)

        // 3. HCI_LE_Set_Event_Mask (enable Connection Complete, Adv Report, Enhanced Conn Complete, Ext Adv Report)
        val leEventMask = byteArrayOf(
            0xFF.toByte(),
            0x1F.toByte(),
            0x00,
            0x00,
            0x00,
            0x00,
            0x00,
            0x00
        )
        val leMaskRsp = sendCommand(BleHciConstants.HCI_OP_LE_SET_EVENT_MASK, leEventMask)
        checkCommandStatus("HCI_LE_Set_Event_Mask", leMaskRsp)

        // 4. HCI_LE_Read_Buffer_Size (with fallback to HCI_Read_Buffer_Size if shared with BR/EDR)
        val leBufRsp = sendCommand(BleHciConstants.HCI_OP_LE_READ_BUFFER_SIZE)
        checkCommandStatus("HCI_LE_Read_Buffer_Size", leBufRsp)
        var pktLen = if (leBufRsp.size >= 3) u16le(leBufRsp, 1) else 0
        var numPkts = if (leBufRsp.size >= 4) (leBufRsp[3].toInt() and 0xFF) else 0
        if (pktLen == 0 || numPkts == 0) {
            val brBufRsp = sendCommand(BleHciConstants.HCI_OP_READ_BUFFER_SIZE)
            checkCommandStatus("HCI_Read_Buffer_Size", brBufRsp)
            if (brBufRsp.size >= 5) {
                pktLen = u16le(brBufRsp, 1)
                numPkts = u16le(brBufRsp, 3)
            }
        }
        leAclDataPacketLength = if (pktLen > 0) pktLen else 27
        totalNumLeAclDataPackets = if (numPkts > 0) numPkts else 8
        aclCreditSemaphore = Semaphore(totalNumLeAclDataPackets)
        inFlightAclPackets.set(0)

        // 5. HCI_LE_Set_Random_Address (6-byte static random address: top 2 bits of MSB set to 11)
        val randomAddr = if (randomAddress != null) {
            require(randomAddress.size == 6) { "Expected 6-byte random address, got ${randomAddress.size}" }
            randomAddress.copyOf()
        } else {
            Crypto.secureRandom.nextBytes(6).also {
                it[0] = (it[0].toInt() or 0x01).toByte() // ensure not all zeros
                it[0] = (it[0].toInt() and 0xFE).toByte() // ensure not all ones
                it[5] = (it[5].toInt() or 0xC0).toByte() // static random address (11xxxxxx)
            }
        }
        val randAddrRsp = sendCommand(BleHciConstants.HCI_OP_LE_SET_RANDOM_ADDRESS, randomAddr)
        checkCommandStatus("HCI_LE_Set_Random_Address", randAddrRsp)
    }

    suspend fun sendCommand(
        opcode: Int,
        params: ByteArray = byteArrayOf(),
        timeout: Duration = 5.seconds
    ): ByteArray = commandMutex.withLock {
        val deferred = CompletableDeferred<ByteArray>()
        synchronized(pendingCommandLock) {
            pendingCommandOpcode = opcode
            pendingCommandDeferred = deferred
        }
        val packet = ByteArray(4 + params.size)
        packet[0] = BleHciConstants.HCI_COMMAND_PKT
        putU16le(packet, 1, opcode)
        packet[3] = (params.size and 0xFF).toByte()
        params.copyInto(packet, destinationOffset = 4)

        try {
            packetIo.writePacket(packet)
            withTimeout(timeout) {
                deferred.await()
            }
        } finally {
            synchronized(pendingCommandLock) {
                if (pendingCommandDeferred === deferred) {
                    pendingCommandOpcode = -1
                    pendingCommandDeferred = null
                }
            }
        }
    }

    fun checkCommandStatus(commandName: String, returnParams: ByteArray) {
        if (returnParams.isEmpty()) {
            throw IOException("$commandName returned empty status")
        }
        val status = returnParams[0].toInt() and 0xFF
        if (status != 0x00) {
            throw IOException("$commandName failed with HCI status 0x${status.toString(16)}")
        }
    }

    suspend fun sendL2capPdu(connectionHandle: Int, cid: Int, payload: ByteArray) {
        val l2capFrame = ByteArray(4 + payload.size)
        putU16le(l2capFrame, 0, payload.size)
        putU16le(l2capFrame, 2, cid)
        payload.copyInto(l2capFrame, destinationOffset = 4)

        val maxSlice = leAclDataPacketLength.coerceAtLeast(27)
        aclSendMutex.withLock {
            var offset = 0
            var first = true
            while (offset < l2capFrame.size) {
                val sliceLen = min(maxSlice, l2capFrame.size - offset)
                val pbFlag = if (first) 0x00 else 0x01 // 0x00 = LE start of L2CAP PDU, 0x01 = continuing
                first = false
                val handleAndFlags = (connectionHandle and 0x0FFF) or (pbFlag shl 12)
                val aclPacket = ByteArray(5 + sliceLen)
                aclPacket[0] = BleHciConstants.HCI_ACL_DATA_PKT
                putU16le(aclPacket, 1, handleAndFlags)
                putU16le(aclPacket, 3, sliceLen)
                l2capFrame.copyInto(aclPacket, destinationOffset = 5, startIndex = offset, endIndex = offset + sliceLen)
                offset += sliceLen

                aclCreditSemaphore.acquire()
                inFlightAclPackets.incrementAndGet()
                try {
                    packetIo.writePacket(aclPacket)
                } catch (t: Throwable) {
                    inFlightAclPackets.decrementAndGet()
                    aclCreditSemaphore.release()
                    throw t
                }
            }
        }
    }

    private fun releaseAclCredits(count: Int) {
        for (i in 0 until count) {
            val current = inFlightAclPackets.get()
            if (current <= 0) break
            if (inFlightAclPackets.compareAndSet(current, current - 1)) {
                aclCreditSemaphore.release()
            }
        }
    }

    private fun releaseAllInFlightAclCredits() {
        val outstanding = inFlightAclPackets.getAndSet(0)
        for (i in 0 until outstanding) {
            aclCreditSemaphore.release()
        }
    }

    private fun handleHciEvent(packet: ByteArray) {
        if (packet.size < 3) return
        val eventCode = packet[1].toInt() and 0xFF
        val paramLen = packet[2].toInt() and 0xFF
        if (packet.size < 3 + paramLen) return
        val params = packet.copyOfRange(3, 3 + paramLen)

        when (eventCode) {
            BleHciConstants.HCI_EV_CMD_COMPLETE -> {
                if (params.size >= 3) {
                    val opcode = u16le(params, 1)
                    val returnParams = params.copyOfRange(3, params.size)
                    val deferred = synchronized(pendingCommandLock) {
                        if (pendingCommandOpcode == opcode) pendingCommandDeferred else null
                    }
                    deferred?.complete(returnParams)
                }
            }
            BleHciConstants.HCI_EV_CMD_STATUS -> {
                if (params.size >= 4) {
                    val status = params[0]
                    val opcode = u16le(params, 2)
                    val deferred = synchronized(pendingCommandLock) {
                        if (pendingCommandOpcode == opcode) pendingCommandDeferred else null
                    }
                    deferred?.complete(byteArrayOf(status))
                }
            }
            BleHciConstants.HCI_EV_NUM_COMP_PKTS -> {
                if (params.isNotEmpty()) {
                    val numHandles = params[0].toInt() and 0xFF
                    var offset = 1
                    for (i in 0 until numHandles) {
                        if (offset + 4 > params.size) break
                        val completed = u16le(params, offset + 2)
                        releaseAclCredits(completed)
                        offset += 4
                    }
                }
            }
            BleHciConstants.HCI_EV_DISCONN_COMPLETE -> {
                if (params.size >= 4) {
                    val status = params[0].toInt() and 0xFF
                    val handle = u16le(params, 1) and 0x0FFF
                    val reason = params[3].toInt() and 0xFF
                    synchronized(reassemblyByHandle) {
                        reassemblyByHandle.remove(handle)
                    }
                    releaseAllInFlightAclCredits()
                    if (status == 0x00) {
                        onDisconnected(handle, reason)
                    }
                }
            }
            BleHciConstants.HCI_EV_LE_META -> {
                if (params.isNotEmpty()) {
                    handleLeMetaEvent(params)
                }
            }
        }
    }

    private fun handleLeMetaEvent(params: ByteArray) {
        val subevent = params[0].toInt() and 0xFF
        when (subevent) {
            BleHciConstants.HCI_EV_LE_CONN_COMPLETE -> {
                // [0]=subevent(0x01), [1]=status, [2..3]=handle, [4]=role, [5]=peerAddrType, [6..11]=peerAddr
                if (params.size >= 12) {
                    val status = params[1].toInt() and 0xFF
                    if (status == 0x00) {
                        val handle = u16le(params, 2) and 0x0FFF
                        val peerAddrType = params[5].toInt() and 0xFF
                        val peerAddr = params.copyOfRange(6, 12)
                        onConnected(handle, peerAddrType, peerAddr)
                    } else {
                        onDisconnected(0, status)
                    }
                }
            }
            BleHciConstants.HCI_EV_LE_ENHANCED_CONN_COMPLETE -> {
                // [0]=subevent(0x0A), [1]=status, [2..3]=handle, [4]=role, [5]=peerAddrType, [6..11]=peerAddr
                if (params.size >= 12) {
                    val status = params[1].toInt() and 0xFF
                    if (status == 0x00) {
                        val handle = u16le(params, 2) and 0x0FFF
                        val peerAddrType = params[5].toInt() and 0xFF
                        val peerAddr = params.copyOfRange(6, 12)
                        onConnected(handle, peerAddrType, peerAddr)
                    } else {
                        onDisconnected(0, status)
                    }
                }
            }
            BleHciConstants.HCI_EV_LE_ADVERTISING_REPORT -> {
                // [0]=subevent(0x02), [1]=numReports
                if (params.size < 2) return
                val numReports = params[1].toInt() and 0xFF
                var offset = 2
                // Note: per Bluetooth Core Spec Vol 4 Part E 7.7.65.2, HCI_LE_Advertising_Report arrays are
                // structured per report when numReports == 1, or grouped by field across numReports.
                // Virtually all controllers emit numReports == 1 per event, but we support both layouts:
                if (numReports == 1) {
                    if (offset + 9 <= params.size) {
                        val addrType = params[offset + 1].toInt() and 0xFF
                        val addr = params.copyOfRange(offset + 2, offset + 8)
                        val dataLen = params[offset + 8].toInt() and 0xFF
                        if (offset + 9 + dataLen <= params.size) {
                            val adData = params.copyOfRange(offset + 9, offset + 9 + dataLen)
                            onAdvertisingReport(addrType, addr, adData)
                        }
                    }
                } else {
                    // Sequential report parsing fallback
                    for (i in 0 until numReports) {
                        if (offset + 9 > params.size) break
                        val addrType = params[offset + 1].toInt() and 0xFF
                        val addr = params.copyOfRange(offset + 2, offset + 8)
                        val dataLen = params[offset + 8].toInt() and 0xFF
                        if (offset + 9 + dataLen > params.size) break
                        val adData = params.copyOfRange(offset + 9, offset + 9 + dataLen)
                        onAdvertisingReport(addrType, addr, adData)
                        offset += 9 + dataLen + 1 // +1 for RSSI
                    }
                }
            }
            BleHciConstants.HCI_EV_LE_EXT_ADVERTISING_REPORT -> {
                // [0]=subevent(0x0D), [1]=numReports, per report:
                // [0..1]=eventType, [2]=addrType, [3..8]=addr, ... [23]=dataLen, [24..24+dataLen]=data
                if (params.size < 2) return
                val numReports = params[1].toInt() and 0xFF
                var offset = 2
                for (i in 0 until numReports) {
                    if (offset + 24 > params.size) break
                    val addrType = params[offset + 2].toInt() and 0xFF
                    val addr = params.copyOfRange(offset + 3, offset + 9)
                    val dataLen = params[offset + 23].toInt() and 0xFF
                    if (offset + 24 + dataLen > params.size) break
                    val adData = params.copyOfRange(offset + 24, offset + 24 + dataLen)
                    onAdvertisingReport(addrType, addr, adData)
                    offset += 24 + dataLen
                }
            }
        }
    }

    private fun handleAclPacket(packet: ByteArray) {
        if (packet.size < 5) return
        val handleAndFlags = u16le(packet, 1)
        val handle = handleAndFlags and 0x0FFF
        val pbFlag = (handleAndFlags ushr 12) and 0x03
        val dlen = u16le(packet, 3)
        if (packet.size < 5 + dlen) return

        val completedPdu: Pair<Int, ByteArray>? = synchronized(reassemblyByHandle) {
            when (pbFlag) {
                0x00, 0x02 -> {
                    // Start of L2CAP PDU
                    if (dlen < 4) return
                    val l2capLen = u16le(packet, 5)
                    val cid = u16le(packet, 7)
                    val chunkLen = dlen - 4
                    if (chunkLen >= l2capLen) {
                        reassemblyByHandle.remove(handle)
                        Pair(cid, packet.copyOfRange(9, 9 + l2capLen))
                    } else {
                        val state = AclReassemblyState(cid, l2capLen)
                        state.buffer.write(packet, 9, chunkLen)
                        reassemblyByHandle[handle] = state
                        null
                    }
                }
                0x01 -> {
                    // Continuing fragment
                    val state = reassemblyByHandle[handle] ?: return
                    val needed = state.expectedLength - state.buffer.size()
                    val toCopy = min(needed, dlen)
                    state.buffer.write(packet, 5, toCopy)
                    if (state.buffer.size() >= state.expectedLength) {
                        reassemblyByHandle.remove(handle)
                        Pair(state.cid, state.buffer.toByteArray())
                    } else {
                        null
                    }
                }
                else -> null
            }
        }

        if (completedPdu != null) {
            val (cid, pdu) = completedPdu
            when (cid) {
                BleHciConstants.L2CAP_CID_ATT -> onAttPdu(handle, pdu)
                BleHciConstants.L2CAP_CID_LE_SIGNALING -> onL2capSignalingPdu(handle, pdu)
                else -> if (cid >= BleHciConstants.L2CAP_DYNAMIC_CID_DEFAULT) {
                    onL2capCoCPdu(handle, cid, pdu)
                }
            }
        }
    }

    fun close() {
        if (closed) return
        closed = true
        synchronized(pendingCommandLock) {
            pendingCommandDeferred?.cancel()
            pendingCommandDeferred = null
        }
        releaseAllInFlightAclCredits()
        packetIo.close()
        scope.cancel()
    }
}

/**
 * [BleL2capChannel] implementation over L2CAP LE Credit-Based Flow Control Mode (`0x14` / `0x15` / `0x16`).
 */
internal class HciBleL2capChannel(
    private val engine: BleHciEngine,
    private val connectionHandle: Int,
    val localCid: Int,
    val peerCid: Int,
    val peerMtu: Int,
    val peerMps: Int,
    initialPeerCredits: Int
) : BleL2capChannel {
    private val peerCredits = Semaphore(initialPeerCredits)
    private val writeMutex = Mutex()
    private val readMutex = Mutex()
    private val incomingSdus = Channel<ByteArray>(Channel.UNLIMITED)

    private var currentIncomingBuf = ByteArray(0)
    private var currentIncomingPos = 0

    private val sduReassemblyLock = Any()
    private var expectedSduLen: Int = -1
    private var sduBuffer = ByteArrayOutputStream()
    private var creditsConsumedSinceLastGrant = 0
    private val nextSigIdentifier = AtomicInteger(10)

    @Volatile
    private var closed = false

    fun onPeerCreditsGranted(credits: Int) {
        for (i in 0 until credits) {
            peerCredits.release()
        }
    }

    fun onKFrameReceived(kFrame: ByteArray) {
        if (closed || kFrame.isEmpty()) return
        var completedSdu: ByteArray? = null
        var creditsToGrant = 0

        synchronized(sduReassemblyLock) {
            creditsConsumedSinceLastGrant += 1
            if (creditsConsumedSinceLastGrant >= BleHciConstants.L2CAP_CREDIT_REPLENISH_THRESHOLD) {
                creditsToGrant = creditsConsumedSinceLastGrant
                creditsConsumedSinceLastGrant = 0
            }

            if (expectedSduLen < 0) {
                // First K-frame of SDU begins with 2-byte little-endian SDU length
                if (kFrame.size < 2) return
                val totalLen = u16le(kFrame, 0)
                val dataLen = kFrame.size - 2
                if (dataLen >= totalLen) {
                    completedSdu = kFrame.copyOfRange(2, 2 + totalLen)
                } else {
                    expectedSduLen = totalLen
                    sduBuffer = ByteArrayOutputStream(totalLen)
                    sduBuffer.write(kFrame, 2, dataLen)
                }
            } else {
                val needed = expectedSduLen - sduBuffer.size()
                val toCopy = min(needed, kFrame.size)
                sduBuffer.write(kFrame, 0, toCopy)
                if (sduBuffer.size() >= expectedSduLen) {
                    completedSdu = sduBuffer.toByteArray()
                    expectedSduLen = -1
                    sduBuffer = ByteArrayOutputStream()
                }
            }
        }

        if (creditsToGrant > 0) {
            engine.scope.launch {
                try {
                    val sigPdu = ByteArray(8)
                    sigPdu[0] = BleHciConstants.L2CAP_FLOW_CONTROL_CREDIT_IND.toByte()
                    sigPdu[1] = (nextSigIdentifier.getAndIncrement() and 0xFF).toByte()
                    putU16le(sigPdu, 2, 4)
                    putU16le(sigPdu, 4, localCid)
                    putU16le(sigPdu, 6, creditsToGrant)
                    engine.sendL2capPdu(connectionHandle, BleHciConstants.L2CAP_CID_LE_SIGNALING, sigPdu)
                } catch (_: Throwable) {
                }
            }
        }

        if (completedSdu != null) {
            incomingSdus.trySend(completedSdu!!)
        }
    }

    override suspend fun read(buffer: ByteArray, offset: Int, length: Int): Int = readMutex.withLock {
        if (length == 0) return 0
        while (currentIncomingPos >= currentIncomingBuf.size) {
            val next = incomingSdus.receiveCatching().getOrNull() ?: return -1
            if (next.isNotEmpty()) {
                currentIncomingBuf = next
                currentIncomingPos = 0
                break
            }
        }
        val available = currentIncomingBuf.size - currentIncomingPos
        val toRead = min(length, available)
        currentIncomingBuf.copyInto(
            destination = buffer,
            destinationOffset = offset,
            startIndex = currentIncomingPos,
            endIndex = currentIncomingPos + toRead
        )
        currentIncomingPos += toRead
        toRead
    }

    override suspend fun write(data: ByteArray) = writeMutex.withLock {
        if (closed) throw IOException("L2CAP channel is closed")
        val maxSduSize = peerMtu.coerceIn(23, 65535)
        val mps = peerMps.coerceIn(23, 65533)
        var sduOffset = 0
        while (sduOffset < data.size) {
            val sduLen = min(maxSduSize, data.size - sduOffset)
            // First K-frame has 2-byte SDU length prefix
            val sduWithHeader = ByteArray(2 + sduLen)
            putU16le(sduWithHeader, 0, sduLen)
            data.copyInto(sduWithHeader, destinationOffset = 2, startIndex = sduOffset, endIndex = sduOffset + sduLen)
            sduOffset += sduLen

            var kOffset = 0
            while (kOffset < sduWithHeader.size) {
                val kLen = min(mps, sduWithHeader.size - kOffset)
                val kFrame = sduWithHeader.copyOfRange(kOffset, kOffset + kLen)
                kOffset += kLen

                peerCredits.acquire()
                engine.sendL2capPdu(connectionHandle, peerCid, kFrame)
            }
        }
    }

    fun onPeerDisconnected() {
        closed = true
        incomingSdus.close()
    }

    override fun close() {
        if (closed) return
        closed = true
        incomingSdus.close()
    }
}
