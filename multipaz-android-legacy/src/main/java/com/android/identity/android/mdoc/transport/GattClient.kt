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
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.os.Build
import org.multipaz.crypto.Algorithm
import org.multipaz.crypto.Hkdf
import org.multipaz.util.Logger
import org.multipaz.util.toHex
import java.io.ByteArrayOutputStream
import java.lang.reflect.InvocationTargetException
import java.nio.ByteBuffer
import java.util.ArrayDeque
import java.util.Arrays
import java.util.Queue
import java.util.UUID

@Suppress("deprecation")
@SuppressLint("MissingPermission")
internal class GattClient(
    private val context: Context,
    private val serviceUuid: UUID,
    private val encodedEDeviceKeyBytes: ByteArray?,
    var characteristicStateUuid: UUID,
    var characteristicClient2ServerUuid: UUID,
    var characteristicServer2ClientUuid: UUID,
    var characteristicIdentUuid: UUID?,
    var characteristicL2CAPUuid: UUID?
) : BluetoothGattCallback() {
    var listener: Listener? = null
    var clearCache = false

    private var gatt: BluetoothGatt? = null
    private var characteristicState: BluetoothGattCharacteristic? = null
    private var characteristicClient2Server: BluetoothGattCharacteristic? = null
    private var characteristicServer2Client: BluetoothGattCharacteristic? = null
    private var characteristicIdent: BluetoothGattCharacteristic? = null
    private var characteristicL2CAP: BluetoothGattCharacteristic? = null
    private var l2capClient: L2CAPClient? = null

    // This is what the 16-bit UUID 0x29 0x02 is encoded like.
    private var clientCharacteristicConfigUuid = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
    private var incomingMessage = ByteArrayOutputStream()
    private var writingQueue: Queue<ByteArray> = ArrayDeque()
    private var inhibitCallbacks = false
    private var negotiatedMtu = 0
    private var identValue: ByteArray? = null
    private var usingL2CAP = false

    fun connect(device: BluetoothDevice) {
        if (encodedEDeviceKeyBytes != null) {
            val ikm: ByteArray = encodedEDeviceKeyBytes
            val info = "BLEIdent".toByteArray()
            val salt = null
            identValue = Hkdf.deriveKey(Algorithm.HMAC_SHA256, ikm, salt, info, 16)
        }
        try {
            gatt = device.connectGatt(context, false, this, BluetoothDevice.TRANSPORT_LE)
        } catch (e: SecurityException) {
            reportError(e)
        }
    }

    fun disconnect() {
        inhibitCallbacks = true
        if (l2capClient != null) {
            l2capClient!!.disconnect()
            l2capClient = null
        }
        if (gatt != null) {
            // used to convey we want to shutdown once all write are done.
            sendMessage(ByteArray(0))
        }
    }

    private fun clearCache(gatt: BluetoothGatt) {
        Logger.d(TAG, "Application requested clearing BLE Service Cache")
        // BluetoothGatt.refresh() is not public API but can be accessed via introspection...
        try {
            val refreshMethod = gatt.javaClass.getMethod("refresh")
            val result = refreshMethod.invoke(gatt) as Boolean
            if (result) {
                Logger.d(TAG, "BluetoothGatt.refresh() invoked successfully")
            } else {
                Logger.e(TAG, "BluetoothGatt.refresh() invoked but returned false")
            }
        } catch (e: NoSuchMethodException) {
            Logger.e(TAG, "Getting BluetoothGatt.refresh() failed with NoSuchMethodException", e)
        } catch (e: IllegalAccessException) {
            Logger.e(TAG, "Getting BluetoothGatt.refresh() failed with IllegalAccessException", e)
        } catch (e: InvocationTargetException) {
            Logger.e(TAG, "Getting BluetoothGatt.refresh() failed with InvocationTargetException", e)
        }
    }

    override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
        Logger.d(TAG, "onConnectionStateChange: status=$status newState=$newState")
        if (newState == BluetoothProfile.STATE_CONNECTED) {
            //Logger.Logger.d(TAG, "Connected");
            try {
                if (clearCache) {
                    clearCache(gatt)
                }
                gatt.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_HIGH)
                gatt.discoverServices()
            } catch (e: SecurityException) {
                reportError(e)
            }
        } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
            //Logger.Logger.d(TAG, "Disconnected");
            reportPeerDisconnected()
        }
    }

    override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
        Logger.d(TAG, "onServicesDiscovered: status=$status")
        if (status == BluetoothGatt.GATT_SUCCESS) {
            val s = gatt.getService(serviceUuid)
            if (s != null) {
                if (characteristicL2CAPUuid != null) {
                    characteristicL2CAP = s.getCharacteristic(characteristicL2CAPUuid)
                    if (characteristicL2CAP != null) {
                        Logger.d(TAG, "L2CAP characteristic found $characteristicL2CAPUuid")
                    }
                }
                characteristicState = s.getCharacteristic(characteristicStateUuid)
                if (characteristicState == null) {
                    reportError(Error("State characteristic not found"))
                    return
                }
                characteristicClient2Server = s.getCharacteristic(
                    characteristicClient2ServerUuid
                )
                if (characteristicClient2Server == null) {
                    reportError(Error("Client2Server characteristic not found"))
                    return
                }
                characteristicServer2Client = s.getCharacteristic(
                    characteristicServer2ClientUuid
                )
                if (characteristicServer2Client == null) {
                    reportError(Error("Server2Client characteristic not found"))
                    return
                }
                if (characteristicIdentUuid != null) {
                    characteristicIdent = s.getCharacteristic(characteristicIdentUuid)
                    if (characteristicIdent == null) {
                        reportError(Error("Ident characteristic not found"))
                        return
                    }
                }
            }

            // Start by bumping MTU, callback in onMtuChanged()...
            //
            // Which MTU should we choose? On Android the maximum MTU size is said to be 517.
            //
            // Also 18013-5 section 8.3.3.1.1.6 Data retrieval says to write attributes to
            // Client2Server and Server2Client characteristics of a size which 3 less the
            // MTU size. If we chose an MTU of 517 then the attribute we'd write would be
            // 514 bytes long.
            //
            // Also note that Bluetooth Core specification Part F section 3.2.9 Long attribute
            // values says "The maximum length of an attribute value shall be 512 octets." ... so
            // with an MTU of 517 we'd blow through that limit. An MTU limited to 515 bytes
            // will work though.
            //
            // ... so we request 515 bytes for the MTU. We might not get such a big MTU, the way
            // it works is that the requestMtu() call will trigger a negotiation between the client (us)
            // and the server (the remote device).
            //
            // We'll get notified in BluetoothGattCallback.onMtuChanged() below.
            //
            // The server will also be notified about the new MTU - if it's running Android
            // it'll be via BluetoothGattServerCallback.onMtuChanged(), see GattServer.java
            // for that in our implementation.
            try {
                if (!gatt.requestMtu(515)) {
                    reportError(Error("Error requesting MTU"))
                    return
                }
            } catch (e: SecurityException) {
                reportError(e)
                return
            }
            this.gatt = gatt
        }
    }

    override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
        negotiatedMtu = mtu
        if (status != BluetoothGatt.GATT_SUCCESS) {
            reportError(Error("Error changing MTU, status: $status"))
            return
        }
        Logger.d(TAG, "Negotiated MTU $mtu")
        if (characteristicIdent != null && identValue != null) {
            // Read ident characteristics...
            //
            // TODO: maybe skip this, it's optional after all...
            //
            try {
                if (!gatt.readCharacteristic(characteristicIdent)) {
                    reportError(Error("Error reading from ident characteristic"))
                }
            } catch (e: SecurityException) {
                reportError(e)
            }
            // Callback happens in onDescriptorRead() right below...
            //
        } else {
            afterIdentObtained(gatt)
        }
    }

    private var mCharacteristicValueSize = 0
    private val characteristicValueSize: Int
        get() {
            if (mCharacteristicValueSize > 0) {
                return mCharacteristicValueSize
            }
            var mtuSize = negotiatedMtu
            if (mtuSize == 0) {
                Logger.w(TAG, "MTU not negotiated, defaulting to 23. Performance will suffer.")
                mtuSize = 23
            }
            mCharacteristicValueSize = DataTransportBle.bleCalculateAttributeValueSize(mtuSize)
            return mCharacteristicValueSize
        }

    @Deprecated("Deprecated in Java")
    @SuppressLint("NewApi")
    override fun onCharacteristicRead(
        gatt: BluetoothGatt,
        characteristic: BluetoothGattCharacteristic,
        status: Int
    ) {
        if (characteristic.uuid == characteristicIdentUuid) {
            val identValue = characteristic.value
            if (Logger.isDebugEnabled) {
                Logger.d(TAG, "Received identValue: ${identValue.toHex()}")
            }
            // TODO: Don't even request IDENT since it cannot work w/ reverse engagement (there's
            //   no way the mdoc reader knows EDeviceKeyBytes at this point) and it's also optional.
            if (!Arrays.equals(identValue, this.identValue)) {
                reportError(Error("Received ident '${identValue.toHex()}' does not match " +
                        "expected ident '${this.identValue!!.toHex()}'"));
                return;
            }
            afterIdentObtained(gatt)
        } else if (characteristic.uuid == characteristicL2CAPUuid) {
            if (!usingL2CAP) {
                reportError(
                    Error(
                        "Unexpected read for L2CAP characteristic "
                                + characteristic.uuid + ", L2CAP not supported"
                    )
                )
                return
            }
            l2capClient = L2CAPClient(context, object : L2CAPClient.Listener {
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
            val psmValue = characteristic.value
            var psmSized = ByteArray(4)
            if (psmValue.size < 4) {
                // Add 00 on left if psm length is lower than 4
                System.arraycopy(psmValue, 0, psmSized, 4 - psmValue.size, psmValue.size)
            } else {
                psmSized = psmValue
            }
            val psm = ByteBuffer.wrap(psmSized).getInt()
            l2capClient!!.connect(this.gatt!!.device, psm)
        } else {
            reportError(Error("Unexpected onCharacteristicRead for characteristic " +
                    "$characteristic.uuid + expected $characteristicIdentUuid"))
        }
    }

    private fun afterIdentObtained(gatt: BluetoothGatt) {
        try {
            // Use L2CAP if supported by GattServer and by this OS version
            usingL2CAP =
                characteristicL2CAP != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
            Logger.d(TAG, "Using L2CAP: $usingL2CAP")
            if (usingL2CAP) {
                // value is returned async above in onCharacteristicRead()
                if (!gatt.readCharacteristic(characteristicL2CAP)) {
                    reportError(Error("Error reading L2CAP characteristic"))
                }
                return
            }
            if (!gatt.setCharacteristicNotification(characteristicServer2Client, true)) {
                reportError(Error("Error setting notification on Server2Client"))
                return
            }
            val d = characteristicServer2Client!!.getDescriptor(clientCharacteristicConfigUuid)
            if (d == null) {
                reportError(
                    Error("Error getting Server2Client clientCharacteristicConfig desc")
                )
                return
            }
            d.setValue(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
            if (!gatt.writeDescriptor(d)) {
                reportError(
                    Error(
                        "Error writing to Server2Client clientCharacteristicConfig "
                                + "desc"
                    )
                )
            }
        } catch (e: SecurityException) {
            reportError(e)
        }
        // Callback happens in onDescriptorWrite() right below...
        //
    }

    override fun onDescriptorWrite(
        gatt: BluetoothGatt,
        descriptor: BluetoothGattDescriptor,
        status: Int
    ) {
        Logger.d(TAG,
            "onDescriptorWrite: $descriptor.uuid " +
            "characteristic=${descriptor.characteristic.uuid} status=$status")
        try {
            val charUuid = descriptor.characteristic.uuid
            if (charUuid == characteristicServer2ClientUuid && descriptor.uuid == clientCharacteristicConfigUuid) {
                if (!gatt.setCharacteristicNotification(characteristicState, true)) {
                    reportError(Error("Error setting notification on State"))
                    return
                }
                val d = characteristicState!!.getDescriptor(clientCharacteristicConfigUuid)
                if (d == null) {
                    reportError(Error("Error getting State clientCharacteristicConfig desc"))
                    return
                }
                d.setValue(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
                if (!gatt.writeDescriptor(d)) {
                    reportError(
                        Error("Error writing to State clientCharacteristicConfig desc")
                    )
                }

                // Continued in this callback right below...
                //
            } else if (charUuid == characteristicStateUuid && descriptor.uuid == clientCharacteristicConfigUuid) {

                // Finally we've set everything up, we can write 0x01 to state to signal
                // to the other end (mDL reader) that it can start sending data to us..
                characteristicState!!.setValue(byteArrayOf(0x01.toByte()))
                if (!this.gatt!!.writeCharacteristic(characteristicState)) {
                    reportError(Error("Error writing to state characteristic"))
                }
            } else {
                reportError(Error(
                        "Unexpected onDescriptorWrite for characteristic UUID $charUuid" +
                                " and descriptor UUID ${descriptor.uuid}"))
            }
        } catch (e: SecurityException) {
            reportError(e)
        }
    }

    override fun onCharacteristicWrite(
        gatt: BluetoothGatt,
        characteristic: BluetoothGattCharacteristic,
        status: Int
    ) {
        val charUuid = characteristic.uuid
        Logger.d(TAG, "onCharacteristicWrite $status $charUuid")
        if (charUuid == characteristicStateUuid) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                reportError(Error("Unexpected status for writing to State, status=$status"))
                return
            }
            reportPeerConnected()
        } else if (charUuid == characteristicClient2ServerUuid) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                reportError(
                    Error(
                        "Unexpected status for writing to Client2Server, status=$status"
                    )
                )
                return
            }
            drainWritingQueue()
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onCharacteristicChanged(
        gatt: BluetoothGatt,
        characteristic: BluetoothGattCharacteristic
    ) {
        Logger.d(TAG, "in onCharacteristicChanged, uuid=${characteristic.uuid}")
        if (characteristic.uuid == characteristicServer2ClientUuid) {
            val data = characteristic.value
            if (data.size < 1) {
                reportError(Error("Invalid data length ${data.size} for Server2Client characteristic"))
                return
            }
            incomingMessage.write(data, 1, data.size - 1)
            val isLast = (data[0].toInt() == 0x00)
            Logger.d(TAG,
                "Received chunk with ${data.size} bytes (last=$isLast), " +
                        "incomingMessage.length=${incomingMessage.toByteArray().size}")
            if (data[0].toInt() == 0x00) {
                // Last message.
                val entireMessage = incomingMessage.toByteArray()
                incomingMessage.reset()
                reportMessageReceived(entireMessage)
            } else if (data[0].toInt() == 0x01) {
                if (data.size != characteristicValueSize) {
                    Logger.w(TAG,
                        "Server2Client received ${data.size} bytes which is not the " +
                                "expected $characteristicValueSize bytes")
                }
            } else {
                reportError(Error("Invalid first byte ${data[0]} in Server2Client data chunk, " +
                        "expected 0 or 1")
                )
            }
        } else if (characteristic.uuid == characteristicStateUuid) {
            val data = characteristic.value
            if (data.size != 1) {
                reportError(Error("Invalid data length ${data.size} for state characteristic"))
                return
            }
            if (data[0].toInt() == 0x02) {
                reportTransportSpecificSessionTermination()
            } else {
                reportError(Error("Invalid byte ${data[0]} for state characteristic"))
            }
        }
    }

    private fun drainWritingQueue() {
        Logger.d(TAG, "drainWritingQueue")
        val chunk = writingQueue.poll() ?: return
        if (chunk.size == 0) {
            Logger.d(TAG, "Chunk is length 0, shutting down GattClient in 1000ms")
            // TODO: On some devices we lose messages already sent if we don't have a delay like
            //  this. Need to properly investigate if this is a problem in our stack or the
            //  underlying BLE subsystem.
            Thread.sleep(1000)
            Logger.d(TAG, "Shutting down GattClient now")
            try {
                gatt!!.disconnect()
                gatt!!.close()
            } catch (e: SecurityException) {
                Logger.e(TAG, "Caught SecurityException while shutting down", e)
            }
            gatt = null
            return
        }
        val isLast = chunk[0].toInt() == 0x00
        Logger.dHex(TAG,"Sending chunk with ${chunk.size} bytes (last=$isLast)", chunk)
        characteristicClient2Server!!.setValue(chunk)
        try {
            if (!gatt!!.writeCharacteristic(characteristicClient2Server)) {
                reportError(Error("Error writing to Client2Server characteristic"))
                return
            }
        } catch (e: SecurityException) {
            reportError(e)
            return
        }
    }

    fun sendMessage(data: ByteArray) {
        // Use socket for L2CAP if applicable
        if (l2capClient != null) {
            l2capClient!!.sendMessage(data)
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

    // When using L2CAP it doesn't support characteristics notification
    fun supportsTransportSpecificTerminationMessage(): Boolean {
        return !usingL2CAP
    }

    fun sendTransportSpecificTermination() {
        val terminationCode = byteArrayOf(0x02.toByte())
        characteristicState!!.setValue(terminationCode)
        try {
            if (!gatt!!.writeCharacteristic(characteristicState)) {
                reportError(Error("Error writing to state characteristic"))
            }
        } catch (e: SecurityException) {
            reportError(e)
        }
    }

    private fun reportPeerConnected() {
        if (listener != null && !inhibitCallbacks) {
            listener!!.onPeerConnected()
        }
    }

    private fun reportPeerDisconnected() {
        if (listener != null && !inhibitCallbacks) {
            listener!!.onPeerDisconnected()
        }
    }

    private fun reportMessageReceived(data: ByteArray) {
        if (listener != null && !inhibitCallbacks) {
            listener!!.onMessageReceived(data)
        }
    }

    private fun reportTransportSpecificSessionTermination() {
        if (listener != null && !inhibitCallbacks) {
            listener!!.onTransportSpecificSessionTermination()
        }
    }

    private fun reportError(error: Throwable) {
        if (listener != null && !inhibitCallbacks) {
            listener!!.onError(error)
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
        private const val TAG = "GattClient"
    }
}
