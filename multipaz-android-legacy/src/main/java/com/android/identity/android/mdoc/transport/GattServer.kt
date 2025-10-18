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

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattServer
import android.bluetooth.BluetoothGattServerCallback
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.os.Build
import org.multipaz.crypto.Algorithm
import org.multipaz.crypto.Hkdf
import org.multipaz.util.Logger
import org.multipaz.util.toHex
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.util.ArrayDeque
import java.util.Queue
import java.util.UUID

@Suppress("deprecation")
@SuppressLint("MissingPermission")
internal class GattServer(
    private val context: Context,
    private val bluetoothManager: BluetoothManager,
    private val serviceUuid: UUID,
    private val encodedEDeviceKeyBytes: ByteArray?,
    var characteristicStateUuid: UUID,
    var characteristicClient2ServerUuid: UUID,
    var characteristicServer2ClientUuid: UUID,
    var characteristicIdentUuid: UUID?,
    var characteristicL2CAPUuid: UUID?
) : BluetoothGattServerCallback() {
    var psm: Int? = null
        private set
    var listener: Listener? = null

    // This is what the 16-bit UUID 0x29 0x02 is encoded like.
    private var clientCharacteristicConfigUuid = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
    private var inhibitCallbacks = false
    private var characteristicState: BluetoothGattCharacteristic? = null
    private var characteristicClient2Server: BluetoothGattCharacteristic? = null
    private var characteristicServer2Client: BluetoothGattCharacteristic? = null
    private var characteristicIdent: BluetoothGattCharacteristic? = null
    private var characteristicL2CAP: BluetoothGattCharacteristic? = null
    private var incomingMessage = ByteArrayOutputStream()
    private var writingQueue: Queue<ByteArray> = ArrayDeque()
    private var gattServer: BluetoothGattServer? = null
    private var currentConnection: BluetoothDevice? = null
    private var negotiatedMtu = 0
    private var l2capServer: L2CAPServer? = null
    private var identValue: ByteArray? = null
    private var usingL2CAP = false

    fun setEDeviceKeyBytes(encodedEDeviceKeyBytes: ByteArray) {
        val ikm: ByteArray = encodedEDeviceKeyBytes
        val info = "BLEIdent".toByteArray()
        val salt = null
        identValue = Hkdf.deriveKey(Algorithm.HMAC_SHA256, ikm, salt, info, 16)
    }

    @SuppressLint("NewApi")
    fun start(): Boolean {
        if (encodedEDeviceKeyBytes != null) {
            setEDeviceKeyBytes(encodedEDeviceKeyBytes)
        }
        gattServer = try {
            bluetoothManager.openGattServer(context, this)
        } catch (e: SecurityException) {
            reportError(e)
            return false
        }
        if (gattServer == null) {
            return false
        }
        val service = BluetoothGattService(
            serviceUuid,
            BluetoothGattService.SERVICE_TYPE_PRIMARY
        )
        var c: BluetoothGattCharacteristic
        var d: BluetoothGattDescriptor

        // State
        c = BluetoothGattCharacteristic(
            characteristicStateUuid, BluetoothGattCharacteristic.PROPERTY_NOTIFY
                    or BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE,
            BluetoothGattCharacteristic.PERMISSION_WRITE
        )
        d = BluetoothGattDescriptor(
            clientCharacteristicConfigUuid,
            BluetoothGattDescriptor.PERMISSION_WRITE
        )
        d.setValue(BluetoothGattDescriptor.DISABLE_NOTIFICATION_VALUE)
        c.addDescriptor(d)
        service.addCharacteristic(c)
        characteristicState = c

        // Client2Server
        c = BluetoothGattCharacteristic(
            characteristicClient2ServerUuid,
            BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE,
            BluetoothGattCharacteristic.PERMISSION_WRITE
        )
        service.addCharacteristic(c)
        characteristicClient2Server = c

        // Server2Client
        c = BluetoothGattCharacteristic(
            characteristicServer2ClientUuid,
            BluetoothGattCharacteristic.PROPERTY_NOTIFY,
            BluetoothGattCharacteristic.PERMISSION_WRITE
        )
        d = BluetoothGattDescriptor(
            clientCharacteristicConfigUuid,
            BluetoothGattDescriptor.PERMISSION_WRITE
        )
        d.setValue(BluetoothGattDescriptor.DISABLE_NOTIFICATION_VALUE)
        c.addDescriptor(d)
        service.addCharacteristic(c)
        characteristicServer2Client = c

        // Ident
        if (characteristicIdentUuid != null) {
            c = BluetoothGattCharacteristic(
                characteristicIdentUuid,
                BluetoothGattCharacteristic.PROPERTY_READ,
                BluetoothGattCharacteristic.PERMISSION_READ
            )
            service.addCharacteristic(c)
            characteristicIdent = c
        }

        // Offers support to L2CAP when we have UUID characteristic and the OS version support it
        usingL2CAP =
            characteristicL2CAPUuid != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
        Logger.i(TAG, "Is L2CAP supported: $usingL2CAP")
        if (usingL2CAP) {
            // Start L2CAP socket server
            l2capServer = L2CAPServer(object : L2CAPServer.Listener {
                override fun onPeerConnected() {
                    reportPeerConnected()
                }

                override fun onPeerDisconnected() {
                    reportPeerDisconnected()
                }

                override fun onMessageReceived(data: ByteArray) {
                    reportMessageReceived(data)
                }

                override fun onError(error: Throwable) {
                    reportError(error)
                }
            })
            psm = l2capServer!!.start(bluetoothManager.adapter)
            if (psm == null) {
                Logger.w(TAG, "Error starting L2CAP server")
                l2capServer = null
                usingL2CAP = false
            } else {
                Logger.i(TAG, "Listening on L2CAP with PSM ${psm}")
                c = BluetoothGattCharacteristic(
                    characteristicL2CAPUuid,
                    BluetoothGattCharacteristic.PROPERTY_READ,
                    BluetoothGattCharacteristic.PERMISSION_READ
                )
                service.addCharacteristic(c)
                characteristicL2CAP = c
            }
        }
        try {
            gattServer!!.addService(service)
        } catch (e: SecurityException) {
            reportError(e)
            return false
        }
        return true
    }

    fun stop() {
        Logger.i(TAG, "stop")
        inhibitCallbacks = true
        if (l2capServer != null) {
            l2capServer!!.stop()
            l2capServer = null
        }
        if (gattServer != null) {
            // used to convey we want to shutdown once all write are done.
            sendMessage(ByteArray(0))
        }
    }

    override fun onConnectionStateChange(device: BluetoothDevice, status: Int, newState: Int) {
        Logger.d(TAG, "onConnectionStateChange: ${device.address} $status + $newState")
        if (newState == BluetoothProfile.STATE_DISCONNECTED && currentConnection != null &&
            device.address == currentConnection!!.address) {
            Logger.d(TAG, "Device ${currentConnection!!.address} which we're currently " +
                        "connected to, has disconnected")
            currentConnection = null
            reportPeerDisconnected()
        }
    }

    @SuppressLint("NewApi")
    override fun onCharacteristicReadRequest(
        device: BluetoothDevice, requestId: Int, offset: Int,
        characteristic: BluetoothGattCharacteristic
    ) {
        Logger.d(TAG, "onCharacteristicReadRequest: ${device.address} $requestId " +
                "$offset ${characteristic.uuid}")
        if (characteristicIdentUuid != null && characteristic.uuid == characteristicIdentUuid) {
            try {
                val ident = if (identValue != null) identValue!! else ByteArray(0)
                gattServer!!.sendResponse(
                    device,
                    requestId,
                    BluetoothGatt.GATT_SUCCESS,
                    0,
                    ident
                )
            } catch (e: SecurityException) {
                reportError(e)
            }
        } else if (characteristicL2CAP != null && characteristic.uuid == characteristicL2CAPUuid) {
            if (!usingL2CAP) {
                reportError(Error("Unexpected read request for L2CAP characteristic, not supported"))
                return
            }
            // TODO: it's not clear this is the right way to encode the PSM and 18013-5 doesn't
            //   seem to give enough guidance on it.
            val encodedPsmValue = ByteBuffer.allocate(4).putInt(psm!!).array()
            gattServer!!.sendResponse(
                device,
                requestId,
                BluetoothGatt.GATT_SUCCESS,
                0,
                encodedPsmValue
            )
        } else {
            reportError(
                Error(
                    "Read on unexpected characteristic with UUID "
                            + characteristic.uuid
                )
            )
        }
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
        val charUuid = characteristic.uuid
        Logger.d(TAG, "onCharacteristicWriteRequest: ${device.address} $requestId " +
                "$offset ${characteristic.uuid} ${value.toHex()}")

        // If we are connected to a device, ignore write from any other device
        if (currentConnection != null &&
            device.address != currentConnection!!.address
        ) {
            Logger.e(TAG, "Ignoring characteristic write request from ${device.address} " +
                    "since we're already connected to ${currentConnection!!.address}")
            return
        }
        if (charUuid == characteristicStateUuid && value.size == 1) {
            if (value[0].toInt() == 0x01) {
                // Close server socket when the connection was done by state characteristic
                stopL2CAPServer()
                if (currentConnection != null) {
                    Logger.e(TAG, "Ignoring connection attempt from ${device.address} " +
                                "since we're already connected to ${currentConnection!!.address}")
                } else {
                    currentConnection = device
                    Logger.d(TAG, "Received connection (state 0x01 on State characteristic) "
                                + "from ${currentConnection!!.address}")
                }
                reportPeerConnected()
            } else if (value[0].toInt() == 0x02) {
                reportTransportSpecificSessionTermination()
            } else {
                reportError(Error("Invalid byte ${value[0]} for state characteristic"))
            }
        } else if (charUuid == characteristicClient2ServerUuid) {
            if (value.size < 1) {
                reportError(Error("Invalid value with length ${value.size}"))
                return
            }
            if (currentConnection == null) {
                // We expect a write 0x01 on the State characteristic before we consider
                // the device to be connected.
                reportError(Error("Write on Client2Server but not connected yet"))
                return
            }
            incomingMessage.write(value, 1, value.size - 1)
            val isLast = (value[0].toInt() == 0x00)
            Logger.dHex(TAG, "Received chunk with ${value.size} bytes " +
                    "(last=$isLast), incomingMessage.length=${incomingMessage.toByteArray().size}", value)
            if (value[0].toInt() == 0x00) {
                // Last message.
                val entireMessage = incomingMessage.toByteArray()
                incomingMessage.reset()
                reportMessageReceived(entireMessage)
            } else if (value[0].toInt() == 0x01) {
                if (value.size != characteristicValueSize) {
                    Logger.w(TAG,
                        "Client2Server received ${value.size} bytes which is not the " +
                            "expected $characteristicValueSize bytes"
                    )
                    return
                }
            } else {
                reportError(Error(
                    "Invalid first byte ${value[0]} in Client2Server data chunk, expected 0 or 1"))
                return
            }
            if (responseNeeded) {
                try {
                    gattServer!!.sendResponse(
                        device,
                        requestId,
                        BluetoothGatt.GATT_SUCCESS,
                        0,
                        null
                    )
                } catch (e: SecurityException) {
                    reportError(e)
                }
            }
        } else {
            reportError(Error(
                "Write on unexpected characteristic with UUID ${characteristic.uuid}")
            )
        }
    }

    private fun stopL2CAPServer() {
        // Close server socket when the connection was done by state characteristic
        if (l2capServer != null) {
            l2capServer!!.stop()
            l2capServer = null
        }
        usingL2CAP = false
    }

    override fun onDescriptorReadRequest(
        device: BluetoothDevice, requestId: Int, offset: Int,
        descriptor: BluetoothGattDescriptor
    ) {
        Logger.d(TAG, "onDescriptorReadRequest: ${device.address} " +
                "${descriptor.characteristic.uuid} ${descriptor.characteristic.uuid} $offset")
        /* Do nothing */
    }

    override fun onDescriptorWriteRequest(
        device: BluetoothDevice, requestId: Int,
        descriptor: BluetoothGattDescriptor,
        preparedWrite: Boolean, responseNeeded: Boolean,
        offset: Int, value: ByteArray
    ) {
        if (Logger.isDebugEnabled) {
            Logger.d(
                TAG, "onDescriptorWriteRequest: ${device.address}" +
                        "${descriptor.characteristic.uuid} $offset ${value.toHex()}"
            )
        }
        if (responseNeeded) {
            try {
                gattServer!!.sendResponse(
                    device,
                    requestId,
                    BluetoothGatt.GATT_SUCCESS,
                    0,
                    null
                )
            } catch (e: SecurityException) {
                reportError(e)
            }
        }
    }

    override fun onMtuChanged(device: BluetoothDevice, mtu: Int) {
        negotiatedMtu = mtu
        Logger.d(TAG, "Negotiated MTU $mtu for $${device.address}")
    }

    private var characteristicValueSizeMemoized = 0
    private val characteristicValueSize: Int
        get() {
            if (characteristicValueSizeMemoized > 0) {
                return characteristicValueSizeMemoized
            }
            var mtuSize = negotiatedMtu
            if (mtuSize == 0) {
                Logger.w(TAG, "MTU not negotiated, defaulting to 23. Performance will suffer.")
                mtuSize = 23
            }
            characteristicValueSizeMemoized = DataTransportBle.bleCalculateAttributeValueSize(mtuSize)
            return characteristicValueSizeMemoized
        }

    fun drainWritingQueue() {
        Logger.d(TAG, "drainWritingQueue")
        val chunk = writingQueue.poll() ?: return
        if (chunk.size == 0) {
            Logger.d(TAG, "Chunk is length 0, shutting down GattServer in 1000ms")
            // TODO: On some devices we lose messages already sent if we don't have a delay like
            //  this. Need to properly investigate if this is a problem in our stack or the
            //  underlying BLE subsystem.
            Thread.sleep(1000)
            Logger.d(TAG, "Shutting down GattServer now")
            try {
                if (currentConnection != null) {
                    gattServer!!.cancelConnection(currentConnection)
                }
                gattServer!!.close()
            } catch (e: SecurityException) {
                Logger.e(TAG, "Caught SecurityException while shutting down", e)
            }
            gattServer = null
            return
        }
        val isLast = chunk[0].toInt() == 0x00
        Logger.d(TAG, "Sending chunk with ${chunk.size} bytes (last=$isLast)")
        characteristicServer2Client!!.setValue(chunk)
        try {
            if (!gattServer!!.notifyCharacteristicChanged(
                    currentConnection,
                    characteristicServer2Client, false
                )
            ) {
                reportError(
                    Error("Error calling notifyCharacteristicsChanged on Server2Client")
                )
                return
            }
        } catch (e: SecurityException) {
            reportError(e)
            return
        }
    }

    override fun onNotificationSent(device: BluetoothDevice, status: Int) {
        Logger.d(TAG, "onNotificationSent $status for ${device.address}")
        if (status != BluetoothGatt.GATT_SUCCESS) {
            reportError(Error("Error in onNotificationSent status=$status"))
            return
        }
        drainWritingQueue()
    }

    fun sendMessage(data: ByteArray) {
        Logger.dHex(TAG, "sendMessage", data)

        // Uses socket L2CAP when it is available
        if (l2capServer != null) {
            l2capServer!!.sendMessage(data)
            return
        }
        // Only initiate the write if no other write was outstanding.
        val queueNeedsDraining = (writingQueue.size == 0)
        if (data.size == 0) {
            // Data of length 0 is used to signal we should shut down.
            writingQueue.add(data)
        } else {
            // Also need room for the leading 0x00 or 0x01.
            //
            val maxDataSize = characteristicValueSize - 1
            var offset = 0
            do {
                val moreDataComing = offset + maxDataSize < data.size
                var size = data.size - offset
                if (size > maxDataSize) {
                    size = maxDataSize
                }
                val chunk = ByteArray(size + 1)
                chunk[0] = if (moreDataComing) 0x01.toByte() else 0x00.toByte()
                System.arraycopy(data, offset, chunk, 1, size)
                writingQueue.add(chunk)
                offset += size
            } while (offset < data.size)
        }
        if (queueNeedsDraining) {
            drainWritingQueue()
        }
    }

    fun reportPeerConnected() {
        if (listener != null && !inhibitCallbacks) {
            listener!!.onPeerConnected()
        }
    }

    fun reportPeerDisconnected() {
        if (listener != null && !inhibitCallbacks) {
            listener!!.onPeerDisconnected()
        }
    }

    fun reportMessageReceived(data: ByteArray) {
        if (listener != null && !inhibitCallbacks) {
            listener!!.onMessageReceived(data)
        }
    }

    fun reportError(error: Throwable) {
        if (listener != null && !inhibitCallbacks) {
            listener!!.onError(error)
        }
    }

    fun reportTransportSpecificSessionTermination() {
        if (listener != null && !inhibitCallbacks) {
            listener!!.onTransportSpecificSessionTermination()
        }
    }

    // When using L2CAP it doesn't support characteristics notification
    fun supportsTransportSpecificTerminationMessage(): Boolean {
        return !usingL2CAP
    }

    fun sendTransportSpecificTermination() {
        val terminationCode = byteArrayOf(0x02.toByte())
        characteristicState!!.setValue(terminationCode)
        try {
            if (!gattServer!!.notifyCharacteristicChanged(
                    currentConnection,
                    characteristicState, false
                )
            ) {
                reportError(Error("Error calling notifyCharacteristicsChanged on State"))
            }
        } catch (e: SecurityException) {
            reportError(e)
        }
    }

    internal interface Listener {
        fun onPeerConnected()
        fun onPeerDisconnected()
        fun onMessageReceived(data: ByteArray)
        fun onTransportSpecificSessionTermination()
        fun onError(error: Throwable)
    }

    companion object {
        private const val TAG = "GattServer"
    }
}
