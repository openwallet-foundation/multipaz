package org.multipaz.mdoc.connectionmethod

import kotlinx.io.bytestring.ByteString
import kotlinx.io.bytestring.ByteStringBuilder
import kotlinx.io.bytestring.buildByteString
import kotlinx.io.bytestring.encodeToByteString
import org.multipaz.cbor.DataItem
import org.multipaz.cbor.addCborMap
import org.multipaz.cbor.buildCborArray
import org.multipaz.mdoc.role.MdocRole
import org.multipaz.nfc.AlternativeCarrierRecord
import org.multipaz.nfc.HandoverCarrierRecords
import org.multipaz.nfc.NdefRecord
import org.multipaz.nfc.Nfc
import org.multipaz.util.ByteDataReader
import org.multipaz.util.Logger
import org.multipaz.util.appendUInt16
import org.multipaz.util.appendUInt8

private const val TAG = "OtherConnectionMethodCodecs"

private const val NFC_DATA_TYPE_MAXIMUM_COMMAND_DATA_LENGTH: UByte = 0x01u
private const val NFC_DATA_TYPE_MAXIMUM_RESPONSE_DATA_LENGTH: UByte = 0x02u
private const val NFC_OPTION_KEY_COMMAND_DATA_FIELD_MAX_LENGTH = 0L
private const val NFC_OPTION_KEY_RESPONSE_DATA_FIELD_MAX_LENGTH = 1L

private const val WIFI_AWARE_OPTION_KEY_PASSPHRASE_INFO_PASSPHRASE = 0L
private const val WIFI_AWARE_OPTION_KEY_CHANNEL_INFO_OPERATING_CLASS = 1L
private const val WIFI_AWARE_OPTION_KEY_CHANNEL_INFO_CHANNEL_NUMBER = 2L
private const val WIFI_AWARE_OPTION_KEY_BAND_INFO_SUPPORTED_BANDS = 3L

private const val HTTP_OPTION_KEY_URI = 0L

internal fun encodeNfcDeviceRetrievalMethod(
    nfc: MdocConnectionMethod.Nfc,
    engagementType: MdocEngagementType,
): DataItem {
    require(engagementType == MdocEngagementType.DEVICE_QR) {
        "NFC data retrieval is not supported in $engagementType"
    }
    return buildCborArray {
        add(MdocConnectionMethod.Nfc.METHOD_TYPE)
        add(MdocConnectionMethod.Nfc.METHOD_MAX_VERSION)
        addCborMap {
            put(NFC_OPTION_KEY_COMMAND_DATA_FIELD_MAX_LENGTH, nfc.commandDataFieldMaxLength)
            put(NFC_OPTION_KEY_RESPONSE_DATA_FIELD_MAX_LENGTH, nfc.responseDataFieldMaxLength)
        }
    }
}

internal fun decodeNfcDeviceRetrievalMethod(array: DataItem): MdocConnectionMethod.Nfc? {
    val type = array[0].asNumber
    val version = array[1].asNumber
    require(type == MdocConnectionMethod.Nfc.METHOD_TYPE)
    if (version > MdocConnectionMethod.Nfc.METHOD_MAX_VERSION) {
        return null
    }
    val map = array[2]
    return MdocConnectionMethod.Nfc(
        commandDataFieldMaxLength = map[NFC_OPTION_KEY_COMMAND_DATA_FIELD_MAX_LENGTH].asNumber,
        responseDataFieldMaxLength = map[NFC_OPTION_KEY_RESPONSE_DATA_FIELD_MAX_LENGTH].asNumber,
    )
}

private fun ByteStringBuilder.appendNfcEncodedInt(dataType: UByte, value: Int) {
    if (value < 0x100) {
        appendUInt8(0x02)
        appendUInt8(dataType)
        appendUInt8(value)
    } else if (value < 0x10000) {
        appendUInt8(0x03)
        appendUInt8(dataType)
        appendUInt16(value)
    } else {
        appendUInt8(0x04)
        appendUInt8(dataType)
        appendUInt8(value / 0x10000)
        appendUInt16(value and 0xFFFF)
    }
}

internal fun encodeNfcNdefHandoverCarrier(
    nfc: MdocConnectionMethod.Nfc,
    handoverType: MdocNfcHandoverType,
    sharedAuxiliaryReferences: List<String>,
): HandoverCarrierRecords {
    val carrierDataReference = MdocConnectionMethod.Nfc.CARRIER_DATA_REFERENCE_NFC
    val record = NdefRecord(
        NdefRecord.Tnf.EXTERNAL_TYPE,
        Nfc.EXTERNAL_TYPE_ISO_18013_5_NFC.encodeToByteString(),
        carrierDataReference.encodeToByteString(),
        buildByteString {
            appendUInt8(0x01) // Version
            // Per ISO/IEC 18013-5 Second Edition clause 11.2.3, maximum command/response data
            // length fields are mandatory for the mdoc and shall not be used by the mdoc reader.
            if (handoverType != MdocNfcHandoverType.NEGOTIATED_HR) {
                appendNfcEncodedInt(
                    NFC_DATA_TYPE_MAXIMUM_COMMAND_DATA_LENGTH,
                    nfc.commandDataFieldMaxLength.toInt()
                )
                appendNfcEncodedInt(
                    NFC_DATA_TYPE_MAXIMUM_RESPONSE_DATA_LENGTH,
                    nfc.responseDataFieldMaxLength.toInt()
                )
            }
        }
    )
    val alternativeCarrierRecord = AlternativeCarrierRecord(
        carrierDataReference = carrierDataReference,
        auxiliaryDataReferences = sharedAuxiliaryReferences,
    )
    return HandoverCarrierRecords(
        carrierRecord = record,
        alternativeCarrierRecord = alternativeCarrierRecord,
    )
}

private const val DEFAULT_NFC_COMMAND_DATA_FIELD_MAX_LENGTH = 0xffffL
private const val DEFAULT_NFC_RESPONSE_DATA_FIELD_MAX_LENGTH = 0x10000L

internal fun decodeNfcNdefHandoverCarrier(
    record: NdefRecord,
    senderRole: MdocRole,
): MdocConnectionMethod.Nfc? {
    if (record.payload.size < 1) {
        Logger.w(TAG, "Empty NFC carrier record payload")
        return null
    }
    with(ByteDataReader(record.payload)) {
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
                Logger.w(TAG, "Invalid LTV field length $fieldLen with ${numBytesRemaining()} bytes remaining")
                return null
            }
            val dataType = getUInt8()
            val valueByteCount = fieldLen - 1
            when (dataType) {
                NFC_DATA_TYPE_MAXIMUM_COMMAND_DATA_LENGTH -> {
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
                NFC_DATA_TYPE_MAXIMUM_RESPONSE_DATA_LENGTH -> {
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
        return when (senderRole) {
            MdocRole.MDOC -> {
                if (commandDataFieldMaxLength == null || responseDataFieldMaxLength == null) {
                    Logger.w(
                        TAG,
                        "Missing mandatory NFC command/response max length in mdoc carrier record " +
                            "(cmd=$commandDataFieldMaxLength, rsp=$responseDataFieldMaxLength)"
                    )
                    return null
                }
                MdocConnectionMethod.Nfc(
                    commandDataFieldMaxLength = commandDataFieldMaxLength,
                    responseDataFieldMaxLength = responseDataFieldMaxLength,
                )
            }
            MdocRole.MDOC_READER -> {
                if (commandDataFieldMaxLength != null || responseDataFieldMaxLength != null) {
                    Logger.w(
                        TAG,
                        "Reader included NFC command/response max length in Handover Request " +
                            "(prohibited by ISO/IEC 18013-5 Second Edition clause 11.2.3); accepting for 2021 compatibility"
                    )
                }
                MdocConnectionMethod.Nfc(
                    commandDataFieldMaxLength = commandDataFieldMaxLength
                        ?: DEFAULT_NFC_COMMAND_DATA_FIELD_MAX_LENGTH,
                    responseDataFieldMaxLength = responseDataFieldMaxLength
                        ?: DEFAULT_NFC_RESPONSE_DATA_FIELD_MAX_LENGTH,
                )
            }
        }
    }
}

internal fun encodeWifiAwareDeviceRetrievalMethod(
    wifiAware: MdocConnectionMethod.WifiAware,
    engagementType: MdocEngagementType,
): DataItem {
    require(engagementType == MdocEngagementType.DEVICE_QR) {
        "Wi-Fi Aware connection method is not supported in $engagementType"
    }
    return buildCborArray {
        add(MdocConnectionMethod.WifiAware.METHOD_TYPE)
        add(MdocConnectionMethod.WifiAware.METHOD_MAX_VERSION)
        addCborMap {
            if (wifiAware.passphraseInfoPassphrase != null) {
                put(WIFI_AWARE_OPTION_KEY_PASSPHRASE_INFO_PASSPHRASE, wifiAware.passphraseInfoPassphrase)
            }
            if (wifiAware.channelInfoChannelNumber != null) {
                put(WIFI_AWARE_OPTION_KEY_CHANNEL_INFO_CHANNEL_NUMBER, wifiAware.channelInfoChannelNumber)
            }
            if (wifiAware.channelInfoOperatingClass != null) {
                put(WIFI_AWARE_OPTION_KEY_CHANNEL_INFO_OPERATING_CLASS, wifiAware.channelInfoOperatingClass)
            }
            if (wifiAware.bandInfoSupportedBands != null) {
                put(WIFI_AWARE_OPTION_KEY_BAND_INFO_SUPPORTED_BANDS, wifiAware.bandInfoSupportedBands.toByteArray())
            }
        }
    }
}

internal fun decodeWifiAwareDeviceRetrievalMethod(array: DataItem): MdocConnectionMethod.WifiAware? {
    val type = array[0].asNumber
    val version = array[1].asNumber
    require(type == MdocConnectionMethod.WifiAware.METHOD_TYPE)
    if (version > MdocConnectionMethod.WifiAware.METHOD_MAX_VERSION) {
        return null
    }
    val map = array[2]
    val passphraseInfoPassphrase =
        map.getOrNull(WIFI_AWARE_OPTION_KEY_PASSPHRASE_INFO_PASSPHRASE)?.asTstr
    val channelInfoChannelNumber =
        map.getOrNull(WIFI_AWARE_OPTION_KEY_CHANNEL_INFO_CHANNEL_NUMBER)?.asNumber
    val channelInfoOperatingClass =
        map.getOrNull(WIFI_AWARE_OPTION_KEY_CHANNEL_INFO_OPERATING_CLASS)?.asNumber
    val bandInfoSupportedBands =
        map.getOrNull(WIFI_AWARE_OPTION_KEY_BAND_INFO_SUPPORTED_BANDS)?.asBstr
    return MdocConnectionMethod.WifiAware(
        passphraseInfoPassphrase = passphraseInfoPassphrase,
        channelInfoChannelNumber = channelInfoChannelNumber,
        channelInfoOperatingClass = channelInfoOperatingClass,
        bandInfoSupportedBands = bandInfoSupportedBands?.let { ByteString(it) },
    )
}

internal fun encodeHttpDeviceRetrievalMethod(
    http: MdocConnectionMethod.Http,
    engagementType: MdocEngagementType,
): DataItem {
    require(
        engagementType == MdocEngagementType.DEVICE_QR ||
            engagementType == MdocEngagementType.READER_QR
    ) {
        "HTTP connection method is not supported in $engagementType"
    }
    return buildCborArray {
        add(MdocConnectionMethod.Http.METHOD_TYPE)
        add(MdocConnectionMethod.Http.METHOD_MAX_VERSION)
        addCborMap {
            put(HTTP_OPTION_KEY_URI, http.uri)
        }
    }
}

internal fun decodeHttpDeviceRetrievalMethod(array: DataItem): MdocConnectionMethod.Http? {
    val type = array[0].asNumber
    val version = array[1].asNumber
    require(type == MdocConnectionMethod.Http.METHOD_TYPE)
    if (version > MdocConnectionMethod.Http.METHOD_MAX_VERSION) {
        return null
    }
    val map = array[2]
    return MdocConnectionMethod.Http(uri = map[HTTP_OPTION_KEY_URI].asTstr)
}
