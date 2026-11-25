package org.multipaz.cbor

import org.multipaz.crypto.Crypto

/**
 * Keyed SipHash-2-4 utilities for hash-flooding-resistant [DataItem.hashCode] implementations.
 */
internal object CborHash {
    internal const val TAG_UINT = 0x01
    internal const val TAG_NINT = 0x02
    internal const val TAG_BSTR = 0x03
    internal const val TAG_TSTR = 0x04
    internal const val TAG_ARRAY = 0x05
    internal const val TAG_MAP = 0x06
    internal const val TAG_MAP_ENTRY = 0x07
    internal const val TAG_TAGGED = 0x08
    internal const val TAG_SIMPLE = 0x09
    internal const val TAG_FLOAT = 0x0a
    internal const val TAG_DOUBLE = 0x0b
    internal const val TAG_INDEF_BSTR = 0x0c
    internal const val TAG_INDEF_TSTR = 0x0d
    internal const val TAG_RAW_CBOR = 0x0e

    private const val V0_INIT = 0x736f6d6570736575L
    private const val V1_INIT = 0x646f72616e646f6dL
    private const val V2_INIT = 0x6c7967656e657261L
    private const val V3_INIT = 0x7465646279746573L

    private val key0: Long
    private val key1: Long

    init {
        val keyBytes = Crypto.secureRandom.nextBytes(16)
        key0 = readLe64(keyBytes, 0)
        key1 = readLe64(keyBytes, 8)
    }

    internal class SipHashState(k0: Long, k1: Long) {
        private var v0 = k0 xor V0_INIT
        private var v1 = k1 xor V1_INIT
        private var v2 = k0 xor V2_INIT
        private var v3 = k1 xor V3_INIT

        private fun sipRound() {
            v0 += v1
            v1 = v1.rotateLeft(13)
            v1 = v1 xor v0
            v0 = v0.rotateLeft(32)
            v2 += v3
            v3 = v3.rotateLeft(16)
            v3 = v3 xor v2
            v0 += v3
            v3 = v3.rotateLeft(21)
            v3 = v3 xor v0
            v2 += v1
            v1 = v1.rotateLeft(17)
            v1 = v1 xor v2
            v2 = v2.rotateLeft(32)
        }

        fun absorb(block: Long) {
            v3 = v3 xor block
            sipRound()
            sipRound()
            v0 = v0 xor block
        }

        fun finish(lastBlock: Long): Long {
            absorb(lastBlock)
            v2 = v2 xor 0xffL
            sipRound()
            sipRound()
            sipRound()
            sipRound()
            return v0 xor v1 xor v2 xor v3
        }

        fun finishFolded(lastBlock: Long): Int = foldToInt(finish(lastBlock))
    }

    private fun readLe64(bytes: ByteArray, offset: Int): Long =
        (bytes[offset].toLong() and 0xffL) or
            ((bytes[offset + 1].toLong() and 0xffL) shl 8) or
            ((bytes[offset + 2].toLong() and 0xffL) shl 16) or
            ((bytes[offset + 3].toLong() and 0xffL) shl 24) or
            ((bytes[offset + 4].toLong() and 0xffL) shl 32) or
            ((bytes[offset + 5].toLong() and 0xffL) shl 40) or
            ((bytes[offset + 6].toLong() and 0xffL) shl 48) or
            ((bytes[offset + 7].toLong() and 0xffL) shl 56)

    private fun foldToInt(hash: Long): Int = (hash xor (hash ushr 32)).toInt()

    private fun absorbBytesAndFinish(
        state: SipHashState,
        bytes: ByteArray,
        lengthOffset: Long
    ): Long {
        var i = 0
        while (i + 8 <= bytes.size) {
            state.absorb(readLe64(bytes, i))
            i += 8
        }
        var last = ((lengthOffset + bytes.size.toLong()) and 0xffL) shl 56
        var j = 0
        while (i + j < bytes.size) {
            last = last or ((bytes[i + j].toLong() and 0xffL) shl (j * 8))
            j++
        }
        return state.finish(last)
    }

    internal fun newState(): SipHashState = SipHashState(key0, key1)

    internal fun sipHash24(k0: Long, k1: Long, data: ByteArray): Long =
        absorbBytesAndFinish(SipHashState(k0, k1), data, 0L)

    internal fun hashLong(tag: Int, value: Long): Int {
        val state = SipHashState(key0, key1)
        state.absorb(value)
        return state.finishFolded((tag.toLong() and 0xffL) or (9L shl 56))
    }

    internal fun hashTwoLongs(tag: Int, a: Long, b: Long): Int {
        val state = SipHashState(key0, key1)
        state.absorb(a)
        state.absorb(b)
        return state.finishFolded((tag.toLong() and 0xffL) or (17L shl 56))
    }

    internal fun hashTwoInts64(tag: Int, a: Int, b: Int): Long {
        val state = SipHashState(key0, key1)
        val packed = (a.toLong() and 0xffffffffL) or ((b.toLong() and 0xffffffffL) shl 32)
        state.absorb(packed)
        return state.finish((tag.toLong() and 0xffL) or (9L shl 56))
    }

    internal fun hashBytes64(tag: Int, bytes: ByteArray): Long {
        val state = SipHashState(key0, key1)
        state.absorb(tag.toLong() and 0xffL)
        return absorbBytesAndFinish(state, bytes, 8L)
    }

    internal fun hashBytes(tag: Int, bytes: ByteArray): Int = foldToInt(hashBytes64(tag, bytes))

    internal fun hashString64(tag: Int, value: String): Long {
        val state = SipHashState(key0, key1)
        state.absorb(tag.toLong() and 0xffL)
        val len = value.length
        var i = 0
        while (i + 4 <= len) {
            val block = (value[i].code.toLong() and 0xffffL) or
                ((value[i + 1].code.toLong() and 0xffffL) shl 16) or
                ((value[i + 2].code.toLong() and 0xffffL) shl 32) or
                ((value[i + 3].code.toLong() and 0xffffL) shl 48)
            state.absorb(block)
            i += 4
        }
        var last = ((8L + len.toLong() * 2L) and 0xffL) shl 56
        var shift = 0
        while (i < len) {
            last = last or ((value[i].code.toLong() and 0xffffL) shl shift)
            shift += 16
            i++
        }
        return state.finish(last)
    }

    internal fun hashString(tag: Int, value: String): Int = foldToInt(hashString64(tag, value))

    internal inline fun hashIntList(
        tag: Int,
        count: Int,
        elementAt: (Int) -> Int
    ): Int {
        val state = newState()
        state.absorb(tag.toLong() and 0xffL)
        var i = 0
        while (i + 2 <= count) {
            val block = (elementAt(i).toLong() and 0xffffffffL) or
                ((elementAt(i + 1).toLong() and 0xffffffffL) shl 32)
            state.absorb(block)
            i += 2
        }
        var last = ((8L + count.toLong() * 4L) and 0xffL) shl 56
        if (i < count) {
            last = last or (elementAt(i).toLong() and 0xffffffffL)
        }
        return state.finishFolded(last)
    }

    internal inline fun hashLongList(
        tag: Int,
        count: Int,
        elementAt: (Int) -> Long
    ): Int {
        val state = newState()
        state.absorb(tag.toLong() and 0xffL)
        for (i in 0 until count) {
            state.absorb(elementAt(i))
        }
        val last = ((8L + count.toLong() * 8L) and 0xffL) shl 56
        return state.finishFolded(last)
    }
}
