package org.multipaz.mdoc.transport

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import org.multipaz.util.Logger
import org.multipaz.util.UUID
import org.multipaz.util.toHex
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.Volatile
import kotlin.math.min
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Direct HCI implementation of [BleCentralHal] for Linux JVM.
 *
 * @param gapDeviceName the GAP Device Name (`0x2A00`) reported if the connected peripheral queries
 *   the central's GAP server on `CID 0x0004` (defaults to `"Multipaz"`).
 * @param randomAddress optional 6-byte static random LE address, or `null` to generate one randomly.
 * @param packetIoFactory factory that opens the [HciPacketIo] transport for each BLE session.
 */
internal class BleCentralHalHci(
    private val gapDeviceName: String = "Multipaz",
    private val randomAddress: ByteArray? = null,
    private val packetIoFactory: suspend () -> HciPacketIo = {
        LinuxHciSocketPacketIo().also { it.open() }
    }
) : BleCentralHal {
    companion object {
        private const val TAG = "BleCentralHalHci"
        private const val DEFAULT_CENTRAL_MTU = 515
    }

    private class DisconnectedAbortException(message: String) : IOException(message)

    private data class DiscoveredCharacteristic(
        val declHandle: Int,
        val properties: Int,
        val valueHandle: Int,
        val uuid: UUID,
        var cccdHandle: Int? = null
    )

    private lateinit var callback: BleCentralHal.Callback
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private val actionQueue = Channel<suspend () -> Unit>(Channel.UNLIMITED)

    @Volatile
    private var engine: BleHciEngine? = null

    @Volatile
    override var isConnected: Boolean = false
        private set

    @Volatile
    private var connectionHandle: Int = -1

    @Volatile
    private var scanningForUuid: UUID? = null

    @Volatile
    private var discoveredPeerAddrType: Int = 0

    @Volatile
    private var discoveredPeerAddr: ByteArray? = null

    @Volatile
    private var negotiatedMtu: Int = 23

    @Volatile
    private var serviceStartHandle: Int = -1

    @Volatile
    private var serviceEndHandle: Int = -1

    private val characteristicsByUuid = ConcurrentHashMap<UUID, DiscoveredCharacteristic>()
    private val handleToCharacteristicUuid = ConcurrentHashMap<Int, UUID>()

    private val attRequestMutex = Mutex()
    private val pendingAttLock = Any()
    private var pendingAttRequestOpcode: Int = -1
    private var pendingAttDeferred: CompletableDeferred<ByteArray>? = null

    private val pendingConnectLock = Any()
    private var pendingConnectDeferred: CompletableDeferred<Int>? = null

    private val pendingL2capLock = Any()
    private var pendingL2capDeferred: CompletableDeferred<HciBleL2capChannel>? = null
    private val nextL2capIdentifier = AtomicInteger(1)

    @Volatile
    private var activeL2capChannel: HciBleL2capChannel? = null

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
                        Logger.w(TAG, "Unhandled error in BleCentralHalHci action", t)
                        callback.onError(t)
                    }
                }
            }
        }
    }

    private fun enqueue(block: suspend () -> Unit) {
        actionQueue.trySend(block)
    }

    override fun setCallback(callback: BleCentralHal.Callback) {
        this.callback = callback
    }

    override fun waitForPowerOn() {
        enqueue {
            try {
                if (engine == null) {
                    val io = packetIoFactory()
                    val hci = BleHciEngine(
                        packetIo = io,
                        onAdvertisingReport = ::handleAdvertisingReport,
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

    override fun startScan(serviceUuid: UUID) {
        enqueue {
            val hci = engine ?: run {
                callback.onScanFailed(IllegalStateException("HCI engine not initialized"))
                return@enqueue
            }
            try {
                scanningForUuid = serviceUuid
                // Disable any previous scan first (ignore status in case scanning wasn't enabled)
                hci.sendCommand(BleHciConstants.HCI_OP_LE_SET_SCAN_ENABLE, byteArrayOf(0x00, 0x00))

                // Active scan, interval = 0x0030 (30 ms), window = 0x0030 (30 ms), ownAddr = Random (0x01), filter = 0x00
                val scanParams = byteArrayOf(
                    0x01,
                    0x30,
                    0x00,
                    0x30,
                    0x00,
                    0x01,
                    0x00
                )
                val paramRsp = hci.sendCommand(BleHciConstants.HCI_OP_LE_SET_SCAN_PARAMETERS, scanParams)
                hci.checkCommandStatus("HCI_LE_Set_Scan_Parameters", paramRsp)

                val enableRsp = hci.sendCommand(
                    BleHciConstants.HCI_OP_LE_SET_SCAN_ENABLE,
                    byteArrayOf(0x01, 0x00)
                )
                hci.checkCommandStatus("HCI_LE_Set_Scan_Enable(true)", enableRsp)
            } catch (t: Throwable) {
                scanningForUuid = null
                callback.onScanFailed(t)
            }
        }
    }

    override fun stopScan() {
        scanningForUuid = null
        enqueue {
            val hci = engine ?: return@enqueue
            try {
                hci.sendCommand(BleHciConstants.HCI_OP_LE_SET_SCAN_ENABLE, byteArrayOf(0x00, 0x00))
            } catch (_: Throwable) {
            }
        }
    }

    override fun onScanStoppedAfterDiscovery() {
        // Scan already disabled in stopScan()
    }

    override fun connectToPeripheral() {
        enqueue {
            val hci = engine ?: run {
                callback.onDisconnected()
                return@enqueue
            }
            val peerAddr = discoveredPeerAddr ?: run {
                callback.onDisconnected()
                return@enqueue
            }
            val deferred = CompletableDeferred<Int>()
            synchronized(pendingConnectLock) {
                pendingConnectDeferred = deferred
            }
            try {
                // HCI_LE_Create_Connection (25 bytes)
                val params = ByteArray(25)
                putU16le(params, 0, 0x0060) // scanInterval
                putU16le(params, 2, 0x0030) // scanWindow
                params[4] = 0x00 // initiatorFilterPolicy = peer address
                params[5] = (discoveredPeerAddrType and 0xFF).toByte()
                peerAddr.copyInto(params, destinationOffset = 6, startIndex = 0, endIndex = 6)
                params[12] = 0x01 // ownAddressType = Random
                putU16le(params, 13, 0x0006) // connIntervalMin (7.5ms)
                putU16le(params, 15, 0x000C) // connIntervalMax (15ms)
                putU16le(params, 17, 0x0000) // connLatency
                putU16le(params, 19, 0x01F4) // supervisionTimeout (5000ms)
                putU16le(params, 21, 0x0000) // minCeLength
                putU16le(params, 23, 0x0000) // maxCeLength

                val statusRsp = hci.sendCommand(BleHciConstants.HCI_OP_LE_CREATE_CONNECTION, params)
                hci.checkCommandStatus("HCI_LE_Create_Connection", statusRsp)

                val handle = withTimeout(10.seconds) {
                    deferred.await()
                }
                connectionHandle = handle
                isConnected = true
                callback.onConnected()
            } catch (t: Throwable) {
                if (!deferred.isCompleted) {
                    try {
                        hci.sendCommand(BleHciConstants.HCI_OP_LE_CREATE_CONNECTION_CANCEL)
                    } catch (_: Throwable) {
                    }
                }
                callback.onDisconnected()
            } finally {
                synchronized(pendingConnectLock) {
                    if (pendingConnectDeferred === deferred) {
                        pendingConnectDeferred = null
                    }
                }
            }
        }
    }

    override fun requestMtu(mtu: Int) {
        enqueue {
            try {
                val req = ByteArray(3)
                req[0] = BleHciConstants.ATT_OP_EXCHANGE_MTU_REQ.toByte()
                putU16le(req, 1, mtu)
                val rsp = sendAttRequest(req)
                if (rsp.isNotEmpty() && (rsp[0].toInt() and 0xFF) == BleHciConstants.ATT_OP_EXCHANGE_MTU_RSP && rsp.size >= 3) {
                    val serverMtu = u16le(rsp, 1)
                    negotiatedMtu = min(mtu, serverMtu.coerceAtLeast(23))
                    callback.onMtuChanged(negotiatedMtu, null)
                } else if (rsp.isNotEmpty() && (rsp[0].toInt() and 0xFF) == BleHciConstants.ATT_OP_ERROR_RSP) {
                    negotiatedMtu = 23
                    callback.onMtuChanged(23, IOException("ATT_EXCHANGE_MTU_REQ rejected with ATT_ERROR_RSP"))
                } else {
                    callback.onMtuChanged(0, IOException("Unexpected ATT MTU response"))
                }
            } catch (t: Throwable) {
                if (t !is DisconnectedAbortException && !closed) {
                    callback.onMtuChanged(0, t)
                }
            }
        }
    }

    override fun discoverServices(serviceUuid: UUID) {
        enqueue {
            try {
                serviceStartHandle = -1
                serviceEndHandle = -1
                val uuidLe = serviceUuid.toLittleEndianBytes()
                val req = ByteArray(7 + 16)
                req[0] = BleHciConstants.ATT_OP_FIND_BY_TYPE_VALUE_REQ.toByte()
                putU16le(req, 1, 0x0001)
                putU16le(req, 3, 0xFFFF)
                putU16le(req, 5, BleHciConstants.GATT_UUID_PRIMARY_SERVICE)
                uuidLe.copyInto(req, destinationOffset = 7)

                val rsp = sendAttRequest(req)
                val op = rsp[0].toInt() and 0xFF
                if (op == BleHciConstants.ATT_OP_FIND_BY_TYPE_VALUE_RSP && rsp.size >= 5) {
                    serviceStartHandle = u16le(rsp, 1)
                    serviceEndHandle = u16le(rsp, 3)
                    callback.onServicesDiscovered(true, null)
                } else if (op == BleHciConstants.ATT_OP_ERROR_RSP) {
                    callback.onServicesDiscovered(false, null)
                } else {
                    callback.onServicesDiscovered(false, IOException("Unexpected ATT response 0x${op.toString(16)}"))
                }
            } catch (t: Throwable) {
                if (t !is DisconnectedAbortException && !closed) {
                    callback.onServicesDiscovered(false, t)
                }
            }
        }
    }

    override fun discoverCharacteristics(
        stateCharacteristicUuid: UUID,
        client2ServerCharacteristicUuid: UUID,
        server2ClientCharacteristicUuid: UUID,
        identCharacteristicUuid: UUID?,
        l2capCharacteristicUuid: UUID?
    ) {
        enqueue {
            try {
                if (serviceStartHandle < 1 || serviceEndHandle < serviceStartHandle) {
                    throw IllegalStateException("Service handles not discovered before discoverCharacteristics")
                }
                characteristicsByUuid.clear()
                handleToCharacteristicUuid.clear()

                val discoveredList = mutableListOf<DiscoveredCharacteristic>()
                var curStart = serviceStartHandle
                while (curStart <= serviceEndHandle) {
                    val req = ByteArray(7)
                    req[0] = BleHciConstants.ATT_OP_READ_BY_TYPE_REQ.toByte()
                    putU16le(req, 1, curStart)
                    putU16le(req, 3, serviceEndHandle)
                    putU16le(req, 5, BleHciConstants.GATT_UUID_CHARACTERISTIC)

                    val rsp = sendAttRequest(req)
                    val op = rsp[0].toInt() and 0xFF
                    if (op == BleHciConstants.ATT_OP_ERROR_RSP) {
                        break
                    }
                    if (op != BleHciConstants.ATT_OP_READ_BY_TYPE_RSP || rsp.size < 2) {
                        throw IOException("Unexpected ATT_READ_BY_TYPE_RSP opcode 0x${op.toString(16)}")
                    }
                    val pairLen = rsp[1].toInt() and 0xFF
                    if (pairLen < 7) break
                    var offset = 2
                    var lastDeclHandle = curStart
                    while (offset + pairLen <= rsp.size) {
                        val declHandle = u16le(rsp, offset)
                        val props = rsp[offset + 2].toInt() and 0xFF
                        val valHandle = u16le(rsp, offset + 3)
                        if (pairLen == 21) {
                            val charUuid = uuidFromLittleEndianBytes(rsp, offset + 5)
                            val disc = DiscoveredCharacteristic(declHandle, props, valHandle, charUuid)
                            discoveredList.add(disc)
                            characteristicsByUuid[charUuid] = disc
                            handleToCharacteristicUuid[valHandle] = charUuid
                        }
                        lastDeclHandle = declHandle
                        offset += pairLen
                    }
                    curStart = lastDeclHandle + 1
                }

                // Discover CCCD (0x2902) descriptors for characteristics that support notifications/indications
                for (i in discoveredList.indices) {
                    val char = discoveredList[i]
                    if ((char.properties and (BleHciConstants.GATT_PROP_NOTIFY or BleHciConstants.GATT_PROP_INDICATE)) == 0) {
                        continue
                    }
                    val descStart = char.valueHandle + 1
                    val descEnd = if (i + 1 < discoveredList.size) {
                        discoveredList[i + 1].declHandle - 1
                    } else {
                        serviceEndHandle
                    }
                    if (descStart <= descEnd) {
                        val req = ByteArray(5)
                        req[0] = BleHciConstants.ATT_OP_FIND_INFO_REQ.toByte()
                        putU16le(req, 1, descStart)
                        putU16le(req, 3, descEnd)
                        val rsp = sendAttRequest(req)
                        val op = rsp[0].toInt() and 0xFF
                        if (op == BleHciConstants.ATT_OP_FIND_INFO_RSP && rsp.size >= 6) {
                            val format = rsp[1].toInt() and 0xFF
                            if (format == 0x01) {
                                // 16-bit UUIDs: [handleLo, handleHi, uuidLo, uuidHi]
                                var offset = 2
                                while (offset + 4 <= rsp.size) {
                                    val h = u16le(rsp, offset)
                                    val u16 = u16le(rsp, offset + 2)
                                    if (u16 == BleHciConstants.GATT_UUID_CCCD) {
                                        char.cccdHandle = h
                                        break
                                    }
                                    offset += 4
                                }
                            }
                        }
                    }
                }

                for (char in discoveredList) {
                    Logger.d(
                        TAG,
                        "Discovered characteristic ${char.uuid}: declHandle=0x${char.declHandle.toString(16)}, valueHandle=0x${char.valueHandle.toString(16)}, props=0x${char.properties.toString(16)}, cccdHandle=${char.cccdHandle?.let { "0x" + it.toString(16) }}"
                    )
                }

                val stateChar = characteristicsByUuid[stateCharacteristicUuid]
                    ?: throw IllegalStateException("State characteristic $stateCharacteristicUuid not found")
                if (stateChar.cccdHandle == null) {
                    throw IllegalStateException("CCCD not found for State characteristic $stateCharacteristicUuid")
                }
                if (!characteristicsByUuid.containsKey(client2ServerCharacteristicUuid)) {
                    throw IllegalStateException("Client2Server characteristic $client2ServerCharacteristicUuid not found")
                }
                val s2cChar = characteristicsByUuid[server2ClientCharacteristicUuid]
                    ?: throw IllegalStateException("Server2Client characteristic $server2ClientCharacteristicUuid not found")
                if (s2cChar.cccdHandle == null) {
                    throw IllegalStateException("CCCD not found for Server2Client characteristic $server2ClientCharacteristicUuid")
                }
                if (identCharacteristicUuid != null && !characteristicsByUuid.containsKey(identCharacteristicUuid)) {
                    throw IllegalStateException("Ident characteristic $identCharacteristicUuid not found")
                }

                val hasL2cap = l2capCharacteristicUuid != null &&
                    characteristicsByUuid.containsKey(l2capCharacteristicUuid)
                callback.onCharacteristicsDiscovered(null, hasL2cap, null)
            } catch (t: Throwable) {
                if (t !is DisconnectedAbortException && !closed) {
                    callback.onCharacteristicsDiscovered(null, false, t)
                }
            }
        }
    }

    override fun readCharacteristic(uuid: UUID) {
        enqueue {
            try {
                val char = characteristicsByUuid[uuid]
                    ?: throw IllegalStateException("Characteristic $uuid not found")
                val req = ByteArray(3)
                req[0] = BleHciConstants.ATT_OP_READ_REQ.toByte()
                putU16le(req, 1, char.valueHandle)
                val rsp = sendAttRequest(req)
                val op = rsp[0].toInt() and 0xFF
                if (op == BleHciConstants.ATT_OP_READ_RSP) {
                    val value = rsp.copyOfRange(1, rsp.size)
                    callback.onCharacteristicRead(uuid, value, null)
                } else if (op == BleHciConstants.ATT_OP_ERROR_RSP && rsp.size >= 5) {
                    val errCode = rsp[4].toInt() and 0xFF
                    throw IOException(
                        "ATT_READ_REQ for $uuid (handle=0x${char.valueHandle.toString(16)}) failed with ATT_ERROR_RSP errCode=0x${errCode.toString(16)}"
                    )
                } else {
                    throw IOException("ATT_READ_REQ for $uuid failed with opcode 0x${op.toString(16)}")
                }
            } catch (t: Throwable) {
                if (t !is DisconnectedAbortException && !closed) {
                    callback.onCharacteristicRead(uuid, null, t)
                }
            }
        }
    }

    override fun enableNotifications(characteristicUuid: UUID) {
        enqueue {
            try {
                val char = characteristicsByUuid[characteristicUuid]
                    ?: throw IllegalStateException("Characteristic $characteristicUuid not found")
                val cccdHandle = char.cccdHandle
                    ?: throw IllegalStateException("CCCD handle missing for $characteristicUuid")
                val req = ByteArray(5)
                req[0] = BleHciConstants.ATT_OP_WRITE_REQ.toByte()
                putU16le(req, 1, cccdHandle)
                req[3] = 0x01 // Enable Notification
                req[4] = 0x00
                val rsp = sendAttRequest(req)
                val op = rsp[0].toInt() and 0xFF
                if (op == BleHciConstants.ATT_OP_WRITE_RSP) {
                    callback.onDescriptorWrite(characteristicUuid, null)
                } else {
                    throw IOException("CCCD write for $characteristicUuid failed with opcode 0x${op.toString(16)}")
                }
            } catch (t: Throwable) {
                if (t !is DisconnectedAbortException && !closed) {
                    callback.onDescriptorWrite(characteristicUuid, t)
                }
            }
        }
    }

    override fun writeCharacteristic(uuid: UUID, value: ByteArray) {
        enqueue {
            try {
                val hci = engine ?: throw IllegalStateException("HCI engine not initialized")
                val char = characteristicsByUuid[uuid]
                    ?: throw IllegalStateException("Characteristic $uuid not found")
                if ((char.properties and BleHciConstants.GATT_PROP_WRITE_NO_RSP) != 0) {
                    val pdu = ByteArray(3 + value.size)
                    pdu[0] = BleHciConstants.ATT_OP_WRITE_CMD.toByte()
                    putU16le(pdu, 1, char.valueHandle)
                    value.copyInto(pdu, destinationOffset = 3)
                    hci.sendL2capPdu(connectionHandle, BleHciConstants.L2CAP_CID_ATT, pdu)
                    callback.onCharacteristicWrite(uuid, null)
                } else {
                    val req = ByteArray(3 + value.size)
                    req[0] = BleHciConstants.ATT_OP_WRITE_REQ.toByte()
                    putU16le(req, 1, char.valueHandle)
                    value.copyInto(req, destinationOffset = 3)
                    val rsp = sendAttRequest(req)
                    val op = rsp[0].toInt() and 0xFF
                    if (op == BleHciConstants.ATT_OP_WRITE_RSP) {
                        callback.onCharacteristicWrite(uuid, null)
                    } else {
                        throw IOException("Characteristic write failed with opcode 0x${op.toString(16)}")
                    }
                }
            } catch (t: Throwable) {
                if (t !is DisconnectedAbortException && !closed) {
                    callback.onCharacteristicWrite(uuid, t)
                }
            }
        }
    }

    override fun connectL2cap(psm: Int) {
        enqueue {
            val hci = engine ?: run {
                callback.onL2capConnected(null, IllegalStateException("HCI engine not initialized"))
                return@enqueue
            }
            val deferred = CompletableDeferred<HciBleL2capChannel>()
            synchronized(pendingL2capLock) {
                pendingL2capDeferred = deferred
            }
            try {
                val identifier = (nextL2capIdentifier.getAndIncrement() and 0xFF).coerceAtLeast(1)
                val scid = BleHciConstants.L2CAP_DYNAMIC_CID_DEFAULT
                val req = ByteArray(14)
                req[0] = BleHciConstants.L2CAP_LE_CREDIT_CONN_REQ.toByte()
                req[1] = identifier.toByte()
                putU16le(req, 2, 10)
                putU16le(req, 4, psm)
                putU16le(req, 6, scid)
                putU16le(req, 8, BleHciConstants.L2CAP_DEFAULT_MTU)
                putU16le(req, 10, BleHciConstants.L2CAP_DEFAULT_MPS)
                putU16le(req, 12, BleHciConstants.L2CAP_INITIAL_CREDITS)

                hci.sendL2capPdu(connectionHandle, BleHciConstants.L2CAP_CID_LE_SIGNALING, req)
                val channel = withTimeout(5.seconds) {
                    deferred.await()
                }
                activeL2capChannel = channel
                callback.onL2capConnected(channel, null)
            } catch (t: Throwable) {
                if (t !is DisconnectedAbortException && !closed) {
                    callback.onL2capConnected(null, t)
                }
            } finally {
                synchronized(pendingL2capLock) {
                    if (pendingL2capDeferred === deferred) {
                        pendingL2capDeferred = null
                    }
                }
            }
        }
    }

    override fun closeConnectedL2capAttempt() {
        activeL2capChannel?.close()
        activeL2capChannel = null
    }

    override fun close() {
        if (closed) return
        closed = true
        scanningForUuid = null
        isConnected = false
        activeL2capChannel?.close()
        activeL2capChannel = null
        synchronized(pendingAttLock) {
            pendingAttRequestOpcode = -1
            pendingAttDeferred?.cancel()
            pendingAttDeferred = null
        }
        synchronized(pendingConnectLock) {
            pendingConnectDeferred?.cancel()
            pendingConnectDeferred = null
        }
        synchronized(pendingL2capLock) {
            pendingL2capDeferred?.cancel()
            pendingL2capDeferred = null
        }
        actionQueue.close()
        engine?.close()
        engine = null
        scope.cancel()
    }

    private suspend fun sendAttRequest(reqPdu: ByteArray, timeout: Duration = 5.seconds): ByteArray =
        attRequestMutex.withLock {
            val hci = engine ?: throw IllegalStateException("HCI engine not initialized")
            val reqOp = reqPdu[0].toInt() and 0xFF
            val deferred = CompletableDeferred<ByteArray>()
            synchronized(pendingAttLock) {
                pendingAttRequestOpcode = reqOp
                pendingAttDeferred = deferred
            }
            try {
                hci.sendL2capPdu(connectionHandle, BleHciConstants.L2CAP_CID_ATT, reqPdu)
                withTimeout(timeout) {
                    deferred.await()
                }
            } finally {
                synchronized(pendingAttLock) {
                    if (pendingAttDeferred === deferred) {
                        pendingAttRequestOpcode = -1
                        pendingAttDeferred = null
                    }
                }
            }
        }

    private fun handleAdvertisingReport(addrType: Int, addr: ByteArray, adData: ByteArray) {
        val targetUuid = scanningForUuid ?: return
        if (adDataContainsServiceUuid(adData, 0, adData.size, targetUuid)) {
            scanningForUuid = null
            discoveredPeerAddrType = addrType
            discoveredPeerAddr = addr.copyOf()
            callback.onPeripheralDiscovered()
        }
    }

    private fun handleLeConnected(handle: Int, peerAddrType: Int, peerAddr: ByteArray) {
        val deferred = synchronized(pendingConnectLock) { pendingConnectDeferred }
        deferred?.complete(handle)
    }

    private fun handleLeDisconnected(handle: Int, reason: Int) {
        val connectDeferred = synchronized(pendingConnectLock) { pendingConnectDeferred }
        if (connectDeferred != null && !connectDeferred.isCompleted) {
            connectDeferred.completeExceptionally(
                IOException("LE connection failed with HCI status/reason 0x${reason.toString(16)}")
            )
            return
        }
        if (closed) return
        isConnected = false
        connectionHandle = -1
        activeL2capChannel?.onPeerDisconnected()
        synchronized(pendingAttLock) {
            pendingAttDeferred?.completeExceptionally(
                DisconnectedAbortException("Disconnected (reason=0x${reason.toString(16)})")
            )
        }
        synchronized(pendingL2capLock) {
            pendingL2capDeferred?.completeExceptionally(
                DisconnectedAbortException("Disconnected (reason=0x${reason.toString(16)})")
            )
        }
        callback.onDisconnected()
    }

    private fun handleAttPdu(handle: Int, pdu: ByteArray) {
        if (pdu.isEmpty()) return
        val opcode = pdu[0].toInt() and 0xFF
        when (opcode) {
            BleHciConstants.ATT_OP_HANDLE_VALUE_NTF -> {
                if (pdu.size >= 3) {
                    val attrHandle = u16le(pdu, 1)
                    val value = pdu.copyOfRange(3, pdu.size)
                    val uuid = handleToCharacteristicUuid[attrHandle]
                    if (uuid != null) {
                        callback.onCharacteristicChanged(uuid, value)
                    } else {
                        Logger.w(TAG, "Received notification for unknown attrHandle 0x${attrHandle.toString(16)}")
                    }
                }
            }
            BleHciConstants.ATT_OP_HANDLE_VALUE_IND -> {
                if (pdu.size >= 3) {
                    val attrHandle = u16le(pdu, 1)
                    val value = pdu.copyOfRange(3, pdu.size)
                    val hci = engine
                    if (hci != null) {
                        scope.launch {
                            try {
                                hci.sendL2capPdu(
                                    handle,
                                    BleHciConstants.L2CAP_CID_ATT,
                                    byteArrayOf(BleHciConstants.ATT_OP_HANDLE_VALUE_CFM.toByte())
                                )
                            } catch (_: Throwable) {
                            }
                        }
                    }
                    val uuid = handleToCharacteristicUuid[attrHandle]
                    if (uuid != null) {
                        callback.onCharacteristicChanged(uuid, value)
                    } else {
                        Logger.w(TAG, "Received indication for unknown attrHandle 0x${attrHandle.toString(16)}")
                    }
                }
            }
            // Workaround for AOSP BLE peripheral GAP client behavior:
            // Both GATT client and GATT server PDUs share the single fixed ATT channel (L2CAP CID
            // 0x0004). Immediately after HCI_LE_Enhanced_Connection_Complete, Android peripherals
            // (e.g. Pixel 8 Pro) initiate their own GATT client transactions back to the central:
            //   - ATT_READ_BY_TYPE_REQ (0x08) for 0x2A00 (GAP Device Name) and 0x2A01 (GAP Appearance)
            //   - ATT_EXCHANGE_MTU_REQ (0x02)
            // These peripheral-initiated ATT requests arrive while our central's own
            // ATT_EXCHANGE_MTU_REQ (0x02) and ATT_FIND_BY_TYPE_VALUE_REQ (0x06) are in flight.
            // Furthermore, if the central replies to 0x2A00 / 0x2A01 with ATT_ERROR_RSP (0x01),
            // Android's GAP client stalls and disconnects the link after 30s (GATT_CONN_TIMEOUT).
            // We therefore serve a minimal GAP attribute table (Device Name at 0x0003 and
            // Appearance at 0x0005) and handle peripheral-initiated ATT requests independently of
            // pendingAttDeferred.
            BleHciConstants.ATT_OP_EXCHANGE_MTU_REQ -> {
                val peerMtu = if (pdu.size >= 3) u16le(pdu, 1) else 23
                negotiatedMtu = min(DEFAULT_CENTRAL_MTU, peerMtu.coerceAtLeast(23))
                Logger.w(
                    TAG,
                    "AOSP peripheral workaround: peripheral initiated ATT_EXCHANGE_MTU_REQ " +
                        "(peerMtu=$peerMtu) on CID 0x0004 while central is acting as GATT client; " +
                        "replying with $DEFAULT_CENTRAL_MTU (negotiated=$negotiatedMtu)"
                )
                val hci = engine
                if (hci != null) {
                    scope.launch {
                        try {
                            val rsp = ByteArray(3)
                            rsp[0] = BleHciConstants.ATT_OP_EXCHANGE_MTU_RSP.toByte()
                            putU16le(rsp, 1, DEFAULT_CENTRAL_MTU)
                            hci.sendL2capPdu(handle, BleHciConstants.L2CAP_CID_ATT, rsp)
                        } catch (_: Throwable) {
                        }
                    }
                }
            }
            BleHciConstants.ATT_OP_READ_BY_TYPE_REQ -> {
                val startHandle = if (pdu.size >= 3) u16le(pdu, 1) else 0x0001
                val endHandle = if (pdu.size >= 5) u16le(pdu, 3) else 0xFFFF
                val uuid16 = if (pdu.size == 7) u16le(pdu, 5) else -1
                val hci = engine
                if (uuid16 == 0x2A00 && startHandle <= 0x0003 && endHandle >= 0x0003 && hci != null) {
                    Logger.w(
                        TAG,
                        "AOSP peripheral workaround: peripheral queried GAP Device Name (0x2A00) " +
                            "via ATT_READ_BY_TYPE_REQ on CID 0x0004; replying with GAP Device Name " +
                            "\"$gapDeviceName\""
                    )
                    // Mandatory GAP Device Name (0x2A00) at handle 0x0003, truncated to fit ATT MTU
                    val fullNameBytes = gapDeviceName.encodeToByteArray()
                    val maxNameLen = (negotiatedMtu - 4).coerceIn(0, 248)
                    val nameBytes = if (fullNameBytes.size > maxNameLen) {
                        fullNameBytes.copyOfRange(0, maxNameLen)
                    } else {
                        fullNameBytes
                    }
                    val rsp = ByteArray(4 + nameBytes.size)
                    rsp[0] = BleHciConstants.ATT_OP_READ_BY_TYPE_RSP.toByte()
                    rsp[1] = (2 + nameBytes.size).toByte()
                    putU16le(rsp, 2, 0x0003)
                    nameBytes.copyInto(rsp, destinationOffset = 4)
                    scope.launch {
                        try {
                            hci.sendL2capPdu(handle, BleHciConstants.L2CAP_CID_ATT, rsp)
                        } catch (_: Throwable) {
                        }
                    }
                } else if (uuid16 == 0x2A01 && startHandle <= 0x0005 && endHandle >= 0x0005 && hci != null) {
                    Logger.w(
                        TAG,
                        "AOSP peripheral workaround: peripheral queried GAP Appearance (0x2A01) " +
                            "via ATT_READ_BY_TYPE_REQ on CID 0x0004; replying with GAP Appearance"
                    )
                    // Mandatory GAP Appearance (0x2A01) at handle 0x0005
                    val rsp = byteArrayOf(
                        BleHciConstants.ATT_OP_READ_BY_TYPE_RSP.toByte(),
                        0x04,
                        0x05,
                        0x00,
                        0x00,
                        0x00
                    )
                    scope.launch {
                        try {
                            hci.sendL2capPdu(handle, BleHciConstants.L2CAP_CID_ATT, rsp)
                        } catch (_: Throwable) {
                        }
                    }
                } else {
                    Logger.w(
                        TAG,
                        "AOSP peripheral workaround: peripheral sent ATT_READ_BY_TYPE_REQ " +
                            "(uuid16=0x${uuid16.toString(16)}) on CID 0x0004; replying with ATT_ERROR_RSP"
                    )
                    sendAttErrorFromCentral(handle, opcode, startHandle, BleHciConstants.ATT_ERR_ATTRIBUTE_NOT_FOUND)
                }
            }
            BleHciConstants.ATT_OP_FIND_INFO_REQ,
            BleHciConstants.ATT_OP_FIND_BY_TYPE_VALUE_REQ,
            BleHciConstants.ATT_OP_READ_BY_GROUP_TYPE_REQ -> {
                val startHandle = if (pdu.size >= 3) u16le(pdu, 1) else 0x0001
                Logger.w(
                    TAG,
                    "AOSP peripheral workaround: peripheral sent ATT request op=0x${opcode.toString(16)} " +
                        "on CID 0x0004; replying with ATT_ERROR_RSP"
                )
                sendAttErrorFromCentral(handle, opcode, startHandle, BleHciConstants.ATT_ERR_ATTRIBUTE_NOT_FOUND)
            }
            BleHciConstants.ATT_OP_READ_REQ,
            BleHciConstants.ATT_OP_READ_BLOB_REQ,
            BleHciConstants.ATT_OP_WRITE_REQ -> {
                val attrHandle = if (pdu.size >= 3) u16le(pdu, 1) else 0x0000
                Logger.w(
                    TAG,
                    "AOSP peripheral workaround: peripheral sent ATT request op=0x${opcode.toString(16)} " +
                        "for handle=0x${attrHandle.toString(16)} on CID 0x0004; replying with ATT_ERROR_RSP"
                )
                sendAttErrorFromCentral(handle, opcode, attrHandle, BleHciConstants.ATT_ERR_INVALID_HANDLE)
            }
            BleHciConstants.ATT_OP_READ_MULTI_REQ,
            BleHciConstants.ATT_OP_PREPARE_WRITE_REQ,
            BleHciConstants.ATT_OP_EXECUTE_WRITE_REQ,
            BleHciConstants.ATT_OP_READ_MULTI_VAR_REQ -> {
                Logger.w(
                    TAG,
                    "AOSP peripheral workaround: peripheral sent unsupported ATT request " +
                        "op=0x${opcode.toString(16)} on CID 0x0004; replying with ATT_ERROR_RSP"
                )
                sendAttErrorFromCentral(handle, opcode, 0x0000, BleHciConstants.ATT_ERR_REQUEST_NOT_SUPPORTED)
            }
            BleHciConstants.ATT_OP_WRITE_CMD,
            BleHciConstants.ATT_OP_SIGNED_WRITE_CMD,
            BleHciConstants.ATT_OP_HANDLE_VALUE_CFM -> {
                // Commands / confirmations from peripheral require no ATT response and must not
                // complete pendingAttDeferred.
            }
            else -> {
                if ((opcode and 0x40) == 0 && (opcode and 0x01) == 0) {
                    // Any other even non-command ATT opcode is a client-to-server request from the peripheral
                    Logger.w(
                        TAG,
                        "AOSP peripheral workaround: peripheral sent unknown ATT request " +
                            "op=0x${opcode.toString(16)} on CID 0x0004; replying with ATT_ERROR_RSP"
                    )
                    sendAttErrorFromCentral(handle, opcode, 0x0000, BleHciConstants.ATT_ERR_REQUEST_NOT_SUPPORTED)
                    return
                }
                val deferred = synchronized(pendingAttLock) {
                    val expectedReqOp = pendingAttRequestOpcode
                    val isMatchingResponse = expectedReqOp < 0 ||
                        (opcode == expectedReqOp + 1) ||
                        (opcode == BleHciConstants.ATT_OP_ERROR_RSP &&
                            (pdu.size < 2 || (pdu[1].toInt() and 0xFF) == expectedReqOp))
                    if (isMatchingResponse) {
                        pendingAttDeferred
                    } else {
                        Logger.w(
                            TAG,
                            "Ignoring mismatched ATT response op=0x${opcode.toString(16)} while waiting for reqOp=0x${expectedReqOp.toString(16)}: ${pdu.toHex()}"
                        )
                        null
                    }
                }
                deferred?.complete(pdu)
            }
        }
    }

    private fun sendAttErrorFromCentral(
        handle: Int,
        reqOpcode: Int,
        attrHandle: Int,
        errorCode: Int
    ) {
        val hci = engine ?: return
        scope.launch {
            try {
                val err = ByteArray(5)
                err[0] = BleHciConstants.ATT_OP_ERROR_RSP.toByte()
                err[1] = (reqOpcode and 0xFF).toByte()
                putU16le(err, 2, attrHandle)
                err[4] = (errorCode and 0xFF).toByte()
                hci.sendL2capPdu(handle, BleHciConstants.L2CAP_CID_ATT, err)
            } catch (_: Throwable) {
            }
        }
    }

    private fun handleL2capSignalingPdu(handle: Int, pdu: ByteArray) {
        if (pdu.size < 4) return
        val code = pdu[0].toInt() and 0xFF
        val identifier = pdu[1].toInt() and 0xFF
        when (code) {
            BleHciConstants.L2CAP_LE_CREDIT_CONN_RSP -> {
                if (pdu.size >= 14) {
                    val dcid = u16le(pdu, 4)
                    val peerMtu = u16le(pdu, 6)
                    val peerMps = u16le(pdu, 8)
                    val initialCredits = u16le(pdu, 10)
                    val result = u16le(pdu, 12)
                    val deferred = synchronized(pendingL2capLock) { pendingL2capDeferred }
                    if (deferred != null) {
                        val hci = engine
                        if (result == 0x0000 && hci != null) {
                            val ch = HciBleL2capChannel(
                                engine = hci,
                                connectionHandle = handle,
                                localCid = BleHciConstants.L2CAP_DYNAMIC_CID_DEFAULT,
                                peerCid = dcid,
                                peerMtu = peerMtu,
                                peerMps = peerMps,
                                initialPeerCredits = initialCredits
                            )
                            deferred.complete(ch)
                        } else {
                            deferred.completeExceptionally(
                                IOException("L2CAP LE Credit Based Connection refused (result=0x${result.toString(16)})")
                            )
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
            BleHciConstants.L2CAP_CONN_PARAM_UPDATE_REQ -> {
                // Accept connection parameter update requests from the peripheral
                val hci = engine
                if (hci != null) {
                    scope.launch {
                        try {
                            val rsp = ByteArray(6)
                            rsp[0] = BleHciConstants.L2CAP_CONN_PARAM_UPDATE_RSP.toByte()
                            rsp[1] = identifier.toByte()
                            putU16le(rsp, 2, 2)
                            putU16le(rsp, 4, 0x0000) // Accepted
                            hci.sendL2capPdu(handle, BleHciConstants.L2CAP_CID_LE_SIGNALING, rsp)
                        } catch (_: Throwable) {
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
