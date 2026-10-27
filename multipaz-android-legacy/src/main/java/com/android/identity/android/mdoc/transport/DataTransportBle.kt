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

import android.content.Context
import android.nfc.NdefRecord
import android.util.Pair
import kotlinx.io.bytestring.ByteString
import org.multipaz.mdoc.connectionmethod.MdocConnectionMethod
import org.multipaz.mdoc.connectionmethod.MdocConnectionMethodBle
import org.multipaz.util.Logger
import org.multipaz.util.UUID
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Locale

/**
 * BLE data transport
 */
abstract class DataTransportBle(
    context: Context,
    role: Role,
    @JvmField protected var connectionMethod: MdocConnectionMethodBle,
    options: DataTransportOptions
) : DataTransport(context, role, options) {
    protected var serviceUuid: UUID? = null
    protected var connectionMethodToReturn: MdocConnectionMethodBle

    /**
     * Gets the amount of time spent scanning for the other device.
     *
     * @return Time in milliseconds or 0 if the transport is advertising instead of scanning.
     */
    var scanningTimeMillis: Long = 0
        protected set

    init {
        connectionMethodToReturn = MdocConnectionMethodBle(
            supportsPeripheralServerMode = connectionMethod.supportsPeripheralServerMode,
            supportsCentralClientMode = connectionMethod.supportsCentralClientMode,
            peripheralServerModeUuid = connectionMethod.peripheralServerModeUuid,
            centralClientModeUuid = connectionMethod.centralClientModeUuid,
            peripheralServerModePsm = null,
            peripheralServerModeMacAddress = connectionMethod.peripheralServerModeMacAddress
        )
    }

    override val connectionMethodForTransport: MdocConnectionMethod
        get() = connectionMethodToReturn

    companion object {
        private const val TAG = "DataTransportBle"

        // The size of buffer for read() calls when using L2CAP sockets.
        const val L2CAP_BUF_SIZE = 65536

        @JvmStatic
        fun fromNdefRecord(
            record: NdefRecord,
            isForHandoverSelect: Boolean
        ): MdocConnectionMethodBle? {
            var centralClient = false
            var peripheral = false
            var uuid: UUID? = null
            var gotLeRole = false
            var gotUuid = false
            var macAddress: ByteArray? = null

            // See createNdefRecords() method for how this data is encoded.
            //
            val payload = ByteBuffer.wrap(record.payload).order(ByteOrder.LITTLE_ENDIAN)
            while (payload.remaining() > 0) {
                val len = payload.get().toInt()
                val type = payload.get().toInt()
                if (type == 0x1c && len == 2) {
                    gotLeRole = true
                    val value = payload.get().toInt()
                    if (value == 0x00) {
                        if (isForHandoverSelect) {
                            peripheral = true
                        } else {
                            centralClient = true
                        }
                    } else if (value == 0x01) {
                        if (isForHandoverSelect) {
                            centralClient = true
                        } else {
                            peripheral = true
                        }
                    } else if (value == 0x02 || value == 0x03) {
                        centralClient = true
                        peripheral = true
                    } else {
                        Logger.w(TAG, String.format("Invalid value %d for LE role", value))
                        return null
                    }
                } else if (type == 0x07) {
                    val uuidLen = len - 1
                    if (uuidLen % 16 != 0) {
                        Logger.w(TAG, String.format("UUID len %d is not divisible by 16", uuidLen))
                        return null
                    }
                    // We only use the last UUID...
                    var n = 0
                    while (n < uuidLen) {
                        val lsb = payload.getLong().toULong()
                        val msb = payload.getLong().toULong()
                        uuid = UUID(msb, lsb)
                        gotUuid = true
                        n += 16
                    }
                } else if (type == 0x1b && len == 0x07) {
                    // MAC address
                    macAddress = ByteArray(6)
                    payload[macAddress]
                } else {
                    Logger.d(TAG, String.format("Skipping unknown type %d of length %d", type, len))
                    payload.position(payload.position() + len - 1)
                }
            }
            if (!gotLeRole) {
                Logger.w(TAG, "Did not find LE role")
                return null
            }

            // Note that UUID may _not_ be set.

            // Note that the UUID for both modes is the same if both peripheral and
            // central client mode is used!
            val cm = MdocConnectionMethodBle(
                supportsPeripheralServerMode = peripheral,
                supportsCentralClientMode = centralClient,
                peripheralServerModeUuid = if (peripheral) uuid else null,
                centralClientModeUuid = if (centralClient) uuid else null,
                peripheralServerModePsm = null,
                peripheralServerModeMacAddress = macAddress?.let { ByteString(it) }
            )
            return cm
        }

        fun fromConnectionMethod(
            context: Context,
            cm: MdocConnectionMethodBle,
            role: Role,
            options: DataTransportOptions
        ): DataTransport {
            require(!(cm.supportsCentralClientMode && cm.supportsPeripheralServerMode)) {
                "BLE connection method is ambiguous. Use disambiguate()"
            }
            if (cm.supportsCentralClientMode) {
                val t: DataTransportBle =
                    DataTransportBleCentralClientMode(context, role, cm, options)
                t.serviceUuid = cm.centralClientModeUuid!!
                return t
            }
            if (cm.supportsPeripheralServerMode) {
                val t: DataTransportBle =
                    DataTransportBlePeripheralServerMode(context, role, cm, options)
                t.serviceUuid = cm.peripheralServerModeUuid!!
                return t
            }
            throw IllegalArgumentException("BLE connection method supports neither modes")
        }

        fun toNdefRecord(
            cm: MdocConnectionMethodBle,
            auxiliaryReferences: List<String>,
            isForHandoverSelect: Boolean
        ): Pair<NdefRecord, ByteArray>? {
            // The OOB data is defined in "Supplement to the Bluetooth Core Specification".
            //
            // See section 1.17.2 for values
            //
            val uuid: UUID?
            val leRole: Int
            if (cm.supportsCentralClientMode && cm.supportsPeripheralServerMode) {
                // Peripheral and Central Role supported,
                // Central Role preferred for connection
                // establishment
                leRole = 0x03
                check(cm.centralClientModeUuid == cm.peripheralServerModeUuid) {
                    "UUIDs for both BLE modes must be the same"
                }
                uuid = cm.centralClientModeUuid
            } else if (cm.supportsCentralClientMode) {
                leRole = if (isForHandoverSelect) {
                    // Only Central Role supported
                    0x01
                } else {
                    // Only Peripheral Role supported
                    0x00
                }
                uuid = cm.centralClientModeUuid
            } else if (cm.supportsPeripheralServerMode) {
                leRole = if (isForHandoverSelect) {
                    // Only Peripheral Role supported
                    0x00
                } else {
                    // Only Central Role supported
                    0x01
                }
                uuid = cm.peripheralServerModeUuid
            } else {
                throw IllegalStateException("At least one of the BLE modes must be set")
            }

            // See "3 Handover to a Bluetooth Carrier" of "Bluetooth® Secure Simple Pairing Using
            // NFC Application Document" Version 1.2. This says:
            //
            //   For Bluetooth LE OOB the name “application/vnd.bluetooth.le.oob” is used as the
            //   [NDEF] record type name. The payload of this type of record is then defined by the
            //   Advertising and Scan Response Data (AD) format that is specified in the Bluetooth Core
            //   Specification ([BLUETOOTH_CORE], Volume 3, Part C, Section 11).
            //
            // Looking that up it says it's just a sequence of {length, AD type, AD data} where each
            // AD is defined in the "Bluetooth Supplement to the Core Specification" document.
            //
            var baos = ByteArrayOutputStream()
            baos.write(0x02)
            baos.write(0x1c) // LE Role
            baos.write(leRole)
            if (uuid != null) {
                baos.write(0x11) // Complete List of 128-bit Service UUID’s (0x07)
                baos.write(0x07)
                val uuidBuf = ByteBuffer.allocate(16).order(ByteOrder.LITTLE_ENDIAN)
                uuidBuf.putLong(0, uuid.leastSignificantBits.toLong())
                uuidBuf.putLong(8, uuid.mostSignificantBits.toLong())
                try {
                    baos.write(uuidBuf.array())
                } catch (e: IOException) {
                    throw IllegalStateException(e)
                }
            }
            val macAddress = cm.peripheralServerModeMacAddress
            if (macAddress != null) {
                require(macAddress.size == 6) {
                    "MAC address should be six bytes, found ${macAddress.size}"
                }
                baos.write(0x07)
                baos.write(0x1b) // MAC address
                try {
                    baos.write(macAddress.toByteArray())
                } catch (e: IOException) {
                    throw IllegalStateException(e)
                }
            }
            val oobData = baos.toByteArray()
            val record = NdefRecord(
                NdefRecord.TNF_MIME_MEDIA,
                "application/vnd.bluetooth.le.oob".toByteArray(),
                "0".toByteArray(),
                oobData
            )

            // From 7.1 Alternative Carrier Record
            //
            baos = ByteArrayOutputStream()
            baos.write(0x01) // CPS: active
            baos.write(0x01) // Length of carrier data reference ("0")
            baos.write('0'.code) // Carrier data reference
            baos.write(auxiliaryReferences.size) // Number of auxiliary references
            for (auxRef in auxiliaryReferences) {
                // Each auxiliary reference consists of a single byte for the length and then as
                // many bytes for the reference itself.
                val auxRefUtf8 = auxRef.toByteArray()
                baos.write(auxRefUtf8.size)
                baos.write(auxRefUtf8, 0, auxRefUtf8.size)
            }
            val acRecordPayload = baos.toByteArray()
            return Pair(record, acRecordPayload)
        }

        internal fun bleCalculateAttributeValueSize(mtuSize: Int): Int {
            val characteristicValueSize: Int
            if (mtuSize > 515) {
                // Bluetooth Core specification Part F section 3.2.9 says "The maximum length of
                // an attribute value shall be 512 octets". ... this is enforced in Android as
                // of Android 13 with the effect being that the application only sees the first
                // 512 bytes.
                Logger.w(
                    TAG, String.format(
                        Locale.US, "MTU size is %d, using 512 as "
                                + "characteristic value size", mtuSize
                    )
                )
                characteristicValueSize = 512
            } else {
                characteristicValueSize = mtuSize - 3
                Logger.w(
                    TAG, String.format(
                        Locale.US, "MTU size is %d, using %d as "
                                + "characteristic value size", mtuSize, characteristicValueSize
                    )
                )
            }
            return characteristicValueSize
        }
    }
}