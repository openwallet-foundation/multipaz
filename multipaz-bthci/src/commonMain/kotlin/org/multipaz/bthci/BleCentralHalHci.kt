package org.multipaz.bthci

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
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.io.IOException
import org.multipaz.mdoc.transport.BleCentralHal
import org.multipaz.util.Logger
import org.multipaz.util.UUID
import org.multipaz.util.toHex
import kotlin.concurrent.Volatile
import kotlin.math.min
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * Direct Bluetooth HCI implementation of [BleCentralHal].
 *
 * @param gapDeviceName the GAP Device Name (`0x2A00`) reported if the connected peripheral queries
 *   the central's GAP server on `CID 0x0004` (defaults to `"Multipaz"`).
 * @param randomAddress optional 6-byte static random LE address in HCI little-endian byte order
 *   (where byte 5 is the MSB with top two bits set to `11`), or `null` to generate one randomly.
 * @param scanInterval the LE scan interval (`2.5 ms` to `10.24 s`, defaults to `60.milliseconds`).
 * @param scanWindow the LE scan window (`2.5 ms` to `10.24 s`, must be `<= scanInterval`, defaults to
 *   `30.milliseconds`).
 * @param connectTimeout per-attempt timeout for `HCI_LE_Create_Connection` before cancelling and
 *   retrying (defaults to `3.seconds`).
 * @param packetIoFactory factory that opens the [HciPacketIo] transport for each BLE session.
 */
class BleCentralHalHci(
    private val gapDeviceName: String = "Multipaz",
    randomAddress: ByteArray? = null,
    private val scanInterval: Duration = 60.milliseconds,
    private val scanWindow: Duration = 30.milliseconds,
    private val connectTimeout: Duration = 3.seconds,
    private val packetIoFactory: suspend () -> HciPacketIo = ::openDefaultHciPacketIo
) : BleCentralHal {
    private val randomAddressBytes: ByteArray? = randomAddress?.copyOf()
    private val scanIntervalUnits: Int = durationToHciScanUnits("scanInterval", scanInterval)
    private val scanWindowUnits: Int = durationToHciScanUnits("scanWindow", scanWindow)

    init {
        if (randomAddressBytes != null) {
            require(randomAddressBytes.size == 6) {
                "Expected 6-byte random address, got ${randomAddressBytes.size}"
            }
        }
        require(scanWindowUnits <= scanIntervalUnits) {
            "scanWindow ($scanWindow) must be <= scanInterval ($scanInterval)"
        }
    }

    companion object {
        private const val TAG = "BleCentralHalHci"
        private const val DEFAULT_CENTRAL_MTU = 515
        private const val L2CAP_DYNAMIC_CID_MAX = 0x007F
        private const val HCI_SCAN_UNITS_MIN = 0x0004 // 2.5 ms
        private const val HCI_SCAN_UNITS_MAX = 0x4000 // 10.24 s

        private fun durationToHciScanUnits(name: String, duration: Duration): Int {
            val units = (duration.inWholeMicroseconds / 625L).toInt()
            require(units in HCI_SCAN_UNITS_MIN..HCI_SCAN_UNITS_MAX) {
                "$name ($duration) out of valid BLE HCI range (2.5ms..10.24s)"
            }
            return units
        }
    }

    private class DisconnectedAbortException(message: String) : IOException(message)
    private class L2capAttemptAbortedException(message: String) : IOException(message)

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

    private val mtuLock = HciLock()

    @Volatile
    private var negotiatedMtu: Int = 23

    private var peripheralInitiatedMtu: Int? = null
    private var centralMtuExchanged: Boolean = false

    @Volatile
    private var serviceStartHandle: Int = -1

    @Volatile
    private var serviceEndHandle: Int = -1

    private val characteristicsByUuid = HciSyncMap<UUID, DiscoveredCharacteristic>()
    private val handleToCharacteristicUuid = HciSyncMap<Int, UUID>()

    private val attRequestMutex = Mutex()
    private val pendingAttLock = HciLock()
    private var pendingAttRequestOpcode: Int = -1
    private var pendingAttDeferred: CompletableDeferred<ByteArray>? = null

    private val pendingConnectLock = HciLock()
    private var pendingConnectDeferred: CompletableDeferred<Int>? = null
    private var onConnectedDelivered: Boolean = false

    private val pendingL2capLock = HciLock()
    private val l2capAttemptEpoch = HciAtomicInt(0)
    private var pendingL2capDeferred: CompletableDeferred<HciBleL2capChannel>? = null
    private var pendingL2capIdentifier: Int = -1
    private var pendingL2capScid: Int = BleHciConstants.L2CAP_DYNAMIC_CID_DEFAULT
    private var currentL2capScid: Int = BleHciConstants.L2CAP_DYNAMIC_CID_DEFAULT
    private val nextL2capIdentifier = HciAtomicInt(1)

    @Volatile
    private var activeL2capChannel: HciBleL2capChannel? = null

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
                        Logger.w(TAG, "Unhandled error in BleCentralHalHci action", e)
                        callback.onError(e)
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
            if (closed) return@enqueue
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

    override fun startScan(serviceUuid: UUID) {
        enqueue {
            if (closed) return@enqueue
            val hci = engine ?: run {
                callback.onScanFailed(IllegalStateException("HCI engine not initialized"))
                return@enqueue
            }
            try {
                scanningForUuid = serviceUuid
                // Disable any previous scan first (ignore status in case scanning wasn't enabled)
                hci.sendCommand(BleHciConstants.HCI_OP_LE_SET_SCAN_ENABLE, byteArrayOf(0x00, 0x00))

                // Active scan, interval = scanIntervalUnits, window = scanWindowUnits,
                // ownAddr = Random (0x01), filter = 0x00
                val scanParams = ByteArray(7)
                scanParams[0] = 0x01
                putU16le(scanParams, 1, scanIntervalUnits)
                putU16le(scanParams, 3, scanWindowUnits)
                scanParams[5] = 0x01
                scanParams[6] = 0x00
                val paramRsp = hci.sendCommand(BleHciConstants.HCI_OP_LE_SET_SCAN_PARAMETERS, scanParams)
                hci.checkCommandStatus("HCI_LE_Set_Scan_Parameters", paramRsp)

                val enableRsp = hci.sendCommand(
                    BleHciConstants.HCI_OP_LE_SET_SCAN_ENABLE,
                    byteArrayOf(0x01, 0x00)
                )
                hci.checkCommandStatus("HCI_LE_Set_Scan_Enable(true)", enableRsp)
            } catch (e: CancellationException) {
                scanningForUuid = null
                throw e
            } catch (e: Exception) {
                scanningForUuid = null
                if (!closed) {
                    callback.onScanFailed(e)
                }
            }
        }
    }

    override fun stopScan() {
        scanningForUuid = null
        enqueue {
            val hci = engine ?: return@enqueue
            try {
                hci.sendCommand(BleHciConstants.HCI_OP_LE_SET_SCAN_ENABLE, byteArrayOf(0x00, 0x00))
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
            }
        }
    }

    override fun onScanStoppedAfterDiscovery() {
        // Scan already disabled in stopScan()
    }

    private suspend fun disconnectOrphanHandle(hci: BleHciEngine, orphanHandle: Int) {
        if (orphanHandle < 0) return
        try {
            val params = ByteArray(3)
            putU16le(params, 0, orphanHandle and 0x0FFF)
            params[2] = 0x13 // Remote User Terminated Connection
            hci.sendCommand(BleHciConstants.HCI_OP_DISCONNECT, params, timeout = 1.seconds)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
        }
    }

    override fun connectToPeripheral() {
        enqueue {
            if (closed) return@enqueue
            val hci = engine ?: run {
                callback.onDisconnected()
                return@enqueue
            }
            val peerAddr = discoveredPeerAddr ?: run {
                callback.onDisconnected()
                return@enqueue
            }
            val deferred = CompletableDeferred<Int>()
            mtuLock.withLock {
                negotiatedMtu = 23
                peripheralInitiatedMtu = null
                centralMtuExchanged = false
            }
            pendingConnectLock.withLock {
                isConnected = false
                connectionHandle = -1
                onConnectedDelivered = false
                pendingConnectDeferred = deferred
            }
            try {
                // HCI_LE_Create_Connection (25 bytes)
                val params = ByteArray(25)
                putU16le(params, 0, scanIntervalUnits) // scanInterval
                putU16le(params, 2, scanWindowUnits) // scanWindow
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

                val handle = withTimeoutOrNull(connectTimeout) {
                    deferred.await()
                }
                if (handle == null) {
                    val racedHandle = pendingConnectLock.withLock {
                        if (pendingConnectDeferred === deferred) {
                            pendingConnectDeferred = null
                        }
                        val h = if (isConnected) connectionHandle else -1
                        isConnected = false
                        connectionHandle = -1
                        onConnectedDelivered = false
                        h
                    }
                    if (racedHandle >= 0) {
                        disconnectOrphanHandle(hci, racedHandle)
                    } else {
                        try {
                            hci.sendCommand(BleHciConstants.HCI_OP_LE_CREATE_CONNECTION_CANCEL)
                        } catch (e: CancellationException) {
                            throw e
                        } catch (_: Exception) {
                        }
                    }
                    if (!closed) {
                        callback.onDisconnected()
                    }
                    return@enqueue
                }

                val deliverConnected = pendingConnectLock.withLock {
                    if (!closed && isConnected && connectionHandle == handle) {
                        onConnectedDelivered = true
                        true
                    } else {
                        false
                    }
                }
                if (!closed) {
                    if (deliverConnected) {
                        callback.onConnected()
                    } else {
                        callback.onDisconnected()
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                val racedHandle = pendingConnectLock.withLock {
                    if (pendingConnectDeferred === deferred) {
                        pendingConnectDeferred = null
                    }
                    val h = if (isConnected) connectionHandle else -1
                    isConnected = false
                    connectionHandle = -1
                    onConnectedDelivered = false
                    h
                }
                if (racedHandle >= 0) {
                    disconnectOrphanHandle(hci, racedHandle)
                } else if (!deferred.isCompleted) {
                    try {
                        hci.sendCommand(BleHciConstants.HCI_OP_LE_CREATE_CONNECTION_CANCEL)
                    } catch (ce: CancellationException) {
                        throw ce
                    } catch (_: Exception) {
                    }
                }
                if (!closed) {
                    callback.onDisconnected()
                }
            } finally {
                pendingConnectLock.withLock {
                    if (pendingConnectDeferred === deferred) {
                        pendingConnectDeferred = null
                    }
                }
            }
        }
    }

    override fun requestMtu(mtu: Int) {
        enqueue {
            if (closed) return@enqueue
            try {
                val req = ByteArray(3)
                req[0] = BleHciConstants.ATT_OP_EXCHANGE_MTU_REQ.toByte()
                putU16le(req, 1, mtu)
                val rsp = sendAttRequest(req)
                if (rsp.isNotEmpty() &&
                    (rsp[0].toInt() and 0xFF) == BleHciConstants.ATT_OP_EXCHANGE_MTU_RSP &&
                    rsp.size >= 3
                ) {
                    val serverMtu = u16le(rsp, 1).coerceAtLeast(23)
                    val updatedMtu = mtuLock.withLock {
                        val peerPrev = peripheralInitiatedMtu
                        val resolved = if (peerPrev != null) {
                            min(min(mtu, serverMtu), peerPrev)
                        } else {
                            min(mtu, serverMtu)
                        }
                        negotiatedMtu = resolved
                        centralMtuExchanged = true
                        resolved
                    }
                    if (!closed) {
                        callback.onMtuChanged(updatedMtu, null)
                    }
                } else if (rsp.isNotEmpty() && (rsp[0].toInt() and 0xFF) == BleHciConstants.ATT_OP_ERROR_RSP) {
                    val existingPeerMtu = mtuLock.withLock {
                        val peerPrev = peripheralInitiatedMtu
                        if (peerPrev != null) {
                            val resolved = min(mtu, peerPrev)
                            negotiatedMtu = resolved
                            centralMtuExchanged = true
                            resolved
                        } else {
                            negotiatedMtu = 23
                            null
                        }
                    }
                    if (!closed) {
                        if (existingPeerMtu != null) {
                            // Peripheral already exchanged MTU on CID 0x0004 and rejected a second exchange;
                            // retain the negotiated MTU rather than falling back to 23 bytes.
                            callback.onMtuChanged(existingPeerMtu, null)
                        } else {
                            callback.onMtuChanged(23, IOException("ATT_EXCHANGE_MTU_REQ rejected with ATT_ERROR_RSP"))
                        }
                    }
                } else if (!closed) {
                    callback.onMtuChanged(0, IOException("Unexpected ATT MTU response"))
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (e !is DisconnectedAbortException && !closed) {
                    callback.onMtuChanged(0, e)
                }
            }
        }
    }

    override fun discoverServices(serviceUuid: UUID) {
        enqueue {
            if (closed) return@enqueue
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
                    val startH = u16le(rsp, 1)
                    val endH = u16le(rsp, 3)
                    if (startH !in 1..0xFFFF || endH !in startH..0xFFFF) {
                        throw IOException(
                            "Invalid GATT service handle range 0x${startH.toString(16)}..0x${endH.toString(16)}"
                        )
                    }
                    serviceStartHandle = startH
                    serviceEndHandle = endH
                    if (!closed) {
                        callback.onServicesDiscovered(true, null)
                    }
                } else if (op == BleHciConstants.ATT_OP_ERROR_RSP) {
                    if (!closed) {
                        callback.onServicesDiscovered(false, null)
                    }
                } else if (!closed) {
                    callback.onServicesDiscovered(false, IOException("Unexpected ATT response 0x${op.toString(16)}"))
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (e !is DisconnectedAbortException && !closed) {
                    callback.onServicesDiscovered(false, e)
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
            if (closed) return@enqueue
            try {
                if (serviceStartHandle < 1 || serviceEndHandle < serviceStartHandle) {
                    throw IllegalStateException("Service handles not discovered before discoverCharacteristics")
                }
                characteristicsByUuid.clear()
                handleToCharacteristicUuid.clear()

                val discoveredList = mutableListOf<DiscoveredCharacteristic>()
                val allDeclHandles = mutableListOf<Int>()
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
                        val errCode = if (rsp.size >= 5) (rsp[4].toInt() and 0xFF) else -1
                        if (errCode == BleHciConstants.ATT_ERR_ATTRIBUTE_NOT_FOUND) {
                            break
                        }
                        throw IOException(
                            "ATT_READ_BY_TYPE_REQ for characteristics failed with errCode=0x${errCode.toString(16)}"
                        )
                    }
                    if (op != BleHciConstants.ATT_OP_READ_BY_TYPE_RSP || rsp.size < 2) {
                        throw IOException("Unexpected ATT_READ_BY_TYPE_RSP opcode 0x${op.toString(16)}")
                    }
                    val pairLen = rsp[1].toInt() and 0xFF
                    if (pairLen < 7 || rsp.size < 2 + pairLen) {
                        throw IOException("Malformed ATT_READ_BY_TYPE_RSP (pairLen=$pairLen, size=${rsp.size})")
                    }
                    var offset = 2
                    var lastDeclHandle = curStart - 1
                    while (offset + pairLen <= rsp.size) {
                        val declHandle = u16le(rsp, offset)
                        if (declHandle <= lastDeclHandle || declHandle > serviceEndHandle) {
                            throw IOException(
                                "Out-of-range characteristic declaration handle 0x${declHandle.toString(16)}"
                            )
                        }
                        val props = rsp[offset + 2].toInt() and 0xFF
                        val valHandle = u16le(rsp, offset + 3)
                        if (valHandle <= declHandle || valHandle > serviceEndHandle) {
                            throw IOException(
                                "Invalid characteristic value handle 0x${valHandle.toString(16)} " +
                                    "for declHandle 0x${declHandle.toString(16)}"
                            )
                        }
                        allDeclHandles.add(declHandle)
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
                for (char in discoveredList) {
                    val notifyOrIndicate = BleHciConstants.GATT_PROP_NOTIFY or BleHciConstants.GATT_PROP_INDICATE
                    if ((char.properties and notifyOrIndicate) == 0) {
                        continue
                    }
                    val descStart = char.valueHandle + 1
                    val nextDecl = allDeclHandles.firstOrNull { it > char.declHandle }
                    val descEnd = if (nextDecl != null) {
                        nextDecl - 1
                    } else {
                        serviceEndHandle
                    }
                    var curDesc = descStart
                    while (curDesc <= descEnd && char.cccdHandle == null) {
                        val req = ByteArray(5)
                        req[0] = BleHciConstants.ATT_OP_FIND_INFO_REQ.toByte()
                        putU16le(req, 1, curDesc)
                        putU16le(req, 3, descEnd)
                        val rsp = sendAttRequest(req)
                        val op = rsp[0].toInt() and 0xFF
                        if (op != BleHciConstants.ATT_OP_FIND_INFO_RSP || rsp.size < 6) {
                            break
                        }
                        val format = rsp[1].toInt() and 0xFF
                        val entrySize = when (format) {
                            0x01 -> 4  // 16-bit UUIDs: [handleLo, handleHi, uuidLo, uuidHi]
                            0x02 -> 18 // 128-bit UUIDs: [handleLo, handleHi, uuid128(16)]
                            else -> break
                        }
                        var offset = 2
                        var lastDescHandle = curDesc - 1
                        while (offset + entrySize <= rsp.size) {
                            val h = u16le(rsp, offset)
                            if (h <= lastDescHandle || h > descEnd) {
                                lastDescHandle = descEnd
                                break
                            }
                            if (format == 0x01 && u16le(rsp, offset + 2) == BleHciConstants.GATT_UUID_CCCD) {
                                char.cccdHandle = h
                                break
                            }
                            lastDescHandle = h
                            offset += entrySize
                        }
                        curDesc = lastDescHandle + 1
                    }
                }

                for (char in discoveredList) {
                    Logger.d(
                        TAG,
                        "Discovered characteristic ${char.uuid}: " +
                            "declHandle=0x${char.declHandle.toString(16)}, " +
                            "valueHandle=0x${char.valueHandle.toString(16)}, " +
                            "props=0x${char.properties.toString(16)}, " +
                            "cccdHandle=${char.cccdHandle?.let { "0x" + it.toString(16) }}"
                    )
                }

                val stateChar = characteristicsByUuid[stateCharacteristicUuid]
                    ?: throw IllegalStateException("State characteristic $stateCharacteristicUuid not found")
                if (stateChar.cccdHandle == null) {
                    throw IllegalStateException("CCCD not found for State characteristic $stateCharacteristicUuid")
                }
                if (!characteristicsByUuid.containsKey(client2ServerCharacteristicUuid)) {
                    throw IllegalStateException(
                        "Client2Server characteristic $client2ServerCharacteristicUuid not found"
                    )
                }
                val s2cChar = characteristicsByUuid[server2ClientCharacteristicUuid]
                    ?: throw IllegalStateException(
                        "Server2Client characteristic $server2ClientCharacteristicUuid not found"
                    )
                if (s2cChar.cccdHandle == null) {
                    throw IllegalStateException(
                        "CCCD not found for Server2Client characteristic $server2ClientCharacteristicUuid"
                    )
                }
                if (identCharacteristicUuid != null && !characteristicsByUuid.containsKey(identCharacteristicUuid)) {
                    throw IllegalStateException("Ident characteristic $identCharacteristicUuid not found")
                }

                val hasL2cap = l2capCharacteristicUuid != null &&
                    characteristicsByUuid.containsKey(l2capCharacteristicUuid)
                if (!closed) {
                    callback.onCharacteristicsDiscovered(null, hasL2cap, null)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (e !is DisconnectedAbortException && !closed) {
                    callback.onCharacteristicsDiscovered(null, false, e)
                }
            }
        }
    }

    override fun readCharacteristic(uuid: UUID) {
        enqueue {
            if (closed) return@enqueue
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
                    if (!closed) {
                        callback.onCharacteristicRead(uuid, value, null)
                    }
                } else if (op == BleHciConstants.ATT_OP_ERROR_RSP && rsp.size >= 5) {
                    val errCode = rsp[4].toInt() and 0xFF
                    throw IOException(
                        "ATT_READ_REQ for $uuid (handle=0x${char.valueHandle.toString(16)}) " +
                            "failed with ATT_ERROR_RSP errCode=0x${errCode.toString(16)}"
                    )
                } else {
                    throw IOException("ATT_READ_REQ for $uuid failed with opcode 0x${op.toString(16)}")
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (e !is DisconnectedAbortException && !closed) {
                    callback.onCharacteristicRead(uuid, null, e)
                }
            }
        }
    }

    override fun enableNotifications(characteristicUuid: UUID) {
        enqueue {
            if (closed) return@enqueue
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
                    if (!closed) {
                        callback.onDescriptorWrite(characteristicUuid, null)
                    }
                } else {
                    throw IOException("CCCD write for $characteristicUuid failed with opcode 0x${op.toString(16)}")
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (e !is DisconnectedAbortException && !closed) {
                    callback.onDescriptorWrite(characteristicUuid, e)
                }
            }
        }
    }

    override fun writeCharacteristic(uuid: UUID, value: ByteArray) {
        enqueue {
            if (closed) return@enqueue
            try {
                val hci = engine ?: throw IllegalStateException("HCI engine not initialized")
                val handle = currentActiveHandleOrThrow()
                val char = characteristicsByUuid[uuid]
                    ?: throw IllegalStateException("Characteristic $uuid not found")
                if ((char.properties and BleHciConstants.GATT_PROP_WRITE_NO_RSP) != 0) {
                    val pdu = ByteArray(3 + value.size)
                    pdu[0] = BleHciConstants.ATT_OP_WRITE_CMD.toByte()
                    putU16le(pdu, 1, char.valueHandle)
                    value.copyInto(pdu, destinationOffset = 3)
                    hci.sendL2capPdu(handle, BleHciConstants.L2CAP_CID_ATT, pdu)
                    if (!closed) {
                        callback.onCharacteristicWrite(uuid, null)
                    }
                } else {
                    val req = ByteArray(3 + value.size)
                    req[0] = BleHciConstants.ATT_OP_WRITE_REQ.toByte()
                    putU16le(req, 1, char.valueHandle)
                    value.copyInto(req, destinationOffset = 3)
                    val rsp = sendAttRequest(req)
                    val op = rsp[0].toInt() and 0xFF
                    if (op == BleHciConstants.ATT_OP_WRITE_RSP) {
                        if (!closed) {
                            callback.onCharacteristicWrite(uuid, null)
                        }
                    } else {
                        throw IOException("Characteristic write failed with opcode 0x${op.toString(16)}")
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (e !is DisconnectedAbortException && !closed) {
                    callback.onCharacteristicWrite(uuid, e)
                }
            }
        }
    }

    private fun advanceL2capScidLocked(): Int {
        val next = if (currentL2capScid >= L2CAP_DYNAMIC_CID_MAX) {
            BleHciConstants.L2CAP_DYNAMIC_CID_DEFAULT
        } else {
            currentL2capScid + 1
        }
        currentL2capScid = next
        return next
    }

    override fun connectL2cap(psm: Int) {
        val attemptEpoch = l2capAttemptEpoch.incrementAndGet()
        enqueue {
            if (closed || l2capAttemptEpoch.get() != attemptEpoch) {
                return@enqueue
            }
            val hci = engine ?: run {
                callback.onL2capConnected(null, IllegalStateException("HCI engine not initialized"))
                return@enqueue
            }
            val identifier = nextNonZeroL2capIdentifier(nextL2capIdentifier)
            val deferred = CompletableDeferred<HciBleL2capChannel>()
            val scid = pendingL2capLock.withLock {
                if (l2capAttemptEpoch.get() != attemptEpoch) {
                    -1
                } else {
                    val allocatedScid = currentL2capScid
                    pendingL2capIdentifier = identifier
                    pendingL2capScid = allocatedScid
                    pendingL2capDeferred = deferred
                    allocatedScid
                }
            }
            if (scid < 0) {
                return@enqueue
            }
            try {
                val handle = currentActiveHandleOrThrow()
                val req = ByteArray(14)
                req[0] = BleHciConstants.L2CAP_LE_CREDIT_CONN_REQ.toByte()
                req[1] = identifier.toByte()
                putU16le(req, 2, 10)
                putU16le(req, 4, psm)
                putU16le(req, 6, scid)
                putU16le(req, 8, BleHciConstants.L2CAP_DEFAULT_MTU)
                putU16le(req, 10, BleHciConstants.L2CAP_DEFAULT_MPS)
                putU16le(req, 12, BleHciConstants.L2CAP_INITIAL_CREDITS)

                hci.sendL2capPdu(handle, BleHciConstants.L2CAP_CID_LE_SIGNALING, req)
                val channel = withTimeoutOrNull(5.seconds) {
                    deferred.await()
                } ?: throw IOException("Timed out waiting for L2CAP LE Credit Based Connection RSP")
                if (closed || l2capAttemptEpoch.get() != attemptEpoch) {
                    channel.disconnectAndClose()
                    return@enqueue
                }
                activeL2capChannel = channel
                callback.onL2capConnected(channel, null)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (e !is DisconnectedAbortException &&
                    e !is L2capAttemptAbortedException &&
                    l2capAttemptEpoch.get() == attemptEpoch &&
                    !closed
                ) {
                    callback.onL2capConnected(null, e)
                }
            } finally {
                pendingL2capLock.withLock {
                    if (pendingL2capDeferred === deferred) {
                        pendingL2capDeferred = null
                        pendingL2capIdentifier = -1
                    }
                }
            }
        }
    }

    override fun closeConnectedL2capAttempt() {
        val prevChannel = activeL2capChannel
        activeL2capChannel = null
        val disconnReq = prevChannel?.closeAndBuildDisconnectReq()
        if (disconnReq != null) {
            // Enqueue on actionQueue so L2CAP_DISCONN_REQ is serialized ahead of any subsequent
            // L2CAP_LE_CREDIT_CONN_REQ enqueued by connectL2cap().
            enqueue {
                val hci = engine ?: return@enqueue
                try {
                    hci.sendL2capPdu(
                        prevChannel.connectionHandle,
                        BleHciConstants.L2CAP_CID_LE_SIGNALING,
                        disconnReq
                    )
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                }
            }
        }
        pendingL2capLock.withLock {
            l2capAttemptEpoch.incrementAndGet()
            val pending = pendingL2capDeferred
            if ((pending != null && !pending.isCompleted) || prevChannel != null) {
                advanceL2capScidLocked()
            }
            pending?.completeExceptionally(L2capAttemptAbortedException("L2CAP connection attempt closed"))
            pendingL2capDeferred = null
            pendingL2capIdentifier = -1
        }
    }

    override suspend fun shutdown() {
        if (closed) return
        shuttingDown = true
        scanningForUuid = null
        engine?.flushAndDisconnect()
    }

    override fun close() {
        if (closed) return
        closed = true
        shuttingDown = true
        scanningForUuid = null
        pendingConnectLock.withLock {
            isConnected = false
            connectionHandle = -1
            onConnectedDelivered = false
            pendingConnectDeferred?.cancel()
            pendingConnectDeferred = null
        }
        activeL2capChannel?.close()
        activeL2capChannel = null
        pendingAttLock.withLock {
            pendingAttRequestOpcode = -1
            pendingAttDeferred?.cancel()
            pendingAttDeferred = null
        }
        pendingL2capLock.withLock {
            pendingL2capIdentifier = -1
            pendingL2capDeferred?.cancel()
            pendingL2capDeferred = null
        }
        actionQueue.close()
        engine?.close()
        engine = null
        scope.cancel()
    }

    private fun currentActiveHandleOrThrow(): Int = pendingConnectLock.withLock {
        if (!isConnected || connectionHandle < 0) {
            throw DisconnectedAbortException("Not connected to peripheral")
        }
        connectionHandle
    }

    private suspend fun sendAttRequest(reqPdu: ByteArray, timeout: Duration = 5.seconds): ByteArray =
        attRequestMutex.withLock {
            val hci = engine ?: throw IllegalStateException("HCI engine not initialized")
            val handle = currentActiveHandleOrThrow()
            val reqOp = reqPdu[0].toInt() and 0xFF
            val deferred = CompletableDeferred<ByteArray>()
            pendingAttLock.withLock {
                pendingAttRequestOpcode = reqOp
                pendingAttDeferred = deferred
            }
            try {
                hci.sendL2capPdu(handle, BleHciConstants.L2CAP_CID_ATT, reqPdu)
                withTimeoutOrNull(timeout) {
                    deferred.await()
                } ?: throw IOException("ATT request op=0x${reqOp.toString(16)} timed out after $timeout")
            } finally {
                pendingAttLock.withLock {
                    if (pendingAttDeferred === deferred) {
                        pendingAttRequestOpcode = -1
                        pendingAttDeferred = null
                    }
                }
            }
        }

    private fun handleAdvertisingReport(addrType: Int, addr: ByteArray, adData: ByteArray) {
        if (closed) return
        val targetUuid = scanningForUuid ?: return
        if (adDataContainsServiceUuid(adData, 0, adData.size, targetUuid)) {
            scanningForUuid = null
            discoveredPeerAddrType = addrType
            discoveredPeerAddr = addr.copyOf()
            if (!closed) {
                callback.onPeripheralDiscovered()
            }
        }
    }

    private fun handleLeConnected(handle: Int, peerAddrType: Int, peerAddr: ByteArray) {
        var orphanHandle = -1
        pendingConnectLock.withLock {
            val deferred = pendingConnectDeferred
            if (!closed && !shuttingDown && deferred != null && !deferred.isCompleted) {
                connectionHandle = handle
                isConnected = true
                onConnectedDelivered = false
                deferred.complete(handle)
            } else {
                orphanHandle = handle
            }
        }
        if (orphanHandle >= 0) {
            val hci = engine
            if (hci != null) {
                scope.launch {
                    disconnectOrphanHandle(hci, orphanHandle)
                }
            }
        }
    }

    private fun handleLeDisconnected(handle: Int, reason: Int) {
        if (closed || shuttingDown) return
        var failConnectDeferred: CompletableDeferred<Int>? = null
        var notifyDisconnected = false
        pendingConnectLock.withLock {
            val connectDeferred = pendingConnectDeferred
            if (connectDeferred != null && !connectDeferred.isCompleted) {
                isConnected = false
                connectionHandle = -1
                onConnectedDelivered = false
                failConnectDeferred = connectDeferred
            } else if (isConnected && (handle < 0 || connectionHandle == handle)) {
                isConnected = false
                connectionHandle = -1
                // Only emit callback.onDisconnected() if connectToPeripheral() already delivered
                // callback.onConnected(); otherwise connectToPeripheral() will see !isConnected
                // upon waking from deferred.await() and deliver callback.onDisconnected() itself.
                notifyDisconnected = onConnectedDelivered
                onConnectedDelivered = false
            }
        }
        if (failConnectDeferred != null) {
            failConnectDeferred!!.completeExceptionally(
                IOException("LE connection failed with HCI status/reason 0x${reason.toString(16)}")
            )
            return
        }
        activeL2capChannel?.onPeerDisconnected()
        pendingAttLock.withLock {
            pendingAttDeferred?.completeExceptionally(
                DisconnectedAbortException("Disconnected (reason=0x${reason.toString(16)})")
            )
        }
        pendingL2capLock.withLock {
            pendingL2capDeferred?.completeExceptionally(
                DisconnectedAbortException("Disconnected (reason=0x${reason.toString(16)})")
            )
        }
        if (notifyDisconnected && !closed) {
            callback.onDisconnected()
        }
    }

    private fun handleAttPdu(handle: Int, pdu: ByteArray) {
        if (closed || handle < 0 || handle != connectionHandle || pdu.isEmpty()) return
        val opcode = pdu[0].toInt() and 0xFF
        when (opcode) {
            BleHciConstants.ATT_OP_HANDLE_VALUE_NTF -> {
                if (pdu.size >= 3) {
                    val attrHandle = u16le(pdu, 1)
                    val value = pdu.copyOfRange(3, pdu.size)
                    val uuid = handleToCharacteristicUuid[attrHandle]
                    if (uuid != null) {
                        if (!closed) {
                            callback.onCharacteristicChanged(uuid, value)
                        }
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
                            } catch (e: CancellationException) {
                                throw e
                            } catch (_: Exception) {
                            }
                        }
                    }
                    val uuid = handleToCharacteristicUuid[attrHandle]
                    if (uuid != null) {
                        if (!closed) {
                            callback.onCharacteristicChanged(uuid, value)
                        }
                    } else {
                        Logger.d(TAG, "Received indication for unknown attrHandle 0x${attrHandle.toString(16)}")
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
                val fromPeer = min(DEFAULT_CENTRAL_MTU, peerMtu.coerceAtLeast(23))
                val updatedMtu = mtuLock.withLock {
                    peripheralInitiatedMtu = fromPeer
                    val resolved = if (centralMtuExchanged) min(negotiatedMtu, fromPeer) else fromPeer
                    negotiatedMtu = resolved
                    resolved
                }
                Logger.w(
                    TAG,
                    "AOSP peripheral workaround: peripheral initiated ATT_EXCHANGE_MTU_REQ " +
                        "(peerMtu=$peerMtu) on CID 0x0004 while central is acting as GATT client; " +
                        "replying with $DEFAULT_CENTRAL_MTU (negotiated=$updatedMtu)"
                )
                val hci = engine
                if (hci != null) {
                    scope.launch {
                        try {
                            val rsp = ByteArray(3)
                            rsp[0] = BleHciConstants.ATT_OP_EXCHANGE_MTU_RSP.toByte()
                            putU16le(rsp, 1, DEFAULT_CENTRAL_MTU)
                            hci.sendL2capPdu(handle, BleHciConstants.L2CAP_CID_ATT, rsp)
                        } catch (e: CancellationException) {
                            throw e
                        } catch (_: Exception) {
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
                        } catch (e: CancellationException) {
                            throw e
                        } catch (_: Exception) {
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
                        } catch (e: CancellationException) {
                            throw e
                        } catch (_: Exception) {
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
            BleHciConstants.ATT_OP_READ_BLOB_REQ -> {
                val attrHandle = if (pdu.size >= 3) u16le(pdu, 1) else 0x0000
                val blobOffset = if (opcode == BleHciConstants.ATT_OP_READ_BLOB_REQ && pdu.size >= 5) {
                    u16le(pdu, 3)
                } else {
                    0
                }
                val hci = engine
                val syntheticVal = when (attrHandle) {
                    0x0003 -> gapDeviceName.encodeToByteArray()
                    0x0005 -> byteArrayOf(0x00, 0x00)
                    else -> null
                }
                if (syntheticVal != null && hci != null && blobOffset <= syntheticVal.size) {
                    val maxLen = (negotiatedMtu - 1).coerceAtLeast(22)
                    val rspOp = if (opcode == BleHciConstants.ATT_OP_READ_BLOB_REQ) {
                        BleHciConstants.ATT_OP_READ_BLOB_RSP
                    } else {
                        BleHciConstants.ATT_OP_READ_RSP
                    }
                    val sliceLen = min(maxLen, syntheticVal.size - blobOffset)
                    val rsp = ByteArray(1 + sliceLen)
                    rsp[0] = rspOp.toByte()
                    syntheticVal.copyInto(
                        rsp,
                        destinationOffset = 1,
                        startIndex = blobOffset,
                        endIndex = blobOffset + sliceLen
                    )
                    scope.launch {
                        try {
                            hci.sendL2capPdu(handle, BleHciConstants.L2CAP_CID_ATT, rsp)
                        } catch (e: CancellationException) {
                            throw e
                        } catch (_: Exception) {
                        }
                    }
                } else {
                    Logger.w(
                        TAG,
                        "AOSP peripheral workaround: peripheral sent ATT read op=0x${opcode.toString(16)} " +
                            "for handle=0x${attrHandle.toString(16)} on CID 0x0004; replying with ATT_ERROR_RSP"
                    )
                    sendAttErrorFromCentral(handle, opcode, attrHandle, BleHciConstants.ATT_ERR_INVALID_HANDLE)
                }
            }
            BleHciConstants.ATT_OP_WRITE_REQ -> {
                val attrHandle = if (pdu.size >= 3) u16le(pdu, 1) else 0x0000
                Logger.w(
                    TAG,
                    "AOSP peripheral workaround: peripheral sent ATT_WRITE_REQ " +
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
                val deferred = pendingAttLock.withLock {
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
                            "Ignoring mismatched ATT response op=0x${opcode.toString(16)} while waiting " +
                                "for reqOp=0x${expectedReqOp.toString(16)}: ${pdu.toHex()}"
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
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
            }
        }
    }

    private fun handleL2capSignalingPdu(handle: Int, pdu: ByteArray) {
        if (closed || handle < 0 || handle != connectionHandle || pdu.size < 4) return
        val code = pdu[0].toInt() and 0xFF
        val identifier = pdu[1].toInt() and 0xFF
        when (code) {
            BleHciConstants.L2CAP_CMD_REJECT -> {
                val reason = if (pdu.size >= 6) u16le(pdu, 4) else -1
                val deferred = pendingL2capLock.withLock {
                    if (pendingL2capIdentifier == identifier) pendingL2capDeferred else null
                }
                deferred?.completeExceptionally(
                    IOException("L2CAP signaling command rejected by peer (reason=0x${reason.toString(16)})")
                )
            }
            BleHciConstants.L2CAP_LE_CREDIT_CONN_RSP -> {
                if (pdu.size >= 14) {
                    val dcid = u16le(pdu, 4)
                    val peerMtu = u16le(pdu, 6)
                    val peerMps = u16le(pdu, 8)
                    val initialCredits = u16le(pdu, 10)
                    val result = u16le(pdu, 12)
                    var localCid = BleHciConstants.L2CAP_DYNAMIC_CID_DEFAULT
                    val deferred = pendingL2capLock.withLock {
                        if (pendingL2capIdentifier == identifier) {
                            localCid = pendingL2capScid
                            pendingL2capDeferred
                        } else {
                            null
                        }
                    }
                    if (deferred != null) {
                        val hci = engine
                        if (result == 0x0000 && hci != null) {
                            if (dcid < BleHciConstants.L2CAP_DYNAMIC_CID_DEFAULT || peerMtu < 23 || peerMps < 23) {
                                deferred.completeExceptionally(
                                    IOException(
                                        "Invalid L2CAP LE Credit Based Connection RSP parameters " +
                                            "(dcid=0x${dcid.toString(16)}, mtu=$peerMtu, mps=$peerMps)"
                                    )
                                )
                            } else {
                                val ch = HciBleL2capChannel(
                                    engine = hci,
                                    connectionHandle = handle,
                                    localCid = localCid,
                                    peerCid = dcid,
                                    peerMtu = peerMtu,
                                    peerMps = peerMps,
                                    initialPeerCredits = initialCredits
                                )
                                deferred.complete(ch)
                            }
                        } else {
                            deferred.completeExceptionally(
                                IOException(
                                    "L2CAP LE Credit Based Connection refused (result=0x${result.toString(16)})"
                                )
                            )
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
                    val ch = activeL2capChannel
                    if (ch != null && ch.connectionHandle == handle && dcid == ch.localCid && scid == ch.peerCid) {
                        ch.onPeerDisconnected()
                        activeL2capChannel = null
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
                                } catch (e: CancellationException) {
                                    throw e
                                } catch (_: Exception) {
                                }
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
                        } catch (e: CancellationException) {
                            throw e
                        } catch (_: Exception) {
                        }
                    }
                }
            }
            else -> {
                val isResponseOrIndication = code == BleHciConstants.L2CAP_DISCONN_RSP ||
                    code == BleHciConstants.L2CAP_CONN_PARAM_UPDATE_RSP ||
                    code == 0x18 || code == 0x1A
                if (!isResponseOrIndication && identifier != 0) {
                    val hci = engine
                    if (hci != null) {
                        scope.launch {
                            try {
                                val rsp = ByteArray(6)
                                rsp[0] = BleHciConstants.L2CAP_CMD_REJECT.toByte()
                                rsp[1] = identifier.toByte()
                                putU16le(rsp, 2, 2)
                                putU16le(rsp, 4, 0x0000) // Command not understood
                                hci.sendL2capPdu(handle, BleHciConstants.L2CAP_CID_LE_SIGNALING, rsp)
                            } catch (e: CancellationException) {
                                throw e
                            } catch (_: Exception) {
                            }
                        }
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
