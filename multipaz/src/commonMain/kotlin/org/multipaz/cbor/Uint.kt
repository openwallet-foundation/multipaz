package org.multipaz.cbor

import kotlinx.io.bytestring.ByteStringBuilder

/**
 * Unsigned Integer (major type 0).
 *
 * @param value the value.
 */
class Uint(val value: ULong) : CborInt(MajorType.UNSIGNED_INTEGER) {
    override fun encode(builder: ByteStringBuilder) {
        Cbor.encodeLength(builder, majorType, value)
    }

    companion object {
        internal fun decode(encodedCbor: ByteArray, offset: Int): Pair<Int, Uint> {
            val (newOffset, value) = Cbor.decodeLength(encodedCbor, offset)
            return Pair(newOffset, Uint(value))
        }
    }

    override fun equals(other: Any?): Boolean = other is Uint && value == other.value

    override fun hashCode(): Int = CborHash.hashLong(CborHash.TAG_UINT, value.toLong())

    override fun toString() = "Uint($value)"
}