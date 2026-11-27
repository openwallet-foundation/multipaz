package org.multipaz.nfc

import org.multipaz.util.ByteDataReader
import org.multipaz.util.appendUInt16
import org.multipaz.util.appendUInt8
import org.multipaz.util.getUInt16
import org.multipaz.util.getUInt8
import kotlinx.io.bytestring.ByteString
import kotlinx.io.bytestring.ByteStringBuilder
import kotlinx.io.bytestring.append

/**
 * A Command APDU according to ISO/IEC 7816.
 *
 * @property cla Command class byte.
 * @property ins Instruction byte.
 * @property p1 Parameter byte 1.
 * @property p2 Parameter byte 2.
 * @property payload Payload.
 * @property le Maximum length of response data field.
 */
data class CommandApdu(
    val cla: Int,
    val ins: Int,
    val p1: Int,
    val p2: Int,
    val payload: ByteString,
    val le: Int
) {
    /**
     * `true` if this command is an ISO/IEC 7816-4 `SELECT APPLICATION` (by DF/AID name) command
     * (`INS = 0xA4`, `P1 = 0x04`).
     */
    val isApplicationSelect: Boolean
        get() = ins == Nfc.INS_SELECT && p1 == Nfc.INS_SELECT_P1_APPLICATION

    /**
     * Encodes the APDU as bytes.
     *
     * @return the bytes of the APDU.
     */
    fun encode(): ByteArray {
        check(payload.size < 0x10000)

        val bsb = ByteStringBuilder()
        bsb.appendUInt8(cla)
        bsb.appendUInt8(ins)
        bsb.appendUInt8(p1)
        bsb.appendUInt8(p2)
        var lcPresent = false

        // Lc and Le must use the same encoding, either short or long
        val useShortEncoding = payload.size < 0x100 && le <= 0x100

        if (payload.size > 0) {
            lcPresent = true
            if (useShortEncoding) {
                bsb.appendUInt8(payload.size)
            } else {
                bsb.appendUInt8(0u)
                bsb.appendUInt16(payload.size)
            }
            bsb.append(payload)
        }
        if (le > 0) {
            if (useShortEncoding) {
                if (le == 0x100) {
                    bsb.appendUInt8(0u)
                } else {
                    bsb.appendUInt8(le)
                }
            } else {
                if (!lcPresent) {
                    bsb.appendUInt8(0u)
                }
                if (le < 0x10000) {
                    bsb.appendUInt16(le)
                } else if (le == 0x10000) {
                    bsb.appendUInt16(0u)
                } else {
                    throw IllegalStateException("invalid LE size $le")
                }
            }
        }
        return bsb.toByteString().toByteArray()
    }

    companion object {
        /**
         * Maximum framing overhead bytes added by [encode] to a Command APDU (`CLA INS P1 P2`,
         * 3-byte extended `Lc`, and 2-byte extended `Le`).
         */
        internal const val MAX_OVERHEAD_SIZE = 9

        /**
         * Framing overhead bytes added by [encode] to a short Command APDU with command data
         * and no `Le` (`CLA INS P1 P2`, 1-byte short `Lc`).
         */
        internal const val SHORT_COMMAND_DATA_OVERHEAD_SIZE = 5

        /**
         * Maximum length of a Command APDU data field (`Lc`) per ISO/IEC 7816-4.
         */
        internal const val MAX_COMMAND_DATA_FIELD_LENGTH = 0xffff

        /**
         * Maximum length of a Response APDU data field (`Le`) per ISO/IEC 7816-4.
         */
        internal const val MAX_RESPONSE_DATA_FIELD_LENGTH = 0x10000

        /**
         * Decodes an APDU.
         *
         * @param encoded the bytes of the APDU
         * @return an object with the decoded fields.
         */
        @OptIn(ExperimentalStdlibApi::class)
        fun decode(encoded: ByteArray): CommandApdu {
            require(encoded.size >= 4)
            val reader = ByteDataReader(encoded)
            val cla = reader.getUInt8()
            val ins = reader.getUInt8()
            val p1 = reader.getUInt8()
            val p2 = reader.getUInt8()
            var payload = ByteString(byteArrayOf())
            var lc = 0
            var le = 0
            if (encoded.size == 5) {
                val encLe = reader.getUInt8().toInt()
                le = if (encLe == 0) 0x100 else encLe
            } else if (encoded.size > 5) {
                lc = reader.getUInt8().toInt()
                var lcEndsAt = 5
                if (lc == 0) {
                    lc = reader.getUInt16().toInt()
                    lcEndsAt = 7
                }
                if (lc > 0 && lcEndsAt + lc <= encoded.size) {
                    val payloadArray = ByteArray(lc)
                    encoded.copyInto(payloadArray, 0, lcEndsAt, lcEndsAt + lc)
                    payload = ByteString(payloadArray)
                } else {
                    lc = 0
                    lcEndsAt = 4
                }
                val leLen = encoded.size - lcEndsAt - lc
                le = when (leLen) {
                    0 -> 0
                    1 -> {
                        val encLe = encoded.getUInt8(encoded.size - 1).toInt()
                        if (encLe == 0x00) 0x100 else encLe
                    }
                    2, 3 -> {
                        val encLe = encoded.getUInt16(encoded.size - 2).toInt()
                        if (encLe == 0x00) 0x10000 else encLe
                    }
                    else -> throw IllegalStateException("Invalid LE len $leLen")
                }
            }
            return CommandApdu(
                cla = cla.toInt(),
                ins = ins.toInt(),
                p1 = p1.toInt(),
                p2 = p2.toInt(),
                payload = payload,
                le = le
            )
        }
    }
}