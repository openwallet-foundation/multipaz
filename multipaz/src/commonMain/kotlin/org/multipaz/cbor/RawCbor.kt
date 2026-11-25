package org.multipaz.cbor

import kotlinx.io.bytestring.ByteStringBuilder

/**
 * Data item for raw CBOR.
 *
 * This type can be used to insert a raw CBOR into arrays, maps, or tags without
 * having to first use [Cbor.decode] to obtain a [DataItem]. This is never
 * returned when decoding bytes of valid CBOR.
 *
 * Note that the validity of the passed in encoded CBOR value isn't verified so
 * care should be taken when using this.
 *
 * @param encodedCbor the bytes of valid CBOR.
 */
class RawCbor(val encodedCbor: ByteArray)
    : DataItem(MajorType.fromInt(encodedCbor[0].toInt().and(0xff) ushr 5)) {

    override fun encode(builder: ByteStringBuilder) {
        builder.append(encodedCbor)
    }

    override fun equals(other: Any?): Boolean = other is RawCbor &&
            encodedCbor.contentEquals(other.encodedCbor)

    override fun hashCode(): Int = CborHash.hashBytes(CborHash.TAG_RAW_CBOR, encodedCbor)

    override fun toString(): String {
        val sb = StringBuilder("RawCbor(")
        for (b in encodedCbor) {
            sb.append(b.toString(16))
        }
        sb.append(")")
        return sb.toString()
    }
}