package org.multipaz.mdoc.transport

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import org.multipaz.util.Logger
import org.multipaz.util.UUID
import java.io.ByteArrayOutputStream
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.Volatile
import kotlin.math.min

/**
 * Direct HCI implementation of [BlePeripheralHal] for Linux JVM.
 */
internal class BlePeripheralHalHci(
    private val randomAddress: ByteArray? = null,
    private val packetIoFactory: suspend () -> HciPacketIo = {
        LinuxHciSocketPacketIo().also { it.open() }
    }
) : BlePeripheralHal {
    companion object {
        private const val TAG = "BlePeripheralHalHci"
        private const val DEFAULT_SERVER_MTU = 515
        private const val DEFAULT_DYNAMIC_L2CAP_PSM = 0x0080
    }

    private class GattAttribute(
        val handle: Int,
        val uuid16: Int?,
        val uuid128: UUID?,
        val characteristicUuid: UUID? = null,
        @Volatile var staticValue: ByteArray = byteArrayOf()
    )

    private lateinit var callback: BlePeripheralHal.Callback
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private val actionQueue = Channel<suspend () -> Unit>(Channel.UNLIMITED)

    @Volatile
    private var engine: BleHciEngine? = null

    @Volatile
    private var connectionHandle: Int = -1

    @Volatile
    private var negotiatedMtu: Int = 23

    @Volatile
    private var l2capServerListening: Boolean = false

    @Volatile
    private var advertisingActive: Boolean = false

    @Volatile
    private var publishedPsm: Int = DEFAULT_DYNAMIC_L2CAP_PSM

    @Volatile
    private var activeL2capChannel: HciBleL2capChannel? = null

    private val attributes = mutableListOf<GattAttribute>()
    private val attributesByHandle = ConcurrentHashMap<Int, GattAttribute>()
    private val valueHandleByCharacteristicUuid = ConcurrentHashMap<UUID, Int>()

    @Volatile
    private var serviceGroupEndHandle: Int = 0

    private val nextReadRequestId = AtomicInteger(1)
    private val pendingReadHandleByRequestId = ConcurrentHashMap<Int, Int>()

    @Volatile
    private var closed = false

    init {
        scope.launch {
            for (action in actionQueue) {
                if (closed) break
                try {
                    action()
                } catch (e: CancellationException) {
                    throw e
                } catch (t: Throwable) {
                    if (!closed) {
                        Logger.w(TAG, "Unhandled error in BlePeripheralHalHci action", t)
                        callback.onError(t)
                    }
                }
            }
        }
    }

    private fun enqueue(block: suspend () -> Unit) {
        actionQueue.trySend(block)
    }

    override fun setCallback(callback: BlePeripheralHal.Callback) {
        this.callback = callback
    }

    override fun waitForPowerOn() {
        enqueue {
            try {
                if (engine == null) {
                    val io = packetIoFactory()
                    val hci = BleHciEngine(
                        packetIo = io,
                        onConnected = ::handleLeConnected,
                        onDisconnected = ::handleLeDisconnected,
                        onAttPdu = ::handleAttPdu,
                        onL2capSignalingPdu = ::handleL2capSignalingPdu,
                        onL2capCoCPdu = ::handleL2capCoCPdu,
                        onFatalError = { err ->
                            if (!closed) callback.onError(err)
                        }
                    )
                    hci.initializeController(randomAddress)
                    engine = hci
                }
                callback.onPowerOn(null)
            } catch (t: Throwable) {
                callback.onPowerOn(t)
            }
        }
    }

    override fun publishL2capChannel() {
        enqueue {
            publishedPsm = DEFAULT_DYNAMIC_L2CAP_PSM
            l2capServerListening = true
            callback.onL2capPublished(publishedPsm, null)
        }
    }

    override fun closeL2capServer() {
        l2capServerListening = false
    }

    override fun setupGattServerAndAddService(
        serviceUuid: UUID,
        stateCharacteristicUuid: UUID,
        client2ServerCharacteristicUuid: UUID,
        server2ClientCharacteristicUuid: UUID,
        identCharacteristicUuid: UUID?,
        l2capCharacteristicUuid: UUID?,
        encodedL2capPsm: ByteArray?
    ) {
        enqueue {
            try {
                attributes.clear()
                attributesByHandle.clear()
                valueHandleByCharacteristicUuid.clear()

                var nextHandle = 1
                fun addAttr(attr: GattAttribute): Int {
                    attributes.add(attr)
                    attributesByHandle[attr.handle] = attr
                    return attr.handle
                }

                // 1. Primary Service declaration (0x2800)
                addAttr(
                    GattAttribute(
                        handle = nextHandle++,
                        uuid16 = BleHciConstants.GATT_UUID_PRIMARY_SERVICE,
                        uuid128 = null,
                        staticValue = serviceUuid.toLittleEndianBytes()
                    )
                )

                fun addCharacteristic(
                    uuid: UUID,
                    properties: Int,
                    includeCccd: Boolean,
                    initialValue: ByteArray = byteArrayOf()
                ) {
                    val declHandle = nextHandle++
                    val valHandle = nextHandle++
                    val declValue = ByteArray(19)
                    declValue[0] = (properties and 0xFF).toByte()
                    putU16le(declValue, 1, valHandle)
                    uuid.toLittleEndianBytes().copyInto(declValue, destinationOffset = 3)

                    addAttr(
                        GattAttribute(
                            handle = declHandle,
                            uuid16 = BleHciConstants.GATT_UUID_CHARACTERISTIC,
                            uuid128 = null,
                            staticValue = declValue
                        )
                    )
                    addAttr(
                        GattAttribute(
                            handle = valHandle,
                            uuid16 = null,
                            uuid128 = uuid,
                            characteristicUuid = uuid,
                            staticValue = initialValue
                        )
                    )
                    valueHandleByCharacteristicUuid[uuid] = valHandle

                    if (includeCccd) {
                        addAttr(
                            GattAttribute(
                                handle = nextHandle++,
                                uuid16 = BleHciConstants.GATT_UUID_CCCD,
                                uuid128 = null,
                                staticValue = byteArrayOf(0x00, 0x00)
                            )
                        )
                    }
                }

                // State: Notify | Write Without Response
                addCharacteristic(
                    uuid = stateCharacteristicUuid,
                    properties = BleHciConstants.GATT_PROP_NOTIFY or BleHciConstants.GATT_PROP_WRITE_NO_RSP,
                    includeCccd = true
                )

                // Client2Server: Write Without Response
                addCharacteristic(
                    uuid = client2ServerCharacteristicUuid,
                    properties = BleHciConstants.GATT_PROP_WRITE_NO_RSP,
                    includeCccd = false
                )

                // Server2Client: Notify
                addCharacteristic(
                    uuid = server2ClientCharacteristicUuid,
                    properties = BleHciConstants.GATT_PROP_NOTIFY,
                    includeCccd = true
                )

                // Optional Ident: Read
                if (identCharacteristicUuid != null) {
                    addCharacteristic(
                        uuid = identCharacteristicUuid,
                        properties = BleHciConstants.GATT_PROP_READ,
                        includeCccd = false
                    )
                }

                // Optional L2CAP: Read
                if (l2capCharacteristicUuid != null) {
                    addCharacteristic(
                        uuid = l2capCharacteristicUuid,
                        properties = BleHciConstants.GATT_PROP_READ,
                        includeCccd = false,
                        initialValue = encodedL2capPsm ?: byteArrayOf()
                    )
                }

                serviceGroupEndHandle = nextHandle - 1
                callback.onServiceAdded(null)
            } catch (t: Throwable) {
                callback.onServiceAdded(t)
            }
        }
    }

    override fun startAdvertising(serviceUuid: UUID) {
        enqueue {
            val hci = engine ?: run {
                callback.onAdvertisingStarted(IllegalStateException("HCI engine not initialized"))
                return@enqueue
            }
            try {
                // Disable any previous advertising first (ignore status if not advertising)
                hci.sendCommand(BleHciConstants.HCI_OP_LE_SET_ADVERTISE_ENABLE, byteArrayOf(0x00))

                // Advertising parameters (15 bytes):
                // intervalMin = 0x0020 (20ms), intervalMax = 0x0040 (40ms), advType = 0x00 (ADV_IND),
                // ownAddrType = 0x01 (Random), peerAddrType = 0x00, peerAddr = 0, channelMap = 0x07, filterPolicy = 0x00
                val advParams = ByteArray(15)
                putU16le(advParams, 0, 0x0020)
                putU16le(advParams, 2, 0x0040)
                advParams[4] = 0x00 // ADV_IND
                advParams[5] = 0x01 // Random Own Address
                advParams[6] = 0x00
                // [7..12] = 0x00 peerAddr
                advParams[13] = 0x07 // Channels 37, 38, 39
                advParams[14] = 0x00
                val paramRsp = hci.sendCommand(BleHciConstants.HCI_OP_LE_SET_ADVERTISING_PARAMETERS, advParams)
                hci.checkCommandStatus("HCI_LE_Set_Advertising_Parameters", paramRsp)

                // Advertising Data (32 bytes: 1 byte length + 31 bytes AD data):
                // Flags: [0x02, 0x01, 0x06]
                // Complete 128-bit UUID list: [0x11, 0x07, <16-byte LE UUID>]
                val advDataCmd = ByteArray(32)
                advDataCmd[0] = 21 // 3 + 18 bytes
                advDataCmd[1] = 0x02
                advDataCmd[2] = BleHciConstants.AD_TYPE_FLAGS.toByte()
                advDataCmd[3] = 0x06
                advDataCmd[4] = 0x11
                advDataCmd[5] = BleHciConstants.AD_TYPE_COMPLETE_UUID128_LIST.toByte()
                serviceUuid.toLittleEndianBytes().copyInto(advDataCmd, destinationOffset = 6)

                val dataRsp = hci.sendCommand(BleHciConstants.HCI_OP_LE_SET_ADVERTISING_DATA, advDataCmd)
                hci.checkCommandStatus("HCI_LE_Set_Advertising_Data", dataRsp)

                val enableRsp = hci.sendCommand(BleHciConstants.HCI_OP_LE_SET_ADVERTISE_ENABLE, byteArrayOf(0x01))
                hci.checkCommandStatus("HCI_LE_Set_Advertise_Enable(true)", enableRsp)
                advertisingActive = true

                callback.onAdvertisingStarted(null)
            } catch (t: Throwable) {
                callback.onAdvertisingStarted(t)
            }
        }
    }

    override fun stopAdvertising() {
        enqueue {
            if (!advertisingActive) return@enqueue
            advertisingActive = false
            val hci = engine ?: return@enqueue
            try {
                hci.sendCommand(BleHciConstants.HCI_OP_LE_SET_ADVERTISE_ENABLE, byteArrayOf(0x00))
            } catch (_: Throwable) {
            }
        }
    }

    override fun sendReadResponse(requestId: Int, statusSuccess: Boolean, value: ByteArray) {
        enqueue {
            val hci = engine ?: return@enqueue
            val handle = connectionHandle
            if (handle < 0) return@enqueue
            val attrHandle = pendingReadHandleByRequestId.remove(requestId) ?: 0
            if (statusSuccess) {
                val maxLen = (negotiatedMtu - 1).coerceAtLeast(22)
                val truncatedLen = min(value.size, maxLen)
                val rsp = ByteArray(1 + truncatedLen)
                rsp[0] = BleHciConstants.ATT_OP_READ_RSP.toByte()
                value.copyInto(rsp, destinationOffset = 1, startIndex = 0, endIndex = truncatedLen)
                hci.sendL2capPdu(handle, BleHciConstants.L2CAP_CID_ATT, rsp)
            } else {
                sendAttError(
                    hci,
                    handle,
                    BleHciConstants.ATT_OP_READ_REQ,
                    attrHandle,
                    BleHciConstants.ATT_ERR_UNLIKELY_ERROR
                )
            }
        }
    }

    override fun notifyCharacteristicChanged(uuid: UUID, value: ByteArray) {
        enqueue {
            val hci = engine ?: run {
                callback.onNotificationSent(uuid, IllegalStateException("HCI engine not initialized"))
                return@enqueue
            }
            val handle = connectionHandle
            if (handle < 0) {
                callback.onNotificationSent(uuid, IllegalStateException("No central connected"))
                return@enqueue
            }
            val valHandle = valueHandleByCharacteristicUuid[uuid]
            if (valHandle == null) {
                callback.onNotificationSent(uuid, IllegalStateException("Characteristic $uuid not found"))
                return@enqueue
            }
            try {
                val pdu = ByteArray(3 + value.size)
                pdu[0] = BleHciConstants.ATT_OP_HANDLE_VALUE_NTF.toByte()
                putU16le(pdu, 1, valHandle)
                value.copyInto(pdu, destinationOffset = 3)
                hci.sendL2capPdu(handle, BleHciConstants.L2CAP_CID_ATT, pdu)
                callback.onNotificationSent(uuid, null)
            } catch (t: Throwable) {
                callback.onNotificationSent(uuid, t)
            }
        }
    }

    override fun close() {
        if (closed) return
        closed = true
        l2capServerListening = false
        activeL2capChannel?.close()
        activeL2capChannel = null
        actionQueue.close()
        engine?.close()
        engine = null
        scope.cancel()
    }

    private fun handleLeConnected(handle: Int, peerAddrType: Int, peerAddr: ByteArray) {
        connectionHandle = handle
        negotiatedMtu = 23
    }

    private fun handleLeDisconnected(handle: Int, reason: Int) {
        if (closed) return
        if (connectionHandle == handle || handle == 0) {
            connectionHandle = -1
            negotiatedMtu = 23
            pendingReadHandleByRequestId.clear()
            for (attr in attributes) {
                if (attr.uuid16 == BleHciConstants.GATT_UUID_CCCD) {
                    attr.staticValue = byteArrayOf(0x00, 0x00)
                }
            }
            activeL2capChannel?.onPeerDisconnected()
            if (advertisingActive) {
                // Controller automatically disables advertising upon LE connection creation;
                // if the connection dropped before the GATT/L2CAP handshake finished and
                // stopAdvertising() was called, re-enable advertising so the central can reconnect.
                enqueue {
                    if (advertisingActive) {
                        engine?.sendCommand(BleHciConstants.HCI_OP_LE_SET_ADVERTISE_ENABLE, byteArrayOf(0x01))
                    }
                }
            }
            callback.onCentralDisconnected()
        }
    }

    private fun handleAttPdu(handle: Int, pdu: ByteArray) {
        if (pdu.isEmpty()) return
        enqueue {
            val hci = engine ?: return@enqueue
            val opcode = pdu[0].toInt() and 0xFF
            when (opcode) {
                BleHciConstants.ATT_OP_EXCHANGE_MTU_REQ -> {
                    if (pdu.size < 3) {
                        sendAttError(hci, handle, opcode, 0, BleHciConstants.ATT_ERR_INVALID_PDU)
                        return@enqueue
                    }
                    val clientMtu = u16le(pdu, 1)
                    negotiatedMtu = min(DEFAULT_SERVER_MTU, clientMtu.coerceAtLeast(23))
                    val rsp = ByteArray(3)
                    rsp[0] = BleHciConstants.ATT_OP_EXCHANGE_MTU_RSP.toByte()
                    putU16le(rsp, 1, DEFAULT_SERVER_MTU)
                    hci.sendL2capPdu(handle, BleHciConstants.L2CAP_CID_ATT, rsp)
                    callback.onMtuChanged(negotiatedMtu)
                }
                BleHciConstants.ATT_OP_READ_BY_GROUP_TYPE_REQ -> {
                    if (pdu.size < 7) {
                        sendAttError(hci, handle, opcode, 0, BleHciConstants.ATT_ERR_INVALID_PDU)
                        return@enqueue
                    }
                    val startHandle = u16le(pdu, 1)
                    val endHandle = u16le(pdu, 3)
                    val groupUuid16 = if (pdu.size == 7) u16le(pdu, 5) else -1
                    if (groupUuid16 != BleHciConstants.GATT_UUID_PRIMARY_SERVICE) {
                        sendAttError(hci, handle, opcode, startHandle, BleHciConstants.ATT_ERR_UNSUPPORTED_GROUP_TYPE)
                        return@enqueue
                    }
                    val primaryAttr = attributesByHandle[1]
                    if (primaryAttr != null && 1 in startHandle..endHandle) {
                        val valBytes = primaryAttr.staticValue
                        val rsp = ByteArray(2 + 4 + valBytes.size)
                        rsp[0] = BleHciConstants.ATT_OP_READ_BY_GROUP_TYPE_RSP.toByte()
                        rsp[1] = (4 + valBytes.size).toByte()
                        putU16le(rsp, 2, 1)
                        putU16le(rsp, 4, serviceGroupEndHandle)
                        valBytes.copyInto(rsp, destinationOffset = 6)
                        hci.sendL2capPdu(handle, BleHciConstants.L2CAP_CID_ATT, rsp)
                    } else {
                        sendAttError(hci, handle, opcode, startHandle, BleHciConstants.ATT_ERR_ATTRIBUTE_NOT_FOUND)
                    }
                }
                BleHciConstants.ATT_OP_FIND_BY_TYPE_VALUE_REQ -> {
                    if (pdu.size < 7) {
                        sendAttError(hci, handle, opcode, 0, BleHciConstants.ATT_ERR_INVALID_PDU)
                        return@enqueue
                    }
                    val startHandle = u16le(pdu, 1)
                    val endHandle = u16le(pdu, 3)
                    val attrType = u16le(pdu, 5)
                    val targetVal = pdu.copyOfRange(7, pdu.size)
                    val primaryAttr = attributesByHandle[1]
                    if (attrType == BleHciConstants.GATT_UUID_PRIMARY_SERVICE &&
                        primaryAttr != null &&
                        1 in startHandle..endHandle &&
                        (primaryAttr.staticValue contentEquals targetVal)
                    ) {
                        val rsp = ByteArray(5)
                        rsp[0] = BleHciConstants.ATT_OP_FIND_BY_TYPE_VALUE_RSP.toByte()
                        putU16le(rsp, 1, 1)
                        putU16le(rsp, 3, serviceGroupEndHandle)
                        hci.sendL2capPdu(handle, BleHciConstants.L2CAP_CID_ATT, rsp)
                    } else {
                        sendAttError(hci, handle, opcode, startHandle, BleHciConstants.ATT_ERR_ATTRIBUTE_NOT_FOUND)
                    }
                }
                BleHciConstants.ATT_OP_READ_BY_TYPE_REQ -> {
                    if (pdu.size != 7 && pdu.size != 21) {
                        sendAttError(hci, handle, opcode, 0, BleHciConstants.ATT_ERR_INVALID_PDU)
                        return@enqueue
                    }
                    val startHandle = u16le(pdu, 1)
                    val endHandle = u16le(pdu, 3)
                    val type16 = if (pdu.size == 7) u16le(pdu, 5) else null
                    val type128 = if (pdu.size == 21) uuidFromLittleEndianBytes(pdu, 5) else null

                    val matching = attributes.filter { attr ->
                        attr.handle in startHandle..endHandle &&
                            ((type16 != null && attr.uuid16 == type16) || (type128 != null && attr.uuid128 == type128))
                    }
                    if (matching.isEmpty()) {
                        sendAttError(hci, handle, opcode, startHandle, BleHciConstants.ATT_ERR_ATTRIBUTE_NOT_FOUND)
                        return@enqueue
                    }
                    val firstVal = matching[0].staticValue
                    val pairLen = (2 + firstVal.size).coerceAtMost(255)
                    val maxRspBytes = (negotiatedMtu - 2).coerceAtLeast(21)
                    val out = ByteArrayOutputStream()
                    out.write(BleHciConstants.ATT_OP_READ_BY_TYPE_RSP)
                    out.write(pairLen)
                    var count = 0
                    for (attr in matching) {
                        if (2 + attr.staticValue.size != pairLen) break
                        if (count > 0 && (out.size() - 2) + pairLen > maxRspBytes) break
                        out.write(u16leBytes(attr.handle))
                        out.write(attr.staticValue)
                        count++
                    }
                    hci.sendL2capPdu(handle, BleHciConstants.L2CAP_CID_ATT, out.toByteArray())
                }
                BleHciConstants.ATT_OP_FIND_INFO_REQ -> {
                    if (pdu.size < 5) {
                        sendAttError(hci, handle, opcode, 0, BleHciConstants.ATT_ERR_INVALID_PDU)
                        return@enqueue
                    }
                    val startHandle = u16le(pdu, 1)
                    val endHandle = u16le(pdu, 3)
                    val matching = attributes.filter { it.handle in startHandle..endHandle }
                    if (matching.isEmpty()) {
                        sendAttError(hci, handle, opcode, startHandle, BleHciConstants.ATT_ERR_ATTRIBUTE_NOT_FOUND)
                        return@enqueue
                    }
                    val firstIs16 = matching[0].uuid16 != null
                    val format = if (firstIs16) 0x01 else 0x02
                    val entryLen = if (firstIs16) 4 else 18
                    val maxRspBytes = (negotiatedMtu - 2).coerceAtLeast(21)
                    val out = ByteArrayOutputStream()
                    out.write(BleHciConstants.ATT_OP_FIND_INFO_RSP)
                    out.write(format)
                    var count = 0
                    for (attr in matching) {
                        val is16 = attr.uuid16 != null
                        if (is16 != firstIs16) break
                        if (count > 0 && (out.size() - 2) + entryLen > maxRspBytes) break
                        out.write(u16leBytes(attr.handle))
                        if (is16) {
                            out.write(u16leBytes(attr.uuid16!!))
                        } else {
                            out.write(attr.uuid128!!.toLittleEndianBytes())
                        }
                        count++
                    }
                    hci.sendL2capPdu(handle, BleHciConstants.L2CAP_CID_ATT, out.toByteArray())
                }
                BleHciConstants.ATT_OP_READ_REQ -> {
                    if (pdu.size < 3) {
                        sendAttError(hci, handle, opcode, 0, BleHciConstants.ATT_ERR_INVALID_PDU)
                        return@enqueue
                    }
                    val attrHandle = u16le(pdu, 1)
                    val attr = attributesByHandle[attrHandle]
                    if (attr == null) {
                        sendAttError(hci, handle, opcode, attrHandle, BleHciConstants.ATT_ERR_INVALID_HANDLE)
                        return@enqueue
                    }
                    if (attr.characteristicUuid != null) {
                        val reqId = nextReadRequestId.getAndIncrement()
                        pendingReadHandleByRequestId[reqId] = attrHandle
                        callback.onCharacteristicReadRequest(reqId, attr.characteristicUuid)
                    } else {
                        val rsp = ByteArray(1 + attr.staticValue.size)
                        rsp[0] = BleHciConstants.ATT_OP_READ_RSP.toByte()
                        attr.staticValue.copyInto(rsp, destinationOffset = 1)
                        hci.sendL2capPdu(handle, BleHciConstants.L2CAP_CID_ATT, rsp)
                    }
                }
                BleHciConstants.ATT_OP_WRITE_REQ -> {
                    if (pdu.size < 3) {
                        sendAttError(hci, handle, opcode, 0, BleHciConstants.ATT_ERR_INVALID_PDU)
                        return@enqueue
                    }
                    val attrHandle = u16le(pdu, 1)
                    val value = pdu.copyOfRange(3, pdu.size)
                    val attr = attributesByHandle[attrHandle]
                    if (attr == null) {
                        sendAttError(hci, handle, opcode, attrHandle, BleHciConstants.ATT_ERR_INVALID_HANDLE)
                        return@enqueue
                    }
                    if (attr.uuid16 == BleHciConstants.GATT_UUID_CCCD) {
                        attr.staticValue = value
                        hci.sendL2capPdu(
                            handle,
                            BleHciConstants.L2CAP_CID_ATT,
                            byteArrayOf(BleHciConstants.ATT_OP_WRITE_RSP.toByte())
                        )
                    } else if (attr.characteristicUuid != null) {
                        hci.sendL2capPdu(
                            handle,
                            BleHciConstants.L2CAP_CID_ATT,
                            byteArrayOf(BleHciConstants.ATT_OP_WRITE_RSP.toByte())
                        )
                        callback.onCharacteristicWriteRequest(attr.characteristicUuid, value, null)
                    } else {
                        sendAttError(hci, handle, opcode, attrHandle, BleHciConstants.ATT_ERR_WRITE_NOT_PERMITTED)
                    }
                }
                BleHciConstants.ATT_OP_WRITE_CMD -> {
                    if (pdu.size < 3) return@enqueue
                    val attrHandle = u16le(pdu, 1)
                    val value = pdu.copyOfRange(3, pdu.size)
                    val attr = attributesByHandle[attrHandle] ?: return@enqueue
                    val charUuid = attr.characteristicUuid ?: return@enqueue
                    callback.onCharacteristicWriteRequest(charUuid, value, null)
                }
                BleHciConstants.ATT_OP_HANDLE_VALUE_CFM -> {
                    // Confirmation for indication; nothing additional needed
                }
                else -> {
                    sendAttError(hci, handle, opcode, 0, BleHciConstants.ATT_ERR_REQUEST_NOT_SUPPORTED)
                }
            }
        }
    }

    private suspend fun sendAttError(
        hci: BleHciEngine,
        connectionHandle: Int,
        reqOpcode: Int,
        attrHandle: Int,
        errorCode: Int
    ) {
        val err = ByteArray(5)
        err[0] = BleHciConstants.ATT_OP_ERROR_RSP.toByte()
        err[1] = (reqOpcode and 0xFF).toByte()
        putU16le(err, 2, attrHandle)
        err[4] = (errorCode and 0xFF).toByte()
        hci.sendL2capPdu(connectionHandle, BleHciConstants.L2CAP_CID_ATT, err)
    }

    private fun handleL2capSignalingPdu(handle: Int, pdu: ByteArray) {
        if (pdu.size < 4) return
        val code = pdu[0].toInt() and 0xFF
        val identifier = pdu[1].toInt() and 0xFF
        when (code) {
            BleHciConstants.L2CAP_LE_CREDIT_CONN_REQ -> {
                if (pdu.size >= 14) {
                    val psm = u16le(pdu, 4)
                    val peerScid = u16le(pdu, 6)
                    val peerMtu = u16le(pdu, 8)
                    val peerMps = u16le(pdu, 10)
                    val initialCredits = u16le(pdu, 12)

                    enqueue {
                        val hci = engine ?: return@enqueue
                        if (l2capServerListening && psm == publishedPsm) {
                            if (advertisingActive) {
                                advertisingActive = false
                                try {
                                    hci.sendCommand(BleHciConstants.HCI_OP_LE_SET_ADVERTISE_ENABLE, byteArrayOf(0x00))
                                } catch (_: Throwable) {
                                }
                            }
                            val localCid = BleHciConstants.L2CAP_DYNAMIC_CID_DEFAULT
                            val channel = HciBleL2capChannel(
                                engine = hci,
                                connectionHandle = handle,
                                localCid = localCid,
                                peerCid = peerScid,
                                peerMtu = peerMtu,
                                peerMps = peerMps,
                                initialPeerCredits = initialCredits
                            )
                            activeL2capChannel = channel

                            val rsp = ByteArray(14)
                            rsp[0] = BleHciConstants.L2CAP_LE_CREDIT_CONN_RSP.toByte()
                            rsp[1] = identifier.toByte()
                            putU16le(rsp, 2, 10)
                            putU16le(rsp, 4, localCid)
                            putU16le(rsp, 6, BleHciConstants.L2CAP_DEFAULT_MTU)
                            putU16le(rsp, 8, BleHciConstants.L2CAP_DEFAULT_MPS)
                            putU16le(rsp, 10, BleHciConstants.L2CAP_INITIAL_CREDITS)
                            putU16le(rsp, 12, 0x0000) // Result = 0x0000 (Connection successful)
                            hci.sendL2capPdu(handle, BleHciConstants.L2CAP_CID_LE_SIGNALING, rsp)

                            callback.onL2capClientConnected(channel)
                        } else {
                            // Refuse connection: 0x0002 = LE_PSM not supported
                            val rsp = ByteArray(14)
                            rsp[0] = BleHciConstants.L2CAP_LE_CREDIT_CONN_RSP.toByte()
                            rsp[1] = identifier.toByte()
                            putU16le(rsp, 2, 10)
                            putU16le(rsp, 12, 0x0002)
                            hci.sendL2capPdu(handle, BleHciConstants.L2CAP_CID_LE_SIGNALING, rsp)
                        }
                    }
                }
            }
            BleHciConstants.L2CAP_FLOW_CONTROL_CREDIT_IND -> {
                if (pdu.size >= 8) {
                    val credits = u16le(pdu, 6)
                    activeL2capChannel?.onPeerCreditsGranted(credits)
                }
            }
            BleHciConstants.L2CAP_DISCONN_REQ -> {
                if (pdu.size >= 8) {
                    val dcid = u16le(pdu, 4)
                    val scid = u16le(pdu, 6)
                    activeL2capChannel?.onPeerDisconnected()
                    val hci = engine
                    if (hci != null) {
                        scope.launch {
                            try {
                                val rsp = ByteArray(8)
                                rsp[0] = BleHciConstants.L2CAP_DISCONN_RSP.toByte()
                                rsp[1] = identifier.toByte()
                                putU16le(rsp, 2, 4)
                                putU16le(rsp, 4, dcid)
                                putU16le(rsp, 6, scid)
                                hci.sendL2capPdu(handle, BleHciConstants.L2CAP_CID_LE_SIGNALING, rsp)
                            } catch (_: Throwable) {
                            }
                        }
                    }
                }
            }
        }
    }

    private fun handleL2capCoCPdu(handle: Int, cid: Int, pdu: ByteArray) {
        val ch = activeL2capChannel
        if (ch != null && cid == ch.localCid) {
            ch.onKFrameReceived(pdu)
        }
    }
}
