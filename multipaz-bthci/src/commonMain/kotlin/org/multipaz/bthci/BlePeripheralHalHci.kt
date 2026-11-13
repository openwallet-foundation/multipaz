package org.multipaz.bthci

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.io.IOException
import org.multipaz.mdoc.transport.BlePeripheralHal
import org.multipaz.util.Logger
import org.multipaz.util.UUID
import kotlin.concurrent.Volatile
import kotlin.math.min
import kotlin.time.Duration

/**
 * Direct Bluetooth HCI implementation of [BlePeripheralHal].
 *
 * @param gapDeviceName the GAP Device Name (`0x2A00`) exposed by the mandatory GAP Primary Service
 *   (`0x1800`) on `CID 0x0004` (defaults to `"Multipaz"`).
 * @param randomAddress optional 6-byte static random LE address in HCI little-endian byte order
 *   (where byte 5 is the MSB with top two bits set to `11`), or `null` to generate one randomly.
 * @param faultConfig optional [BleHciFaultConfig] for injecting deterministic BLE handshake faults.
 * @param packetIoFactory factory that opens the [HciPacketIo] transport for each BLE session.
 */
class BlePeripheralHalHci(
    private val gapDeviceName: String = "Multipaz",
    randomAddress: ByteArray? = null,
    private val faultConfig: BleHciFaultConfig = BleHciFaultConfig(),
    private val packetIoFactory: suspend () -> HciPacketIo = ::openDefaultHciPacketIo
) : BlePeripheralHal {
    private val randomAddressBytes: ByteArray? = randomAddress?.copyOf()

    init {
        if (randomAddressBytes != null) {
            require(randomAddressBytes.size == 6) {
                "Expected 6-byte random address, got ${randomAddressBytes.size}"
            }
        }
    }

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
        val properties: Int = 0,
        var groupEndHandle: Int = handle,
        @Volatile var staticValue: ByteArray = byteArrayOf()
    )

    private data class PendingReadRequest(
        val connectionHandle: Int,
        val attrHandle: Int
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
    private val attributesByHandle = HciSyncMap<Int, GattAttribute>()
    private val valueHandleByCharacteristicUuid = HciSyncMap<UUID, Int>()

    private val nextReadRequestId = HciAtomicInt(1)
    private val pendingReadByRequestId = HciSyncMap<Int, PendingReadRequest>()

    @Volatile
    private var isoServiceEndHandle: Int = 1

    private var initialAdvertisingDelayUsed = false
    private var midHandshakeDisconnectCount = 0
    private var mtuExchangeFailCount = 0
    private var serviceDiscoveryFailCount = 0
    private var charDiscoveryFailCount = 0
    private var charReadFailCount = 0
    private var cccdWriteFailCount = 0
    private var l2capConnectFailCount = 0

    @Volatile
    private var shuttingDown = false

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
                } catch (e: Exception) {
                    if (!closed) {
                        Logger.w(TAG, "Unhandled error in BlePeripheralHalHci action", e)
                        callback.onError(e)
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
                        onL2capCocPdu = ::handleL2capCocPdu,
                        onFatalError = { err ->
                            if (!closed) callback.onError(err)
                        }
                    )
                    hci.initializeController(randomAddressBytes)
                    engine = hci
                }
                if (!closed) {
                    callback.onPowerOn(null)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (!closed) {
                    callback.onPowerOn(e)
                }
            }
        }
    }

    override fun publishL2capChannel() {
        enqueue {
            if (closed) return@enqueue
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
            if (closed) return@enqueue
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

                // 1. ISO 18013-5 Primary Service declaration (0x2800)
                val isoServiceAttr = GattAttribute(
                    handle = nextHandle++,
                    uuid16 = BleHciConstants.GATT_UUID_PRIMARY_SERVICE,
                    uuid128 = null,
                    properties = BleHciConstants.GATT_PROP_READ,
                    staticValue = serviceUuid.toLittleEndianBytes()
                )
                addAttr(isoServiceAttr)

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
                            properties = BleHciConstants.GATT_PROP_READ,
                            staticValue = declValue
                        )
                    )
                    addAttr(
                        GattAttribute(
                            handle = valHandle,
                            uuid16 = null,
                            uuid128 = uuid,
                            characteristicUuid = uuid,
                            properties = properties,
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
                                properties = BleHciConstants.GATT_PROP_READ or BleHciConstants.GATT_PROP_WRITE,
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

                isoServiceAttr.groupEndHandle = nextHandle - 1
                isoServiceEndHandle = isoServiceAttr.groupEndHandle

                // 2. Mandatory GAP Primary Service (0x1800) per Bluetooth Core Spec Vol 3 Part C Section 12,
                // placed after the ISO 18013-5 service handles so existing ISO 18013-5 service handles
                // remain contiguous at handle 1 while centrals querying 0x1800 / 0x2A00 / 0x2A01 succeed.
                val gapServiceAttr = GattAttribute(
                    handle = nextHandle++,
                    uuid16 = BleHciConstants.GATT_UUID_PRIMARY_SERVICE,
                    uuid128 = null,
                    properties = BleHciConstants.GATT_PROP_READ,
                    staticValue = u16leBytes(BleHciConstants.GAP_UUID_SERVICE)
                )
                addAttr(gapServiceAttr)

                fun addCharacteristic16(uuid16: Int, value: ByteArray) {
                    val declHandle = nextHandle++
                    val valHandle = nextHandle++
                    val declValue = ByteArray(5)
                    declValue[0] = BleHciConstants.GATT_PROP_READ.toByte()
                    putU16le(declValue, 1, valHandle)
                    putU16le(declValue, 3, uuid16)
                    addAttr(
                        GattAttribute(
                            handle = declHandle,
                            uuid16 = BleHciConstants.GATT_UUID_CHARACTERISTIC,
                            uuid128 = null,
                            properties = BleHciConstants.GATT_PROP_READ,
                            staticValue = declValue
                        )
                    )
                    addAttr(
                        GattAttribute(
                            handle = valHandle,
                            uuid16 = uuid16,
                            uuid128 = null,
                            properties = BleHciConstants.GATT_PROP_READ,
                            staticValue = value
                        )
                    )
                }

                addCharacteristic16(BleHciConstants.GAP_UUID_DEVICE_NAME, gapDeviceName.encodeToByteArray())
                addCharacteristic16(BleHciConstants.GAP_UUID_APPEARANCE, byteArrayOf(0x00, 0x00))
                gapServiceAttr.groupEndHandle = nextHandle - 1

                if (!closed) {
                    callback.onServiceAdded(null)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (!closed) {
                    callback.onServiceAdded(e)
                }
            }
        }
    }

    override fun startAdvertising(serviceUuid: UUID) {
        enqueue {
            if (closed) return@enqueue
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

                val delayBeforeEnable = if (!initialAdvertisingDelayUsed &&
                    faultConfig.initialAdvertisingDelay > Duration.ZERO
                ) {
                    initialAdvertisingDelayUsed = true
                    faultConfig.initialAdvertisingDelay
                } else {
                    Duration.ZERO
                }

                advertisingActive = true
                if (delayBeforeEnable > Duration.ZERO) {
                    if (!closed) {
                        callback.onAdvertisingStarted(null)
                    }
                    scope.launch {
                        delay(delayBeforeEnable)
                        enqueue {
                            if (advertisingActive && connectionHandle < 0 && !closed && !shuttingDown) {
                                try {
                                    engine?.sendCommand(
                                        BleHciConstants.HCI_OP_LE_SET_ADVERTISE_ENABLE,
                                        byteArrayOf(0x01)
                                    )
                                } catch (e: CancellationException) {
                                    throw e
                                } catch (_: Exception) {
                                }
                            }
                        }
                    }
                } else {
                    val enableRsp = hci.sendCommand(BleHciConstants.HCI_OP_LE_SET_ADVERTISE_ENABLE, byteArrayOf(0x01))
                    hci.checkCommandStatus("HCI_LE_Set_Advertise_Enable(true)", enableRsp)
                    if (!closed) {
                        callback.onAdvertisingStarted(null)
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (!closed) {
                    callback.onAdvertisingStarted(e)
                }
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
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
            }
        }
    }

    private suspend fun sendPduIfConnected(
        hci: BleHciEngine,
        handle: Int,
        cid: Int,
        pdu: ByteArray
    ): Boolean {
        if (closed || shuttingDown || handle < 0 || handle != connectionHandle) {
            return false
        }
        return try {
            hci.sendL2capPdu(handle, cid, pdu)
            true
        } catch (e: CancellationException) {
            throw e
        } catch (e: IOException) {
            Logger.d(TAG, "Dropped outbound L2CAP PDU on CID 0x${cid.toString(16)} after disconnect: ${e.message}")
            false
        }
    }

    override fun sendReadResponse(requestId: Int, statusSuccess: Boolean, value: ByteArray) {
        enqueue {
            val hci = engine ?: return@enqueue
            val pending = pendingReadByRequestId.remove(requestId) ?: return@enqueue
            val handle = connectionHandle
            if (handle < 0 || pending.connectionHandle != handle) return@enqueue
            if (statusSuccess) {
                val maxLen = (negotiatedMtu - 1).coerceAtLeast(22)
                val truncatedLen = min(value.size, maxLen)
                val rsp = ByteArray(1 + truncatedLen)
                rsp[0] = BleHciConstants.ATT_OP_READ_RSP.toByte()
                value.copyInto(rsp, destinationOffset = 1, startIndex = 0, endIndex = truncatedLen)
                sendPduIfConnected(hci, handle, BleHciConstants.L2CAP_CID_ATT, rsp)
            } else {
                sendAttError(
                    hci,
                    handle,
                    BleHciConstants.ATT_OP_READ_REQ,
                    pending.attrHandle,
                    BleHciConstants.ATT_ERR_UNLIKELY_ERROR
                )
            }
        }
    }

    override fun notifyCharacteristicChanged(uuid: UUID, value: ByteArray) {
        enqueue {
            if (closed) return@enqueue
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
                if (!closed) {
                    callback.onNotificationSent(uuid, null)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (!closed) {
                    callback.onNotificationSent(uuid, e)
                }
            }
        }
    }

    override suspend fun shutdown() {
        if (closed) return
        shuttingDown = true
        l2capServerListening = false
        advertisingActive = false
        engine?.flushAndDisconnect()
    }

    override fun close() {
        if (closed) return
        closed = true
        shuttingDown = true
        l2capServerListening = false
        advertisingActive = false
        activeL2capChannel?.close()
        activeL2capChannel = null
        actionQueue.close()
        engine?.close()
        engine = null
        scope.cancel()
    }

    private fun handleLeConnected(handle: Int, peerAddrType: Int, peerAddr: ByteArray) {
        enqueue {
            if (closed || shuttingDown) return@enqueue
            connectionHandle = handle
            negotiatedMtu = 23
        }
    }

    private fun handleLeDisconnected(handle: Int, reason: Int) {
        if (closed || shuttingDown || handle < 0) return
        enqueue {
            if (closed || shuttingDown) return@enqueue
            if (connectionHandle == handle) {
                connectionHandle = -1
                negotiatedMtu = 23
                pendingReadByRequestId.clear()
                for (attr in attributes) {
                    if (attr.uuid16 == BleHciConstants.GATT_UUID_CCCD) {
                        attr.staticValue = byteArrayOf(0x00, 0x00)
                    }
                }
                activeL2capChannel?.onPeerDisconnected()
                activeL2capChannel = null
                if (advertisingActive) {
                    // Controller automatically disables advertising upon LE connection creation;
                    // if the connection dropped before the GATT/L2CAP handshake finished and
                    // stopAdvertising() was called, re-enable advertising so the central can reconnect.
                    try {
                        engine?.sendCommand(BleHciConstants.HCI_OP_LE_SET_ADVERTISE_ENABLE, byteArrayOf(0x01))
                    } catch (e: CancellationException) {
                        throw e
                    } catch (_: Exception) {
                    }
                }
                if (!closed) {
                    callback.onCentralDisconnected()
                }
            }
        }
    }

    private fun handleAttPdu(handle: Int, pdu: ByteArray) {
        if (pdu.isEmpty()) return
        enqueue {
            val hci = engine ?: return@enqueue
            if (closed || shuttingDown || handle != connectionHandle) return@enqueue
            if (pdu.size > negotiatedMtu) {
                negotiatedMtu = pdu.size.coerceAtMost(DEFAULT_SERVER_MTU)
            }
            val opcode = pdu[0].toInt() and 0xFF
            when (opcode) {
                BleHciConstants.ATT_OP_EXCHANGE_MTU_REQ -> {
                    if (pdu.size < 3) {
                        sendAttError(hci, handle, opcode, 0, BleHciConstants.ATT_ERR_INVALID_PDU)
                        return@enqueue
                    }
                    if (mtuExchangeFailCount < faultConfig.failMtuExchangeAttempts) {
                        mtuExchangeFailCount++
                        sendAttError(hci, handle, opcode, 0, BleHciConstants.ATT_ERR_REQUEST_NOT_SUPPORTED)
                        return@enqueue
                    }
                    val clientMtu = u16le(pdu, 1)
                    negotiatedMtu = min(DEFAULT_SERVER_MTU, clientMtu.coerceAtLeast(23))
                    val rsp = ByteArray(3)
                    rsp[0] = BleHciConstants.ATT_OP_EXCHANGE_MTU_RSP.toByte()
                    putU16le(rsp, 1, DEFAULT_SERVER_MTU)
                    if (sendPduIfConnected(hci, handle, BleHciConstants.L2CAP_CID_ATT, rsp) && !closed) {
                        callback.onMtuChanged(negotiatedMtu)
                    }
                }
                BleHciConstants.ATT_OP_READ_BY_GROUP_TYPE_REQ -> {
                    if (pdu.size != 7 && pdu.size != 21) {
                        sendAttError(hci, handle, opcode, 0, BleHciConstants.ATT_ERR_INVALID_PDU)
                        return@enqueue
                    }
                    val startHandle = u16le(pdu, 1)
                    val endHandle = u16le(pdu, 3)
                    if (startHandle == 0 || startHandle > endHandle) {
                        sendAttError(hci, handle, opcode, startHandle, BleHciConstants.ATT_ERR_INVALID_HANDLE)
                        return@enqueue
                    }
                    val groupUuid16 = if (pdu.size == 7) u16le(pdu, 5) else -1
                    if (groupUuid16 != BleHciConstants.GATT_UUID_PRIMARY_SERVICE) {
                        sendAttError(
                            hci,
                            handle,
                            opcode,
                            startHandle,
                            BleHciConstants.ATT_ERR_UNSUPPORTED_GROUP_TYPE
                        )
                        return@enqueue
                    }
                    if (startHandle == 1 && serviceDiscoveryFailCount < faultConfig.failServiceDiscoveryAttempts) {
                        serviceDiscoveryFailCount++
                        sendAttError(hci, handle, opcode, startHandle, BleHciConstants.ATT_ERR_ATTRIBUTE_NOT_FOUND)
                        return@enqueue
                    }
                    val matching = attributes.filter {
                        it.handle in startHandle..endHandle && it.uuid16 == BleHciConstants.GATT_UUID_PRIMARY_SERVICE
                    }
                    if (matching.isEmpty()) {
                        sendAttError(hci, handle, opcode, startHandle, BleHciConstants.ATT_ERR_ATTRIBUTE_NOT_FOUND)
                        return@enqueue
                    }
                    val firstVal = matching[0].staticValue
                    val entryLen = (4 + firstVal.size).coerceAtMost(255)
                    val maxRspBytes = (negotiatedMtu - 2).coerceAtLeast(21)
                    val out = HciByteBuffer()
                    out.write(BleHciConstants.ATT_OP_READ_BY_GROUP_TYPE_RSP)
                    out.write(entryLen)
                    var count = 0
                    for (attr in matching) {
                        if (4 + attr.staticValue.size != entryLen) break
                        if (count > 0 && (out.size - 2) + entryLen > maxRspBytes) break
                        out.write(u16leBytes(attr.handle))
                        out.write(u16leBytes(attr.groupEndHandle))
                        out.write(attr.staticValue)
                        count++
                    }
                    sendPduIfConnected(hci, handle, BleHciConstants.L2CAP_CID_ATT, out.toByteArray())
                }
                BleHciConstants.ATT_OP_FIND_BY_TYPE_VALUE_REQ -> {
                    if (pdu.size < 7) {
                        sendAttError(hci, handle, opcode, 0, BleHciConstants.ATT_ERR_INVALID_PDU)
                        return@enqueue
                    }
                    val startHandle = u16le(pdu, 1)
                    val endHandle = u16le(pdu, 3)
                    if (startHandle == 0 || startHandle > endHandle) {
                        sendAttError(hci, handle, opcode, startHandle, BleHciConstants.ATT_ERR_INVALID_HANDLE)
                        return@enqueue
                    }
                    if (startHandle == 1 && midHandshakeDisconnectCount < faultConfig.disconnectMidHandshakeAttempts) {
                        midHandshakeDisconnectCount++
                        disconnectConnectionHandle(hci, handle)
                        return@enqueue
                    }
                    if (startHandle == 1 && serviceDiscoveryFailCount < faultConfig.failServiceDiscoveryAttempts) {
                        serviceDiscoveryFailCount++
                        sendAttError(hci, handle, opcode, startHandle, BleHciConstants.ATT_ERR_ATTRIBUTE_NOT_FOUND)
                        return@enqueue
                    }
                    val attrType = u16le(pdu, 5)
                    val targetVal = pdu.copyOfRange(7, pdu.size)
                    val matching = attributes.filter {
                        it.handle in startHandle..endHandle &&
                            it.uuid16 == attrType &&
                            (it.staticValue contentEquals targetVal)
                    }
                    if (matching.isEmpty()) {
                        sendAttError(hci, handle, opcode, startHandle, BleHciConstants.ATT_ERR_ATTRIBUTE_NOT_FOUND)
                        return@enqueue
                    }
                    val maxRspBytes = (negotiatedMtu - 1).coerceAtLeast(22)
                    val out = HciByteBuffer()
                    out.write(BleHciConstants.ATT_OP_FIND_BY_TYPE_VALUE_RSP)
                    var count = 0
                    for (attr in matching) {
                        if (count > 0 && (out.size - 1) + 4 > maxRspBytes) break
                        out.write(u16leBytes(attr.handle))
                        out.write(u16leBytes(attr.groupEndHandle))
                        count++
                    }
                    sendPduIfConnected(hci, handle, BleHciConstants.L2CAP_CID_ATT, out.toByteArray())
                }
                BleHciConstants.ATT_OP_READ_BY_TYPE_REQ -> {
                    if (pdu.size != 7 && pdu.size != 21) {
                        sendAttError(hci, handle, opcode, 0, BleHciConstants.ATT_ERR_INVALID_PDU)
                        return@enqueue
                    }
                    val startHandle = u16le(pdu, 1)
                    val endHandle = u16le(pdu, 3)
                    if (startHandle == 0 || startHandle > endHandle) {
                        sendAttError(hci, handle, opcode, startHandle, BleHciConstants.ATT_ERR_INVALID_HANDLE)
                        return@enqueue
                    }
                    val type16 = if (pdu.size == 7) u16le(pdu, 5) else null
                    val type128 = if (pdu.size == 21) uuidFromLittleEndianBytes(pdu, 5) else null

                    if (type16 == BleHciConstants.GATT_UUID_CHARACTERISTIC && startHandle <= isoServiceEndHandle) {
                        if (midHandshakeDisconnectCount < faultConfig.disconnectMidHandshakeAttempts) {
                            midHandshakeDisconnectCount++
                            disconnectConnectionHandle(hci, handle)
                            return@enqueue
                        }
                        if (charDiscoveryFailCount < faultConfig.failCharacteristicDiscoveryAttempts) {
                            charDiscoveryFailCount++
                            sendAttError(hci, handle, opcode, startHandle, BleHciConstants.ATT_ERR_ATTRIBUTE_NOT_FOUND)
                            return@enqueue
                        }
                    }

                    val matching = attributes.filter { attr ->
                        attr.handle in startHandle..endHandle &&
                            ((type16 != null && attr.uuid16 == type16) || (type128 != null && attr.uuid128 == type128))
                    }
                    if (matching.isEmpty()) {
                        sendAttError(hci, handle, opcode, startHandle, BleHciConstants.ATT_ERR_ATTRIBUTE_NOT_FOUND)
                        return@enqueue
                    }
                    val firstAttr = matching[0]
                    if ((firstAttr.properties and BleHciConstants.GATT_PROP_READ) == 0) {
                        sendAttError(hci, handle, opcode, firstAttr.handle, BleHciConstants.ATT_ERR_READ_NOT_PERMITTED)
                        return@enqueue
                    }
                    val maxValuePerPair = (negotiatedMtu - 4).coerceIn(0, 253)
                    val firstValSliceLen = min(firstAttr.staticValue.size, maxValuePerPair)
                    val pairLen = 2 + firstValSliceLen
                    val maxRspBytes = (negotiatedMtu - 2).coerceAtLeast(21)
                    val out = HciByteBuffer()
                    out.write(BleHciConstants.ATT_OP_READ_BY_TYPE_RSP)
                    out.write(pairLen)
                    var count = 0
                    for (attr in matching) {
                        if ((attr.properties and BleHciConstants.GATT_PROP_READ) == 0) break
                        val valSliceLen = min(attr.staticValue.size, maxValuePerPair)
                        if (2 + valSliceLen != pairLen) break
                        if (count > 0 && (out.size - 2) + pairLen > maxRspBytes) break
                        out.write(u16leBytes(attr.handle))
                        out.write(attr.staticValue, 0, valSliceLen)
                        count++
                    }
                    sendPduIfConnected(hci, handle, BleHciConstants.L2CAP_CID_ATT, out.toByteArray())
                }
                BleHciConstants.ATT_OP_FIND_INFO_REQ -> {
                    if (pdu.size < 5) {
                        sendAttError(hci, handle, opcode, 0, BleHciConstants.ATT_ERR_INVALID_PDU)
                        return@enqueue
                    }
                    val startHandle = u16le(pdu, 1)
                    val endHandle = u16le(pdu, 3)
                    if (startHandle == 0 || startHandle > endHandle) {
                        sendAttError(hci, handle, opcode, startHandle, BleHciConstants.ATT_ERR_INVALID_HANDLE)
                        return@enqueue
                    }
                    val matching = attributes.filter { it.handle in startHandle..endHandle }
                    if (matching.isEmpty()) {
                        sendAttError(hci, handle, opcode, startHandle, BleHciConstants.ATT_ERR_ATTRIBUTE_NOT_FOUND)
                        return@enqueue
                    }
                    val firstIs16 = matching[0].uuid16 != null
                    val format = if (firstIs16) 0x01 else 0x02
                    val entryLen = if (firstIs16) 4 else 18
                    val maxRspBytes = (negotiatedMtu - 2).coerceAtLeast(21)
                    val out = HciByteBuffer()
                    out.write(BleHciConstants.ATT_OP_FIND_INFO_RSP)
                    out.write(format)
                    var count = 0
                    for (attr in matching) {
                        val is16 = attr.uuid16 != null
                        if (is16 != firstIs16) break
                        if (count > 0 && (out.size - 2) + entryLen > maxRspBytes) break
                        out.write(u16leBytes(attr.handle))
                        if (is16) {
                            out.write(u16leBytes(attr.uuid16!!))
                        } else {
                            out.write(attr.uuid128!!.toLittleEndianBytes())
                        }
                        count++
                    }
                    sendPduIfConnected(hci, handle, BleHciConstants.L2CAP_CID_ATT, out.toByteArray())
                }
                BleHciConstants.ATT_OP_READ_REQ,
                BleHciConstants.ATT_OP_READ_BLOB_REQ -> {
                    val minSize = if (opcode == BleHciConstants.ATT_OP_READ_BLOB_REQ) 5 else 3
                    if (pdu.size < minSize) {
                        sendAttError(hci, handle, opcode, 0, BleHciConstants.ATT_ERR_INVALID_PDU)
                        return@enqueue
                    }
                    val attrHandle = u16le(pdu, 1)
                    val blobOffset = if (opcode == BleHciConstants.ATT_OP_READ_BLOB_REQ) u16le(pdu, 3) else 0
                    val attr = attributesByHandle[attrHandle]
                    if (attr == null) {
                        sendAttError(hci, handle, opcode, attrHandle, BleHciConstants.ATT_ERR_INVALID_HANDLE)
                        return@enqueue
                    }
                    if ((attr.properties and BleHciConstants.GATT_PROP_READ) == 0) {
                        sendAttError(hci, handle, opcode, attrHandle, BleHciConstants.ATT_ERR_READ_NOT_PERMITTED)
                        return@enqueue
                    }
                    if (attr.characteristicUuid != null) {
                        if (opcode == BleHciConstants.ATT_OP_READ_BLOB_REQ) {
                            sendAttError(
                                hci,
                                handle,
                                opcode,
                                attrHandle,
                                BleHciConstants.ATT_ERR_REQUEST_NOT_SUPPORTED
                            )
                            return@enqueue
                        }
                        if (charReadFailCount < faultConfig.failCharacteristicReadAttempts) {
                            charReadFailCount++
                            sendAttError(
                                hci,
                                handle,
                                opcode,
                                attrHandle,
                                BleHciConstants.ATT_ERR_UNLIKELY_ERROR
                            )
                            return@enqueue
                        }
                        if (!closed) {
                            val reqId = nextReadRequestId.getAndIncrement()
                            pendingReadByRequestId[reqId] = PendingReadRequest(handle, attrHandle)
                            callback.onCharacteristicReadRequest(reqId, attr.characteristicUuid)
                        }
                    } else {
                        val fullBytes = attr.staticValue
                        if (blobOffset > fullBytes.size) {
                            sendAttError(hci, handle, opcode, attrHandle, BleHciConstants.ATT_ERR_INVALID_OFFSET)
                            return@enqueue
                        }
                        val maxLen = (negotiatedMtu - 1).coerceAtLeast(22)
                        val sliceLen = min(maxLen, fullBytes.size - blobOffset)
                        val rspOp = if (opcode == BleHciConstants.ATT_OP_READ_BLOB_REQ) {
                            BleHciConstants.ATT_OP_READ_BLOB_RSP
                        } else {
                            BleHciConstants.ATT_OP_READ_RSP
                        }
                        val rsp = ByteArray(1 + sliceLen)
                        rsp[0] = rspOp.toByte()
                        fullBytes.copyInto(
                            rsp,
                            destinationOffset = 1,
                            startIndex = blobOffset,
                            endIndex = blobOffset + sliceLen
                        )
                        sendPduIfConnected(hci, handle, BleHciConstants.L2CAP_CID_ATT, rsp)
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
                        if (value.size != 2) {
                            sendAttError(
                                hci,
                                handle,
                                opcode,
                                attrHandle,
                                BleHciConstants.ATT_ERR_INVALID_ATTR_VALUE_LEN
                            )
                            return@enqueue
                        }
                        if (cccdWriteFailCount < faultConfig.failCccdWriteAttempts) {
                            cccdWriteFailCount++
                            sendAttError(
                                hci,
                                handle,
                                opcode,
                                attrHandle,
                                BleHciConstants.ATT_ERR_UNLIKELY_ERROR
                            )
                            return@enqueue
                        }
                        attr.staticValue = value
                        sendPduIfConnected(
                            hci,
                            handle,
                            BleHciConstants.L2CAP_CID_ATT,
                            byteArrayOf(BleHciConstants.ATT_OP_WRITE_RSP.toByte())
                        )
                    } else if (attr.characteristicUuid != null &&
                        (attr.properties and (BleHciConstants.GATT_PROP_WRITE or BleHciConstants.GATT_PROP_WRITE_NO_RSP)) != 0
                    ) {
                        if (sendPduIfConnected(
                                hci,
                                handle,
                                BleHciConstants.L2CAP_CID_ATT,
                                byteArrayOf(BleHciConstants.ATT_OP_WRITE_RSP.toByte())
                            ) && !closed
                        ) {
                            callback.onCharacteristicWriteRequest(attr.characteristicUuid, value, null)
                        }
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
                    if ((attr.properties and (BleHciConstants.GATT_PROP_WRITE_NO_RSP or BleHciConstants.GATT_PROP_WRITE)) == 0) {
                        return@enqueue
                    }
                    if (!closed) {
                        callback.onCharacteristicWriteRequest(charUuid, value, null)
                    }
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

    private suspend fun disconnectConnectionHandle(hci: BleHciEngine, handle: Int) {
        val params = ByteArray(3)
        putU16le(params, 0, handle and 0x0FFF)
        params[2] = 0x13 // Remote User Terminated Connection
        try {
            hci.sendCommand(BleHciConstants.HCI_OP_DISCONNECT, params)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
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
        sendPduIfConnected(hci, connectionHandle, BleHciConstants.L2CAP_CID_ATT, err)
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
                        if (closed || shuttingDown || handle != connectionHandle) return@enqueue
                        val rejectResult: Int? = when {
                            !l2capServerListening || psm != publishedPsm -> 0x0002 // LE_PSM not supported
                            activeL2capChannel != null -> 0x0004 // No resources available
                            peerScid < BleHciConstants.L2CAP_DYNAMIC_CID_DEFAULT -> 0x0009 // Invalid Source CID
                            peerMtu < 23 || peerMps < 23 -> 0x000B // Unacceptable parameters
                            l2capConnectFailCount < faultConfig.failL2capConnectAttempts -> {
                                l2capConnectFailCount++
                                0x0004 // No resources available
                            }
                            else -> null
                        }
                        if (rejectResult == null) {
                            if (advertisingActive) {
                                try {
                                    hci.sendCommand(
                                        BleHciConstants.HCI_OP_LE_SET_ADVERTISE_ENABLE,
                                        byteArrayOf(0x00)
                                    )
                                } catch (e: CancellationException) {
                                    throw e
                                } catch (_: Exception) {
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

                            val rsp = ByteArray(14)
                            rsp[0] = BleHciConstants.L2CAP_LE_CREDIT_CONN_RSP.toByte()
                            rsp[1] = identifier.toByte()
                            putU16le(rsp, 2, 10)
                            putU16le(rsp, 4, localCid)
                            putU16le(rsp, 6, BleHciConstants.L2CAP_DEFAULT_MTU)
                            putU16le(rsp, 8, BleHciConstants.L2CAP_DEFAULT_MPS)
                            putU16le(rsp, 10, BleHciConstants.L2CAP_INITIAL_CREDITS)
                            putU16le(rsp, 12, 0x0000) // Result = 0x0000 (Connection successful)

                            activeL2capChannel = channel
                            if (sendPduIfConnected(hci, handle, BleHciConstants.L2CAP_CID_LE_SIGNALING, rsp)) {
                                advertisingActive = false
                                if (!closed) {
                                    callback.onL2capClientConnected(channel)
                                } else {
                                    channel.close()
                                }
                            } else {
                                activeL2capChannel = null
                                channel.close()
                            }
                        } else {
                            val rsp = ByteArray(14)
                            rsp[0] = BleHciConstants.L2CAP_LE_CREDIT_CONN_RSP.toByte()
                            rsp[1] = identifier.toByte()
                            putU16le(rsp, 2, 10)
                            putU16le(rsp, 12, rejectResult)
                            sendPduIfConnected(hci, handle, BleHciConstants.L2CAP_CID_LE_SIGNALING, rsp)
                        }
                    }
                }
            }
            BleHciConstants.L2CAP_FLOW_CONTROL_CREDIT_IND -> {
                if (pdu.size >= 8) {
                    val cid = u16le(pdu, 4)
                    val credits = u16le(pdu, 6)
                    val ch = activeL2capChannel
                    if (ch != null && ch.connectionHandle == handle && cid == ch.peerCid) {
                        ch.onPeerCreditsGranted(credits)
                    }
                }
            }
            BleHciConstants.L2CAP_DISCONN_REQ -> {
                if (pdu.size >= 8) {
                    val dcid = u16le(pdu, 4)
                    val scid = u16le(pdu, 6)
                    enqueue {
                        val hci = engine ?: return@enqueue
                        if (closed || shuttingDown || handle != connectionHandle) return@enqueue
                        val ch = activeL2capChannel
                        if (ch != null && dcid == ch.localCid && scid == ch.peerCid) {
                            ch.onPeerDisconnected()
                            activeL2capChannel = null
                            val rsp = ByteArray(8)
                            rsp[0] = BleHciConstants.L2CAP_DISCONN_RSP.toByte()
                            rsp[1] = identifier.toByte()
                            putU16le(rsp, 2, 4)
                            putU16le(rsp, 4, dcid)
                            putU16le(rsp, 6, scid)
                            sendPduIfConnected(hci, handle, BleHciConstants.L2CAP_CID_LE_SIGNALING, rsp)
                        }
                    }
                }
            }
            else -> {
                val isResponseOrIndication = code == BleHciConstants.L2CAP_CMD_REJECT ||
                    code == BleHciConstants.L2CAP_DISCONN_RSP ||
                    code == BleHciConstants.L2CAP_CONN_PARAM_UPDATE_RSP ||
                    code == BleHciConstants.L2CAP_LE_CREDIT_CONN_RSP ||
                    code == 0x18 || code == 0x1A
                if (!isResponseOrIndication && identifier != 0) {
                    enqueue {
                        val hci = engine ?: return@enqueue
                        if (closed || shuttingDown || handle != connectionHandle) return@enqueue
                        val rsp = ByteArray(6)
                        rsp[0] = BleHciConstants.L2CAP_CMD_REJECT.toByte()
                        rsp[1] = identifier.toByte()
                        putU16le(rsp, 2, 2)
                        putU16le(rsp, 4, 0x0000) // Command not understood
                        sendPduIfConnected(hci, handle, BleHciConstants.L2CAP_CID_LE_SIGNALING, rsp)
                    }
                }
            }
        }
    }

    private fun handleL2capCocPdu(handle: Int, cid: Int, pdu: ByteArray) {
        val ch = activeL2capChannel
        if (ch != null && ch.connectionHandle == handle && cid == ch.localCid) {
            ch.onKFrameReceived(pdu)
        }
    }
}
