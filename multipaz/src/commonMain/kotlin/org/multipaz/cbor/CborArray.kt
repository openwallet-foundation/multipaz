package org.multipaz.cbor

import kotlinx.io.bytestring.ByteStringBuilder
import org.multipaz.util.getUInt8

/**
 * Array (major type 4).
 *
 * @param items a list with the items in the array.
 * @param indefiniteLength whether the array is to be encoded or was encoded using indefinite length.
 */
class CborArray(
    val items: MutableList<DataItem>,
    val indefiniteLength: Boolean = false
) : DataItem(MajorType.ARRAY) {
    override fun encode(builder: ByteStringBuilder) {
        if (indefiniteLength) {
            val majorTypeShifted = (majorType.type shl 5)
            builder.append((majorTypeShifted + 31).toByte())
            items.forEach { it.encode(builder) }
            builder.append(0xff.toByte())
        } else {
            Cbor.encodeLength(builder, majorType, items.size)
            items.forEach { it.encode(builder) }
        }
    }

    companion object {
        /**
         * Creates a new builder.
         *
         * @return an [ArrayBuilder], call [ArrayBuilder.end] when done adding items to get a
         * [CborBuilder].
         */
        fun builder(): ArrayBuilder<CborBuilder> {
            val dataItem = CborArray(mutableListOf())
            return ArrayBuilder(CborBuilder(dataItem), dataItem)
        }

        internal fun decode(encodedCbor: ByteArray, offset: Int): Pair<Int, CborArray> {
            val lowBits = encodedCbor[offset].toInt().and(0x1f)
            if (lowBits == 31) {
                // indefinite length
                var cursor = offset + 1
                val items = mutableListOf<DataItem>()
                while (true) {
                    if (encodedCbor.getUInt8(cursor) == Cbor.BREAK) {
                        // BREAK code, we're done
                        cursor += 1
                        break
                    }
                    val (nextItemOffset, item) = Cbor.decode(encodedCbor, cursor)
                    items.add(item)
                    check(nextItemOffset > cursor)
                    cursor = nextItemOffset
                }
                return Pair(cursor, CborArray(items, true))
            } else {
                var (cursor, numItems) = Cbor.decodeLength(encodedCbor, offset)
                val items = mutableListOf<DataItem>()
                if (numItems == 0UL) {
                    return Pair(cursor, CborArray(mutableListOf()))
                }
                for (n in IntRange(0, numItems.toInt() - 1)) {
                    val (nextItemOffset, item) = Cbor.decode(encodedCbor, cursor)
                    items.add(item)
                    check(nextItemOffset > cursor)
                    cursor = nextItemOffset
                }
                return Pair(cursor, CborArray(items))
            }
        }
    }

    override fun equals(other: Any?): Boolean {
        if (!(other is CborArray)) {
            return false
        }
        if (other.items.size != items.size) {
            return false
        }
        for (n in IntRange(0, items.size - 1)) {
            if (!items[n].equals(other.items[n])) {
                return false
            }
        }
        return true
    }

    override fun hashCode(): Int {
        var result = 0
        for (item in items) {
            result = 31*result + item.hashCode()
        }
        return result
    }

    override fun toString(): String {
        val sb = StringBuilder("CborArray(")
        if (indefiniteLength) {
            sb.append("_ ")
        }
        var first = true
        for (item in items) {
            if (!first) {
                sb.append(", ")
            }
            first = false
            sb.append("$item")
        }
        sb.append(")")
        return sb.toString()
    }
}

/**
 * Builds a [DataItem] for a CBOR array with the given builder action.
 *
 * Example usage:
 * ```
 * val dataItem = buildCborArray {
 *     add("stuff")
 *     add(42)
 *     addCborArray {
 *         add("foo")
 *         add("bar")
 *     }
 *     addCborMap {
 *         put("a", "foo")
 *         put("b", "bar")
 *     }
 * }
 * ```
 *
 * @param builderAction the builder action.
 * @return the resulting [DataItem].
 */
fun buildCborArray(
    builderAction: ArrayBuilder<CborBuilder>.() -> Unit
): DataItem {
    val builder = CborArray.builder()
    builder.builderAction()
    return builder.end().build()
}

/**
 * Adds the [DataItem] for a CBOR array produced by the given builder action to a CBOR array.
 *
 * @param builderAction the builder action.
 */
fun<T> ArrayBuilder<T>.addCborArray(builderAction: ArrayBuilder<ArrayBuilder<T>>.() -> Unit) {
    val innerBuilder = addArray()
    innerBuilder.builderAction()
    innerBuilder.end()
}

/**
 * Adds the [DataItem] for a CBOR map produced by the given builder action to a CBOR array.
 *
 * @param builderAction the builder action.
 */
fun<T> ArrayBuilder<T>.addCborMap(builderAction: MapBuilder<ArrayBuilder<T>>.() -> Unit) {
    val innerBuilder = addMap()
    innerBuilder.builderAction()
    innerBuilder.end()
}
