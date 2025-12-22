package org.multipaz.util

import kotlin.math.min

/**
 * Compresses data using DEFLATE algorithm according to [RFC 1951](https://www.ietf.org/rfc/rfc1951.txt).
 *
 * The implementation may not support [compressionLevel] and use a fixed level instead.
 *
 * @receiver the data to compress.
 * @param compressionLevel must be between 0 and 9, both inclusive.
 * @return the compressed data.
 * @throws IllegalArgumentException if [compressionLevel] isn't valid.
 */
expect suspend fun ByteArray.deflate(compressionLevel: Int = 5): ByteArray

/**
 * Decompresses data compressed DEFLATE algorithm according to [RFC 1951](https://www.ietf.org/rfc/rfc1951.txt).
 *
 * @receiver the compressed data to decompress.
 * @return the decompressed data.
 * @throws IllegalArgumentException if the given data is invalid
 */
expect suspend fun ByteArray.inflate(): ByteArray


/**
 * Computes Andler32 checksum.
 *
 * @receiver data to checksum
 * @return checksum serialized as four bytes, as required for zlib wrapper.
 */
fun ByteArray.adler32(): ByteArray {
    var lo = 1
    var hi = 0
    var i = 0
    while (i < size) {
        // All arithmetic is modulo 65521, but instead of applying mod after every addition
        // we delay actual mod operation until there is a potential to overflow.
        // 3800 is max number of cycles not to overflow in 31 bit
        val count = min(size - i, 3800)
        repeat(count) {
            lo = lo + (this[i++].toInt() and 0xFF)
            hi = hi + lo
        }
        lo %= 65521
        hi %= 65521
    }

    return byteArrayOf(
        ((hi shr 8) and 0xFF).toByte(),
        (hi and 0xFF).toByte(),
        ((lo shr 8) and 0xFF).toByte(),
        (lo and 0xFF).toByte()
    )
}

private val zlibWrapperHeader = byteArrayOf(120, -38)

/**
 * Compresses data using DEFLATE algorithm according to
 * [RFC 1951](https://www.ietf.org/rfc/rfc1951.txt) with zlib wrapper
 * [RFC 1950](https://www.ietf.org/rfc/rfc1950.txt).
 *
 * @receiver the data to compress.
 * @param compressionLevel must be between 0 and 9, both inclusive.
 * @return the compressed data.
 * @throws IllegalArgumentException if [compressionLevel] isn't valid.
 */
suspend fun ByteArray.zlibDeflate(compressionLevel: Int = 9): ByteArray =
    zlibWrapperHeader + deflate() + adler32()


/**
 * Decompresses data compressed DEFLATE algorithm according to
 * [RFC 1951](https://www.ietf.org/rfc/rfc1951.txt) with zlib wrapper
 * [RFC 1950](https://www.ietf.org/rfc/rfc1950.txt).
 *
 * @receiver the compressed data to decompress.
 * @return the decompressed data.
 * @throws IllegalArgumentException if the given data is invalid
 */
suspend fun ByteArray.zlibInflate(): ByteArray {
    if (!(sliceArray(0..<2) contentEquals zlibWrapperHeader)) {
        throw IllegalArgumentException("invalid compression (wrong header)")
    }
    val data = sliceArray(2..<size - 4).inflate()
    val checksum = data.adler32()
    val expectedChecksum = sliceArray(size - 4..<size)
    if (!(checksum contentEquals expectedChecksum)) {
        throw IllegalArgumentException("invalid compression (checksum mismatch)")
    }
    return data
}
