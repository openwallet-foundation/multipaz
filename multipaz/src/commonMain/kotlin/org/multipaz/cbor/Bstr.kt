package org.multipaz.cbor

import kotlinx.io.bytestring.ByteStringBuilder

/**
 * Byte String (major type 2).
 *
 * @param value the [ByteArray] for the value of the byte string.
 */
data class Bstr(val value: ByteArray) : DataItem(MajorType.BYTE_STRING) {
    override fun encode(builder: ByteStringBuilder) {
        Cbor.encodeLength(builder, majorType, value.size)
        builder.append(value)
    }

    companion object {
        internal fun decode(encodedCbor: ByteArray, offset: Int): Pair<Int, Bstr> {
            val (payloadBegin, length) = Cbor.decodeLength(encodedCbor, offset)
            val remaining = (encodedCbor.size - payloadBegin).toULong()
            if (length > remaining) {
                throw IllegalArgumentException(
                    "Byte string length $length exceeds remaining $remaining bytes at offset $offset"
                )
            }
            val payloadEnd = payloadBegin + length.toInt()
            val slice = encodedCbor.sliceArray(IntRange(payloadBegin, payloadEnd - 1))
            return Pair(payloadEnd, Bstr(slice))
        }

        private val HEX_DIGITS = "0123456789abcdef".toCharArray()
    }

    override fun equals(other: Any?): Boolean = other is Bstr && value.contentEquals(other.value)

    override fun hashCode(): Int = CborHash.hashBytes(CborHash.TAG_BSTR, value)

    override fun toString(): String {
        val sb = StringBuilder("Bstr(")
        for (b in value) {
            sb.append(HEX_DIGITS[b.toInt().and(0xff) shr 4])
            sb.append(HEX_DIGITS[b.toInt().and(0x0f)])
        }
        sb.append(")")
        return sb.toString()
    }
}