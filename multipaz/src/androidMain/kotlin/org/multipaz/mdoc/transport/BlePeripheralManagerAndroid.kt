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
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.multipaz.context.applicationContext
import org.multipaz.util.Logger
import org.multipaz.util.UUID
import org.multipaz.util.fromJavaUuid
import org.multipaz.util.toHex
import org.multipaz.util.toJavaUuid
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

internal fun createBlePeripheralManagerAndroid(): BlePeripheralManager =
    BlePeripheralManagerImpl(
        hal = BlePeripheralHalAndroid(),
    )

/**
 * Coordinates `BluetoothGattServer.addService()` and `BluetoothGattServer.close()` to work around
 * an AOSP bug in `com.android.bluetooth.gatt.GattServerManager` / `HandleMap`.
 *
 * ### Background on the AOSP `GattServerManager` / `HandleMap` Bug
 * In AOSP's Bluetooth stack:
 * 1. `btif_gatts_add_service` hops across four thread boundaries (`GattThread -> jni_thread ->
 *    main_thread -> jni_thread -> GattThread`) before `GattServerManager.onServiceAddedFromNative()`
 *    runs on `GattThread`.
 * 2. `GattServerManager.unregisterServer()` (invoked by `BluetoothGattServer.close()`) runs
 *    `deleteServices(serverIf)` and `serverMap.remove(serverIf)` synchronously on `GattThread`,
 *    and hops directly `GattThread -> main_thread` (`BTA_GATTS_AppDeregister`).
 * 3. `GattServerManager.onServiceAddedFromNative()` inserts all service, characteristic, and
 *    descriptor handles into `handleMap.entries` **before** checking
 *    `val app = serverMap.getById(serverIf) ?: return`.
 *
 * Consequently, if `BluetoothGattServer.close()` is called while `addService()` is still in flight
 * (before `BluetoothGattServerCallback.onServiceAdded()` fires), `deleteServices(serverIf)` sees no
 * handles in `handleMap` yet, `serverMap.remove(serverIf)` removes the server registration, and
 * `onServiceAddedFromNative()` arrives milliseconds later and permanently orphans `(serverIf,
 * handle=176..186)` in `handleMap.entries`. Because C++ (`GATTS_AddService` in `gatt_api.cc`) frees
 * the handle range on deregistration and reassigns the exact same starting handle (`176`) to the
 * next `BluetoothGattServer`, and `HandleMap.getByHandle(handle)` uses
 * `entries.firstOrNull { it.handle == handle }`, the orphaned `serverIf` entry shadows all future
 * GATT servers on the device (`BtGatt.ContextMap: Context not found for ID=<serverIf>`) until
 * Bluetooth is toggled off and on.
 *
 * To prevent this, if [releaseActiveServer] is called while `addService()` is in flight, closing
 * that server is deferred until its [onServiceAdded] callback arrives (or [fallbackTimeout] elapses).
 */
internal class GattServerCloseCoordinator<S : Any>(
    private val closeServer: (S) -> Unit,
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.IO + SupervisorJob()),
    private val fallbackTimeout: Duration = 5.seconds,
) {
    companion object {
        private const val TAG = "GattServerCloseCoordinator"
    }

    private val lock = Any()
    private var activeServer: S? = null
    private var serviceAddInFlight = false
    private val deferredCloseJobs = mutableMapOf<S, Job>()
    private var mtuNegotiatedPeerAddress: String? = null
    private var gattOrL2capActivitySeenForConnection = false

    fun onAddServiceStarted(server: S) {
        synchronized(lock) {
            activeServer = server
            serviceAddInFlight = true
        }
    }

    /**
     * Handles completion of `onServiceAdded` for [server].
     *
     * @return `true` if [server] is still the active GATT server and the caller should forward
     *   `onServiceAdded` to the transport callback; `false` if [server] was retired while
     *   `addService()` was in flight and has now been closed.
     */
    fun onServiceAdded(server: S, status: Int): Boolean {
        val deferredJobToCancel: Job?
        val shouldCloseNow: Boolean
        val isActive: Boolean
        synchronized(lock) {
            val job = deferredCloseJobs.remove(server)
            if (job != null) {
                deferredJobToCancel = job
                shouldCloseNow = true
                isActive = false
            } else if (activeServer === server) {
                serviceAddInFlight = false
                deferredJobToCancel = null
                shouldCloseNow = false
                isActive = true
            } else {
                deferredJobToCancel = null
                shouldCloseNow = false
                isActive = false
            }
        }
        deferredJobToCancel?.cancel()
        if (shouldCloseNow) {
            Logger.w(
                TAG,
                "AOSP bug workaround: onServiceAdded(status=$status) completed for deferred-close " +
                    "BluetoothGattServer; closing gattServer now"
            )
            try {
                closeServer(server)
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                Logger.w(TAG, "Error closing deferred BluetoothGattServer", e)
            }
        }
        return isActive
    }

    /**
     * Releases the current active server. If `addService()` is still in flight, defers closing
     * until [onServiceAdded] fires (or [fallbackTimeout] elapses).
     */
    fun releaseActiveServer() {
        val serverToCloseImmediately: S?
        val serverToDefer: S?
        synchronized(lock) {
            val server = activeServer ?: return
            activeServer = null
            mtuNegotiatedPeerAddress = null
            gattOrL2capActivitySeenForConnection = false
            if (serviceAddInFlight) {
                serviceAddInFlight = false
                serverToCloseImmediately = null
                serverToDefer = server
                val job = scope.launch {
                    delay(fallbackTimeout)
                    val timedOutServer = synchronized(lock) {
                        deferredCloseJobs.remove(server)
                    }
                    if (timedOutServer != null) {
                        Logger.w(
                            TAG,
                            "AOSP bug workaround: timed out waiting $fallbackTimeout for " +
                                "onServiceAdded() on deferred-close BluetoothGattServer; closing now"
                        )
                        try {
                            closeServer(server)
                        } catch (e: Exception) {
                            if (e is CancellationException) throw e
                            Logger.w(TAG, "Error closing timed-out deferred BluetoothGattServer", e)
                        }
                    }
                }
                deferredCloseJobs[server] = job
            } else {
                serverToCloseImmediately = server
                serverToDefer = null
            }
        }
        if (serverToDefer != null) {
            Logger.w(
                TAG,
                "AOSP bug workaround: close() called while BluetoothGattServer.addService() is " +
                    "still in flight; deferring gattServer.close() until onServiceAdded() " +
                    "completes to avoid poisoning Android GattServerManager HandleMap"
            )
        }
        if (serverToCloseImmediately != null) {
            try {
                closeServer(serverToCloseImmediately)
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                Logger.w(TAG, "Error closing BluetoothGattServer", e)
            }
        }
    }

    fun onMtuChanged(peerAddress: String) {
        synchronized(lock) {
            if (mtuNegotiatedPeerAddress != peerAddress) {
                gattOrL2capActivitySeenForConnection = false
            }
            mtuNegotiatedPeerAddress = peerAddress
        }
    }

    fun onGattOrL2capActivity() {
        synchronized(lock) {
            gattOrL2capActivitySeenForConnection = true
        }
    }

    /**
     * Checks whether a disconnecting peer had negotiated MTU on this server without any GATT
     * attribute read/write requests or L2CAP connections ever reaching the app callback, which is
     * the signature symptom of an already-poisoned `HandleMap` in AOSP's `GattServerManager`.
     *
     * @return `true` if the AOSP `HandleMap` shadowing symptom was detected and logged.
     */
    fun onPeerDisconnected(peerAddress: String): Boolean {
        val suspectedHandleMapPoisoning = synchronized(lock) {
            val noActivityAfterMtu = mtuNegotiatedPeerAddress == peerAddress &&
                !gattOrL2capActivitySeenForConnection
            if (mtuNegotiatedPeerAddress == peerAddress) {
                mtuNegotiatedPeerAddress = null
            }
            gattOrL2capActivitySeenForConnection = false
            noActivityAfterMtu
        }
        if (suspectedHandleMapPoisoning) {
            Logger.w(
                TAG,
                "Central $peerAddress disconnected after negotiating MTU without any GATT " +
                    "characteristic/descriptor requests or L2CAP connection reaching " +
                    "BluetoothGattServerCallback. This may be caused by an orphaned HandleMap " +
                    "entry in Android's GattServerManager (AOSP bug); cycling Bluetooth off " +
                    "and on clears the orphaned entry."
            )
        }
        return suspectedHandleMapPoisoning
    }
}

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
    private val gattServerCloseCoordinator = GattServerCloseCoordinator<BluetoothGattServer>(
        closeServer = { server -> server.close() }
    )
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

    private inner class PerServerGattCallback : BluetoothGattServerCallback() {
        @Volatile
        var server: BluetoothGattServer? = null

        override fun onServiceAdded(status: Int, service: BluetoothGattService?) {
            Logger.d(TAG, "onServiceAdded: $status")
            val srv = server
            if (srv != null && !gattServerCloseCoordinator.onServiceAdded(srv, status)) {
                return
            }
            if (status == BluetoothGatt.GATT_SUCCESS) {
                callback.onServiceAdded(null)
            } else {
                callback.onServiceAdded(IllegalStateException("onServiceAdded: Expected GATT_SUCCESS got $status"))
            }
        }

        override fun onConnectionStateChange(device: BluetoothDevice, status: Int, newState: Int) {
            Logger.d(TAG, "onConnectionStateChange: ${device.address} $status + $newState")
            val srv = server ?: return
            if (srv !== gattServer) return
            if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                gattServerCloseCoordinator.onPeerDisconnected(device.address)
                if (device.address == connectedDevice?.address) {
                    Logger.i(TAG, "Central ${device.address} disconnected")
                    callback.onCentralDisconnected()
                }
            }
        }

        override fun onCharacteristicReadRequest(
            device: BluetoothDevice,
            requestId: Int,
            offset: Int,
            characteristic: BluetoothGattCharacteristic
        ) {
            Logger.d(TAG, "onCharacteristicReadRequest: ${device.address} $requestId $offset ${characteristic.uuid}")
            val srv = server ?: return
            if (srv !== gattServer) return
            gattServerCloseCoordinator.onGattOrL2capActivity()
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
            val srv = server ?: return
            if (srv !== gattServer) return
            gattServerCloseCoordinator.onGattOrL2capActivity()
            if (responseNeeded) {
                srv.sendResponse(
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
            val srv = server ?: return
            if (srv !== gattServer) return
            gattServerCloseCoordinator.onGattOrL2capActivity()
            if (responseNeeded) {
                srv.sendResponse(
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
            val srv = server ?: return
            if (srv !== gattServer) return
            gattServerCloseCoordinator.onMtuChanged(device.address)
            callback.onMtuChanged(mtu)
        }

        override fun onNotificationSent(device: BluetoothDevice, status: Int) {
            Logger.d(TAG, "onNotificationSent $status for ${device.address}")
            val srv = server ?: return
            if (srv !== gattServer) return
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
            gattServerCloseCoordinator.onGattOrL2capActivity()
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
        // Defensively release any previous BluetoothGattServer instance before opening a new one
        // so we never leak a serverIf registration or orphan HandleMap entries in Android's
        // Bluetooth daemon.
        gattServerCloseCoordinator.releaseActiveServer()
        gattServer = null
        service = null

        val perServerCallback = PerServerGattCallback()
        val newServer = bluetoothManager.openGattServer(applicationContext, perServerCallback)
        if (newServer == null) {
            callback.onServiceAdded(
                IllegalStateException("BluetoothManager.openGattServer() returned null, is Bluetooth off?")
            )
            return
        }
        perServerCallback.server = newServer
        gattServer = newServer
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
        gattServerCloseCoordinator.onAddServiceStarted(newServer)
        if (!newServer.addService(service!!)) {
            gattServerCloseCoordinator.onServiceAdded(newServer, BluetoothGatt.GATT_FAILURE)
            gattServerCloseCoordinator.releaseActiveServer()
            gattServer = null
            service = null
            callback.onServiceAdded(
                IllegalStateException("BluetoothGattServer.addService() returned false")
            )
        }
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
        // Release the BluetoothGattServer via GattServerCloseCoordinator:
        // - If addService() has already completed, gattServer.close() is called immediately
        //   (GattServerManager.unregisterServer() already calls deleteServices(serverIf) for all
        //   services owned by serverIf, so calling removeService() first is redundant).
        // - If addService() is still in flight, gattServer.close() is deferred until
        //   onServiceAdded() arrives so we never orphan entries in GattServerManager's HandleMap.
        gattServerCloseCoordinator.releaseActiveServer()
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
