package org.multipaz.cbor

import kotlinx.io.bytestring.ByteStringBuilder
import org.multipaz.util.getUInt8

/**
 * Byte String (major type 2), indefinite length.
 *
 * @param chunks the chunks in the byte string.
 */
data class IndefLengthBstr(val chunks: List<ByteArray>) : DataItem(MajorType.BYTE_STRING) {
    override fun encode(builder: ByteStringBuilder) {
        val majorTypeShifted = (majorType.type shl 5)
        builder.append((majorTypeShifted + 31).toByte())
        chunks.forEach {
            Cbor.encodeLength(builder, majorType, it.size)
            builder.append(it)
        }
        builder.append(0xff.toByte())
    }

    companion object {
        internal fun decode(
            encodedCbor: ByteArray,
            offset: Int,
            depth: Int = 0
        ): Pair<Int, IndefLengthBstr> {
            val majorTypeShifted = (MajorType.BYTE_STRING.type shl 5)
            val marker = (majorTypeShifted + 31).toByte()
            check(encodedCbor[offset] == marker)
            val chunks = mutableListOf<ByteArray>()
            var cursor = offset + 1
            while (true) {
                if (cursor >= encodedCbor.size) {
                    throw IllegalArgumentException(
                        "Truncated indefinite-length byte string at offset $offset"
                    )
                }
                if (encodedCbor.getUInt8(cursor) == Cbor.BREAK) {
                    // BREAK code, we're done
                    cursor += 1
                    break
                }
                val (chunkEndOffset, chunk) = Cbor.decode(encodedCbor, cursor, depth)
                check(chunk is Bstr)
                chunks.add(chunk.value)
                check(chunkEndOffset > cursor)
                cursor = chunkEndOffset
            }
            return Pair(cursor, IndefLengthBstr(chunks))
        }

        private val HEX_DIGITS = "0123456789abcdef".toCharArray()
    }

    override fun equals(other: Any?): Boolean =
        other is IndefLengthBstr &&
            chunks.size == other.chunks.size &&
            chunks.indices.all { chunks[it].contentEquals(other.chunks[it]) }

    override fun hashCode(): Int =
        CborHash.hashLongList(CborHash.TAG_INDEF_BSTR, chunks.size) {
            CborHash.hashBytes64(CborHash.TAG_BSTR, chunks[it])
        }

    override fun toString(): String {
        val sb = StringBuilder("IndefLengthBstr(")
        for (chunk in chunks) {
            for (b in chunk) {
                sb.append(HEX_DIGITS[b.toInt().and(0xff) shr 4])
                sb.append(HEX_DIGITS[b.toInt().and(0x0f)])
            }
            sb.append(' ')
        }
        sb.append(")")
        return sb.toString()
    }
}