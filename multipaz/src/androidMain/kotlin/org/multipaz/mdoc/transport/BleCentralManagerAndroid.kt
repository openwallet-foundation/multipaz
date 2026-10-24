package org.multipaz.mdoc.transport

import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothSocket
import android.bluetooth.BluetoothStatusCodes
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.os.Build
import android.os.ParcelUuid
import androidx.annotation.RequiresApi
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.multipaz.context.applicationContext
import org.multipaz.util.Logger
import org.multipaz.util.UUID
import org.multipaz.util.fromJavaUuid
import org.multipaz.util.toJavaUuid
import kotlin.time.Duration.Companion.seconds

internal fun createBleCentralManagerAndroid(): BleCentralManager =
    BleCentralManagerImpl(
        hal = BleCentralHalAndroid(),
    )

internal class BluetoothSocketL2capChannel(
    val socket: BluetoothSocket
) : BleL2capChannel {
    override suspend fun read(buffer: ByteArray, offset: Int, length: Int): Int =
        withContext(Dispatchers.IO) {
            socket.inputStream.read(buffer, offset, length)
        }

    override suspend fun write(data: ByteArray) =
        withContext(Dispatchers.IO) {
            socket.outputStream.write(data)
            socket.outputStream.flush()
        }

    override fun close() {
        try {
            socket.close()
        } catch (_: Exception) {
        }
    }
}

internal class BleCentralHalAndroid : BleCentralHal {
    companion object {
        private const val TAG = "BleCentralHalAndroid"
        private val CLIENT_CHARACTERISTIC_CONFIG_UUID =
            UUID.fromString("00002902-0000-1000-8000-00805f9b34fb").toJavaUuid()

        @Volatile
        private var deferredCloseJob: Job? = null
        private var deferredCloseSocket: BluetoothSocket? = null
    }

    private lateinit var callback: BleCentralHal.Callback
    private val bluetoothManager = applicationContext.getSystemService(BluetoothManager::class.java)
    private var device: BluetoothDevice? = null
    private var gatt: BluetoothGatt? = null
    private var service: BluetoothGattService? = null
    private var targetServiceUuid: UUID? = null
    private val characteristicsByUuid = mutableMapOf<UUID, BluetoothGattCharacteristic>()
    private var l2capSocket: BluetoothSocket? = null
    private var l2capConnectJob: Job? = null

    init {
        if (bluetoothManager.adapter == null) {
            throw IllegalStateException("Bluetooth is not available on this device")
        }
        if (deferredCloseJob != null) {
            Logger.i(TAG, "Closing deferred L2CAP socket (via init)")
            try {
                deferredCloseSocket?.close()
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                Logger.e(TAG, "Error closing L2CAP socket (via init)", e)
            }
            deferredCloseSocket = null
            try {
                deferredCloseJob?.cancel()
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                Logger.e(TAG, "Error canceling deferred closing job (via init)", e)
            }
            deferredCloseJob = null
        }
    }

    override val isConnected: Boolean
        get() = gatt != null

    override fun setCallback(callback: BleCentralHal.Callback) {
        this.callback = callback
    }

    private val scanCallback: ScanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            Logger.d(TAG, "onScanResult: callbackType=$callbackType result=$result")
            device = result.device
            callback.onPeripheralDiscovered()
        }

        override fun onScanFailed(errorCode: Int) {
            Logger.d(TAG, "onScanFailed: errorCode=$errorCode")
            callback.onScanFailed(IllegalStateException("BLE scan failed with error code $errorCode"))
        }
    }

    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            Logger.d(TAG, "onConnectionStateChange: status=$status newState=$newState")
            if (gatt !== this@BleCentralHalAndroid.gatt) {
                Logger.d(TAG, "Ignoring onConnectionStateChange for stale BluetoothGatt instance")
                return
            }
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    gatt.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_HIGH)
                    callback.onConnected()
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    // Immediately clear and close the disconnected BluetoothGatt instance.
                    // On Android, when a connection attempt fails (e.g. status=147), BluetoothGatt
                    // can invoke onConnectionStateChange(..., STATE_DISCONNECTED) a second time a
                    // few milliseconds later (e.g. status=133) when unregistering the client.
                    // Clearing this@BleCentralHalAndroid.gatt here both releases the clientIf
                    // immediately and filters out any trailing callbacks from this BluetoothGatt.
                    this@BleCentralHalAndroid.gatt = null
                    try {
                        gatt.close()
                    } catch (_: Exception) {
                    }
                    callback.onDisconnected()
                }
                else -> {
                    Logger.w(TAG, "onConnectionStateChange(): Unexpected newState $newState")
                }
            }
        }

        override fun onMtuChanged(gatt: BluetoothGatt?, mtu: Int, status: Int) {
            Logger.d(TAG, "onMtuChanged: mtu=$mtu status=$status")
            if (status != BluetoothGatt.GATT_SUCCESS) {
                callback.onMtuChanged(mtu, IllegalStateException("Expected GATT_SUCCESS but got $status"))
            } else {
                callback.onMtuChanged(mtu, null)
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            Logger.d(TAG, "onServicesDiscovered: status=$status")
            if (status != BluetoothGatt.GATT_SUCCESS) {
                callback.onServicesDiscovered(
                    hasTargetService = false,
                    error = IllegalStateException("Expected GATT_SUCCESS but got $status")
                )
            } else {
                val targetUuid = targetServiceUuid
                val foundService = if (targetUuid != null) gatt.getService(targetUuid.toJavaUuid()) else null
                service = foundService
                callback.onServicesDiscovered(hasTargetService = foundService != null, error = null)
            }
        }

        override fun onCharacteristicRead(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray,
            status: Int
        ) {
            val uuid = UUID.fromJavaUuid(characteristic.uuid)
            if (status != BluetoothGatt.GATT_SUCCESS) {
                callback.onCharacteristicRead(
                    uuid,
                    null,
                    IllegalStateException("onCharacteristicRead: Expected GATT_SUCCESS but got $status")
                )
            } else {
                callback.onCharacteristicRead(uuid, value, null)
            }
        }

        @Suppress("DEPRECATION")
        @Deprecated(
            "Used natively in Android 12 and lower",
            ReplaceWith("onCharacteristicRead(gatt, characteristic, characteristic.value, status)")
        )
        override fun onCharacteristicRead(
            gatt: BluetoothGatt?,
            characteristic: BluetoothGattCharacteristic?,
            status: Int
        ) = onCharacteristicRead(gatt!!, characteristic!!, characteristic.value, status)

        override fun onCharacteristicWrite(
            gatt: BluetoothGatt?,
            characteristic: BluetoothGattCharacteristic?,
            status: Int
        ) {
            val uuid = UUID.fromJavaUuid(characteristic!!.uuid)
            if (status != BluetoothGatt.GATT_SUCCESS) {
                callback.onCharacteristicWrite(
                    uuid,
                    IllegalStateException("onCharacteristicWrite: Expected GATT_SUCCESS but got $status")
                )
            } else {
                callback.onCharacteristicWrite(uuid, null)
            }
        }

        override fun onDescriptorWrite(
            gatt: BluetoothGatt?,
            descriptor: BluetoothGattDescriptor?,
            status: Int
        ) {
            val charUuid = UUID.fromJavaUuid(descriptor!!.characteristic.uuid)
            if (status != BluetoothGatt.GATT_SUCCESS) {
                callback.onDescriptorWrite(
                    charUuid,
                    IllegalStateException("Expected GATT_SUCCESS but got $status")
                )
            } else {
                callback.onDescriptorWrite(charUuid, null)
            }
        }

        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray
        ) {
            callback.onCharacteristicChanged(UUID.fromJavaUuid(characteristic.uuid), value)
        }

        @Suppress("DEPRECATION")
        @Deprecated(
            "Used natively in Android 12 and lower",
            ReplaceWith("onCharacteristicChanged(gatt, characteristic, characteristic.value)")
        )
        override fun onCharacteristicChanged(
            gatt: BluetoothGatt?,
            characteristic: BluetoothGattCharacteristic?
        ) = onCharacteristicChanged(gatt!!, characteristic!!, characteristic.value)
    }

    override fun waitForPowerOn() {
        callback.onPowerOn(null)
    }

    override fun startScan(serviceUuid: UUID) {
        val filter = ScanFilter.Builder()
            .setServiceUuid(ParcelUuid(serviceUuid.toJavaUuid()))
            .build()
        val settings = ScanSettings.Builder()
            .setCallbackType(ScanSettings.CALLBACK_TYPE_ALL_MATCHES)
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()
        bluetoothManager.adapter.bluetoothLeScanner
            .startScan(listOf(filter), settings, scanCallback)
    }

    override fun stopScan() {
        bluetoothManager.adapter.bluetoothLeScanner?.stopScan(scanCallback)
    }

    override fun onScanStoppedAfterDiscovery() {
        if (bluetoothManager.adapter.isDiscovering) {
            try {
                Logger.i(TAG, "Calling cancelDiscovery()")
                bluetoothManager.adapter.cancelDiscovery()
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                Logger.w(TAG, "Ignoring error when calling cancelDiscovery", e)
            }
        }
    }

    override fun connectToPeripheral() {
        val dev = checkNotNull(device) { "device is null" }
        // Close any previous BluetoothGatt instance from a failed connection attempt before
        // retrying. Failing to call close() leaks a registered clientIf in Android's Bluetooth
        // daemon (com.android.bluetooth) for the lifetime of the process, which can interfere
        // with subsequent BLE connections and ATT MTU negotiation when this device later acts
        // as a BLE peripheral.
        gatt?.let { prevGatt ->
            try {
                prevGatt.disconnect()
            } catch (_: Exception) {
            }
            try {
                prevGatt.close()
            } catch (_: Exception) {
            }
        }
        gatt = dev.connectGatt(applicationContext, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
    }

    override fun requestMtu(mtu: Int) {
        val g = checkNotNull(gatt) { "gatt is null" }
        g.requestMtu(mtu)
    }

    override fun discoverServices(serviceUuid: UUID) {
        val g = checkNotNull(gatt) { "gatt is null" }
        targetServiceUuid = serviceUuid
        if (!g.discoverServices()) {
            callback.onServicesDiscovered(
                hasTargetService = false,
                error = IllegalStateException("discoverServices() returned false")
            )
        }
    }

    override fun discoverCharacteristics(
        stateCharacteristicUuid: UUID,
        client2ServerCharacteristicUuid: UUID,
        server2ClientCharacteristicUuid: UUID,
        identCharacteristicUuid: UUID?,
        l2capCharacteristicUuid: UUID?
    ) {
        val svc = checkNotNull(service) { "service is null" }
        characteristicsByUuid.clear()

        val stateChar = svc.getCharacteristic(stateCharacteristicUuid.toJavaUuid())
            ?: run {
                callback.onCharacteristicsDiscovered(null, false, IllegalStateException("State characteristic not found"))
                return
            }
        characteristicsByUuid[stateCharacteristicUuid] = stateChar

        val c2sChar = svc.getCharacteristic(client2ServerCharacteristicUuid.toJavaUuid())
            ?: run {
                callback.onCharacteristicsDiscovered(null, false, IllegalStateException("Client2Server characteristic not found"))
                return
            }
        characteristicsByUuid[client2ServerCharacteristicUuid] = c2sChar

        val s2cChar = svc.getCharacteristic(server2ClientCharacteristicUuid.toJavaUuid())
            ?: run {
                callback.onCharacteristicsDiscovered(null, false, IllegalStateException("Server2Client characteristic not found"))
                return
            }
        characteristicsByUuid[server2ClientCharacteristicUuid] = s2cChar

        if (identCharacteristicUuid != null) {
            val identChar = svc.getCharacteristic(identCharacteristicUuid.toJavaUuid())
                ?: run {
                    callback.onCharacteristicsDiscovered(null, false, IllegalStateException("Ident characteristic not found"))
                    return
                }
            characteristicsByUuid[identCharacteristicUuid] = identChar
        }

        var hasL2cap = false
        if (l2capCharacteristicUuid != null) {
            val l2capChar = svc.getCharacteristic(l2capCharacteristicUuid.toJavaUuid())
            if (l2capChar != null) {
                characteristicsByUuid[l2capCharacteristicUuid] = l2capChar
                hasL2cap = true
            } else {
                Logger.i(TAG, "L2CAP characteristic requested but not found")
            }
        }

        callback.onCharacteristicsDiscovered(
            maxWriteSize = null,
            hasL2capCharacteristic = hasL2cap,
            error = null
        )
    }

    override fun readCharacteristic(uuid: UUID) {
        val g = checkNotNull(gatt) { "gatt is null" }
        val char = checkNotNull(characteristicsByUuid[uuid]) { "Characteristic $uuid not found" }
        g.readCharacteristic(char)
    }

    override fun enableNotifications(characteristicUuid: UUID) {
        val g = checkNotNull(gatt) { "gatt is null" }
        val characteristic = checkNotNull(characteristicsByUuid[characteristicUuid]) {
            "Characteristic $characteristicUuid not found"
        }
        if (!g.setCharacteristicNotification(characteristic, true)) {
            callback.onDescriptorWrite(characteristicUuid, IllegalStateException("Error setting notification"))
            return
        }
        val descriptor = characteristic.getDescriptor(CLIENT_CHARACTERISTIC_CONFIG_UUID)
        if (descriptor == null) {
            callback.onDescriptorWrite(
                characteristicUuid,
                IllegalStateException("Error getting clientCharacteristicConfig descriptor")
            )
            return
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val rc = g.writeDescriptor(
                descriptor,
                BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
            )
            if (rc != BluetoothStatusCodes.SUCCESS) {
                callback.onDescriptorWrite(
                    characteristicUuid,
                    IllegalStateException("Error writing to clientCharacteristicConfig descriptor rc=$rc")
                )
            }
        } else {
            @Suppress("DEPRECATION")
            descriptor.setValue(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
            @Suppress("DEPRECATION")
            if (!g.writeDescriptor(descriptor)) {
                callback.onDescriptorWrite(
                    characteristicUuid,
                    IllegalStateException("Error writing to clientCharacteristicConfig descriptor")
                )
            }
        }
    }

    override fun writeCharacteristic(uuid: UUID, value: ByteArray) {
        val g = checkNotNull(gatt) { "gatt is null" }
        val characteristic = checkNotNull(characteristicsByUuid[uuid]) { "Characteristic $uuid not found" }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            val rc = g.writeCharacteristic(
                characteristic,
                value,
                BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
            )
            if (rc != BluetoothStatusCodes.SUCCESS) {
                callback.onCharacteristicWrite(
                    uuid,
                    IllegalStateException("Error writing to characteristic ${characteristic.uuid}, rc=$rc")
                )
            }
        } else {
            @Suppress("DEPRECATION")
            characteristic.setValue(value)
            @Suppress("DEPRECATION")
            if (!g.writeCharacteristic(characteristic)) {
                callback.onCharacteristicWrite(
                    uuid,
                    IllegalStateException("Error writing to characteristic ${characteristic.uuid}")
                )
            }
        }
    }

    override fun connectL2cap(psm: Int) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            connectL2capQ(psm)
        } else {
            callback.onL2capConnected(null, IllegalStateException("L2CAP only supported on API 29 or later"))
        }
    }

    @RequiresApi(api = Build.VERSION_CODES.Q)
    private fun connectL2capQ(psm: Int) {
        val dev = checkNotNull(device) { "device is null" }
        try {
            val socket = dev.createInsecureL2capChannel(psm)
            l2capSocket = socket
            l2capConnectJob = CoroutineScope(Dispatchers.IO).launch {
                try {
                    socket.connect()
                    callback.onL2capConnected(BluetoothSocketL2capChannel(socket), null)
                } catch (e: Exception) {
                    if (e is CancellationException) throw e
                    callback.onL2capConnected(null, e)
                }
            }
        } catch (e: Exception) {
            callback.onL2capConnected(null, e)
        }
    }

    override fun closeConnectedL2capAttempt() {
        try {
            l2capSocket?.close()
        } catch (e: Exception) {
            Logger.w(TAG, "Error during L2CAP cleanup close", e)
        }
        l2capConnectJob?.cancel()
        l2capConnectJob = null
        l2capSocket = null
    }

    override fun close() {
        Logger.d(TAG, "close()")
        bluetoothManager.adapter.bluetoothLeScanner?.stopScan(scanCallback)
        gatt?.disconnect()
        gatt?.close()
        gatt = null
        device = null
        l2capConnectJob?.cancel()
        l2capConnectJob = null
        l2capSocket?.let {
            deferredCloseSocket = it
            deferredCloseJob = CoroutineScope(Dispatchers.IO).launch {
                delay(2.seconds)
                Logger.i(TAG, "Closing deferred L2CAP socket (via delay)")
                try {
                    deferredCloseSocket?.close()
                } catch (e: Exception) {
                    if (e is CancellationException) throw e
                    Logger.e(TAG, "Error closing L2CAP socket (via delay)", e)
                }
                deferredCloseSocket = null
                deferredCloseJob = null
            }
        }
        l2capSocket = null
    }
}
