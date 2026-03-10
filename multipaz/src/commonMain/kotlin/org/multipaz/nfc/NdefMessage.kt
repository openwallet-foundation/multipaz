package org.multipaz.nfc

import kotlinx.io.bytestring.ByteStringBuilder

/**
 * An immutable NDEF message.
 *
 * @property records the records in the message.
 */
data class NdefMessage(
    val records: List<NdefRecord>
) {
    /**
     * Encodes the NDEF message.
     *
     * @return the encoded message.
     */
    fun encode(): ByteArray {
        val bsb = ByteStringBuilder()
        for (idx in records.indices) {
            val record = records[idx]
            val mb = (idx == 0)                  // first record
            val me = (idx == records.size - 1)   // last record
            record.encode(bsb, mb, me)
        }
        return bsb.toByteString().toByteArray()
    }

    companion object {
        /**
         * Decodes a NDEF message.
         *
         * @param encoded the encoded messages.
         * @return the decoded message
         * @throws IllegalArgumentException if the message is invalid.
         */
        fun fromEncoded(encoded: ByteArray): NdefMessage {
            return NdefMessage(NdefRecord.fromEncoded(encoded))
        }
    }
}

