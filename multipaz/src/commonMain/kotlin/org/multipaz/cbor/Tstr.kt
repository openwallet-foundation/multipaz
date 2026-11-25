package org.multipaz.cbor

import kotlinx.io.bytestring.ByteStringBuilder

/**
 * Unicode String (major type 3).
 *
 * @param value the [String] for the value of the byte string.
 */
class Tstr(val value: String) : DataItem(MajorType.UNICODE_STRING) {
    override fun encode(builder: ByteStringBuilder) {
        val encodedValue = value.encodeToByteArray()
        Cbor.encodeLength(builder, majorType, encodedValue.size)
        builder.append(value.encodeToByteArray())
    }

    companion object {
        internal fun decode(encodedCbor: ByteArray, offset: Int): Pair<Int, Tstr> {
            val (payloadBegin, length) = Cbor.decodeLength(encodedCbor, offset)
            val remaining = (encodedCbor.size - payloadBegin).toULong()
            if (length > remaining) {
                throw IllegalArgumentException(
                    "Text string length $length exceeds remaining $remaining bytes at offset $offset"
                )
            }
            val payloadEnd = payloadBegin + length.toInt()
            val slice = encodedCbor.sliceArray(IntRange(payloadBegin, payloadEnd - 1))
            return Pair(payloadEnd, Tstr(slice.decodeToString()))
        }
    }

    override fun equals(other: Any?): Boolean = other is Tstr && value == other.value

    override fun hashCode(): Int = CborHash.hashString(CborHash.TAG_TSTR, value)

    override fun toString(): String = "Tstr(\"$value\")"
}