package org.multipaz.mdoc.transport

import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattServer
import android.bluetooth.BluetoothGattServerCallback
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothServerSocket
import android.bluetooth.BluetoothSocket
import android.bluetooth.BluetoothStatusCodes
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.bluetooth.le.BluetoothLeAdvertiser
import android.os.Build
import android.os.ParcelUuid
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.multipaz.context.applicationContext
import org.multipaz.util.Logger
import org.multipaz.util.UUID
import org.multipaz.util.fromJavaUuid
import org.multipaz.util.toHex
import org.multipaz.util.toJavaUuid
import kotlin.time.Duration.Companion.seconds

internal fun createBlePeripheralManagerAndroid(): BlePeripheralManager =
    BlePeripheralManagerImpl(
        hal = BlePeripheralHalAndroid(),
    )

internal class BlePeripheralHalAndroid : BlePeripheralHal {
    companion object {
        private const val TAG = "BlePeripheralHalAndroid"
        private val CLIENT_CHARACTERISTIC_CONFIG_UUID =
            UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

        @Volatile
        private var deferredCloseJob: Job? = null
        private var deferredCloseSocket: BluetoothSocket? = null
    }

    private lateinit var callback: BlePeripheralHal.Callback
    private val bluetoothManager = applicationContext.getSystemService(BluetoothManager::class.java)
    private var gattServer: BluetoothGattServer? = null
    private var service: BluetoothGattService? = null
    private val characteristicsByUuid = mutableMapOf<UUID, BluetoothGattCharacteristic>()
    private var advertiser: BluetoothLeAdvertiser? = null
    private var connectedDevice: BluetoothDevice? = null
    private val pendingReadDevices = mutableMapOf<Int, BluetoothDevice>()
    private var l2capServerSocket: BluetoothServerSocket? = null
    private var l2capSocket: BluetoothSocket? = null
    private var l2capServerClosedIntentionally = false

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

    override fun setCallback(callback: BlePeripheralHal.Callback) {
        this.callback = callback
    }

    private val gattServerCallback = object : BluetoothGattServerCallback() {
        override fun onServiceAdded(status: Int, service: BluetoothGattService?) {
            Logger.d(TAG, "onServiceAdded: $status")
            if (status == BluetoothGatt.GATT_SUCCESS) {
                callback.onServiceAdded(null)
            } else {
                callback.onServiceAdded(IllegalStateException("onServiceAdded: Expected GATT_SUCCESS got $status"))
            }
        }

        override fun onConnectionStateChange(device: BluetoothDevice, status: Int, newState: Int) {
            Logger.d(TAG, "onConnectionStateChange: ${device.address} $status + $newState")
            if (newState == BluetoothProfile.STATE_DISCONNECTED &&
                device.address == connectedDevice?.address
            ) {
                Logger.i(TAG, "Central ${device.address} disconnected")
                callback.onCentralDisconnected()
            }
        }

        override fun onCharacteristicReadRequest(
            device: BluetoothDevice,
            requestId: Int,
            offset: Int,
            characteristic: BluetoothGattCharacteristic
        ) {
            Logger.d(TAG, "onCharacteristicReadRequest: ${device.address} $requestId $offset ${characteristic.uuid}")
            pendingReadDevices[requestId] = device
            callback.onCharacteristicReadRequest(requestId, UUID.fromJavaUuid(characteristic.uuid))
        }

        override fun onCharacteristicWriteRequest(
            device: BluetoothDevice,
            requestId: Int,
            characteristic: BluetoothGattCharacteristic,
            preparedWrite: Boolean,
            responseNeeded: Boolean,
            offset: Int,
            value: ByteArray
        ) {
            Logger.d(
                TAG,
                "onCharacteristicWriteRequest: ${device.address} $requestId $offset " +
                        "${characteristic.uuid} ${value.toHex()}"
            )
            if (responseNeeded) {
                gattServer?.sendResponse(
                    device,
                    requestId,
                    BluetoothGatt.GATT_SUCCESS,
                    0,
                    null
                )
            }
            if (connectedDevice == null) {
                connectedDevice = device
            }
            callback.onCharacteristicWriteRequest(
                uuid = UUID.fromJavaUuid(characteristic.uuid),
                value = value,
                maxWriteSizeForCentral = null
            )
        }

        override fun onDescriptorWriteRequest(
            device: BluetoothDevice,
            requestId: Int,
            descriptor: BluetoothGattDescriptor,
            preparedWrite: Boolean,
            responseNeeded: Boolean,
            offset: Int,
            value: ByteArray
        ) {
            if (Logger.isDebugEnabled) {
                Logger.d(
                    TAG,
                    "onDescriptorWriteRequest: ${device.address} ${descriptor.characteristic.uuid} " +
                            "$offset ${value.toHex()}"
                )
            }
            if (responseNeeded) {
                gattServer?.sendResponse(
                    device,
                    requestId,
                    BluetoothGatt.GATT_SUCCESS,
                    0,
                    null
                )
            }
        }

        override fun onMtuChanged(device: BluetoothDevice, mtu: Int) {
            Logger.d(TAG, "Negotiated MTU $mtu for ${device.address}")
            callback.onMtuChanged(mtu)
        }

        override fun onNotificationSent(device: BluetoothDevice, status: Int) {
            Logger.d(TAG, "onNotificationSent $status for ${device.address}")
            if (status != BluetoothGatt.GATT_SUCCESS) {
                callback.onNotificationSent(
                    UUID(0UL, 0UL),
                    IllegalStateException("onNotificationSent: Expected GATT_SUCCESS but got $status")
                )
            } else {
                callback.onNotificationSent(UUID(0UL, 0UL), null)
            }
        }
    }

    private val advertiseCallback: AdvertiseCallback = object : AdvertiseCallback() {
        override fun onStartSuccess(settingsInEffect: AdvertiseSettings) {
            callback.onAdvertisingStarted(null)
        }

        override fun onStartFailure(errorCode: Int) {
            callback.onAdvertisingStarted(IllegalStateException("Started advertising failed with $errorCode"))
        }
    }

    override fun waitForPowerOn() {
        callback.onPowerOn(null)
    }

    override fun publishL2capChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            try {
                val serverSocket = bluetoothManager.adapter.listenUsingInsecureL2capChannel()
                l2capServerSocket = serverSocket
                val psm = serverSocket.psm
                CoroutineScope(Dispatchers.IO).launch {
                    l2capAcceptLoop(serverSocket)
                }
                callback.onL2capPublished(psm, null)
            } catch (e: Exception) {
                callback.onL2capPublished(null, e)
            }
        } else {
            Logger.w(TAG, "L2CAP only supported on API 29 or later")
            callback.onL2capPublished(null, null)
        }
    }

    private fun l2capAcceptLoop(serverSocket: BluetoothServerSocket) {
        try {
            val socket = serverSocket.accept()
            l2capSocket = socket
            connectedDevice = socket.remoteDevice
            try {
                serverSocket.close()
            } catch (_: Exception) {
            }
            l2capServerSocket = null
            callback.onL2capClientConnected(BluetoothSocketL2capChannel(socket))
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            if (l2capServerClosedIntentionally) {
                Logger.d(TAG, "Ignoring L2CAP accept error after intentional close", e)
            } else {
                callback.onError(IllegalStateException("Accepting L2CAP socket failed", e))
            }
        }
    }

    override fun closeL2capServer() {
        l2capServerClosedIntentionally = true
        try {
            l2capServerSocket?.close()
        } catch (_: Exception) {
        }
        l2capServerSocket = null
    }

    private fun addCharacteristic(
        characteristicUuid: UUID,
        properties: Int,
        permissions: Int,
    ): BluetoothGattCharacteristic {
        val characteristic = BluetoothGattCharacteristic(
            characteristicUuid.toJavaUuid(),
            properties,
            permissions
        )
        if (properties and BluetoothGattCharacteristic.PROPERTY_NOTIFY != 0) {
            val descriptor = BluetoothGattDescriptor(
                CLIENT_CHARACTERISTIC_CONFIG_UUID.toJavaUuid(),
                BluetoothGattDescriptor.PERMISSION_WRITE
            )
            @Suppress("DEPRECATION")
            descriptor.setValue(BluetoothGattDescriptor.DISABLE_NOTIFICATION_VALUE)
            characteristic.addDescriptor(descriptor)
        }
        service!!.addCharacteristic(characteristic)
        characteristicsByUuid[characteristicUuid] = characteristic
        return characteristic
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
        // Defensively close any previous BluetoothGattServer instance before opening a new one
        // so we never leak a serverIf registration in Android's Bluetooth daemon.
        gattServer?.let { prevServer ->
            try {
                service?.let { prevServer.removeService(it) }
            } catch (_: Exception) {
            }
            try {
                prevServer.close()
            } catch (_: Exception) {
            }
        }
        gattServer = bluetoothManager.openGattServer(applicationContext, gattServerCallback)
        service = BluetoothGattService(
            serviceUuid.toJavaUuid(),
            BluetoothGattService.SERVICE_TYPE_PRIMARY
        )
        characteristicsByUuid.clear()

        addCharacteristic(
            characteristicUuid = stateCharacteristicUuid,
            properties = BluetoothGattCharacteristic.PROPERTY_NOTIFY +
                    BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE,
            permissions = BluetoothGattCharacteristic.PERMISSION_WRITE
        )
        addCharacteristic(
            characteristicUuid = client2ServerCharacteristicUuid,
            properties = BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE,
            permissions = BluetoothGattCharacteristic.PERMISSION_WRITE
        )
        addCharacteristic(
            characteristicUuid = server2ClientCharacteristicUuid,
            properties = BluetoothGattCharacteristic.PROPERTY_NOTIFY,
            permissions = BluetoothGattCharacteristic.PERMISSION_WRITE
        )
        if (identCharacteristicUuid != null) {
            addCharacteristic(
                characteristicUuid = identCharacteristicUuid,
                properties = BluetoothGattCharacteristic.PROPERTY_READ,
                permissions = BluetoothGattCharacteristic.PERMISSION_READ
            )
        }
        if (l2capCharacteristicUuid != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            addCharacteristic(
                characteristicUuid = l2capCharacteristicUuid,
                properties = BluetoothGattCharacteristic.PROPERTY_READ,
                permissions = BluetoothGattCharacteristic.PERMISSION_READ
            )
        }
        gattServer!!.addService(service!!)
    }

    override fun startAdvertising(serviceUuid: UUID) {
        advertiser = bluetoothManager.adapter.bluetoothLeAdvertiser
        if (advertiser == null) {
            callback.onAdvertisingStarted(IllegalStateException("Advertiser not available, is Bluetooth off?"))
            return
        }
        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
            .setConnectable(true)
            .setTimeout(0)
            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_MEDIUM)
            .build()
        val data = AdvertiseData.Builder()
            .setIncludeTxPowerLevel(false)
            .addServiceUuid(ParcelUuid(serviceUuid.toJavaUuid()))
            .build()
        Logger.d(TAG, "Started advertising UUID $serviceUuid")
        advertiser!!.startAdvertising(settings, data, advertiseCallback)
    }

    override fun stopAdvertising() {
        advertiser?.stopAdvertising(advertiseCallback)
    }

    override fun sendReadResponse(requestId: Int, statusSuccess: Boolean, value: ByteArray) {
        val device = pendingReadDevices.remove(requestId) ?: connectedDevice ?: return
        gattServer?.sendResponse(
            device,
            requestId,
            if (statusSuccess) BluetoothGatt.GATT_SUCCESS else BluetoothGatt.GATT_FAILURE,
            0,
            value
        )
    }

    override fun notifyCharacteristicChanged(uuid: UUID, value: ByteArray) {
        val gServer = checkNotNull(gattServer) { "gattServer is null" }
        val device = checkNotNull(connectedDevice) { "connectedDevice is null" }
        val characteristic = checkNotNull(characteristicsByUuid[uuid]) { "Characteristic $uuid not found" }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            val rc = gServer.notifyCharacteristicChanged(device, characteristic, false, value)
            if (rc != BluetoothStatusCodes.SUCCESS) {
                callback.onNotificationSent(
                    uuid,
                    IllegalStateException("Error notifyCharacteristicChanged on characteristic $uuid rc=$rc")
                )
            }
        } else {
            @Suppress("DEPRECATION")
            characteristic.setValue(value)
            @Suppress("DEPRECATION")
            if (!gServer.notifyCharacteristicChanged(device, characteristic, false)) {
                callback.onNotificationSent(
                    uuid,
                    IllegalStateException("Error notifyCharacteristicChanged on characteristic $uuid")
                )
            }
        }
    }

    override fun close() {
        Logger.d(TAG, "close()")
        connectedDevice = null
        pendingReadDevices.clear()
        advertiser?.stopAdvertising(advertiseCallback)
        advertiser = null
        gattServer?.removeService(service)
        gattServer?.close()
        gattServer = null
        service = null
        l2capServerClosedIntentionally = true
        try {
            l2capServerSocket?.close()
        } catch (_: Exception) {
        }
        l2capServerSocket = null
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
