package org.multipaz.cbor

import kotlinx.io.bytestring.ByteStringBuilder

/**
 * Negative Integer (major type 1).
 *
 * @param value the value, without the sign.
 */
class Nint(val value: ULong) : CborInt(MajorType.NEGATIVE_INTEGER) {
    override fun encode(builder: ByteStringBuilder) =
        Cbor.encodeLength(builder, majorType, value - 1UL)


    companion object {
        internal fun decode(encodedCbor: ByteArray, offset: Int): Pair<Int, Nint> {
            val (newOffset, value) = Cbor.decodeLength(encodedCbor, offset)
            return Pair(newOffset, Nint(value + 1UL))
        }
    }

    override fun equals(other: Any?): Boolean = other is Nint && value == other.value

    override fun hashCode(): Int = CborHash.hashLong(CborHash.TAG_NINT, value.toLong())

    override fun toString(): String = "Nint($value)"

}
