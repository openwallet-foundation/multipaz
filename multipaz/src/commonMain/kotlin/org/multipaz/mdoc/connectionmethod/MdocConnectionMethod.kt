package org.multipaz.mdoc.connectionmethod

import kotlinx.io.bytestring.decodeToString
import org.multipaz.cbor.Cbor.decode
import org.multipaz.mdoc.role.MdocRole
import org.multipaz.nfc.NdefRecord
import org.multipaz.nfc.Nfc
import org.multipaz.util.ByteDataReader
import org.multipaz.util.Logger
import org.multipaz.util.UUID

/**
 * A class representing the ConnectionMethod structure exchanged between mdoc and mdoc reader.
 *
 * This is an abstract class - applications are expected to interact with concrete
 * implementations, for example [MdocConnectionMethodBle] or [MdocConnectionMethodNfc].
 */
abstract class MdocConnectionMethod {

    /**
     * NDEF records generated for a [MdocConnectionMethod] in NFC Connection Handover.
     *
     * @property carrierRecord the carrier configuration NDEF record (e.g. `"0"` or `"nfc"`).
     * @property acRecord the `"ac"` Alternative Carrier NDEF record embedded in `"Hs"` or `"Hr"`.
     * @property auxiliaryRecords any method-specific auxiliary data NDEF records (e.g. `"chble"`).
     */
    data class NdefRecords(
        val carrierRecord: NdefRecord,
        val acRecord: NdefRecord,
        val auxiliaryRecords: List<NdefRecord> = emptyList(),
    )

    /**
     * Generates `DeviceRetrievalMethod` CBOR for [MdocEngagementType.DEVICE_QR].
     *
     * @return the encoded `DeviceRetrievalMethod` CBOR bytes.
     */
    fun toDeviceEngagement(): ByteArray = toDeviceEngagement(MdocEngagementType.DEVICE_QR)

    /**
     * Generates `DeviceRetrievalMethod` CBOR for the given [MdocConnectionMethod].
     *
     * @param engagementType the engagement context controlling context-specific field rules and validation.
     * @return the encoded `DeviceRetrievalMethod` CBOR bytes.
     */
    abstract fun toDeviceEngagement(
        engagementType: MdocEngagementType,
    ): ByteArray

    /**
     * Creates NDEF Connection Handover Carrier Configuration, Alternative Carrier, and optional
     * method-specific Auxiliary Data records (such as `ChBleOptions`).
     *
     * @param auxiliaryReferences list of shared auxiliary data references (e.g. `"mdoc"` or `"mdocreader"`)
     *   to include in the Alternative Carrier record.
     * @param role if [MdocRole.MDOC], generates records for a Handover Select (`Hs`) message;
     *   if [MdocRole.MDOC_READER], generates records for a Handover Request (`Hr`) message.
     * @param skipUuids if `true`, omits service UUIDs from the carrier configuration record.
     * @param includeChBleOptions if `true`, includes a `ChBleOptions` auxiliary record for BLE even when
     *   selecting a GATT-only mode (used in Negotiated Handover `Hs` when `Hr` included `ChBleOptions`).
     * @return the [NdefRecords] or `null` if NFC Connection Handover is not supported for this method.
     */
    abstract fun toNdefRecord(
        auxiliaryReferences: List<String>,
        role: MdocRole,
        skipUuids: Boolean,
        includeChBleOptions: Boolean = false,
    ): NdefRecords?

    companion object {
        private const val TAG = "ConnectionMethod"

        /**
         * Parsed representation of an NFC Forum Connection Handover `"ac"` Alternative Carrier record.
         *
         * @property cps the 2-bit Carrier Power State (`0`..`3`).
         * @property carrierDataReference the carrier configuration record ID referenced by this carrier.
         * @property auxiliaryDataReferences the auxiliary data record IDs referenced by this carrier.
         */
        data class AlternativeCarrier(
            val cps: Int,
            val carrierDataReference: String,
            val auxiliaryDataReferences: List<String>,
        )

        /**
         * Parses an `"ac"` Alternative Carrier [NdefRecord], or returns `null` if [record] is not an `"ac"` record.
         *
         * @param record the NDEF record to inspect.
         * @return the parsed [AlternativeCarrier] or `null`.
         */
        fun parseAlternativeCarrierRecord(record: NdefRecord): AlternativeCarrier? {
            if (record.tnf != NdefRecord.Tnf.WELL_KNOWN || record.type != Nfc.RTD_ALTERNATIVE_CARRIER) {
                return null
            }
            val reader = ByteDataReader(record.payload)
            if (reader.exhausted()) return null
            val cps = reader.getUInt8().toInt() and 0x03
            val cdrLen = reader.getUInt8().toInt()
            val carrierDataReference = reader.getByteString(cdrLen).decodeToString()
            val auxCount = reader.getUInt8().toInt()
            val auxRefs = mutableListOf<String>()
            repeat(auxCount) {
                val auxLen = reader.getUInt8().toInt()
                auxRefs.add(reader.getByteString(auxLen).decodeToString())
            }
            return AlternativeCarrier(cps, carrierDataReference, auxRefs)
        }

        /**
         * Constructs a new [MdocConnectionMethod] from `DeviceRetrievalMethod` CBOR.
         *
         * @param encodedDeviceRetrievalMethod the bytes of `DeviceRetrievalMethod` CBOR.
         * @return a [MdocConnectionMethod]-derived instance or `null` if the method isn't supported.
         * @throws IllegalArgumentException if the given CBOR is malformed.
         */
        fun fromDeviceEngagement(encodedDeviceRetrievalMethod: ByteArray): MdocConnectionMethod? {
            val array = decode(encodedDeviceRetrievalMethod)
            val type = array[0].asNumber
            when (type) {
                MdocConnectionMethodNfc.METHOD_TYPE -> return MdocConnectionMethodNfc.fromDeviceEngagement(
                    encodedDeviceRetrievalMethod
                )

                MdocConnectionMethodBle.METHOD_TYPE -> return MdocConnectionMethodBle.fromDeviceEngagement(
                    encodedDeviceRetrievalMethod
                )

                MdocConnectionMethodWifiAware.METHOD_TYPE -> return MdocConnectionMethodWifiAware.fromDeviceEngagement(
                    encodedDeviceRetrievalMethod
                )

                MdocConnectionMethodHttp.METHOD_TYPE -> return MdocConnectionMethodHttp.fromDeviceEngagement(
                    encodedDeviceRetrievalMethod
                )
            }
            Logger.w(TAG, "Unsupported ConnectionMethod type $type in DeviceEngagement")
            return null
        }

        /**
         * Constructs a new [MdocConnectionMethod] from an NFC carrier configuration record.
         *
         * @param record the carrier configuration [NdefRecord].
         * @param role if [MdocRole.MDOC], [record] is from a Handover Select (`Hs`) message;
         *   if [MdocRole.MDOC_READER], [record] is from a Handover Request (`Hr`) message.
         * @param uuid fallback UUID to use when the carrier record omits a UUID.
         * @param auxiliaryRecords auxiliary data records associated with this carrier (e.g. `"chble"`).
         * @return the decoded [MdocConnectionMethod] or `null` if [record] is not a supported carrier record.
         */
        fun fromNdefRecord(
            record: NdefRecord,
            role: MdocRole,
            uuid: UUID?,
            auxiliaryRecords: List<NdefRecord> = emptyList(),
        ): MdocConnectionMethod? {
            if (record.tnf == NdefRecord.Tnf.MIME_MEDIA &&
                record.type.decodeToString() == Nfc.MIME_TYPE_CONNECTION_HANDOVER_BLE &&
                record.id.decodeToString() == MdocConnectionMethodBle.CARRIER_DATA_REFERENCE_BLE
            ) {
                return MdocConnectionMethodBle.fromNdefRecord(record, role, uuid, auxiliaryRecords)
            } else if (record.tnf == NdefRecord.Tnf.EXTERNAL_TYPE &&
                record.type.decodeToString() == Nfc.EXTERNAL_TYPE_ISO_18013_5_NFC &&
                record.id.decodeToString() == "nfc"
            ) {
                return MdocConnectionMethodNfc.fromNdefRecord(record, role)
            } else if (record.tnf == NdefRecord.Tnf.EXTERNAL_TYPE) {
                val externalType = record.type.decodeToString()
                if (externalType == Nfc.EXTERNAL_TYPE_ISO_18013_5_DEVICE_ENGAGEMENT ||
                    externalType == Nfc.EXTERNAL_TYPE_ISO_18013_5_READER_ENGAGEMENT ||
                    externalType == Nfc.EXTERNAL_TYPE_ISO_18013_5_CH_BLE_OPTIONS
                ) {
                    // Auxiliary data records are not standalone connection methods.
                    return null
                }
            }
            Logger.w(TAG, "No support for NDEF record $record")
            return null
        }

        /**
         * Combines connection methods so that all [MdocConnectionMethodBle] entries are merged into a
         * single [MdocConnectionMethodBle] instance (satisfying ISO/IEC 18013-5 clause 9.2's rule that
         * each `(type, version)` pair appears at most once in `DeviceRetrievalMethods`).
         *
         * `peripheralModeUuid` and `centralModeUuid` are tracked independently (allowing distinct UUIDs in
         * QR engagement while still rejecting conflicting UUIDs within the same role). Note that NFC Static
         * Connection Handover additionally requires both roles to share a single UUID; that constraint is
         * checked when encoding NDEF records in [MdocConnectionMethodBle.toNdefRecord].
         *
         * @param connectionMethods a list of connection methods.
         * @return the list of connection methods with BLE methods merged into a single entry.
         * @throws IllegalArgumentException if BLE methods have conflicting UUIDs, PSMs, or addresses.
         */
        fun combine(connectionMethods: List<MdocConnectionMethod>): List<MdocConnectionMethod> {
            val numBleMethods = connectionMethods.count { it is MdocConnectionMethodBle }
            if (numBleMethods <= 1) {
                return connectionMethods.toList()
            }
            val result = mutableListOf<MdocConnectionMethod>()
            val bleMethods = mutableListOf<MdocConnectionMethodBle>()
            for (cm in connectionMethods) {
                if (cm is MdocConnectionMethodBle) {
                    bleMethods.add(cm)
                } else {
                    result.add(cm)
                }
            }
            var gattPeripheral = false
            var gattCentral = false
            var directL2capPeripheral = false
            var directL2capCentral = false
            var peripheralModeUuid: UUID? = null
            var centralModeUuid: UUID? = null
            var psm: Int? = null
            var peripheralAddress: BlePeripheralAddress? = null

            for (ble in bleMethods) {
                if (ble.gattPeripheral) gattPeripheral = true
                if (ble.gattCentral) gattCentral = true
                if (ble.directL2capPeripheral) directL2capPeripheral = true
                if (ble.directL2capCentral) directL2capCentral = true

                if (ble.peripheralModeUuid != null) {
                    if (peripheralModeUuid == null) {
                        peripheralModeUuid = ble.peripheralModeUuid
                    } else {
                        require(peripheralModeUuid == ble.peripheralModeUuid) {
                            "Conflicting peripheralModeUuid values when combining BLE connection methods"
                        }
                    }
                }
                if (ble.centralModeUuid != null) {
                    if (centralModeUuid == null) {
                        centralModeUuid = ble.centralModeUuid
                    } else {
                        require(centralModeUuid == ble.centralModeUuid) {
                            "Conflicting centralModeUuid values when combining BLE connection methods"
                        }
                    }
                }
                if (ble.directL2capPsm != null) {
                    if (psm == null) {
                        psm = ble.directL2capPsm
                    } else {
                        require(psm == ble.directL2capPsm) {
                            "Conflicting directL2capPsm values when combining BLE connection methods"
                        }
                    }
                }
                if (ble.peripheralAddress != null) {
                    if (peripheralAddress == null) {
                        peripheralAddress = ble.peripheralAddress
                    } else {
                        require(peripheralAddress == ble.peripheralAddress) {
                            "Conflicting peripheralAddress values when combining BLE connection methods"
                        }
                    }
                }
            }

            val combined = MdocConnectionMethodBle(
                gattPeripheral = gattPeripheral,
                gattCentral = gattCentral,
                directL2capPeripheral = directL2capPeripheral,
                directL2capCentral = directL2capCentral,
                peripheralModeUuid = if (gattPeripheral || directL2capPeripheral) peripheralModeUuid else null,
                centralModeUuid = if (gattCentral || directL2capCentral) centralModeUuid else null,
                directL2capPsm = psm,
                peripheralAddress = peripheralAddress,
            )
            return listOf(combined) + result
        }

        /**
         * Disambiguates a list of connection methods so each returned entry represents at most one
         * BLE mode (`directL2capCentral`, `directL2capPeripheral`, `gattCentral`, or `gattPeripheral`).
         *
         * @param connectionMethods the list of connection methods to disambiguate.
         * @param role the local peer's [MdocRole] (`MDOC` when disambiguating an offer from an mdoc reader,
         *   `MDOC_READER` when disambiguating an offer from an mdoc), used to associate `directL2capPsm` and
         *   `peripheralAddress` with the remote peer's BLE peripheral modes.
         * @return a list of single-mode connection methods.
         */
        fun disambiguate(
            connectionMethods: List<MdocConnectionMethod>,
            role: MdocRole,
        ): List<MdocConnectionMethod> {
            val result = mutableListOf<MdocConnectionMethod>()
            for (cm in connectionMethods) {
                if (cm is MdocConnectionMethodBle && cm.enabledModeCount > 1) {
                    val ccAddr = if (role == MdocRole.MDOC || !cm.hasPeripheralMode) {
                        cm.peripheralAddress
                    } else {
                        null
                    }
                    val psAddr = if (role == MdocRole.MDOC_READER || !cm.hasCentralMode) {
                        cm.peripheralAddress
                    } else {
                        null
                    }
                    val ccPsm = if (role == MdocRole.MDOC || !cm.directL2capPeripheral) {
                        cm.directL2capPsm
                    } else {
                        null
                    }
                    val psPsm = if (role == MdocRole.MDOC_READER || !cm.directL2capCentral) {
                        cm.directL2capPsm
                    } else {
                        null
                    }

                    if (cm.directL2capCentral) {
                        result.add(
                            MdocConnectionMethodBle(
                                gattPeripheral = false,
                                gattCentral = false,
                                directL2capPeripheral = false,
                                directL2capCentral = true,
                                centralModeUuid = cm.centralModeUuid,
                                directL2capPsm = ccPsm,
                                peripheralAddress = ccAddr,
                            )
                        )
                    }
                    if (cm.directL2capPeripheral) {
                        result.add(
                            MdocConnectionMethodBle(
                                gattPeripheral = false,
                                gattCentral = false,
                                directL2capPeripheral = true,
                                directL2capCentral = false,
                                peripheralModeUuid = cm.peripheralModeUuid,
                                directL2capPsm = psPsm,
                                peripheralAddress = psAddr,
                            )
                        )
                    }
                    if (cm.gattCentral) {
                        result.add(
                            MdocConnectionMethodBle(
                                gattPeripheral = false,
                                gattCentral = true,
                                directL2capPeripheral = false,
                                directL2capCentral = false,
                                centralModeUuid = cm.centralModeUuid,
                                directL2capPsm = null,
                                peripheralAddress = ccAddr,
                            )
                        )
                    }
                    if (cm.gattPeripheral) {
                        result.add(
                            MdocConnectionMethodBle(
                                gattPeripheral = true,
                                gattCentral = false,
                                directL2capPeripheral = false,
                                directL2capCentral = false,
                                peripheralModeUuid = cm.peripheralModeUuid,
                                directL2capPsm = null,
                                peripheralAddress = psAddr,
                            )
                        )
                    }
                } else {
                    result.add(cm)
                }
            }
            return result
        }
    }
}
