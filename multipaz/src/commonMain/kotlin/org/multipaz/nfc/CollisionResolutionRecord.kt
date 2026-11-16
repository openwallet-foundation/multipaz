package org.multipaz.nfc

import kotlinx.io.bytestring.buildByteString
import org.multipaz.crypto.Crypto
import org.multipaz.util.appendUInt16
import org.multipaz.util.getUInt16
import kotlin.random.Random

/**
 * Collision Resolution record (`"cr"`).
 *
 * Reference: NFC Forum Connection Handover Technical Specification Version 1.5 section 7.3.
 *
 * @property randomNumber the unsigned 16-bit random number (`0..65535`) used for handover request
 *   collision resolution.
 * @throws IllegalArgumentException if [randomNumber] is outside `0..65535`.
 */
data class CollisionResolutionRecord(
    val randomNumber: Int,
) {
    init {
        require(randomNumber in 0..0xffff) {
            "Collision Resolution randomNumber must be in 0..65535, got $randomNumber"
        }
    }

    /**
     * Generates an [NdefRecord] for this Collision Resolution record.
     *
     * @return the encoded `"cr"` [NdefRecord].
     */
    fun generateNdefRecord(): NdefRecord {
        val payload = buildByteString {
            appendUInt16(randomNumber)
        }
        return NdefRecord(
            tnf = NdefRecord.Tnf.WELL_KNOWN,
            type = Nfc.RTD_COLLISION_RESOLUTION,
            payload = payload,
        )
    }

    companion object {
        /**
         * Creates a new [CollisionResolutionRecord] with a randomly generated 16-bit number.
         *
         * @param random the [Random] instance to use, defaulting to [Crypto.secureRandom].
         * @return a new [CollisionResolutionRecord].
         */
        fun create(random: Random = Crypto.secureRandom): CollisionResolutionRecord =
            CollisionResolutionRecord(random.nextInt(0x10000))

        /**
         * Checks if [record] is a `"cr"` Collision Resolution record and parses it if so.
         *
         * @param record the NDEF record to inspect.
         * @return the parsed [CollisionResolutionRecord], or `null` if [record] is not a `"cr"` record.
         * @throws IllegalArgumentException if [record] is a `"cr"` record with a malformed payload.
         */
        @Throws(IllegalArgumentException::class)
        fun fromNdefRecord(record: NdefRecord): CollisionResolutionRecord? {
            if (record.tnf != NdefRecord.Tnf.WELL_KNOWN ||
                record.type != Nfc.RTD_COLLISION_RESOLUTION
            ) {
                return null
            }
            require(record.payload.size == 2) {
                "Unexpected length of Collision Resolution Record: ${record.payload.size}, expected 2"
            }
            return CollisionResolutionRecord(record.payload.getUInt16(0).toInt())
        }
    }
}
