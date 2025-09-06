package org.multipaz.mdoc.connectionmethod

import kotlinx.io.bytestring.ByteString
import org.multipaz.cbor.Cbor.decode
import org.multipaz.nfc.NdefRecord
import org.multipaz.nfc.Nfc
import org.multipaz.util.Logger
import org.multipaz.util.UUID
import kotlinx.io.bytestring.decodeToString
import org.multipaz.mdoc.role.MdocRole

// TODO: Make this a sealed class when multipaz-android-legacy is removed.

/**
 * A class representing the ConnectionMethod structure exchanged between mdoc and mdoc reader.
 *
 * This is an abstract class - applications are expected to interact with concrete
 * implementations, for example [MdocConnectionMethodBle] or [MdocConnectionMethodNfc].
 */
abstract class MdocConnectionMethod {
    /**
     * Generates `DeviceRetrievalMethod` CBOR for the given [MdocConnectionMethod].
     *
     * See ISO/IEC 18013-5:2021 section 8.2.1.1 Device engagement structure for where
     * `DeviceRetrievalMethod` CBOR is defined.
     *
     * This is the reverse operation of [.fromDeviceEngagement].
     */
    // TODO: at some point when we do a lot of other breaking changes, rename toDeviceEngagement()
    //  to toDataItem() and make it return a DataItem. Ditto for fromDeviceEngagement().
    abstract fun toDeviceEngagement(): ByteArray

    /**
     * Creates a NDEF Connection Handover Carrier Reference record and Auxiliary Data Reference records.
     *
     * @param auxiliaryReferences A list of references to include in the Alternative Carrier Record
     * @param role If [MdocRole.MDOC] the generated records will be for the Handover Select message, otherwise
     * for Handover  Request.
     * @param skipUuids if `true`, UUIDs will not be included in the record
     * @return The NDEF record and the Alternative Carrier record or null if NFC handover is not supported.
     */
    abstract fun toNdefRecord(
        auxiliaryReferences: List<String>,
        role: MdocRole,
        skipUuids: Boolean
    ): Pair<NdefRecord, NdefRecord>?

    companion object {
        private const val TAG = "ConnectionMethod"

        /**
         * Constructs a new [MdocConnectionMethod] from `DeviceRetrievalMethod` CBOR.
         *
         * See ISO/IEC 18013-5:2021 section 8.2.1.1 Device engagement structure for where
         * `DeviceRetrievalMethod` CBOR is defined.
         *
         * This is the reverse operation of [.toDeviceEngagement].
         *
         * @param encodedDeviceRetrievalMethod the bytes of `DeviceRetrievalMethod` CBOR.
         * @return A [MdocConnectionMethod]-derived instance or `null` if the method
         * isn't supported.
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
            }
            Logger.w(TAG, "Unsupported ConnectionMethod type $type in DeviceEngagement")
            return null
        }

        /**
         * Constructs a new [MdocConnectionMethod] from a NFC record.
         *
         * @param role If [MdocRole.MDOC] the record is from a Handover Select message,
         *   [MdocRole.MDOC_READER] if from a for Handover Request message.
         * @param uuid If a UUID in a record isn't available, use this instead.
         * @returns the decoded method or `null` if the method isn't supported.
         */
        fun fromNdefRecord(
            record: NdefRecord,
            role: MdocRole,
            uuid: UUID?
        ): MdocConnectionMethod? {
            if (record.tnf == NdefRecord.Tnf.MIME_MEDIA &&
                record.type.decodeToString() == Nfc.MIME_TYPE_CONNECTION_HANDOVER_BLE &&
                record.id.decodeToString() == "0") {
                return MdocConnectionMethodBle.fromNdefRecord(record, role, uuid)
            } else if (record.tnf == NdefRecord.Tnf.EXTERNAL_TYPE &&
                record.type.decodeToString() == Nfc.EXTERNAL_TYPE_ISO_18013_5_NFC &&
                record.id.decodeToString() == "nfc") {
                return MdocConnectionMethodNfc.fromNdefRecord(record, role)
            }
            // TODO: add support for Wifi Aware and others.
            Logger.w(TAG, "No support for NDEF record $record")
            return null
        }

        /**
         * Combines connection methods.
         *
         * Given a list of connection methods, produce a list where similar methods are combined
         * into a single one. This is currently only applicable for BLE and one requirement is that
         * all method instances share the same UUID. If this is not the case,
         * [IllegalArgumentException] is thrown.
         *
         * This is the reverse of [.disambiguate].
         *
         * @param connectionMethods a list of connection methods.
         * @return the given list of connection methods where similar methods are combined into one.
         * @throws IllegalArgumentException if some methods couldn't be combined
         */
        fun combine(connectionMethods: List<MdocConnectionMethod>): List<MdocConnectionMethod> {
            val result = mutableListOf<MdocConnectionMethod>()

            // Don't change the order if there is nothing to combine.
            var numBleMethods = 0
            for (cm in connectionMethods) {
                if (cm is MdocConnectionMethodBle) {
                    numBleMethods += 1
                }
            }
            if (numBleMethods <= 1) {
                result.addAll(connectionMethods)
                return result
            }
            val bleMethods: MutableList<MdocConnectionMethodBle> = ArrayList()
            for (cm in connectionMethods) {
                if (cm is MdocConnectionMethodBle) {
                    bleMethods.add(cm)
                } else {
                    result.add(cm)
                }
            }
            if (bleMethods.size == 0) {
                return result
            }
            var supportsPeripheralServerMode = false
            var supportsCentralClientMode = false
            var uuid: UUID? = null
            var mac: ByteString? = null
            var psm: Int? = null
            for (ble in bleMethods) {
                if (ble.supportsPeripheralServerMode) {
                    supportsPeripheralServerMode = true
                }
                if (ble.supportsCentralClientMode) {
                    supportsCentralClientMode = true
                }
                val c = ble.centralClientModeUuid
                val p = ble.peripheralServerModeUuid
                if (uuid == null) {
                    if (c != null) {
                        uuid = c
                    } else if (p != null) {
                        uuid = p
                    }
                } else {
                    require(!(c != null && uuid != c)) { "UUIDs for both BLE modes are not the same" }
                    require(!(p != null && uuid != p)) { "UUIDs for both BLE modes are not the same" }
                }
                if (mac == null && ble.peripheralServerModeMacAddress != null) {
                    mac = ble.peripheralServerModeMacAddress
                }
                if (psm == null && ble.peripheralServerModePsm != null) {
                    psm = ble.peripheralServerModePsm
                }
            }
            val combined =
                MdocConnectionMethodBle(
                    supportsPeripheralServerMode = supportsPeripheralServerMode,
                    supportsCentralClientMode = supportsCentralClientMode,
                    peripheralServerModeUuid = if (supportsPeripheralServerMode) uuid else null,
                    centralClientModeUuid = if (supportsCentralClientMode) uuid else null,
                    peripheralServerModePsm = psm,
                    peripheralServerModeMacAddress = mac
                )
            return listOf(combined) + result
        }

        /**
         * Disambiguate a list of connection methods.
         *
         * Given a list of connection methods, produce a list where each method represents exactly
         * one connectable endpoint. For example, for BLE if both central client mode and peripheral
         * server mode is set, replaces this with two connection methods so it's clear which one is
         * which.
         *
         * The [MdocConnectionMethodBle.peripheralServerModePsm] and [MdocConnectionMethodBle.peripheralServerModeMacAddress]
         * properties are duplicated in each of the resulting [MdocConnectionMethodBle] instances. The PSM
         * and MAC address is only conveyed on one of the instances, see [role] argument for which one.
         *
         * This is the reverse of [.combine].
         *
         * @param connectionMethods a list of connection methods.
         * @param role If [MdocRole.MDOC] the PSM and MAC address will appear only in the resulting
         *   [MdocConnectionMethodBle] objects for central client mode, otherwise they only appear in the object for
         *   peripheral server mode.
         * @return the given list of connection methods where each instance is unambiguously refers
         * to one and only one connectable endpoint.
         */
        fun disambiguate(
            connectionMethods: List<MdocConnectionMethod>,
            role: MdocRole,
        ): List<MdocConnectionMethod> {
            val result = mutableListOf<MdocConnectionMethod>()
            for (cm in connectionMethods) {
                // Only BLE needs disambiguation
                if (cm is MdocConnectionMethodBle) {
                    val cmBle = cm
                    if (cmBle.supportsCentralClientMode && cmBle.supportsPeripheralServerMode) {
                        val (ccMac, ccPsm) = if (role == MdocRole.MDOC) {
                            Pair(cmBle.peripheralServerModeMacAddress, cmBle.peripheralServerModePsm)
                        } else {
                            Pair(null, null)
                        }
                        val centralClientMode =
                            MdocConnectionMethodBle(
                                supportsPeripheralServerMode = false,
                                supportsCentralClientMode = true,
                                peripheralServerModeUuid = null,
                                centralClientModeUuid = cmBle.centralClientModeUuid,
                                peripheralServerModePsm = ccPsm,
                                peripheralServerModeMacAddress = ccMac
                            )
                        result.add(centralClientMode)
                        val (psMac, psPsm) = if (role == MdocRole.MDOC_READER) {
                            Pair(cmBle.peripheralServerModeMacAddress, cmBle.peripheralServerModePsm)
                        } else {
                            Pair(null, null)
                        }
                        val peripheralServerMode =
                            MdocConnectionMethodBle(
                                supportsPeripheralServerMode = true,
                                supportsCentralClientMode = false,
                                peripheralServerModeUuid = cmBle.peripheralServerModeUuid,
                                centralClientModeUuid = null,
                                peripheralServerModePsm = psPsm,
                                peripheralServerModeMacAddress = psMac
                            )
                        result.add(peripheralServerMode)
                        continue
                    }
                }
                result.add(cm)
            }
            return result
        }
    }
}