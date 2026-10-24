package org.multipaz.mdoc.transport

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.withContext
import kotlinx.io.Sink
import kotlinx.io.Source
import kotlinx.io.asSink
import kotlinx.io.asSource
import kotlinx.io.buffered
import org.multipaz.util.Logger
import org.multipaz.util.UUID
import org.multipaz.util.toByteArray
import org.multipaz.util.toKotlinError
import org.multipaz.util.toNSData
import platform.CoreBluetooth.CBCentralManager
import platform.CoreBluetooth.CBCentralManagerDelegateProtocol
import platform.CoreBluetooth.CBCentralManagerStatePoweredOn
import platform.CoreBluetooth.CBCharacteristic
import platform.CoreBluetooth.CBCharacteristicWriteWithoutResponse
import platform.CoreBluetooth.CBL2CAPChannel
import platform.CoreBluetooth.CBPeripheral
import platform.CoreBluetooth.CBPeripheralDelegateProtocol
import platform.CoreBluetooth.CBPeripheralStateConnected
import platform.CoreBluetooth.CBService
import platform.CoreBluetooth.CBUUID
import platform.CoreFoundation.CFAbsoluteTime
import platform.Foundation.NSError
import platform.Foundation.NSNumber
import platform.darwin.NSObject
import kotlin.math.min
import kotlin.time.Duration

internal fun createBleCentralManagerIos(): BleCentralManager =
    BleCentralManagerImpl(
        hal = BleCentralHalIos(),
        postScanDelay = Duration.ZERO,
        requireL2capPeripheralConnectedFirst = true,
    )

internal class CoreBluetoothL2capChannel(
    private val channel: CBL2CAPChannel
) : BleL2capChannel {
    companion object {
        private const val L2CAP_CHUNK_SIZE = 512
    }

    private var sink: Sink? = channel.outputStream!!.asSink().buffered()
    private var source: Source? = channel.inputStream!!.asSource().buffered()

    override suspend fun read(buffer: ByteArray, offset: Int, length: Int): Int =
        withContext(Dispatchers.IO) {
            source?.readAtMostTo(buffer, offset, offset + length) ?: -1
        }

    override suspend fun write(data: ByteArray) {
        // iOS fills in zeroes near the end if a very large message is written at once;
        // chunking in L2CAP_CHUNK_SIZE writes avoids this.
        for (offset in 0 until data.size step L2CAP_CHUNK_SIZE) {
            val size = min(L2CAP_CHUNK_SIZE, data.size - offset)
            val chunk = data.sliceArray(offset until offset + size)
            withContext(Dispatchers.IO) {
                sink?.write(chunk)
                sink?.flush()
            }
        }
    }

    override fun close() {
        try {
            sink?.close()
        } catch (_: Exception) {
        }
        sink = null
        try {
            source?.close()
        } catch (_: Exception) {
        }
        source = null
    }
}

internal class BleCentralHalIos : BleCentralHal {
    companion object {
        private const val TAG = "BleCentralHalIos"
    }

    private lateinit var callback: BleCentralHal.Callback
    private val centralManager: CBCentralManager
    private var peripheral: CBPeripheral? = null
    private val characteristicsByUuid = mutableMapOf<UUID, CBCharacteristic>()
    private var pendingWriteCharacteristicUuid: UUID? = null
    private var pendingDiscoverCharacteristicsSetup: (() -> Unit)? = null
    private var activeL2capChannel: CoreBluetoothL2capChannel? = null

    @Suppress("PARAMETER_NAME_CHANGED_ON_OVERRIDE")
    private val peripheralDelegate = object : NSObject(), CBPeripheralDelegateProtocol {
        override fun peripheral(
            peripheral: CBPeripheral,
            didDiscoverServices: NSError?
        ) {
            val error = didDiscoverServices
            Logger.d(TAG, "didDiscoverServices peripheral=$peripheral error=${error?.toKotlinError()}")
            if (error != null) {
                callback.onServicesDiscovered(hasTargetService = false, error = error.toKotlinError())
            } else {
                callback.onServicesDiscovered(
                    hasTargetService = !peripheral.services.isNullOrEmpty(),
                    error = null
                )
            }
        }

        override fun peripheral(
            peripheral: CBPeripheral,
            didDiscoverCharacteristicsForService: CBService,
            error: NSError?
        ) {
            Logger.d(
                TAG,
                "didDiscoverCharacteristicsForService service=$didDiscoverCharacteristicsForService " +
                        "error=${error?.toKotlinError()}"
            )
            if (error != null) {
                callback.onCharacteristicsDiscovered(null, false, error.toKotlinError())
            } else {
                pendingDiscoverCharacteristicsSetup?.invoke()
            }
        }

        override fun peripheral(
            peripheral: CBPeripheral,
            didUpdateValueForCharacteristic: CBCharacteristic,
            error: NSError?
        ) {
            val characteristic = didUpdateValueForCharacteristic
            val uuid = UUID.fromString(characteristic.UUID.UUIDString)
            if (error != null) {
                callback.onCharacteristicRead(uuid, null, error.toKotlinError())
                return
            }
            val value = characteristic.value?.toByteArray() ?: byteArrayOf()
            // For read characteristics (ident / l2cap), forward to onCharacteristicRead;
            // for notified characteristics (server2Client / state), forward to onCharacteristicChanged.
            callback.onCharacteristicRead(uuid, value, null)
            callback.onCharacteristicChanged(uuid, value)
        }

        override fun peripheralIsReadyToSendWriteWithoutResponse(peripheral: CBPeripheral) {
            Logger.d(TAG, "peripheralIsReadyToSendWriteWithoutResponse peripheral=$peripheral")
            val uuid = pendingWriteCharacteristicUuid ?: UUID(0UL, 0UL)
            pendingWriteCharacteristicUuid = null
            callback.onCharacteristicWrite(uuid, null)
        }

        override fun peripheral(peripheral: CBPeripheral, didModifyServices: List<*>) {
            Logger.d(TAG, "peripheral:didModifyServices invalidatedServices=$didModifyServices")
            callback.onError(IllegalStateException("Remote service vanished"))
        }

        override fun peripheral(
            peripheral: CBPeripheral,
            didOpenL2CAPChannel: CBL2CAPChannel?,
            error: NSError?
        ) {
            Logger.d(TAG, "peripheralDidOpenL2CAPChannel")
            if (error != null || didOpenL2CAPChannel == null) {
                callback.onL2capConnected(
                    null,
                    IllegalStateException("peripheralDidOpenL2CAPChannel failed", error?.toKotlinError())
                )
            } else {
                val channel = CoreBluetoothL2capChannel(didOpenL2CAPChannel)
                activeL2capChannel = channel
                callback.onL2capConnected(channel, null)
            }
        }
    }

    @Suppress("PARAMETER_NAME_CHANGED_ON_OVERRIDE")
    private val centralManagerDelegate = object : NSObject(), CBCentralManagerDelegateProtocol {
        override fun centralManagerDidUpdateState(cbCentralManager: CBCentralManager) {
            Logger.d(TAG, "didUpdateState state=${centralManager.state}")
            if (centralManager.state == CBCentralManagerStatePoweredOn) {
                callback.onPowerOn(null)
            } else {
                callback.onPowerOn(IllegalStateException("Expected poweredOn, got ${centralManager.state}"))
            }
        }

        override fun centralManager(
            central: CBCentralManager,
            didDiscoverPeripheral: CBPeripheral,
            advertisementData: Map<Any?, *>,
            RSSI: NSNumber
        ) {
            Logger.d(TAG, "didDiscoverPeripheral: discoveredPeripheral=$didDiscoverPeripheral")
            peripheral = didDiscoverPeripheral
            didDiscoverPeripheral.delegate = peripheralDelegate
            callback.onPeripheralDiscovered()
        }

        override fun centralManager(
            central: CBCentralManager,
            didConnectPeripheral: CBPeripheral
        ) {
            Logger.d(TAG, "didConnectPeripheral: peripheral=$didConnectPeripheral")
            if (didConnectPeripheral == peripheral) {
                callback.onConnected()
            } else {
                Logger.w(TAG, "Callback for unexpected peripheral")
            }
        }

        override fun centralManager(
            central: CBCentralManager,
            didFailToConnectPeripheral: CBPeripheral,
            error: NSError?
        ) {
            Logger.d(
                TAG,
                "didFailToConnectPeripheral: peripheral=$didFailToConnectPeripheral " +
                        "error=${error?.toKotlinError()}"
            )
            callback.onDisconnected()
        }

        override fun centralManager(
            central: CBCentralManager,
            didDisconnectPeripheral: CBPeripheral,
            timestamp: CFAbsoluteTime,
            isReconnecting: Boolean,
            error: NSError?
        ) {
            Logger.d(
                TAG,
                "didDisconnectPeripheral: peripheral=$didDisconnectPeripheral timestamp=$timestamp " +
                        "isReconnecting=$isReconnecting error=${error?.toKotlinError()}"
            )
            callback.onDisconnected()
        }
    }

    init {
        centralManager = CBCentralManager(
            delegate = centralManagerDelegate,
            queue = null,
            options = null,
        )
    }

    override val isConnected: Boolean
        get() = peripheral?.state == CBPeripheralStateConnected

    override fun setCallback(callback: BleCentralHal.Callback) {
        this.callback = callback
    }

    override fun waitForPowerOn() {
        if (centralManager.state == CBCentralManagerStatePoweredOn) {
            callback.onPowerOn(null)
        }
    }

    override fun startScan(serviceUuid: UUID) {
        centralManager.scanForPeripheralsWithServices(
            serviceUUIDs = listOf(CBUUID.UUIDWithString(serviceUuid.toString())),
            options = null
        )
    }

    override fun stopScan() {
        centralManager.stopScan()
    }

    override fun onScanStoppedAfterDiscovery() {
    }

    override fun connectToPeripheral() {
        val p = checkNotNull(peripheral) { "peripheral is null" }
        centralManager.connectPeripheral(p, null)
    }

    override fun requestMtu(mtu: Int) {
        // The MTU isn't configurable in CoreBluetooth so complete immediately.
        callback.onMtuChanged(515, null)
    }

    override fun discoverServices(serviceUuid: UUID) {
        val p = checkNotNull(peripheral) { "peripheral is null" }
        p.discoverServices(
            serviceUUIDs = listOf(CBUUID.UUIDWithString(serviceUuid.toString()))
        )
    }

    private fun findCharacteristic(
        availableCharacteristics: List<CBCharacteristic>,
        uuid: UUID
    ): CBCharacteristic? {
        val uuidLowercase = uuid.toString().lowercase()
        return availableCharacteristics.find {
            it.UUID.UUIDString.lowercase() == uuidLowercase
        }
    }

    override fun discoverCharacteristics(
        stateCharacteristicUuid: UUID,
        client2ServerCharacteristicUuid: UUID,
        server2ClientCharacteristicUuid: UUID,
        identCharacteristicUuid: UUID?,
        l2capCharacteristicUuid: UUID?
    ) {
        val p = checkNotNull(peripheral) { "peripheral is null" }
        val services = p.services
        if (services.isNullOrEmpty()) {
            callback.onCharacteristicsDiscovered(null, false, IllegalStateException("No services on peripheral"))
            return
        }
        val cbService = services.first() as CBService
        pendingDiscoverCharacteristicsSetup = {
            try {
                @Suppress("UNCHECKED_CAST")
                val chars = (cbService.characteristics as? List<CBCharacteristic>) ?: emptyList()
                characteristicsByUuid.clear()

                val stateChar = findCharacteristic(chars, stateCharacteristicUuid)
                    ?: throw IllegalStateException("Missing characteristic with UUID $stateCharacteristicUuid")
                val c2sChar = findCharacteristic(chars, client2ServerCharacteristicUuid)
                    ?: throw IllegalStateException("Missing characteristic with UUID $client2ServerCharacteristicUuid")
                val s2cChar = findCharacteristic(chars, server2ClientCharacteristicUuid)
                    ?: throw IllegalStateException("Missing characteristic with UUID $server2ClientCharacteristicUuid")

                characteristicsByUuid[stateCharacteristicUuid] = stateChar
                characteristicsByUuid[client2ServerCharacteristicUuid] = c2sChar
                characteristicsByUuid[server2ClientCharacteristicUuid] = s2cChar

                if (identCharacteristicUuid != null) {
                    val identChar = findCharacteristic(chars, identCharacteristicUuid)
                        ?: throw IllegalStateException("Missing characteristic with UUID $identCharacteristicUuid")
                    characteristicsByUuid[identCharacteristicUuid] = identChar
                }

                var hasL2cap = false
                if (l2capCharacteristicUuid != null) {
                    val l2capChar = findCharacteristic(chars, l2capCharacteristicUuid)
                    if (l2capChar != null) {
                        characteristicsByUuid[l2capCharacteristicUuid] = l2capChar
                        hasL2cap = true
                    }
                }

                val maxWriteLen = p.maximumWriteValueLengthForType(CBCharacteristicWriteWithoutResponse).toInt()
                callback.onCharacteristicsDiscovered(
                    maxWriteSize = maxWriteLen,
                    hasL2capCharacteristic = hasL2cap,
                    error = null
                )
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                callback.onCharacteristicsDiscovered(null, false, e)
            }
        }
        p.discoverCharacteristics(characteristicUUIDs = null, forService = cbService)
    }

    override fun readCharacteristic(uuid: UUID) {
        val p = checkNotNull(peripheral) { "peripheral is null" }
        val char = checkNotNull(characteristicsByUuid[uuid]) { "Characteristic $uuid not found" }
        p.readValueForCharacteristic(char)
    }

    override fun enableNotifications(characteristicUuid: UUID) {
        val p = checkNotNull(peripheral) { "peripheral is null" }
        val char = checkNotNull(characteristicsByUuid[characteristicUuid]) {
            "Characteristic $characteristicUuid not found"
        }
        p.setNotifyValue(true, char)
        callback.onDescriptorWrite(characteristicUuid, null)
    }

    override fun writeCharacteristic(uuid: UUID, value: ByteArray) {
        val p = checkNotNull(peripheral) { "peripheral is null" }
        val char = checkNotNull(characteristicsByUuid[uuid]) { "Characteristic $uuid not found" }
        pendingWriteCharacteristicUuid = uuid
        p.writeValue(
            data = value.toNSData(),
            forCharacteristic = char,
            type = CBCharacteristicWriteWithoutResponse
        )
        if (p.canSendWriteWithoutResponse) {
            pendingWriteCharacteristicUuid = null
            callback.onCharacteristicWrite(
                uuid,
                IllegalStateException("canSendWriteWithoutResponse is true right after writing value")
            )
        }
    }

    override fun connectL2cap(psm: Int) {
        val p = checkNotNull(peripheral) { "peripheral is null" }
        p.openL2CAPChannel(psm.toUShort())
    }

    override fun closeConnectedL2capAttempt() {
        activeL2capChannel?.close()
        activeL2capChannel = null
    }

    override fun close() {
        centralManager.delegate = null
        peripheral?.let {
            centralManager.cancelPeripheralConnection(it)
        }
        peripheral?.delegate = null
        peripheral = null
        activeL2capChannel?.close()
        activeL2capChannel = null
    }
}
