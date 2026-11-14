package org.multipaz.nfc

import kotlinx.io.bytestring.buildByteString
import kotlinx.io.bytestring.decodeToString
import kotlinx.io.bytestring.encodeToByteString
import org.multipaz.util.ByteDataReader
import org.multipaz.util.appendByteArray
import org.multipaz.util.appendUInt8

/**
 * Alternative Carrier record (`"ac"`).
 *
 * Reference: NFC Forum Connection Handover Technical Specification, Alternative Carrier Record.
 *
 * @property cps the 2-bit Carrier Power State ([CPS_INACTIVE], [CPS_ACTIVE], [CPS_ACTIVATING], or [CPS_UNKNOWN]).
 * @property carrierDataReference the ID of the carrier configuration record referenced by this carrier.
 * @property auxiliaryDataReferences the IDs of any auxiliary data records referenced by this carrier.
 */
data class AlternativeCarrierRecord(
    val cps: Int = CPS_ACTIVE,
    val carrierDataReference: String,
    val auxiliaryDataReferences: List<String> = emptyList(),
) {
    init {
        require(cps in 0..3) {
            "Carrier Power State must be in 0..3, got $cps"
        }
    }

    /**
     * Generates an [NdefRecord] for this Alternative Carrier record.
     *
     * @return the encoded `"ac"` [NdefRecord].
     */
    fun generateNdefRecord(): NdefRecord {
        val cdrBytes = carrierDataReference.encodeToByteString()
        check(cdrBytes.size < 0x100) {
            "Carrier data reference UTF-8 length must fit in 1 byte, got ${cdrBytes.size}"
        }
        check(auxiliaryDataReferences.size < 0x100) {
            "Auxiliary data reference count must fit in 1 byte, got ${auxiliaryDataReferences.size}"
        }
        val payload = buildByteString {
            appendUInt8(cps and 0x03)
            appendUInt8(cdrBytes.size)
            appendByteArray(cdrBytes.toByteArray())
            appendUInt8(auxiliaryDataReferences.size)
            for (auxRef in auxiliaryDataReferences) {
                val auxRefUtf8 = auxRef.encodeToByteArray()
                check(auxRefUtf8.size < 0x100) {
                    "Auxiliary data reference UTF-8 length must fit in 1 byte, got ${auxRefUtf8.size}"
                }
                appendUInt8(auxRefUtf8.size)
                appendByteArray(auxRefUtf8)
            }
        }
        return NdefRecord(
            tnf = NdefRecord.Tnf.WELL_KNOWN,
            type = Nfc.RTD_ALTERNATIVE_CARRIER,
            payload = payload,
        )
    }

    companion object {
        /**
         * Carrier Power State: Inactive (`0x00`).
         */
        const val CPS_INACTIVE = 0x00

        /**
         * Carrier Power State: Active (`0x01`).
         */
        const val CPS_ACTIVE = 0x01

        /**
         * Carrier Power State: Activating (`0x02`).
         */
        const val CPS_ACTIVATING = 0x02

        /**
         * Carrier Power State: Unknown (`0x03`).
         */
        const val CPS_UNKNOWN = 0x03

        /**
         * Checks if [record] is an `"ac"` Alternative Carrier record and parses it if so.
         *
         * @param record the NDEF record to inspect.
         * @return the parsed [AlternativeCarrierRecord], or `null` if [record] is not an `"ac"` record.
         * @throws IllegalArgumentException if [record] is an `"ac"` record with a malformed payload.
         */
        fun fromNdefRecord(record: NdefRecord): AlternativeCarrierRecord? {
            if (record.tnf != NdefRecord.Tnf.WELL_KNOWN ||
                record.type != Nfc.RTD_ALTERNATIVE_CARRIER
            ) {
                return null
            }
            val reader = ByteDataReader(record.payload)
            require(reader.numBytesRemaining() >= 3) {
                "Unexpected length of Alternative Carrier Record: ${record.payload.size}"
            }
            val cps = reader.getUInt8().toInt() and 0x03
            val cdrLen = reader.getUInt8().toInt()
            require(reader.numBytesRemaining() >= cdrLen + 1) {
                "Truncated carrier data reference in Alternative Carrier Record"
            }
            val carrierDataReference = reader.getByteString(cdrLen).decodeToString()
            val auxCount = reader.getUInt8().toInt()
            val auxRefs = mutableListOf<String>()
            repeat(auxCount) {
                require(reader.numBytesRemaining() >= 1) {
                    "Truncated auxiliary data reference length in Alternative Carrier Record"
                }
                val auxLen = reader.getUInt8().toInt()
                require(reader.numBytesRemaining() >= auxLen) {
                    "Truncated auxiliary data reference in Alternative Carrier Record"
                }
                auxRefs.add(reader.getByteString(auxLen).decodeToString())
            }
            require(reader.exhausted()) {
                "Unexpected trailing bytes in Alternative Carrier Record"
            }
            return AlternativeCarrierRecord(
                cps = cps,
                carrierDataReference = carrierDataReference,
                auxiliaryDataReferences = auxRefs,
            )
        }
    }
}
