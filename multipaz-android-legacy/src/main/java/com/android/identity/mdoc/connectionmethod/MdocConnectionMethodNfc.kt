package com.android.identity.mdoc.connectionmethod

import kotlinx.io.bytestring.ByteStringBuilder
import kotlinx.io.bytestring.buildByteString
import kotlinx.io.bytestring.encodeToByteString
import org.multipaz.cbor.Cbor
import org.multipaz.cbor.addCborMap
import org.multipaz.cbor.buildCborArray
import org.multipaz.mdoc.role.MdocRole
import org.multipaz.nfc.NdefRecord
import org.multipaz.nfc.Nfc
import org.multipaz.util.ByteDataReader
import org.multipaz.util.Logger
import org.multipaz.util.appendByteString
import org.multipaz.util.appendUInt16
import org.multipaz.util.appendUInt8

/**
 * Connection method for NFC.
 *
 * @param commandDataFieldMaxLength  the maximum length for the command data field.
 * @param responseDataFieldMaxLength the maximum length of the response data field.
 */
data class MdocConnectionMethodNfc(
    val commandDataFieldMaxLength: Long,
    val responseDataFieldMaxLength: Long
): MdocConnectionMethod() {
    override fun toString(): String =
        "nfc:cmd_max_length=$commandDataFieldMaxLength:resp_max_length=$responseDataFieldMaxLength"

    override fun toDeviceEngagement(): ByteArray {
        return Cbor.encode(
            buildCborArray {
                add(METHOD_TYPE)
                add(METHOD_MAX_VERSION)
                addCborMap {
                    put(OPTION_KEY_COMMAND_DATA_FIELD_MAX_LENGTH, commandDataFieldMaxLength)
                    put(OPTION_KEY_RESPONSE_DATA_FIELD_MAX_LENGTH, responseDataFieldMaxLength)
                }
            }
        )
    }

    private fun ByteStringBuilder.appendEncodedInt(dataType: UByte, value: Int) {
        if (value < 0x100) {
            appendUInt8(0x02) // Length
            appendUInt8(dataType)
            appendUInt8(value)
        } else if (value < 0x10000) {
            appendUInt8(0x03) // Length
            appendUInt8(dataType)
            appendUInt16(value)
        } else {
            appendUInt8(0x04) // Length
            appendUInt8(dataType)
            appendUInt8(value / 0x10000)
            appendUInt16(value.and(0xFFFF))
        }
    }

    override fun toNdefRecord(
        auxiliaryReferences: List<String>,
        role: MdocRole,
        skipUuids: Boolean
    ): Pair<NdefRecord, NdefRecord>? {
        val carrierDataReference = "nfc".encodeToByteString()

        val record = NdefRecord(
            NdefRecord.Tnf.EXTERNAL_TYPE,
            Nfc.EXTERNAL_TYPE_ISO_18013_5_NFC.encodeToByteString(),
            carrierDataReference,
            buildByteString {
                appendUInt8(0x01) // Version
                // Per ISO/IEC 18013-5 clause 8.2.2.2 / Second Edition clause 11.2.3, maximum
                // command/response data length fields are mandatory for the mdoc and shall not
                // be used by the mdoc reader.
                if (role == MdocRole.MDOC) {
                    appendEncodedInt(DATA_TYPE_MAXIMUM_COMMAND_DATA_LENGTH, commandDataFieldMaxLength.toInt())
                    appendEncodedInt(DATA_TYPE_MAXIMUM_RESPONSE_DATA_LENGTH, responseDataFieldMaxLength.toInt())
                }
            }
        )

        check(auxiliaryReferences.size < 0x100)
        val acRecord = NdefRecord(
            tnf = NdefRecord.Tnf.WELL_KNOWN,
            type = Nfc.RTD_ALTERNATIVE_CARRIER,
            payload = buildByteString {
                appendUInt8(0x01) // CPS: active
                appendUInt8(carrierDataReference.size) // Length of carrier data reference
                appendByteString(carrierDataReference)
                appendUInt8(auxiliaryReferences.size) // Number of auxiliary references
                for (auxRef in auxiliaryReferences) {
                    val auxRefUtf8 = auxRef.encodeToByteString()
                    check(auxRefUtf8.size < 0x100)
                    appendUInt8(auxRefUtf8.size)
                    appendByteString(auxRefUtf8)
                }
            }
        )
        return Pair(record, acRecord)
    }

    companion object {
        private const val TAG = "MdocConnectionMethodNfc"

        private const val DATA_TYPE_MAXIMUM_COMMAND_DATA_LENGTH: UByte = 0x01u
        private const val DATA_TYPE_MAXIMUM_RESPONSE_DATA_LENGTH: UByte = 0x02u
        private const val DEFAULT_COMMAND_DATA_FIELD_MAX_LENGTH = 0xffffL
        private const val DEFAULT_RESPONSE_DATA_FIELD_MAX_LENGTH = 0x10000L

        /**
         * The device retrieval method type for NFC according to ISO/IEC 18013-5:2021 clause 8.2.1.1.
         */
        const val METHOD_TYPE = 1L

        /**
         * The supported version of the device retrieval method type for NFC.
         */
        const val METHOD_MAX_VERSION = 1L

        private const val OPTION_KEY_COMMAND_DATA_FIELD_MAX_LENGTH = 0L
        private const val OPTION_KEY_RESPONSE_DATA_FIELD_MAX_LENGTH = 1L

        internal fun fromDeviceEngagement(encodedDeviceRetrievalMethod: ByteArray): MdocConnectionMethodNfc? {
            val array = Cbor.decode(encodedDeviceRetrievalMethod)
            val type = array[0].asNumber
            val version = array[1].asNumber
            require(type == METHOD_TYPE)
            if (version > METHOD_MAX_VERSION) {
                return null
            }
            val map = array[2]
            return MdocConnectionMethodNfc(
                map[OPTION_KEY_COMMAND_DATA_FIELD_MAX_LENGTH].asNumber,
                map[OPTION_KEY_RESPONSE_DATA_FIELD_MAX_LENGTH].asNumber
            )
        }

        internal fun fromNdefRecord(
            record: NdefRecord,
            role: MdocRole,
        ): MdocConnectionMethodNfc? {
            if (record.payload.size < 1) {
                Logger.w(TAG, "Empty NFC carrier record payload")
                return null
            }
            with (ByteDataReader(record.payload)) {
                val version = getUInt8().toInt()
                if (version != 0x01) {
                    Logger.w(TAG, "Expected version 0x01, found $version")
                    return null
                }
                var commandDataFieldMaxLength: Long? = null
                var responseDataFieldMaxLength: Long? = null
                while (!exhausted()) {
                    val fieldLen = getUInt8().toInt()
                    if (fieldLen < 1 || numBytesRemaining() < fieldLen) {
                        Logger.w(
                            TAG,
                            "Invalid LTV field length $fieldLen with ${numBytesRemaining()} bytes remaining"
                        )
                        return null
                    }
                    val dataType = getUInt8()
                    val valueByteCount = fieldLen - 1
                    when (dataType) {
                        DATA_TYPE_MAXIMUM_COMMAND_DATA_LENGTH -> {
                            if (valueByteCount < 1 || valueByteCount > 2) {
                                Logger.w(TAG, "expected cmdLen in range 2-3, got $fieldLen")
                                return null
                            }
                            if (commandDataFieldMaxLength != null) {
                                Logger.w(TAG, "Duplicate NFC data type 0x01 in carrier record")
                                return null
                            }
                            var value = 0u
                            for (n in 0 until valueByteCount) {
                                value = value * 256u + getUInt8()
                            }
                            commandDataFieldMaxLength = value.toLong()
                        }
                        DATA_TYPE_MAXIMUM_RESPONSE_DATA_LENGTH -> {
                            if (valueByteCount < 1 || valueByteCount > 3) {
                                Logger.w(TAG, "expected rspLen in range 2-4, got $fieldLen")
                                return null
                            }
                            if (responseDataFieldMaxLength != null) {
                                Logger.w(TAG, "Duplicate NFC data type 0x02 in carrier record")
                                return null
                            }
                            var value = 0u
                            for (n in 0 until valueByteCount) {
                                value = value * 256u + getUInt8()
                            }
                            responseDataFieldMaxLength = value.toLong()
                        }
                        else -> {
                            Logger.w(TAG, "Ignoring unknown NFC carrier data type 0x${dataType.toString(16)}")
                            skip(valueByteCount)
                        }
                    }
                }
                return when (role) {
                    MdocRole.MDOC -> {
                        if (commandDataFieldMaxLength == null || responseDataFieldMaxLength == null) {
                            Logger.w(
                                TAG,
                                "Missing mandatory NFC command/response max length in mdoc carrier record " +
                                    "(cmd=$commandDataFieldMaxLength, rsp=$responseDataFieldMaxLength)"
                            )
                            return null
                        }
                        MdocConnectionMethodNfc(
                            commandDataFieldMaxLength,
                            responseDataFieldMaxLength
                        )
                    }
                    MdocRole.MDOC_READER -> {
                        if (commandDataFieldMaxLength != null || responseDataFieldMaxLength != null) {
                            Logger.w(
                                TAG,
                                "Reader included NFC command/response max length in Handover Request " +
                                    "(prohibited by ISO/IEC 18013-5); accepting for compatibility"
                            )
                        }
                        MdocConnectionMethodNfc(
                            commandDataFieldMaxLength ?: DEFAULT_COMMAND_DATA_FIELD_MAX_LENGTH,
                            responseDataFieldMaxLength ?: DEFAULT_RESPONSE_DATA_FIELD_MAX_LENGTH
                        )
                    }
                }
            }
        }
    }
}
