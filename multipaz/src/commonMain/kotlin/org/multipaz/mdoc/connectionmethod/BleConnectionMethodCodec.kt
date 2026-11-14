package org.multipaz.mdoc.connectionmethod

import kotlinx.io.bytestring.ByteString
import kotlinx.io.bytestring.buildByteString
import kotlinx.io.bytestring.decodeToString
import kotlinx.io.bytestring.encodeToByteString
import org.multipaz.cbor.Cbor
import org.multipaz.cbor.DataItem
import org.multipaz.cbor.addCborMap
import org.multipaz.cbor.buildCborArray
import org.multipaz.cbor.buildCborMap
import org.multipaz.mdoc.role.MdocRole
import org.multipaz.nfc.AlternativeCarrierRecord
import org.multipaz.nfc.HandoverCarrierRecords
import org.multipaz.nfc.NdefRecord
import org.multipaz.nfc.Nfc
import org.multipaz.util.ByteDataReader
import org.multipaz.util.Logger
import org.multipaz.util.UUID
import org.multipaz.util.appendByteArray
import org.multipaz.util.appendUInt64Le
import org.multipaz.util.appendUInt8

private const val TAG = "BleConnectionMethodCodec"

private const val OPTION_KEY_GATT_PERIPHERAL = 0L
private const val OPTION_KEY_GATT_CENTRAL = 1L
private const val OPTION_KEY_DIRECT_L2CAP_PERIPHERAL = 2L
private const val OPTION_KEY_DIRECT_L2CAP_CENTRAL = 3L
private const val OPTION_KEY_PERIPHERAL_MODE_UUID = 10L
private const val OPTION_KEY_CENTRAL_MODE_UUID = 11L
private const val OPTION_KEY_PERIPHERAL_ADDRESS = 20L
private const val OPTION_KEY_DIRECT_L2CAP_PSM = 21L

private const val CH_BLE_OPTION_KEY_DIRECT_L2CAP_PSM = 0L
private const val CH_BLE_OPTION_KEY_GATT = 1L
private const val CH_BLE_OPTION_KEY_DIRECT_L2CAP = 2L

// Bluetooth Assigned Numbers / Core Specification Supplement AD types
private const val BLE_LE_COMPLETE_LIST_OF_128_BIT_SERVICE_CLASS_UUIDS: UByte = 0x07u
private const val BLE_LE_BLUETOOTH_DEVICE_ADDRESS: UByte = 0x1Bu
private const val BLE_LE_ROLE: UByte = 0x1Cu

// Bluetooth Core Specification Supplement section 1.17.2 LE Role values
private const val BLE_LE_ROLE_ONLY_PERIPHERAL: UByte = 0x00u
private const val BLE_LE_ROLE_ONLY_CENTRAL: UByte = 0x01u
private const val BLE_LE_ROLE_PERIPHERAL_AND_CENTRAL_PERIPHERAL_PREFERRED: UByte = 0x02u
private const val BLE_LE_ROLE_PERIPHERAL_AND_CENTRAL_CENTRAL_PREFERRED: UByte = 0x03u

private class CoalescedBleModes(
    val gattPeripheral: Boolean,
    val gattCentral: Boolean,
    val directL2capPeripheral: Boolean,
    val directL2capCentral: Boolean,
    val peripheralModeUuid: UUID?,
    val centralModeUuid: UUID?,
    val peripheralPsm: Int?,
    val centralPsm: Int?,
    val mdocPeripheralAddress: BlePeripheralAddress?,
    val mdocCentralAddress: BlePeripheralAddress?,
) {
    val hasPeripheralMode: Boolean get() = gattPeripheral || directL2capPeripheral
    val hasCentralMode: Boolean get() = gattCentral || directL2capCentral
    val hasGatt: Boolean get() = gattPeripheral || gattCentral
    val hasDirectL2cap: Boolean get() = directL2capPeripheral || directL2capCentral
    val enabledModeCount: Int
        get() = (if (gattPeripheral) 1 else 0) +
            (if (gattCentral) 1 else 0) +
            (if (directL2capPeripheral) 1 else 0) +
            (if (directL2capCentral) 1 else 0)
}

private fun coalesceBleModes(bleMethods: List<MdocConnectionMethod.Ble>): CoalescedBleModes {
    require(bleMethods.isNotEmpty()) { "At least one BLE connection method is required" }

    var gattPeripheral = false
    var gattCentral = false
    var directL2capPeripheral = false
    var directL2capCentral = false
    var peripheralModeUuid: UUID? = null
    var centralModeUuid: UUID? = null
    var peripheralPsm: Int? = null
    var centralPsm: Int? = null
    var mdocPeripheralAddress: BlePeripheralAddress? = null
    var mdocCentralAddress: BlePeripheralAddress? = null

    for (ble in bleMethods) {
        when (ble) {
            is MdocConnectionMethod.Ble.GattPeripheral -> gattPeripheral = true
            is MdocConnectionMethod.Ble.GattCentral -> gattCentral = true
            is MdocConnectionMethod.Ble.DirectL2capPeripheral -> {
                directL2capPeripheral = true
                if (ble.psm != null) {
                    require(peripheralPsm == null || peripheralPsm == ble.psm) {
                        "Conflicting psm values in DirectL2capPeripheral connection methods"
                    }
                    peripheralPsm = ble.psm
                }
            }
            is MdocConnectionMethod.Ble.DirectL2capCentral -> {
                directL2capCentral = true
                if (ble.psm != null) {
                    require(centralPsm == null || centralPsm == ble.psm) {
                        "Conflicting psm values in DirectL2capCentral connection methods"
                    }
                    centralPsm = ble.psm
                }
            }
        }
        if (ble.isMdocPeripheral) {
            if (ble.uuid != null) {
                require(peripheralModeUuid == null || peripheralModeUuid == ble.uuid) {
                    "Conflicting peripheralModeUuid values in BLE connection methods"
                }
                peripheralModeUuid = ble.uuid
            }
            if (ble.peripheralAddress != null) {
                require(mdocPeripheralAddress == null || mdocPeripheralAddress == ble.peripheralAddress) {
                    "Conflicting peripheralAddress values in mdoc peripheral BLE connection methods"
                }
                mdocPeripheralAddress = ble.peripheralAddress
            }
        } else {
            if (ble.uuid != null) {
                require(centralModeUuid == null || centralModeUuid == ble.uuid) {
                    "Conflicting centralModeUuid values in BLE connection methods"
                }
                centralModeUuid = ble.uuid
            }
            if (ble.peripheralAddress != null) {
                require(mdocCentralAddress == null || mdocCentralAddress == ble.peripheralAddress) {
                    "Conflicting peripheralAddress values in mdoc central BLE connection methods"
                }
                mdocCentralAddress = ble.peripheralAddress
            }
        }
    }

    return CoalescedBleModes(
        gattPeripheral = gattPeripheral,
        gattCentral = gattCentral,
        directL2capPeripheral = directL2capPeripheral,
        directL2capCentral = directL2capCentral,
        peripheralModeUuid = peripheralModeUuid,
        centralModeUuid = centralModeUuid,
        peripheralPsm = peripheralPsm,
        centralPsm = centralPsm,
        mdocPeripheralAddress = mdocPeripheralAddress,
        mdocCentralAddress = mdocCentralAddress,
    )
}

private fun validateBleEngagementContext(
    coalesced: CoalescedBleModes,
    engagementType: MdocEngagementType,
) {
    when (engagementType) {
        MdocEngagementType.DEVICE_QR -> {
            require(!coalesced.directL2capCentral) {
                "DirectL2capCentral cannot be used in one-way DEVICE_QR engagement"
            }
            if (coalesced.hasPeripheralMode) {
                require(coalesced.peripheralModeUuid != null) {
                    "uuid is required in DEVICE_QR when an mdoc peripheral BLE mode is enabled"
                }
            }
            if (coalesced.hasCentralMode) {
                require(coalesced.centralModeUuid != null) {
                    "uuid is required in DEVICE_QR when an mdoc central BLE mode is enabled"
                }
            }
            if (coalesced.directL2capPeripheral) {
                require(coalesced.peripheralPsm != null) {
                    "psm is required in DEVICE_QR for DirectL2capPeripheral"
                }
            }
        }
        MdocEngagementType.READER_QR -> {
            require(!coalesced.directL2capPeripheral) {
                "DirectL2capPeripheral cannot be used in one-way READER_QR engagement"
            }
            if (coalesced.hasPeripheralMode) {
                require(coalesced.peripheralModeUuid != null) {
                    "uuid is required in READER_QR when GattPeripheral is enabled"
                }
            }
            if (coalesced.hasCentralMode) {
                require(coalesced.centralModeUuid != null) {
                    "uuid is required in READER_QR when an mdoc central BLE mode is enabled"
                }
            }
            if (coalesced.directL2capCentral) {
                require(coalesced.centralPsm != null) {
                    "psm is required in READER_QR for DirectL2capCentral"
                }
            }
        }
        MdocEngagementType.CCE_REQUEST -> {
            if (coalesced.hasCentralMode) {
                require(coalesced.centralModeUuid != null) {
                    "uuid is required in CCE_REQUEST when an mdoc central BLE mode is enabled"
                }
            }
            if (coalesced.directL2capCentral) {
                require(coalesced.centralPsm != null) {
                    "psm is required in CCE_REQUEST for DirectL2capCentral"
                }
            }
        }
        MdocEngagementType.CCE_SELECT -> {
            require(coalesced.enabledModeCount == 1) {
                "Exactly one BLE mode must be selected in CCE_SELECT, got ${coalesced.enabledModeCount}"
            }
            if (coalesced.hasPeripheralMode) {
                require(coalesced.peripheralModeUuid != null) {
                    "uuid is required in CCE_SELECT when selecting an mdoc peripheral BLE mode"
                }
            }
            if (coalesced.directL2capPeripheral) {
                require(coalesced.peripheralPsm != null) {
                    "psm is required in CCE_SELECT when selecting DirectL2capPeripheral"
                }
            }
        }
    }
}

internal fun encodeBleDeviceRetrievalMethod(
    bleMethods: List<MdocConnectionMethod.Ble>,
    engagementType: MdocEngagementType,
): DataItem {
    val coalesced = coalesceBleModes(bleMethods)
    validateBleEngagementContext(coalesced, engagementType)

    val senderIsMdoc = engagementType == MdocEngagementType.DEVICE_QR ||
        engagementType == MdocEngagementType.CCE_SELECT
    val includePeripheralUuid = coalesced.hasPeripheralMode &&
        engagementType != MdocEngagementType.CCE_REQUEST
    val includeCentralUuid = coalesced.hasCentralMode &&
        engagementType != MdocEngagementType.CCE_SELECT
    val psmToEmit = if (senderIsMdoc) {
        if (coalesced.directL2capPeripheral) coalesced.peripheralPsm else null
    } else {
        if (coalesced.directL2capCentral) coalesced.centralPsm else null
    }
    val addressToEmit = if (senderIsMdoc) {
        if (coalesced.hasPeripheralMode) coalesced.mdocPeripheralAddress else null
    } else {
        if (coalesced.hasCentralMode) coalesced.mdocCentralAddress else null
    }

    return buildCborArray {
        add(MdocConnectionMethod.Ble.METHOD_TYPE)
        add(MdocConnectionMethod.Ble.METHOD_MAX_VERSION)
        addCborMap {
            put(OPTION_KEY_GATT_PERIPHERAL, coalesced.gattPeripheral)
            put(OPTION_KEY_GATT_CENTRAL, coalesced.gattCentral)
            if (coalesced.hasDirectL2cap) {
                put(OPTION_KEY_DIRECT_L2CAP_PERIPHERAL, coalesced.directL2capPeripheral)
                put(OPTION_KEY_DIRECT_L2CAP_CENTRAL, coalesced.directL2capCentral)
            }
            if (includePeripheralUuid) {
                put(OPTION_KEY_PERIPHERAL_MODE_UUID, coalesced.peripheralModeUuid!!.toByteArray())
            }
            if (includeCentralUuid) {
                put(OPTION_KEY_CENTRAL_MODE_UUID, coalesced.centralModeUuid!!.toByteArray())
            }
            if (addressToEmit != null) {
                put(OPTION_KEY_PERIPHERAL_ADDRESS, addressToEmit.toEncoded())
            }
            if (psmToEmit != null) {
                put(OPTION_KEY_DIRECT_L2CAP_PSM, psmToEmit)
            }
        }
    }
}

private fun buildDecodedBleList(
    gattPeripheral: Boolean,
    gattCentral: Boolean,
    directL2capPeripheral: Boolean,
    directL2capCentral: Boolean,
    peripheralModeUuid: UUID?,
    centralModeUuid: UUID?,
    wirePsm: Int?,
    wireAddress: BlePeripheralAddress?,
    senderRole: MdocRole,
): List<MdocConnectionMethod.Ble> {
    val mdocPeripheralAddr = if (senderRole == MdocRole.MDOC) wireAddress else null
    val mdocCentralAddr = if (senderRole == MdocRole.MDOC_READER) wireAddress else null
    val mdocPeripheralPsm = if (senderRole == MdocRole.MDOC) wirePsm else null
    val mdocCentralPsm = if (senderRole == MdocRole.MDOC_READER) wirePsm else null

    return buildList {
        if (directL2capCentral) {
            add(
                MdocConnectionMethod.Ble.DirectL2capCentral(
                    uuid = centralModeUuid,
                    psm = mdocCentralPsm,
                    peripheralAddress = mdocCentralAddr,
                )
            )
        }
        if (directL2capPeripheral) {
            add(
                MdocConnectionMethod.Ble.DirectL2capPeripheral(
                    uuid = peripheralModeUuid,
                    psm = mdocPeripheralPsm,
                    peripheralAddress = mdocPeripheralAddr,
                )
            )
        }
        if (gattCentral) {
            add(
                MdocConnectionMethod.Ble.GattCentral(
                    uuid = centralModeUuid,
                    peripheralAddress = mdocCentralAddr,
                )
            )
        }
        if (gattPeripheral) {
            add(
                MdocConnectionMethod.Ble.GattPeripheral(
                    uuid = peripheralModeUuid,
                    peripheralAddress = mdocPeripheralAddr,
                )
            )
        }
    }
}

internal fun decodeBleDeviceRetrievalMethod(
    array: DataItem,
    senderRole: MdocRole,
): List<MdocConnectionMethod.Ble> {
    val type = array[0].asNumber
    val version = array[1].asNumber
    require(type == MdocConnectionMethod.Ble.METHOD_TYPE)
    if (version > MdocConnectionMethod.Ble.METHOD_MAX_VERSION) {
        return emptyList()
    }
    val map = array[2]
    require(map.hasKey(OPTION_KEY_GATT_PERIPHERAL) && map.hasKey(OPTION_KEY_GATT_CENTRAL)) {
        "BleOptions must contain keys 0 (GattPeripheral) and 1 (GattCentral)"
    }
    val gattPeripheral = map[OPTION_KEY_GATT_PERIPHERAL].asBoolean
    val gattCentral = map[OPTION_KEY_GATT_CENTRAL].asBoolean
    val hasKey2 = map.hasKey(OPTION_KEY_DIRECT_L2CAP_PERIPHERAL)
    val hasKey3 = map.hasKey(OPTION_KEY_DIRECT_L2CAP_CENTRAL)
    require(hasKey2 == hasKey3) {
        "BleOptions keys 2 (DirectL2capPeripheral) and 3 (DirectL2capCentral) must both be present or both omitted"
    }
    val directL2capPeripheral = if (hasKey2) map[OPTION_KEY_DIRECT_L2CAP_PERIPHERAL].asBoolean else false
    val directL2capCentral = if (hasKey3) map[OPTION_KEY_DIRECT_L2CAP_CENTRAL].asBoolean else false
    require(gattPeripheral || gattCentral || directL2capPeripheral || directL2capCentral) {
        "At least one mode in BleOptions must be true"
    }
    val peripheralAddress = map.getOrNull(OPTION_KEY_PERIPHERAL_ADDRESS)?.let {
        BlePeripheralAddress.fromEncoded(it.asBstr)
    }
    val peripheralModeUuid = map.getOrNull(OPTION_KEY_PERIPHERAL_MODE_UUID)?.let {
        UUID.fromByteArray(it.asBstr)
    }
    val centralModeUuid = map.getOrNull(OPTION_KEY_CENTRAL_MODE_UUID)?.let {
        UUID.fromByteArray(it.asBstr)
    }
    val directL2capPsm = map.getOrNull(OPTION_KEY_DIRECT_L2CAP_PSM)?.asNumber?.toInt()

    return buildDecodedBleList(
        gattPeripheral = gattPeripheral,
        gattCentral = gattCentral,
        directL2capPeripheral = directL2capPeripheral,
        directL2capCentral = directL2capCentral,
        peripheralModeUuid = peripheralModeUuid,
        centralModeUuid = centralModeUuid,
        wirePsm = directL2capPsm,
        wireAddress = peripheralAddress,
        senderRole = senderRole,
    )
}

internal fun encodeBleNdefHandoverCarrier(
    bleMethods: List<MdocConnectionMethod.Ble>,
    handoverType: MdocNfcHandoverType,
    sharedAuxiliaryReferences: List<String>,
    hrHadChBleOptions: Boolean,
): HandoverCarrierRecords {
    val coalesced = coalesceBleModes(bleMethods)
    val senderIsMdoc = handoverType != MdocNfcHandoverType.NEGOTIATED_HR

    val leRole: UByte
    val uuidToEmit: UUID?
    when (handoverType) {
        MdocNfcHandoverType.STATIC_HS -> {
            require(!coalesced.directL2capCentral) {
                "DirectL2capCentral cannot be used in Static Handover Select"
            }
            if (coalesced.hasCentralMode && coalesced.hasPeripheralMode) {
                require(coalesced.centralModeUuid != null && coalesced.peripheralModeUuid != null) {
                    "Both peripheral and central BLE UUIDs must be set for dual-role Static Handover"
                }
                require(coalesced.centralModeUuid == coalesced.peripheralModeUuid) {
                    "UUIDs for both BLE modes are not the same"
                }
                uuidToEmit = coalesced.centralModeUuid
                leRole = BLE_LE_ROLE_PERIPHERAL_AND_CENTRAL_CENTRAL_PREFERRED
            } else if (coalesced.hasCentralMode) {
                require(coalesced.centralModeUuid != null) {
                    "uuid is required when mdoc offers GattCentral in Static Handover"
                }
                uuidToEmit = coalesced.centralModeUuid
                leRole = BLE_LE_ROLE_ONLY_CENTRAL
            } else {
                require(coalesced.peripheralModeUuid != null) {
                    "uuid is required when mdoc offers peripheral BLE mode in Static Handover"
                }
                uuidToEmit = coalesced.peripheralModeUuid
                leRole = BLE_LE_ROLE_ONLY_PERIPHERAL
            }
        }
        MdocNfcHandoverType.NEGOTIATED_HR -> {
            if (coalesced.hasCentralMode && coalesced.hasPeripheralMode) {
                require(coalesced.centralModeUuid != null) {
                    "uuid is required when reader offers central BLE modes in Handover Request"
                }
                require(
                    coalesced.peripheralModeUuid == null ||
                        coalesced.centralModeUuid == coalesced.peripheralModeUuid
                ) {
                    "UUIDs for both BLE modes are not the same"
                }
                uuidToEmit = coalesced.centralModeUuid
                leRole = BLE_LE_ROLE_PERIPHERAL_AND_CENTRAL_PERIPHERAL_PREFERRED
            } else if (coalesced.hasCentralMode) {
                require(coalesced.centralModeUuid != null) {
                    "uuid is required when reader offers central BLE mode in Handover Request"
                }
                uuidToEmit = coalesced.centralModeUuid
                leRole = BLE_LE_ROLE_ONLY_PERIPHERAL
            } else {
                // Per Clause 11.1.3, when reader only supports mdoc peripheral modes in Hr, UUID is NOT sent.
                uuidToEmit = null
                leRole = BLE_LE_ROLE_ONLY_CENTRAL
            }
        }
        MdocNfcHandoverType.NEGOTIATED_HS -> {
            require(coalesced.enabledModeCount == 1) {
                "Exactly one BLE mode must be selected in NEGOTIATED_HS, got ${coalesced.enabledModeCount}"
            }
            if (coalesced.hasCentralMode) {
                // Per Clause 11.1.3, mdoc central mode omits UUID in Negotiated Hs.
                uuidToEmit = null
                leRole = BLE_LE_ROLE_ONLY_CENTRAL
            } else {
                require(coalesced.peripheralModeUuid != null) {
                    "uuid is required when mdoc selects peripheral BLE mode in Negotiated Handover Select"
                }
                uuidToEmit = coalesced.peripheralModeUuid
                leRole = BLE_LE_ROLE_ONLY_PERIPHERAL
            }
        }
    }

    val addressToEmit = if (senderIsMdoc) {
        if (coalesced.hasPeripheralMode) coalesced.mdocPeripheralAddress else null
    } else {
        if (coalesced.hasCentralMode) coalesced.mdocCentralAddress else null
    }

    val oobData = buildByteString {
        appendUInt8(0x02) // Length
        appendUInt8(BLE_LE_ROLE)
        appendUInt8(leRole)
        if (uuidToEmit != null) {
            appendUInt8(0x11) // Length
            appendUInt8(BLE_LE_COMPLETE_LIST_OF_128_BIT_SERVICE_CLASS_UUIDS)
            appendUInt64Le(uuidToEmit.leastSignificantBits)
            appendUInt64Le(uuidToEmit.mostSignificantBits)
        }
        if (addressToEmit != null) {
            val encodedAddress = addressToEmit.toEncoded()
            appendUInt8(1 + encodedAddress.size) // 0x08
            appendUInt8(BLE_LE_BLUETOOTH_DEVICE_ADDRESS)
            appendByteArray(encodedAddress)
        }
    }

    val carrierRecord = NdefRecord(
        NdefRecord.Tnf.MIME_MEDIA,
        Nfc.MIME_TYPE_CONNECTION_HANDOVER_BLE.encodeToByteString(),
        MdocConnectionMethod.Ble.CARRIER_DATA_REFERENCE_BLE.encodeToByteString(),
        oobData
    )

    val emitChBleOptions = coalesced.hasDirectL2cap ||
        (handoverType == MdocNfcHandoverType.NEGOTIATED_HS && hrHadChBleOptions)
    val auxiliaryRecords = mutableListOf<NdefRecord>()
    val effectiveAuxiliaryReferences = if (emitChBleOptions) {
        val psmToEmit = if (senderIsMdoc) {
            if (coalesced.directL2capPeripheral) {
                requireNotNull(coalesced.peripheralPsm) {
                    "psm is required when advertising DirectL2capPeripheral in ChBleOptions"
                }
            } else {
                null
            }
        } else {
            if (coalesced.directL2capCentral) {
                requireNotNull(coalesced.centralPsm) {
                    "psm is required when advertising DirectL2capCentral in ChBleOptions"
                }
            } else {
                null
            }
        }
        val encodedChBleOptions = Cbor.encode(
            buildCborMap {
                if (psmToEmit != null) {
                    put(CH_BLE_OPTION_KEY_DIRECT_L2CAP_PSM, psmToEmit)
                }
                put(CH_BLE_OPTION_KEY_GATT, coalesced.hasGatt)
                put(CH_BLE_OPTION_KEY_DIRECT_L2CAP, coalesced.hasDirectL2cap)
            }
        )
        auxiliaryRecords.add(
            NdefRecord(
                tnf = NdefRecord.Tnf.EXTERNAL_TYPE,
                type = Nfc.EXTERNAL_TYPE_ISO_18013_5_CH_BLE_OPTIONS.encodeToByteString(),
                id = MdocConnectionMethod.Ble.AUXILIARY_DATA_REFERENCE_CH_BLE_OPTIONS.encodeToByteString(),
                payload = ByteString(encodedChBleOptions)
            )
        )
        val chBleRef = MdocConnectionMethod.Ble.AUXILIARY_DATA_REFERENCE_CH_BLE_OPTIONS
        if (chBleRef in sharedAuxiliaryReferences) {
            sharedAuxiliaryReferences
        } else {
            sharedAuxiliaryReferences + chBleRef
        }
    } else {
        sharedAuxiliaryReferences
    }

    val alternativeCarrierRecord = AlternativeCarrierRecord(
        carrierDataReference = MdocConnectionMethod.Ble.CARRIER_DATA_REFERENCE_BLE,
        auxiliaryDataReferences = effectiveAuxiliaryReferences,
    )
    return HandoverCarrierRecords(
        carrierRecord = carrierRecord,
        alternativeCarrierRecord = alternativeCarrierRecord,
        auxiliaryRecords = auxiliaryRecords,
    )
}

internal fun decodeBleNdefHandoverCarrier(
    record: NdefRecord,
    senderRole: MdocRole,
    fallbackUuid: UUID?,
    auxiliaryRecords: List<NdefRecord>,
): List<MdocConnectionMethod.Ble> {
    var oobCentral = false
    var oobPeripheral = false
    var uuid: UUID? = fallbackUuid
    var gotLeRole = false
    var peripheralAddress: BlePeripheralAddress? = null

    val reader = ByteDataReader(record.payload)
    while (!reader.exhausted()) {
        val len = reader.getUInt8()
        if (len == 0u.toUByte()) {
            continue
        }
        val type = reader.getUInt8()
        if (type == BLE_LE_ROLE && len == 2u.toUByte()) {
            gotLeRole = true
            when (val value = reader.getUInt8()) {
                BLE_LE_ROLE_ONLY_PERIPHERAL -> {
                    if (senderRole == MdocRole.MDOC) oobPeripheral = true else oobCentral = true
                }
                BLE_LE_ROLE_ONLY_CENTRAL -> {
                    if (senderRole == MdocRole.MDOC) oobCentral = true else oobPeripheral = true
                }
                BLE_LE_ROLE_PERIPHERAL_AND_CENTRAL_PERIPHERAL_PREFERRED,
                BLE_LE_ROLE_PERIPHERAL_AND_CENTRAL_CENTRAL_PREFERRED -> {
                    oobCentral = true
                    oobPeripheral = true
                }
                else -> {
                    Logger.w(TAG, "Invalid value $value for LE role")
                    return emptyList()
                }
            }
        } else if (type == BLE_LE_COMPLETE_LIST_OF_128_BIT_SERVICE_CLASS_UUIDS) {
            val uuidLen = len - 1u
            if (uuidLen % 16u != 0u) {
                Logger.w(TAG, "UUID len $uuidLen is not divisible by 16")
                return emptyList()
            }
            if (uuidLen > 16u) {
                reader.skip((16u * (uuidLen / 16u - 1u)).toInt())
            }
            val lsb = reader.getUInt64Le()
            val msb = reader.getUInt64Le()
            uuid = UUID(msb, lsb)
        } else if (type == BLE_LE_BLUETOOTH_DEVICE_ADDRESS && len == 8u.toUByte()) {
            val encodedAddr = reader.getByteString(7).toByteArray()
            peripheralAddress = try {
                BlePeripheralAddress.fromEncoded(encodedAddr)
            } catch (e: IllegalArgumentException) {
                Logger.w(TAG, "Invalid BLE peripheral address in OOB record", e)
                return emptyList()
            }
        } else {
            Logger.d(TAG, "Skipping unknown AD type $type of length $len")
            reader.skip((len - 1u).toInt())
        }
    }
    if (!gotLeRole) {
        Logger.w(TAG, "Did not find LE role")
        return emptyList()
    }

    val chBleRecord = auxiliaryRecords.find {
        it.tnf == NdefRecord.Tnf.EXTERNAL_TYPE &&
            it.type.decodeToString() == Nfc.EXTERNAL_TYPE_ISO_18013_5_CH_BLE_OPTIONS &&
            it.id.decodeToString() == MdocConnectionMethod.Ble.AUXILIARY_DATA_REFERENCE_CH_BLE_OPTIONS
    }

    val gattPeripheral: Boolean
    val gattCentral: Boolean
    val directL2capPeripheral: Boolean
    val directL2capCentral: Boolean
    val psm: Int?

    if (chBleRecord == null) {
        gattPeripheral = oobPeripheral
        gattCentral = oobCentral
        directL2capPeripheral = false
        directL2capCentral = false
        psm = null
    } else {
        val map = Cbor.decode(chBleRecord.payload.toByteArray())
        val gatt = map[CH_BLE_OPTION_KEY_GATT].asBoolean
        val directL2cap = map[CH_BLE_OPTION_KEY_DIRECT_L2CAP].asBoolean
        psm = map.getOrNull(CH_BLE_OPTION_KEY_DIRECT_L2CAP_PSM)?.asNumber?.toInt()
        gattPeripheral = oobPeripheral && gatt
        gattCentral = oobCentral && gatt
        when (senderRole) {
            MdocRole.MDOC -> {
                directL2capPeripheral = oobPeripheral && directL2cap && psm != null
                directL2capCentral = oobCentral && !oobPeripheral && directL2cap
            }
            MdocRole.MDOC_READER -> {
                directL2capPeripheral = oobPeripheral && directL2cap
                directL2capCentral = oobCentral && directL2cap && psm != null
            }
        }
    }

    if (!gattPeripheral && !gattCentral && !directL2capPeripheral && !directL2capCentral) {
        Logger.w(TAG, "No enabled BLE modes after decoding BLE OOB + ChBleOptions")
        return emptyList()
    }

    return buildDecodedBleList(
        gattPeripheral = gattPeripheral,
        gattCentral = gattCentral,
        directL2capPeripheral = directL2capPeripheral,
        directL2capCentral = directL2capCentral,
        peripheralModeUuid = if (senderRole == MdocRole.MDOC && (gattPeripheral || directL2capPeripheral)) uuid else null,
        centralModeUuid = if (gattCentral || directL2capCentral) uuid else null,
        wirePsm = psm,
        wireAddress = peripheralAddress,
        senderRole = senderRole,
    )
}
