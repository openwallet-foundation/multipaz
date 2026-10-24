package org.multipaz.mdoc.transport

import kotlinx.cinterop.ObjCSignatureOverride
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.multipaz.util.Logger
import org.multipaz.util.UUID
import org.multipaz.util.toByteArray
import org.multipaz.util.toKotlinError
import org.multipaz.util.toNSData
import platform.CoreBluetooth.CBATTErrorRequestNotSupported
import platform.CoreBluetooth.CBATTErrorSuccess
import platform.CoreBluetooth.CBATTRequest
import platform.CoreBluetooth.CBAdvertisementDataServiceUUIDsKey
import platform.CoreBluetooth.CBAttributePermissionsReadable
import platform.CoreBluetooth.CBAttributePermissionsWriteable
import platform.CoreBluetooth.CBCentral
import platform.CoreBluetooth.CBCharacteristic
import platform.CoreBluetooth.CBCharacteristicPropertyNotify
import platform.CoreBluetooth.CBCharacteristicPropertyRead
import platform.CoreBluetooth.CBCharacteristicPropertyWriteWithoutResponse
import platform.CoreBluetooth.CBL2CAPChannel
import platform.CoreBluetooth.CBL2CAPPSM
import platform.CoreBluetooth.CBMutableCharacteristic
import platform.CoreBluetooth.CBMutableService
import platform.CoreBluetooth.CBPeripheralManager
import platform.CoreBluetooth.CBPeripheralManagerDelegateProtocol
import platform.CoreBluetooth.CBPeripheralManagerStatePoweredOn
import platform.CoreBluetooth.CBUUID
import platform.Foundation.NSArray
import platform.Foundation.NSError
import platform.darwin.NSObject

internal fun createBlePeripheralManagerIos(): BlePeripheralManager =
    BlePeripheralManagerImpl(
        hal = BlePeripheralHalIos(),
    )

internal class BlePeripheralHalIos : BlePeripheralHal {
    companion object {
        private const val TAG = "BlePeripheralHalIos"
    }

    private lateinit var callback: BlePeripheralHal.Callback
    private val peripheralManager: CBPeripheralManager
    private var service: CBMutableService? = null
    private val characteristicsByUuid = mutableMapOf<UUID, CBMutableCharacteristic>()
    private var stateCharacteristic: CBMutableCharacteristic? = null
    private var server2ClientCharacteristic: CBMutableCharacteristic? = null
    private var connectedCentral: CBCentral? = null
    private var nextReadRequestId = 1
    private val pendingReadRequests = mutableMapOf<Int, CBATTRequest>()
    private var pendingNotifyPair: Pair<UUID, ByteArray>? = null
    private var publishedL2capPsm: Int? = null
    private var activeL2capChannel: CoreBluetoothL2capChannel? = null

    private val peripheralManagerDelegate = object : NSObject(), CBPeripheralManagerDelegateProtocol {
        override fun peripheralManagerDidUpdateState(peripheral: CBPeripheralManager) {
            if (peripheralManager.state == CBPeripheralManagerStatePoweredOn) {
                callback.onPowerOn(null)
            } else {
                callback.onPowerOn(IllegalStateException("Expected poweredOn, got ${peripheralManager.state}"))
            }
        }

        override fun peripheralManager(
            peripheral: CBPeripheralManager,
            didReceiveWriteRequests: List<*>
        ) {
            for (attRequest in didReceiveWriteRequests) {
                attRequest as CBATTRequest
                val charUuid = UUID.fromString(attRequest.characteristic.UUID.UUIDString)
                val data = attRequest.value?.toByteArray() ?: byteArrayOf()
                if (attRequest.characteristic == stateCharacteristic && connectedCentral == null) {
                    connectedCentral = attRequest.central
                }
                val maxWriteLen = attRequest.central.maximumUpdateValueLength.toInt()
                callback.onCharacteristicWriteRequest(
                    uuid = charUuid,
                    value = data,
                    maxWriteSizeForCentral = maxWriteLen
                )
            }
        }

        override fun peripheralManager(
            peripheral: CBPeripheralManager,
            didReceiveReadRequest: CBATTRequest
        ) {
            val requestId = nextReadRequestId++
            pendingReadRequests[requestId] = didReceiveReadRequest
            val charUuid = UUID.fromString(didReceiveReadRequest.characteristic.UUID.UUIDString)
            callback.onCharacteristicReadRequest(requestId, charUuid)
        }

        @ObjCSignatureOverride
        override fun peripheralManager(
            peripheral: CBPeripheralManager,
            central: CBCentral,
            didUnsubscribeFromCharacteristic: CBCharacteristic
        ) {
            if (central.identifier != connectedCentral?.identifier) {
                return
            }
            if (didUnsubscribeFromCharacteristic != stateCharacteristic &&
                didUnsubscribeFromCharacteristic != server2ClientCharacteristic
            ) {
                return
            }
            Logger.i(
                TAG,
                "Central ${central.identifier.UUIDString} unsubscribed from " +
                        didUnsubscribeFromCharacteristic.UUID.UUIDString
            )
            callback.onCentralDisconnected()
        }

        override fun peripheralManagerIsReadyToUpdateSubscribers(peripheral: CBPeripheralManager) {
            val pending = pendingNotifyPair ?: return
            pendingNotifyPair = null
            notifyCharacteristicChanged(pending.first, pending.second)
        }

        override fun peripheralManager(
            peripheral: CBPeripheralManager,
            didPublishL2CAPChannel: CBL2CAPPSM,
            error: NSError?
        ) {
            Logger.i(TAG, "peripheralManager didPublishL2CAPChannel")
            if (error != null) {
                callback.onL2capPublished(
                    null,
                    IllegalStateException("peripheralManager didPublishL2CAPChannel failed", error.toKotlinError())
                )
            } else {
                val psm = didPublishL2CAPChannel.toInt()
                publishedL2capPsm = psm
                callback.onL2capPublished(psm, null)
            }
        }

        override fun peripheralManager(
            peripheral: CBPeripheralManager,
            didOpenL2CAPChannel: CBL2CAPChannel?,
            error: NSError?
        ) {
            Logger.i(TAG, "peripheralManager didOpenL2CAPChannel")
            if (error != null || didOpenL2CAPChannel == null) {
                Logger.w(TAG, "peripheralManager didOpenL2CAPChannel error=$error")
            } else {
                val channel = CoreBluetoothL2capChannel(didOpenL2CAPChannel)
                activeL2capChannel = channel
                callback.onL2capClientConnected(channel)
            }
        }
    }

    init {
        peripheralManager = CBPeripheralManager(
            delegate = peripheralManagerDelegate,
            queue = null,
            options = null
        )
    }

    override fun setCallback(callback: BlePeripheralHal.Callback) {
        this.callback = callback
    }

    override fun waitForPowerOn() {
        if (peripheralManager.state == CBPeripheralManagerStatePoweredOn) {
            callback.onPowerOn(null)
        }
    }

    override fun publishL2capChannel() {
        peripheralManager.publishL2CAPChannelWithEncryption(false)
    }

    override fun closeL2capServer() {
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
        service = CBMutableService(
            type = CBUUID.UUIDWithString(serviceUuid.toString()),
            primary = true
        )
        characteristicsByUuid.clear()

        val stateChar = CBMutableCharacteristic(
            type = CBUUID.UUIDWithString(stateCharacteristicUuid.toString()),
            properties = CBCharacteristicPropertyNotify + CBCharacteristicPropertyWriteWithoutResponse,
            value = null,
            permissions = CBAttributePermissionsWriteable,
        )
        stateCharacteristic = stateChar
        characteristicsByUuid[stateCharacteristicUuid] = stateChar

        val c2sChar = CBMutableCharacteristic(
            type = CBUUID.UUIDWithString(client2ServerCharacteristicUuid.toString()),
            properties = CBCharacteristicPropertyWriteWithoutResponse,
            value = null,
            permissions = CBAttributePermissionsWriteable,
        )
        characteristicsByUuid[client2ServerCharacteristicUuid] = c2sChar

        val s2cChar = CBMutableCharacteristic(
            type = CBUUID.UUIDWithString(server2ClientCharacteristicUuid.toString()),
            properties = CBCharacteristicPropertyNotify,
            value = null,
            permissions = CBAttributePermissionsReadable + CBAttributePermissionsWriteable,
        )
        server2ClientCharacteristic = s2cChar
        characteristicsByUuid[server2ClientCharacteristicUuid] = s2cChar

        val charList = mutableListOf<CBMutableCharacteristic>(stateChar, c2sChar, s2cChar)

        if (identCharacteristicUuid != null) {
            val identChar = CBMutableCharacteristic(
                type = CBUUID.UUIDWithString(identCharacteristicUuid.toString()),
                properties = CBCharacteristicPropertyRead,
                value = null,
                permissions = CBAttributePermissionsReadable,
            )
            characteristicsByUuid[identCharacteristicUuid] = identChar
            charList.add(identChar)
        }

        if (l2capCharacteristicUuid != null && encodedL2capPsm != null) {
            val l2capChar = CBMutableCharacteristic(
                type = CBUUID.UUIDWithString(l2capCharacteristicUuid.toString()),
                properties = CBCharacteristicPropertyRead,
                value = encodedL2capPsm.toNSData(),
                permissions = CBAttributePermissionsReadable,
            )
            characteristicsByUuid[l2capCharacteristicUuid] = l2capChar
            charList.add(l2capChar)
        }

        service!!.setCharacteristics((service!!.characteristics ?: emptyList<CBMutableCharacteristic>()) + charList)
        peripheralManager.addService(service!!)
        callback.onServiceAdded(null)
    }

    override fun startAdvertising(serviceUuid: UUID) {
        peripheralManager.startAdvertising(
            mapOf(
                CBAdvertisementDataServiceUUIDsKey to
                        (listOf(CBUUID.UUIDWithString(serviceUuid.toString())) as NSArray)
            )
        )
        callback.onAdvertisingStarted(null)
    }

    override fun stopAdvertising() {
        peripheralManager.stopAdvertising()
    }

    override fun sendReadResponse(requestId: Int, statusSuccess: Boolean, value: ByteArray) {
        val attRequest = pendingReadRequests.remove(requestId) ?: return
        if (statusSuccess) {
            attRequest.value = value.toNSData()
            peripheralManager.respondToRequest(attRequest, CBATTErrorSuccess)
        } else {
            peripheralManager.respondToRequest(attRequest, CBATTErrorRequestNotSupported)
        }
    }

    override fun notifyCharacteristicChanged(uuid: UUID, value: ByteArray) {
        val characteristic = checkNotNull(characteristicsByUuid[uuid]) { "Characteristic $uuid not found" }
        CoroutineScope(Dispatchers.Main).launch {
            val wasSent = peripheralManager.updateValue(
                value = value.toNSData(),
                forCharacteristic = characteristic,
                onSubscribedCentrals = null
            )
            if (wasSent) {
                Logger.i(TAG, "Wrote to characteristic ${characteristic.UUID}")
                callback.onNotificationSent(uuid, null)
            } else {
                Logger.i(TAG, "Not ready to send to characteristic ${characteristic.UUID}, waiting")
                pendingNotifyPair = Pair(uuid, value)
            }
        }
    }

    override fun close() {
        connectedCentral = null
        pendingReadRequests.clear()
        pendingNotifyPair = null
        publishedL2capPsm?.let {
            peripheralManager.unpublishL2CAPChannel(it.toUShort())
        }
        publishedL2capPsm = null
        peripheralManager.stopAdvertising()
        peripheralManager.removeAllServices()
        service = null
        peripheralManager.delegate = null
        activeL2capChannel?.close()
        activeL2capChannel = null
    }
}
