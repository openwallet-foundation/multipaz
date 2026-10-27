/*
 * Copyright 2022 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.android.identity.android.mdoc.transport

import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.bluetooth.le.BluetoothLeAdvertiser
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.ParcelUuid
import org.multipaz.mdoc.connectionmethod.MdocConnectionMethodBle
import org.multipaz.util.Logger
import org.multipaz.util.toJavaUuid
import java.util.UUID

/**
 * BLE data transport implementation conforming to ISO 18013-5 mdoc
 * central client mode.
 */
class DataTransportBleCentralClientMode(
    context: Context,
    role: Role,
    connectionMethod: MdocConnectionMethodBle,
    options: DataTransportOptions
) : DataTransportBle(context, role, connectionMethod, options) {
    private var characteristicStateUuid = UUID.fromString("00000005-a123-48ce-896b-4c76973373e6")
    private var characteristicClient2ServerUuid = UUID.fromString("00000006-a123-48ce-896b-4c76973373e6")
    private var characteristicServer2ClientUuid = UUID.fromString("00000007-a123-48ce-896b-4c76973373e6")
    private var characteristicIdentUuid = UUID.fromString("00000008-a123-48ce-896b-4c76973373e6")

    /**
     * In _mdoc central client mode_ the _mdoc_ acts as the GATT client and the _mdoc reader_ acts as the
     * GATT server. According to ISO 18013-5 Table A.1 this means that in _mdoc central client mode_
     * the GATT server (for the _mdoc reader_) should advertise
     * UUID 0000000B-A123-48CE896B-4C76973373E6 and the GATT client (for the _mdoc_) should
     * connect to that UUID.
     */
    private var characteristicL2CAPUuidMdocReader = UUID.fromString("0000000b-a123-48ce-896b-4c76973373e6")
    private var bluetoothManager: BluetoothManager? = null
    private var bluetoothLeAdvertiser: BluetoothLeAdvertiser? = null
    private var gattClient: GattClient? = null
    private var scanner: BluetoothLeScanner? = null
    private var encodedEDeviceKeyBytes: ByteArray? = null
    private var timeScanningStartedMillis: Long = 0

    // a flag to prevent multiple GattClient connects which cause to multiple
    // new GattClient instances and to crashes
    // https://stackoverflow.com/a/38276808/4940838
    private var isConnecting = false

    private var scanCallback: ScanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            Logger.d(TAG, "onScanCallback: callbackType=$callbackType result=$result")
            // if we already scanned and connect to device we don't want to
            // reconnect to another GattClient instance.
            if (isConnecting) {
                return
            }
            isConnecting = true
            val device = result.device
            scanningTimeMillis = System.currentTimeMillis() - timeScanningStartedMillis
            Logger.i(TAG, "Scanned for $scanningTimeMillis milliseconds. "
                        + "Connecting to device with address ${device.address}")
            if (scanner != null) {
                Logger.d(TAG, "Stopped scanning for UUID $serviceUuid")
                try {
                    scanner!!.stopScan(this)
                } catch (e: SecurityException) {
                    reportError(e)
                    return
                }
                scanner = null
            }
            connectToDevice(device)
        }

        override fun onBatchScanResults(results: List<ScanResult>) {
            Logger.w(TAG, "Ignoring unexpected onBatchScanResults")
        }

        override fun onScanFailed(errorCode: Int) {
            reportError(Error("BLE scan failed with error code $errorCode"))
        }
    }

    /**
     * Callback to receive information about the advertisement process.
     */
    private var advertiseCallback: AdvertiseCallback = object : AdvertiseCallback() {
        override fun onStartSuccess(settingsInEffect: AdvertiseSettings) {}
        override fun onStartFailure(errorCode: Int) {
            reportError(Error("BLE advertise failed with error code $errorCode"))
        }
    }

    private var gattServer: GattServer? = null

    private fun connectToDevice(device: BluetoothDevice) {
        reportConnecting()
        connectGatt(device)
    }

    private fun connectGatt(device: BluetoothDevice) {
        var characteristicL2CAPUuid: UUID? = null
        if (options.bleUseL2CAP) {
            characteristicL2CAPUuid = characteristicL2CAPUuidMdocReader
        }
        gattClient = GattClient(
            context,
            serviceUuid!!.toJavaUuid(),
            encodedEDeviceKeyBytes,
            characteristicStateUuid, characteristicClient2ServerUuid,
            characteristicServer2ClientUuid, characteristicIdentUuid,
            characteristicL2CAPUuid
        )
        gattClient!!.listener = object : GattClient.Listener {
            override fun onPeerConnected() {
                reportConnected()
            }

            override fun onPeerDisconnected() {
                if (gattClient != null) {
                    gattClient!!.listener = null
                    gattClient!!.disconnect()
                    gattClient = null
                }
                reportDisconnected()
            }

            override fun onMessageReceived(data: ByteArray) {
                reportMessageReceived(data)
            }

            override fun onTransportSpecificSessionTermination() {
                reportTransportSpecificSessionTermination()
            }

            override fun onError(error: Throwable) {
                reportError(error)
            }
        }
        gattClient!!.clearCache = options.bleClearCache
        gattClient!!.connect(device)
    }

    override fun setEDeviceKeyBytes(encodedEDeviceKeyBytes: ByteArray) {
        this.encodedEDeviceKeyBytes = encodedEDeviceKeyBytes
        gattServer?.setEDeviceKeyBytes(encodedEDeviceKeyBytes)
    }

    // TODO: Check if BLE is enabled and error out if not so...
    private fun connectAsMdoc() {
        bluetoothManager = context.getSystemService(BluetoothManager::class.java)
        val bluetoothAdapter = bluetoothManager!!.getAdapter()
        val macAddress = connectionMethod.peripheralServerModeMacAddress
        if (macAddress != null) {
            Logger.i(TAG, "MAC address provided, no scanning needed")
            val device = bluetoothAdapter.getRemoteDevice(macAddress.toByteArray())
            connectToDevice(device)
            return
        }

        // Start scanning...
        val filter = ScanFilter.Builder()
            .setServiceUuid(ParcelUuid(serviceUuid!!.toJavaUuid()))
            .build()
        val settings = ScanSettings.Builder()
            .setCallbackType(ScanSettings.CALLBACK_TYPE_ALL_MATCHES)
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()
        Logger.d(TAG, "Started scanning for UUID $serviceUuid")
        timeScanningStartedMillis = System.currentTimeMillis()
        scanner = bluetoothAdapter.bluetoothLeScanner
        val filterList: MutableList<ScanFilter> = ArrayList()
        filterList.add(filter)
        try {
            scanner!!.startScan(filterList, settings, scanCallback)
        } catch (e: SecurityException) {
            reportError(e)
        }
    }

    private fun connectAsMdocReader() {
        val bluetoothManager = context.getSystemService(
            BluetoothManager::class.java
        )
        var characteristicL2CAPUuid: UUID? = null
        if (options.bleUseL2CAP) {
            characteristicL2CAPUuid = characteristicL2CAPUuidMdocReader
        }
        gattServer = GattServer(
            context, bluetoothManager, serviceUuid!!.toJavaUuid(),
            encodedEDeviceKeyBytes,
            characteristicStateUuid, characteristicClient2ServerUuid,
            characteristicServer2ClientUuid, characteristicIdentUuid,
            characteristicL2CAPUuid
        )
        gattServer!!.listener = object : GattServer.Listener {
            override fun onPeerConnected() {
                Logger.i(TAG, "onPeerConnected")
                reportConnected()
                // No need to advertise anymore since we now have a client...
                if (bluetoothLeAdvertiser != null) {
                    Logger.d(TAG, "Stopping advertising UUID $serviceUuid")
                    try {
                        bluetoothLeAdvertiser!!.stopAdvertising(advertiseCallback)
                    } catch (e: SecurityException) {
                        reportError(e)
                        return
                    }
                    bluetoothLeAdvertiser = null
                }
            }

            override fun onPeerDisconnected() {
                reportDisconnected()
            }

            override fun onTransportSpecificSessionTermination() {
                Logger.d(TAG, "onTransportSpecificSessionTermination")
                reportTransportSpecificSessionTermination()
            }

            override fun onMessageReceived(data: ByteArray) {
                reportMessageReceived(data)
            }

            override fun onError(error: Throwable) {
                reportError(error)
            }
        }
        if (!gattServer!!.start()) {
            reportError(Error("Error starting Gatt Server"))
            gattServer!!.stop()
            gattServer = null
            return
        }
        val bluetoothAdapter = bluetoothManager.adapter
        bluetoothLeAdvertiser = bluetoothAdapter.bluetoothLeAdvertiser
        if (bluetoothLeAdvertiser == null) {
            reportError(Error("Failed to create BLE advertiser"))
            gattServer!!.stop()
            gattServer = null
        } else {
            val settings = AdvertiseSettings.Builder()
                .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
                .setConnectable(true)
                .setTimeout(0)
                .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_MEDIUM)
                .build()
            val data = AdvertiseData.Builder()
                .setIncludeTxPowerLevel(false)
                .addServiceUuid(ParcelUuid(serviceUuid!!.toJavaUuid()))
                .build()
            Logger.d(TAG, "Started advertising UUID $serviceUuid")
            try {
                bluetoothLeAdvertiser!!.startAdvertising(settings, data, advertiseCallback)
            } catch (e: SecurityException) {
                reportError(e)
            }
        }
    }

    override fun connect() {
        // TODO: Check if BLE is enabled and error out if not so...
        if (role === Role.MDOC) {
            connectAsMdoc()
        } else {
            connectAsMdocReader()
        }
    }

    override fun close() {
        Logger.i(TAG, "close")
        isConnecting = false
        inhibitCallbacks()
        if (bluetoothLeAdvertiser != null) {
            Logger.d(TAG, "Stopping advertising UUID $serviceUuid")
            try {
                bluetoothLeAdvertiser!!.stopAdvertising(advertiseCallback)
            } catch (e: SecurityException) {
                Logger.e(TAG, "Caught SecurityException while shutting down", e)
            }
            bluetoothLeAdvertiser = null
        }
        if (gattServer != null) {
            gattServer!!.listener = null
            gattServer!!.stop()
            gattServer = null
        }
        if (scanner != null) {
            Logger.d(TAG, "Stopped scanning for UUID $serviceUuid")
            try {
                scanner!!.stopScan(scanCallback)
            } catch (e: SecurityException) {
                Logger.e(TAG, "Caught SecurityException while shutting down", e)
            }
            scanner = null
        }
        if (gattClient != null) {
            gattClient!!.listener = null
            gattClient!!.disconnect()
            gattClient = null
        }
    }

    override fun sendMessage(data: ByteArray) {
        require(data.size != 0) { "Data to send cannot be empty" }
        if (gattServer != null) {
            gattServer!!.sendMessage(data)
        } else if (gattClient != null) {
            gattClient!!.sendMessage(data)
        }
    }

    override fun sendTransportSpecificTerminationMessage() {
        if (gattServer == null) {
            if (gattClient == null) {
                reportError(Error("Transport-specific termination not available"))
                return
            }
            gattClient!!.sendTransportSpecificTermination()
            return
        }
        gattServer!!.sendTransportSpecificTermination()
    }

    override fun supportsTransportSpecificTerminationMessage(): Boolean {
        if (gattServer != null) {
            return gattServer!!.supportsTransportSpecificTerminationMessage()
        } else if (gattClient != null) {
            return gattClient!!.supportsTransportSpecificTerminationMessage()
        }
        return false
    }

    companion object {
        private const val TAG = "DataTransportBleCCM" // limit to <= 23 chars
    }
}
