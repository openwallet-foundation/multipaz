package org.multipaz.crypto

import kotlin.concurrent.Volatile

/**
 * Pure-Kotlin implementation of NIST FIPS 202 SHA-3 (SHA3-256, SHA3-384, SHA3-512) and SHAKE256.
 *
 * Used as a fallback on platforms lacking native SHA-3 digest support (such as Android Conscrypt,
 * pre-iOS 26 CryptoKit, and WebCrypto) and for XOF operations (`SHAKE256`) in PQ/T hybrid KEM seed
 * expansion and HPKE `deriveKeyPair`.
 */
internal object Sha3 {

    private const val RATE_BYTES_256 = 136 // 1088 bits (SHA3-256 and SHAKE256)
    private const val RATE_BYTES_384 = 104 // 832 bits (SHA3-384)
    private const val RATE_BYTES_512 = 72 // 576 bits (SHA3-512)
    private const val SHA3_256_OUTPUT_BYTES = 32
    private const val SHA3_384_OUTPUT_BYTES = 48
    private const val SHA3_512_OUTPUT_BYTES = 64
    private const val DOMAIN_SHA3 = 0x06
    private const val DOMAIN_SHAKE = 0x1F
    private const val PAD_FINAL = 0x80

    @Volatile
    private var zeroSink: Long = 0L

    private val ROUND_CONSTANTS: LongArray = longArrayOf(
        0x0000000000000001UL.toLong(), 0x0000000000008082UL.toLong(),
        0x800000000000808aUL.toLong(), 0x8000000080008000UL.toLong(),
        0x000000000000808bUL.toLong(), 0x0000000080000001UL.toLong(),
        0x8000000080008081UL.toLong(), 0x8000000000008009UL.toLong(),
        0x000000000000008aUL.toLong(), 0x0000000000000088UL.toLong(),
        0x0000000080008009UL.toLong(), 0x000000008000000aUL.toLong(),
        0x000000008000808bUL.toLong(), 0x800000000000008bUL.toLong(),
        0x8000000000008089UL.toLong(), 0x8000000000008003UL.toLong(),
        0x8000000000008002UL.toLong(), 0x8000000000000080UL.toLong(),
        0x000000000000800aUL.toLong(), 0x800000008000000aUL.toLong(),
        0x8000000080008081UL.toLong(), 0x8000000000008080UL.toLong(),
        0x0000000080000001UL.toLong(), 0x8000000080008008UL.toLong()
    )

    // Rotation offsets rho[x + 5*y] from FIPS 202 Table 2
    private val RHO_OFFSETS: IntArray = intArrayOf(
        0, 1, 62, 28, 27,
        36, 44, 6, 55, 20,
        3, 10, 43, 25, 39,
        41, 45, 15, 21, 8,
        18, 2, 61, 56, 14
    )

    // Pi lane permutation indices: pi[x + 5*y] = y + 5*((2*x + 3*y) % 5) -> target index for (x, y) -> (y, (2x+3y)%5)
    private val PI_LANES: IntArray = intArrayOf(
        0, 10, 20, 5, 15,
        16, 1, 11, 21, 6,
        7, 17, 2, 12, 22,
        23, 8, 18, 3, 13,
        14, 24, 9, 19, 4
    )

    /**
     * Computes the FIPS 202 SHA3-256 digest of the concatenated [chunks].
     */
    fun sha3_256(vararg chunks: ByteArray): ByteArray =
        sponge(
            chunks = chunks,
            rateBytes = RATE_BYTES_256,
            domainSuffix = DOMAIN_SHA3,
            outputLength = SHA3_256_OUTPUT_BYTES,
        )

    /**
     * Computes the FIPS 202 SHA3-384 digest of the concatenated [chunks].
     */
    fun sha3_384(vararg chunks: ByteArray): ByteArray =
        sponge(
            chunks = chunks,
            rateBytes = RATE_BYTES_384,
            domainSuffix = DOMAIN_SHA3,
            outputLength = SHA3_384_OUTPUT_BYTES,
        )

    /**
     * Computes the FIPS 202 SHA3-512 digest of the concatenated [chunks].
     */
    fun sha3_512(vararg chunks: ByteArray): ByteArray =
        sponge(
            chunks = chunks,
            rateBytes = RATE_BYTES_512,
            domainSuffix = DOMAIN_SHA3,
            outputLength = SHA3_512_OUTPUT_BYTES,
        )

    /**
     * Computes [outputLength] bytes of FIPS 202 SHAKE256 output over the concatenated [chunks].
     */
    fun shake256(vararg chunks: ByteArray, outputLength: Int): ByteArray {
        require(outputLength >= 0) { "outputLength must be non-negative, got $outputLength" }
        return sponge(
            chunks = chunks,
            rateBytes = RATE_BYTES_256,
            domainSuffix = DOMAIN_SHAKE,
            outputLength = outputLength,
        )
    }

    private fun sponge(
        chunks: Array<out ByteArray>,
        rateBytes: Int,
        domainSuffix: Int,
        outputLength: Int
    ): ByteArray {
        val state = LongArray(25)
        val block = ByteArray(rateBytes)
        val output = ByteArray(outputLength)
        try {
            var blockOffset = 0
            for (chunk in chunks) {
                var chunkOffset = 0
                while (chunkOffset < chunk.size) {
                    val toCopy = minOf(rateBytes - blockOffset, chunk.size - chunkOffset)
                    chunk.copyInto(
                        destination = block,
                        destinationOffset = blockOffset,
                        startIndex = chunkOffset,
                        endIndex = chunkOffset + toCopy
                    )
                    blockOffset += toCopy
                    chunkOffset += toCopy
                    if (blockOffset == rateBytes) {
                        xorBlockAndPermute(state, block, rateBytes)
                        blockOffset = 0
                    }
                }
            }

            // Pad remaining bytes in final block with domainSuffix and 0x80 at the last byte
            for (i in blockOffset until rateBytes) {
                block[i] = 0
            }
            block[blockOffset] = (block[blockOffset].toInt() xor domainSuffix).toByte()
            block[rateBytes - 1] = (block[rateBytes - 1].toInt() xor PAD_FINAL).toByte()
            xorBlockAndPermute(state, block, rateBytes)

            var outOffset = 0
            while (outOffset < outputLength) {
                val take = minOf(rateBytes, outputLength - outOffset)
                squeezeBlock(state, output, outOffset, take)
                outOffset += take
                if (outOffset < outputLength) {
                    keccakF1600(state)
                }
            }
            return output
        } finally {
            block.secureZero()
            for (i in state.indices) {
                state[i] = 0L
            }
            zeroSink = state[0]
        }
    }

    private fun xorBlockAndPermute(state: LongArray, block: ByteArray, rateBytes: Int) {
        val rateLanes = rateBytes / 8
        for (i in 0 until rateLanes) {
            val base = i * 8
            val lane = (block[base].toLong() and 0xFFL) or
                ((block[base + 1].toLong() and 0xFFL) shl 8) or
                ((block[base + 2].toLong() and 0xFFL) shl 16) or
                ((block[base + 3].toLong() and 0xFFL) shl 24) or
                ((block[base + 4].toLong() and 0xFFL) shl 32) or
                ((block[base + 5].toLong() and 0xFFL) shl 40) or
                ((block[base + 6].toLong() and 0xFFL) shl 48) or
                ((block[base + 7].toLong() and 0xFFL) shl 56)
            state[i] = state[i] xor lane
        }
        keccakF1600(state)
    }

    private fun squeezeBlock(state: LongArray, out: ByteArray, outOffset: Int, length: Int) {
        var pos = 0
        var laneIdx = 0
        while (pos < length) {
            val lane = state[laneIdx++]
            val laneBytes = minOf(8, length - pos)
            for (b in 0 until laneBytes) {
                out[outOffset + pos + b] = ((lane ushr (b * 8)) and 0xFFL).toByte()
            }
            pos += laneBytes
        }
    }

    private fun keccakF1600(a: LongArray) {
        val c = LongArray(5)
        val b = LongArray(25)
        try {
            for (round in 0 until 24) {
                // Theta
                for (x in 0 until 5) {
                    c[x] = a[x] xor a[x + 5] xor a[x + 10] xor a[x + 15] xor a[x + 20]
                }
                for (x in 0 until 5) {
                    val d = c[(x + 4) % 5] xor c[(x + 1) % 5].rotateLeft(1)
                    for (y in 0 until 5) {
                        val idx = x + 5 * y
                        a[idx] = a[idx] xor d
                    }
                }

                // Rho and Pi
                for (idx in 0 until 25) {
                    b[PI_LANES[idx]] = a[idx].rotateLeft(RHO_OFFSETS[idx])
                }

                // Chi
                for (y in 0 until 5) {
                    val row = 5 * y
                    val b0 = b[row]
                    val b1 = b[row + 1]
                    val b2 = b[row + 2]
                    val b3 = b[row + 3]
                    val b4 = b[row + 4]
                    a[row] = b0 xor (b1.inv() and b2)
                    a[row + 1] = b1 xor (b2.inv() and b3)
                    a[row + 2] = b2 xor (b3.inv() and b4)
                    a[row + 3] = b3 xor (b4.inv() and b0)
                    a[row + 4] = b4 xor (b0.inv() and b1)
                }

                // Iota
                a[0] = a[0] xor ROUND_CONSTANTS[round]
            }
        } finally {
            for (i in c.indices) {
                c[i] = 0L
            }
            for (i in b.indices) {
                b[i] = 0L
            }
            zeroSink = c[0] xor b[0]
        }
    }
}
