package org.multipaz.mdoc.connectionmethod

import kotlinx.io.bytestring.ByteString
import kotlinx.io.bytestring.buildByteString
import kotlinx.io.bytestring.decodeToString
import kotlinx.io.bytestring.encodeToByteString
import org.multipaz.cbor.Cbor
import org.multipaz.cbor.addCborMap
import org.multipaz.cbor.buildCborArray
import org.multipaz.cbor.buildCborMap
import org.multipaz.mdoc.role.MdocRole
import org.multipaz.nfc.NdefRecord
import org.multipaz.nfc.Nfc
import org.multipaz.util.ByteDataReader
import org.multipaz.util.Logger
import org.multipaz.util.UUID
import org.multipaz.util.appendByteArray
import org.multipaz.util.appendUInt64Le
import org.multipaz.util.appendUInt8

/**
 * Engagement context in which a `DeviceRetrievalMethod` CBOR structure is encoded or decoded.
 *
 * Reference: ISO/IEC 18013-5 Second Edition clauses 9.2, 9.3, 9.5, and 11.1.2.
 */
enum class MdocEngagementType {
    /**
     * Forward one-way engagement in a `DeviceEngagement` QR code (`mdoc:` URI).
     */
    DEVICE_QR,

    /**
     * Reverse one-way engagement in a `ReaderEngagement` QR code (`mdoc:` URI).
     */
    READER_QR,

    /**
     * NFC Concurrent Channel Engagement (CCE) `CCEHandoverRequest` sent from mdoc reader to mdoc.
     */
    CCE_REQUEST,

    /**
     * NFC Concurrent Channel Engagement (CCE) `CCEHandoverSelect` sent from mdoc to mdoc reader.
     */
    CCE_SELECT,
}

/**
 * Bluetooth LE device address type according to Bluetooth Core Specification Supplement section 1.16.
 *
 * @property code the 1-byte wire value (`0x00` for public, `0x01` for random).
 */
enum class BleAddressType(val code: Int) {
    /**
     * Public Device Address (`0x00`).
     */
    PUBLIC(0x00),

    /**
     * Random Device Address (`0x01`).
     */
    RANDOM(0x01);

    companion object {
        /**
         * Resolves a [BleAddressType] from its 1-byte wire code.
         *
         * @param code the 1-byte address type code.
         * @return the corresponding [BleAddressType].
         * @throws IllegalArgumentException if [code] is not `0x00` or `0x01`.
         */
        fun fromCode(code: Int): BleAddressType =
            entries.find { it.code == code }
                ?: throw IllegalArgumentException("Invalid BLE address type code: $code")
    }
}

/**
 * Bluetooth LE Peripheral Device Address (`BD_ADDR` + address type) used in `BleOptions` key `20`
 * and BLE OOB AD type `0x1B` (Bluetooth Core Specification Supplement section 1.16).
 *
 * @property address the 6-byte Bluetooth MAC address in standard big-endian byte order
 *   (e.g. `11:22:33:44:55:66` is `ByteString(0x11, 0x22, 0x33, 0x44, 0x55, 0x66)`).
 * @property addressType whether [address] is a [BleAddressType.PUBLIC] or [BleAddressType.RANDOM] address.
 */
data class BlePeripheralAddress(
    val address: ByteString,
    val addressType: BleAddressType,
) {
    init {
        require(address.size == 6) {
            "BLE MAC address should be 6 bytes, got ${address.size}"
        }
    }

    /**
     * Encodes this address to the 7-byte Bluetooth CSS section 1.16 wire representation
     * (6-byte little-endian `BD_ADDR` followed by 1-byte address type).
     *
     * @return the 7-byte encoded peripheral address.
     */
    fun toEncoded(): ByteArray = byteArrayOf(
        address[5],
        address[4],
        address[3],
        address[2],
        address[1],
        address[0],
        addressType.code.toByte(),
    )

    override fun toString(): String {
        val hexDigits = "0123456789abcdef"
        val sb = StringBuilder()
        for (n in 0..5) {
            if (n > 0) {
                sb.append("-")
            }
            val value = address[n].toInt() and 0xff
            sb.append(hexDigits[value shr 4])
            sb.append(hexDigits[value and 0x0f])
        }
        sb.append("/")
        sb.append(addressType.name.lowercase())
        return sb.toString()
    }

    companion object {
        /**
         * Decodes a 7-byte Bluetooth CSS section 1.16 wire representation into a [BlePeripheralAddress].
         *
         * @param encoded the 7-byte encoded peripheral address (6-byte little-endian `BD_ADDR` + 1-byte type).
         * @return the decoded [BlePeripheralAddress].
         * @throws IllegalArgumentException if [encoded] is not 7 bytes or has an invalid address type.
         */
        fun fromEncoded(encoded: ByteArray): BlePeripheralAddress {
            require(encoded.size == 7) {
                "Encoded BLE peripheral address must be 7 bytes, got ${encoded.size}"
            }
            val bigEndianMac = ByteString(
                encoded[5],
                encoded[4],
                encoded[3],
                encoded[2],
                encoded[1],
                encoded[0],
            )
            val addressType = BleAddressType.fromCode(encoded[6].toInt() and 0xff)
            return BlePeripheralAddress(bigEndianMac, addressType)
        }
    }
}

/**
 * Connection method for BLE according to ISO/IEC 18013-5 Second Edition clauses 11.1.1–11.1.3.
 *
 * All four mode flags follow the mdoc-perspective naming from `BleOptions`:
 * - [gattPeripheral] (`0`): mdoc GATT peripheral server mode (mdoc is BLE Peripheral / GATT server).
 * - [gattCentral] (`1`): mdoc GATT central client mode (mdoc reader is BLE Peripheral / GATT server).
 * - [directL2capPeripheral] (`2`): mdoc BLE direct L2CAP peripheral mode (mdoc is BLE Peripheral / L2CAP server).
 * - [directL2capCentral] (`3`): mdoc BLE direct L2CAP central mode (mdoc reader is BLE Peripheral / L2CAP server).
 *
 * @property gattPeripheral whether mdoc GATT peripheral server mode is supported/selected.
 * @property gattCentral whether mdoc GATT central client mode is supported/selected.
 * @property directL2capPeripheral whether mdoc direct L2CAP peripheral mode is supported/selected.
 * @property directL2capCentral whether mdoc direct L2CAP central mode is supported/selected.
 * @property peripheralModeUuid the service UUID for mdoc peripheral modes ([gattPeripheral] / [directL2capPeripheral]), if known.
 * @property centralModeUuid the service UUID for mdoc central modes ([gattCentral] / [directL2capCentral]), if known.
 * @property directL2capPsm the L2CAP PSM of whichever peer acts as BLE Peripheral / L2CAP server, if known.
 * @property peripheralAddress the 7-byte BLE peripheral device address of whichever peer acts as BLE Peripheral, if known.
 */
data class MdocConnectionMethodBle(
    val gattPeripheral: Boolean,
    val gattCentral: Boolean,
    val directL2capPeripheral: Boolean = false,
    val directL2capCentral: Boolean = false,
    val peripheralModeUuid: UUID? = null,
    val centralModeUuid: UUID? = null,
    val directL2capPsm: Int? = null,
    val peripheralAddress: BlePeripheralAddress? = null,
) : MdocConnectionMethod() {

    /**
     * Whether either mdoc peripheral mode ([gattPeripheral] or [directL2capPeripheral]) is enabled.
     */
    val hasPeripheralMode: Boolean
        get() = gattPeripheral || directL2capPeripheral

    /**
     * Whether either mdoc central mode ([gattCentral] or [directL2capCentral]) is enabled.
     */
    val hasCentralMode: Boolean
        get() = gattCentral || directL2capCentral

    /**
     * Whether either GATT mode ([gattPeripheral] or [gattCentral]) is enabled.
     */
    val hasGatt: Boolean
        get() = gattPeripheral || gattCentral

    /**
     * Whether either direct L2CAP mode ([directL2capPeripheral] or [directL2capCentral]) is enabled.
     */
    val hasDirectL2cap: Boolean
        get() = directL2capPeripheral || directL2capCentral

    /**
     * Number of BLE modes enabled on this instance (`0`..`4`).
     */
    val enabledModeCount: Int
        get() =
            (if (gattPeripheral) 1 else 0) +
                (if (gattCentral) 1 else 0) +
                (if (directL2capPeripheral) 1 else 0) +
                (if (directL2capCentral) 1 else 0)

    override fun toString(): String {
        val sb = StringBuilder("ble")
        if (enabledModeCount == 1) {
            when {
                gattPeripheral -> sb.append(":peripheral_server_mode:uuid=$peripheralModeUuid")
                gattCentral -> sb.append(":central_client_mode:uuid=$centralModeUuid")
                directL2capPeripheral -> sb.append(":peripheral_l2cap_mode:uuid=$peripheralModeUuid")
                directL2capCentral -> sb.append(":central_l2cap_mode:uuid=$centralModeUuid")
            }
        } else {
            if (gattPeripheral) {
                sb.append(":peripheral_server_mode:uuid=$peripheralModeUuid")
            }
            if (directL2capPeripheral) {
                sb.append(":peripheral_l2cap_mode:uuid=$peripheralModeUuid")
            }
            if (gattCentral) {
                sb.append(":central_client_mode:uuid=$centralModeUuid")
            }
            if (directL2capCentral) {
                sb.append(":central_l2cap_mode:uuid=$centralModeUuid")
            }
        }
        if (directL2capPsm != null) {
            sb.append(":psm=$directL2capPsm")
        }
        if (peripheralAddress != null) {
            sb.append(":addr=$peripheralAddress")
        }
        return sb.toString()
    }

    override fun toDeviceEngagement(engagementType: MdocEngagementType): ByteArray {
        require(enabledModeCount >= 1) {
            "At least one BLE mode must be enabled"
        }
        when (engagementType) {
            MdocEngagementType.DEVICE_QR -> {
                require(!directL2capCentral) {
                    "directL2capCentral cannot be true in one-way DEVICE_QR engagement"
                }
                if (hasPeripheralMode) {
                    require(peripheralModeUuid != null) {
                        "peripheralModeUuid is required in DEVICE_QR when a peripheral mode is enabled"
                    }
                }
                if (hasCentralMode) {
                    require(centralModeUuid != null) {
                        "centralModeUuid is required in DEVICE_QR when a central mode is enabled"
                    }
                }
                if (directL2capPeripheral) {
                    require(directL2capPsm != null) {
                        "directL2capPsm is required in DEVICE_QR when directL2capPeripheral is true"
                    }
                }
            }
            MdocEngagementType.READER_QR -> {
                require(!directL2capPeripheral) {
                    "directL2capPeripheral cannot be true in one-way READER_QR engagement"
                }
                if (hasPeripheralMode) {
                    require(peripheralModeUuid != null) {
                        "peripheralModeUuid is required in READER_QR when a peripheral mode is enabled"
                    }
                }
                if (hasCentralMode) {
                    require(centralModeUuid != null) {
                        "centralModeUuid is required in READER_QR when a central mode is enabled"
                    }
                }
                if (directL2capCentral) {
                    require(directL2capPsm != null) {
                        "directL2capPsm is required in READER_QR when directL2capCentral is true"
                    }
                }
            }
            MdocEngagementType.CCE_REQUEST -> {
                if (hasCentralMode) {
                    require(centralModeUuid != null) {
                        "centralModeUuid is required in CCE_REQUEST when a central mode is enabled"
                    }
                }
                if (directL2capCentral) {
                    require(directL2capPsm != null) {
                        "directL2capPsm is required in CCE_REQUEST when directL2capCentral is true"
                    }
                }
            }
            MdocEngagementType.CCE_SELECT -> {
                require(enabledModeCount == 1) {
                    "Exactly one BLE mode must be selected in CCE_SELECT, got $enabledModeCount"
                }
                if (hasPeripheralMode) {
                    require(peripheralModeUuid != null) {
                        "peripheralModeUuid is required in CCE_SELECT when selecting a peripheral mode"
                    }
                }
                if (directL2capPeripheral) {
                    require(directL2capPsm != null) {
                        "directL2capPsm is required in CCE_SELECT when selecting directL2capPeripheral"
                    }
                }
            }
        }

        val senderIsMdoc = engagementType == MdocEngagementType.DEVICE_QR ||
            engagementType == MdocEngagementType.CCE_SELECT
        val includePeripheralUuid = hasPeripheralMode && engagementType != MdocEngagementType.CCE_REQUEST
        val includeCentralUuid = hasCentralMode && engagementType != MdocEngagementType.CCE_SELECT
        val includePsm = if (senderIsMdoc) directL2capPeripheral else directL2capCentral
        val includePeripheralAddress = peripheralAddress != null &&
            (if (senderIsMdoc) hasPeripheralMode else hasCentralMode)

        return Cbor.encode(
            buildCborArray {
                add(METHOD_TYPE)
                add(METHOD_MAX_VERSION)
                addCborMap {
                    put(OPTION_KEY_GATT_PERIPHERAL, gattPeripheral)
                    put(OPTION_KEY_GATT_CENTRAL, gattCentral)
                    if (hasDirectL2cap) {
                        put(OPTION_KEY_DIRECT_L2CAP_PERIPHERAL, directL2capPeripheral)
                        put(OPTION_KEY_DIRECT_L2CAP_CENTRAL, directL2capCentral)
                    }
                    if (includePeripheralUuid) {
                        put(OPTION_KEY_PERIPHERAL_MODE_UUID, peripheralModeUuid!!.toByteArray())
                    }
                    if (includeCentralUuid) {
                        put(OPTION_KEY_CENTRAL_MODE_UUID, centralModeUuid!!.toByteArray())
                    }
                    if (includePeripheralAddress) {
                        put(OPTION_KEY_PERIPHERAL_ADDRESS, peripheralAddress!!.toEncoded())
                    }
                    if (includePsm) {
                        put(OPTION_KEY_DIRECT_L2CAP_PSM, directL2capPsm!!)
                    }
                }
            }
        )
    }

    override fun toNdefRecord(
        auxiliaryReferences: List<String>,
        role: MdocRole,
        skipUuids: Boolean,
        includeChBleOptions: Boolean,
    ): NdefRecords {
        require(enabledModeCount >= 1) {
            "At least one BLE mode must be enabled"
        }

        // OOB LE Role (0x1C) values from Bluetooth Core Specification Supplement section 1.17.2:
        //   0x00: Only Peripheral Role supported
        //   0x01: Only Central Role supported
        //   0x02: Peripheral and Central Roles supported, Peripheral Role preferred (reader in Hr)
        //   0x03: Peripheral and Central Roles supported, Central Role preferred (mdoc in Hs)
        val leRole: UByte
        val uuid: UUID?
        if (hasCentralMode && hasPeripheralMode) {
            when (role) {
                MdocRole.MDOC -> {
                    require(centralModeUuid != null && peripheralModeUuid != null) {
                        "Both peripheralModeUuid and centralModeUuid must be set for dual-role Static Handover"
                    }
                    require(centralModeUuid == peripheralModeUuid) {
                        "UUIDs for both BLE modes are not the same"
                    }
                    require(!directL2capCentral) {
                        "directL2capCentral cannot be true in Static Handover Select"
                    }
                    uuid = centralModeUuid
                    leRole = BLE_LE_ROLE_PERIPHERAL_AND_CENTRAL_CENTRAL_PREFERRED
                }
                MdocRole.MDOC_READER -> {
                    require(centralModeUuid != null) {
                        "centralModeUuid is required when reader offers central modes in Handover Request"
                    }
                    require(peripheralModeUuid == null || centralModeUuid == peripheralModeUuid) {
                        "UUIDs for both BLE modes are not the same"
                    }
                    uuid = centralModeUuid
                    leRole = BLE_LE_ROLE_PERIPHERAL_AND_CENTRAL_PERIPHERAL_PREFERRED
                }
            }
        } else if (hasCentralMode) {
            when (role) {
                MdocRole.MDOC -> {
                    // In Static Handover (!skipUuids), centralModeUuid is required; in Negotiated Hs (skipUuids),
                    // mdoc central mode omits the UUID per Clause 11.1.3.
                    if (!skipUuids) {
                        require(centralModeUuid != null) {
                            "centralModeUuid is required when mdoc offers central mode in Static Handover"
                        }
                    }
                    uuid = if (skipUuids) null else centralModeUuid
                    leRole = BLE_LE_ROLE_ONLY_CENTRAL
                }
                MdocRole.MDOC_READER -> {
                    require(centralModeUuid != null) {
                        "centralModeUuid is required when reader offers central mode in Handover Request"
                    }
                    uuid = centralModeUuid
                    leRole = BLE_LE_ROLE_ONLY_PERIPHERAL
                }
            }
        } else {
            // hasPeripheralMode only
            when (role) {
                MdocRole.MDOC -> {
                    require(peripheralModeUuid != null) {
                        "peripheralModeUuid is required when mdoc offers/selects peripheral mode in Handover Select"
                    }
                    uuid = if (skipUuids) null else peripheralModeUuid
                    leRole = BLE_LE_ROLE_ONLY_PERIPHERAL
                }
                MdocRole.MDOC_READER -> {
                    // Per Clause 11.1.3, when reader only supports peripheral modes in Hr, UUID is NOT sent.
                    uuid = null
                    leRole = BLE_LE_ROLE_ONLY_CENTRAL
                }
            }
        }

        val senderIsPeripheral = when (role) {
            MdocRole.MDOC -> hasPeripheralMode
            MdocRole.MDOC_READER -> hasCentralMode
        }

        val oobData = buildByteString {
            appendUInt8(0x02) // Length
            appendUInt8(BLE_LE_ROLE)
            appendUInt8(leRole)
            if (uuid != null && !skipUuids) {
                appendUInt8(0x11) // Length
                appendUInt8(BLE_LE_COMPLETE_LIST_OF_128_BIT_SERVICE_CLASS_UUIDS)
                appendUInt64Le(uuid.leastSignificantBits)
                appendUInt64Le(uuid.mostSignificantBits)
            }
            if (peripheralAddress != null && senderIsPeripheral) {
                val encodedAddress = peripheralAddress.toEncoded()
                appendUInt8(1 + encodedAddress.size) // 0x08
                appendUInt8(BLE_LE_BLUETOOTH_DEVICE_ADDRESS)
                appendByteArray(encodedAddress)
            }
        }

        val carrierRecord = NdefRecord(
            NdefRecord.Tnf.MIME_MEDIA,
            Nfc.MIME_TYPE_CONNECTION_HANDOVER_BLE.encodeToByteString(),
            CARRIER_DATA_REFERENCE_BLE.encodeToByteString(),
            oobData
        )

        val emitChBleOptions = hasDirectL2cap || includeChBleOptions
        val auxiliaryRecords = mutableListOf<NdefRecord>()
        val effectiveAuxiliaryReferences = if (emitChBleOptions) {
            val senderIsL2capServer = when (role) {
                MdocRole.MDOC -> directL2capPeripheral
                MdocRole.MDOC_READER -> directL2capCentral
            }
            if (senderIsL2capServer) {
                require(directL2capPsm != null) {
                    "directL2capPsm is required when advertising direct L2CAP server role in ChBleOptions"
                }
            }
            val encodedChBleOptions = Cbor.encode(
                buildCborMap {
                    if (senderIsL2capServer) {
                        put(CH_BLE_OPTION_KEY_DIRECT_L2CAP_PSM, directL2capPsm!!)
                    }
                    put(CH_BLE_OPTION_KEY_GATT, hasGatt)
                    put(CH_BLE_OPTION_KEY_DIRECT_L2CAP, hasDirectL2cap)
                }
            )
            auxiliaryRecords.add(
                NdefRecord(
                    tnf = NdefRecord.Tnf.EXTERNAL_TYPE,
                    type = Nfc.EXTERNAL_TYPE_ISO_18013_5_CH_BLE_OPTIONS.encodeToByteString(),
                    id = AUXILIARY_DATA_REFERENCE_CH_BLE_OPTIONS.encodeToByteString(),
                    payload = ByteString(encodedChBleOptions)
                )
            )
            if (AUXILIARY_DATA_REFERENCE_CH_BLE_OPTIONS in auxiliaryReferences) {
                auxiliaryReferences
            } else {
                auxiliaryReferences + AUXILIARY_DATA_REFERENCE_CH_BLE_OPTIONS
            }
        } else {
            auxiliaryReferences
        }

        check(effectiveAuxiliaryReferences.size < 0x100)
        val acRecordPayload = buildByteString {
            appendUInt8(0x01) // CPS: active
            appendUInt8(0x01) // Length of carrier data reference ("0")
            appendUInt8('0'.code)
            appendUInt8(effectiveAuxiliaryReferences.size)
            for (auxRef in effectiveAuxiliaryReferences) {
                val auxRefUtf8 = auxRef.encodeToByteArray()
                check(auxRefUtf8.size < 0x100)
                appendUInt8(auxRefUtf8.size)
                appendByteArray(auxRefUtf8)
            }
        }
        val acRecord = NdefRecord(
            tnf = NdefRecord.Tnf.WELL_KNOWN,
            type = Nfc.RTD_ALTERNATIVE_CARRIER,
            payload = acRecordPayload
        )
        return NdefRecords(
            carrierRecord = carrierRecord,
            acRecord = acRecord,
            auxiliaryRecords = auxiliaryRecords,
        )
    }

    companion object {
        private const val TAG = "MdocConnectionMethodBle"

        /**
         * The device retrieval method type for BLE according to ISO/IEC 18013-5 clause 9.2 / 11.1.2.
         */
        const val METHOD_TYPE = 2L

        /**
         * The supported version of the device retrieval method type for BLE.
         */
        const val METHOD_MAX_VERSION = 1L

        /**
         * Carrier data reference ID (`"0"`) for BLE OOB records in NFC Connection Handover.
         */
        const val CARRIER_DATA_REFERENCE_BLE = "0"

        /**
         * Auxiliary data reference ID (`"chble"`) for `ChBleOptions` records in NFC Connection Handover.
         */
        const val AUXILIARY_DATA_REFERENCE_CH_BLE_OPTIONS = "chble"

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

        internal fun fromDeviceEngagement(encodedDeviceRetrievalMethod: ByteArray): MdocConnectionMethodBle? {
            val array = Cbor.decode(encodedDeviceRetrievalMethod)
            val type = array[0].asNumber
            val version = array[1].asNumber
            require(type == METHOD_TYPE)
            if (version > METHOD_MAX_VERSION) {
                return null
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
            return MdocConnectionMethodBle(
                gattPeripheral = gattPeripheral,
                gattCentral = gattCentral,
                directL2capPeripheral = directL2capPeripheral,
                directL2capCentral = directL2capCentral,
                peripheralModeUuid = map.getOrNull(OPTION_KEY_PERIPHERAL_MODE_UUID)?.let {
                    UUID.fromByteArray(it.asBstr)
                },
                centralModeUuid = map.getOrNull(OPTION_KEY_CENTRAL_MODE_UUID)?.let {
                    UUID.fromByteArray(it.asBstr)
                },
                directL2capPsm = map.getOrNull(OPTION_KEY_DIRECT_L2CAP_PSM)?.asNumber?.toInt(),
                peripheralAddress = peripheralAddress,
            )
        }

        internal fun fromNdefRecord(
            record: NdefRecord,
            role: MdocRole,
            uuidToReplace: UUID?,
            auxiliaryRecords: List<NdefRecord> = emptyList(),
        ): MdocConnectionMethodBle? {
            var oobCentral = false
            var oobPeripheral = false
            var uuid: UUID? = uuidToReplace
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
                            if (role == MdocRole.MDOC) {
                                oobPeripheral = true
                            } else {
                                oobCentral = true
                            }
                        }
                        BLE_LE_ROLE_ONLY_CENTRAL -> {
                            if (role == MdocRole.MDOC) {
                                oobCentral = true
                            } else {
                                oobPeripheral = true
                            }
                        }
                        BLE_LE_ROLE_PERIPHERAL_AND_CENTRAL_PERIPHERAL_PREFERRED,
                        BLE_LE_ROLE_PERIPHERAL_AND_CENTRAL_CENTRAL_PREFERRED -> {
                            oobCentral = true
                            oobPeripheral = true
                        }
                        else -> {
                            Logger.w(TAG, "Invalid value $value for LE role")
                            return null
                        }
                    }
                } else if (type == BLE_LE_COMPLETE_LIST_OF_128_BIT_SERVICE_CLASS_UUIDS) {
                    val uuidLen = len - 1u
                    if (uuidLen % 16u != 0u) {
                        Logger.w(TAG, "UUID len $uuidLen is not divisible by 16")
                        return null
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
                        return null
                    }
                } else {
                    Logger.d(TAG, "Skipping unknown AD type $type of length $len")
                    reader.skip((len - 1u).toInt())
                }
            }
            if (!gotLeRole) {
                Logger.w(TAG, "Did not find LE role")
                return null
            }

            val chBleRecord = auxiliaryRecords.find {
                it.tnf == NdefRecord.Tnf.EXTERNAL_TYPE &&
                    it.type.decodeToString() == Nfc.EXTERNAL_TYPE_ISO_18013_5_CH_BLE_OPTIONS &&
                    it.id.decodeToString() == AUXILIARY_DATA_REFERENCE_CH_BLE_OPTIONS
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
                when (role) {
                    MdocRole.MDOC -> {
                        // Sender is mdoc (Hs):
                        // - If mdoc is peripheral (Static or Negotiated Hs), directL2capPeripheral requires mdoc's PSM.
                        // - If mdoc is central-only (Negotiated Hs), directL2capCentral = directL2cap (no mdoc PSM).
                        // - If mdoc is dual-role (Static Hs), directL2capCentral is false (reader hasn't sent a PSM).
                        directL2capPeripheral = oobPeripheral && directL2cap && psm != null
                        directL2capCentral = oobCentral && !oobPeripheral && directL2cap
                    }
                    MdocRole.MDOC_READER -> {
                        // Sender is mdoc reader (Hr):
                        // - directL2capCentral requires reader's PSM in ChBleOptions.
                        // - directL2capPeripheral does not use reader's PSM (mdoc supplies its PSM in Hs).
                        directL2capPeripheral = oobPeripheral && directL2cap
                        directL2capCentral = oobCentral && directL2cap && psm != null
                    }
                }
            }

            if (!gattPeripheral && !gattCentral && !directL2capPeripheral && !directL2capCentral) {
                Logger.w(TAG, "No enabled BLE modes after decoding BLE OOB + ChBleOptions")
                return null
            }

            return MdocConnectionMethodBle(
                gattPeripheral = gattPeripheral,
                gattCentral = gattCentral,
                directL2capPeripheral = directL2capPeripheral,
                directL2capCentral = directL2capCentral,
                peripheralModeUuid = if (role == MdocRole.MDOC && (gattPeripheral || directL2capPeripheral)) uuid else null,
                centralModeUuid = if (gattCentral || directL2capCentral) uuid else null,
                directL2capPsm = psm,
                peripheralAddress = peripheralAddress,
            )
        }
    }
}
