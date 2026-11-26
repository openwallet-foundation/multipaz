package org.multipaz.cbor

import kotlinx.io.bytestring.ByteStringBuilder
import org.multipaz.util.getUInt8

/**
 * Unicode String (major type 3), indefinite length.
 *
 * @param chunks the chunks in the string.
 */
data class IndefLengthTstr(val chunks: List<String>) : DataItem(MajorType.UNICODE_STRING) {

    override fun encode(builder: ByteStringBuilder) {

        val majorTypeShifted = (majorType.type shl 5)
        builder.append((majorTypeShifted + 31).toByte())
        chunks.forEach() {
            val encodedStr = it.encodeToByteArray()
            Cbor.encodeLength(builder, majorType, encodedStr.size)
            builder.append(encodedStr)
        }
        builder.append(0xff.toByte())
    }

    companion object {
        internal fun decode(
            encodedCbor: ByteArray,
            offset: Int,
            depth: Int = 0
        ): Pair<Int, IndefLengthTstr> {
            val majorTypeShifted = (MajorType.UNICODE_STRING.type shl 5)
            val marker = (majorTypeShifted + 31).toByte()
            check(encodedCbor[offset] == marker)
            val chunks = mutableListOf<String>()
            var cursor = offset + 1
            while (true) {
                if (cursor >= encodedCbor.size) {
                    throw IllegalArgumentException(
                        "Truncated indefinite-length text string at offset $offset"
                    )
                }
                if (encodedCbor.getUInt8(cursor) == Cbor.BREAK) {
                    // BREAK code, we're done
                    cursor += 1
                    break
                }
                val (chunkEndOffset, chunk) = Cbor.decode(encodedCbor, cursor, depth)
                check(chunk is Tstr)
                chunks.add(chunk.value)
                check(chunkEndOffset > cursor)
                cursor = chunkEndOffset
            }
            return Pair(cursor, IndefLengthTstr(chunks))
        }
    }

    override fun hashCode(): Int =
        CborHash.hashLongList(CborHash.TAG_INDEF_TSTR, chunks.size) {
            CborHash.hashString64(CborHash.TAG_TSTR, chunks[it])
        }

    override fun toString(): String {
        val sb = StringBuilder("IndefLengthTstr(")
        for (chunk in chunks) {
            sb.append("\"$chunk\"")
            sb.append(' ')
        }
        sb.append(")")
        return sb.toString()
    }
}