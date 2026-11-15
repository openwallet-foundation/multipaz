package org.multipaz.crypto

import org.multipaz.util.fromHex

/**
 * Pure-Kotlin elliptic curve point decompression and on-curve validation for SEC 1 / RFC 9053
 * Weierstrass and Brainpool curves where $p \equiv 3 \pmod 4$.
 */
internal object EcPointDecompression {

    private class CurveParams(
        val byteLen: Int,
        val p: UIntArray,
        val a: UIntArray,
        val b: UIntArray,
        val sqrtExp: UIntArray, // (p + 1) / 4
    ) {
        companion object {
            fun fromHexParams(byteLen: Int, pHex: String, aHex: String, bHex: String): CurveParams {
                val pBytes = pHex.fromHex()
                val numLimbs = (byteLen + 3) / 4
                val p = fromBigEndian(pBytes, numLimbs)
                val a = fromBigEndian(aHex.fromHex(), numLimbs)
                val b = fromBigEndian(bHex.fromHex(), numLimbs)
                check((p[0] and 3u) == 3u) { "Expected p == 3 (mod 4)" }
                // Compute (p + 1) / 4
                val pPlusOne = UIntArray(numLimbs + 1)
                var carry = 1UL
                for (i in 0 until numLimbs) {
                    val sum = p[i].toULong() + carry
                    pPlusOne[i] = sum.toUInt()
                    carry = sum shr 32
                }
                pPlusOne[numLimbs] = carry.toUInt()
                val sqrtExp = UIntArray(numLimbs)
                for (i in 0 until numLimbs) {
                    sqrtExp[i] = (pPlusOne[i] shr 2) or (pPlusOne[i + 1] shl 30)
                }
                return CurveParams(byteLen, p, a, b, sqrtExp)
            }
        }
    }

    private val P256_PARAMS by lazy {
        CurveParams.fromHexParams(
            byteLen = 32,
            pHex = "ffffffff00000001000000000000000000000000ffffffffffffffffffffffff",
            aHex = "ffffffff00000001000000000000000000000000fffffffffffffffffffffffc",
            bHex = "5ac635d8aa3a93e7b3ebbd55769886bc651d06b0cc53b0f63bce3c3e27d2604b",
        )
    }

    private val P384_PARAMS by lazy {
        CurveParams.fromHexParams(
            byteLen = 48,
            pHex = "fffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffeffffffff0000000000000000ffffffff",
            aHex = "fffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffeffffffff0000000000000000fffffffc",
            bHex = "b3312fa7e23ee7e4988e056be3f82d19181d9c6efe8141120314088f5013875ac656398d8a2ed19d2a85c8edd3ec2aef",
        )
    }

    private val P521_PARAMS by lazy {
        CurveParams.fromHexParams(
            byteLen = 66,
            pHex = "01ffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff",
            aHex = "01fffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffc",
            bHex = "0051953eb9618e1c9a1f929a21a0b68540eea2da725b99b315f3b8b489918ef109e156193951ec7e937b1652c0bd3bb1bf073573df883d2c34f1ef451fd46b503f00",
        )
    }

    private val BRAINPOOLP256R1_PARAMS by lazy {
        CurveParams.fromHexParams(
            byteLen = 32,
            pHex = "a9fb57dba1eea9bc3e660a909d838d726e3bf623d52620282013481d1f6e5377",
            aHex = "7d5a0975fc2c3057eef67530417affe7fb8055c126dc5c6ce94a4b44f330b5d9",
            bHex = "26dc5c6ce94a4b44f330b5d9bbd77cbf958416295cf7e1ce6bccdc18ff8c07b6",
        )
    }

    private val BRAINPOOLP320R1_PARAMS by lazy {
        CurveParams.fromHexParams(
            byteLen = 40,
            pHex = "d35e472036bc4fb7e13c785ed201e065f98fcfa6f6f40def4f92b9ec7893ec28fcd412b1f1b32e27",
            aHex = "3ee30b568fbab0f883ccebd46d3f3bb8a2a73513f5eb79da66190eb085ffa9f492f375a97d860eb4",
            bHex = "520883949dfdbc42d3ad198640688a6fe13f41349554b49acc31dccd884539816f5eb4ac8fb1f1a6",
        )
    }

    private val BRAINPOOLP384R1_PARAMS by lazy {
        CurveParams.fromHexParams(
            byteLen = 48,
            pHex = "8cb91e82a3386d280f5d6f7e50e641df152f7109ed5456b412b1da197fb71123acd3a729901d1a71874700133107ec53",
            aHex = "7bc382c63d8c150c3c72080ace05afa0c2bea28e4fb22787139165efba91f90f8aa5814a503ad4eb04a8c7dd22ce2826",
            bHex = "04a8c7dd22ce28268b39b55416f0447c2fb77de107dcd2a62e880ea53eeb62d57cb4390295dbc9943ab78696fa504c11",
        )
    }

    private val BRAINPOOLP512R1_PARAMS by lazy {
        CurveParams.fromHexParams(
            byteLen = 64,
            pHex = "aadd9db8dbe9c48b3fd4e6ae33c9fc07cb308db3b3c9d20ed6639cca703308717d4d9b009bc66842aecda12ae6a380e62881ff2f2d82c68528aa6056583a48f3",
            aHex = "7830a3318b603b89e2327145ac234cc594cbdd8d3df91610a83441caea9863bc2ded5d5aa8253aa10a2ef1c98b9ac8b57f1117a72bf2c7b9e7c1ac4d77fc94ca",
            bHex = "3df91610a83441caea9863bc2ded5d5aa8253aa10a2ef1c98b9ac8b57f1117a72bf2c7b9e7c1ac4d77fc94cadc083e67984050b75ebae5dd2809bd638016f723",
        )
    }

    private fun paramsFor(curve: EcCurve): CurveParams = when (curve) {
        EcCurve.P256 -> P256_PARAMS
        EcCurve.P384 -> P384_PARAMS
        EcCurve.P521 -> P521_PARAMS
        EcCurve.BRAINPOOLP256R1 -> BRAINPOOLP256R1_PARAMS
        EcCurve.BRAINPOOLP320R1 -> BRAINPOOLP320R1_PARAMS
        EcCurve.BRAINPOOLP384R1 -> BRAINPOOLP384R1_PARAMS
        EcCurve.BRAINPOOLP512R1 -> BRAINPOOLP512R1_PARAMS
        else -> throw IllegalArgumentException("Point decompression is not supported for curve $curve")
    }

    /**
     * Computes the uncompressed big-endian `y` coordinate for `(x, yParity)` on [curve].
     *
     * @param curve an EC2 Weierstrass or Brainpool curve.
     * @param xBytes big-endian `x` coordinate of length `(curve.bitSize + 7) / 8`.
     * @param yParity `true` if `y` is odd (COSE `-3: true`), `false` if `y` is even (COSE `-3: false`).
     * @throws IllegalArgumentException if `xBytes` is out of range or has no point on [curve].
     */
    fun decompressY(curve: EcCurve, xBytes: ByteArray, yParity: Boolean): ByteArray {
        val params = paramsFor(curve)
        require(xBytes.size == params.byteLen) {
            "Expected ${params.byteLen}-byte x coordinate for $curve, got ${xBytes.size}"
        }
        val n = params.p.size
        val x = fromBigEndian(xBytes, n)
        require(compare(x, params.p) < 0) {
            "Invalid EC2 x coordinate: x >= p for $curve"
        }

        val rhs = evalRhs(x, params)
        var y = modPow(rhs, params.sqrtExp, params.p)
        val y2 = mulMod(y, y, params.p)
        require(compare(y2, rhs) == 0) {
            "Invalid compressed EC2 point: x coordinate is not on curve $curve"
        }

        val yIsOdd = (y[0] and 1u) == 1u
        if (yIsOdd != yParity) {
            require(!isZero(y)) {
                "Invalid compressed EC2 point: y is zero so odd parity is impossible on $curve"
            }
            val negY = params.p.copyOf()
            subInPlace(negY, y)
            y = negY
        }
        return toBigEndian(y, params.byteLen)
    }

    /**
     * Validates that `(xBytes, yBytes)` represents a valid affine point on [curve].
     */
    fun checkPointOnCurve(curve: EcCurve, xBytes: ByteArray, yBytes: ByteArray) {
        val params = paramsFor(curve)
        require(xBytes.size == params.byteLen && yBytes.size == params.byteLen) {
            "Expected ${params.byteLen}-byte coordinates for $curve, got (${xBytes.size}, ${yBytes.size})"
        }
        val n = params.p.size
        val x = fromBigEndian(xBytes, n)
        val y = fromBigEndian(yBytes, n)
        require(compare(x, params.p) < 0 && compare(y, params.p) < 0) {
            "Invalid EC2 coordinates: coordinate >= p for $curve"
        }
        val rhs = evalRhs(x, params)
        val lhs = mulMod(y, y, params.p)
        require(compare(lhs, rhs) == 0) {
            "Invalid EC2 public key: point is not on curve $curve"
        }
    }

    private fun evalRhs(x: UIntArray, params: CurveParams): UIntArray {
        val x2 = mulMod(x, x, params.p)
        val x3 = mulMod(x2, x, params.p)
        val ax = mulMod(params.a, x, params.p)
        val x3PlusAx = addMod(x3, ax, params.p)
        return addMod(x3PlusAx, params.b, params.p)
    }

    private fun fromBigEndian(bytes: ByteArray, numLimbs: Int): UIntArray {
        val limbs = UIntArray(numLimbs)
        var bitPos = 0
        for (i in bytes.indices.reversed()) {
            val b = bytes[i].toInt() and 0xff
            val limbIdx = bitPos ushr 5
            val shift = bitPos and 31
            limbs[limbIdx] = limbs[limbIdx] or (b.toUInt() shl shift)
            bitPos += 8
        }
        return limbs
    }

    private fun toBigEndian(limbs: UIntArray, byteLen: Int): ByteArray {
        val out = ByteArray(byteLen)
        var bitPos = 0
        for (i in out.indices.reversed()) {
            val limbIdx = bitPos ushr 5
            val shift = bitPos and 31
            out[i] = if (limbIdx < limbs.size) (limbs[limbIdx] shr shift).toByte() else 0
            bitPos += 8
        }
        return out
    }

    private fun isZero(a: UIntArray): Boolean = a.all { it == 0u }

    private fun compare(a: UIntArray, b: UIntArray): Int {
        val maxLen = maxOf(a.size, b.size)
        for (i in maxLen - 1 downTo 0) {
            val av = if (i < a.size) a[i] else 0u
            val bv = if (i < b.size) b[i] else 0u
            if (av != bv) return if (av < bv) -1 else 1
        }
        return 0
    }

    private fun subInPlace(a: UIntArray, b: UIntArray) {
        var borrow = 0L
        for (i in a.indices) {
            val av = a[i].toLong() and 0xffffffffL
            val bv = if (i < b.size) (b[i].toLong() and 0xffffffffL) else 0L
            val diff = av - bv - borrow
            if (diff < 0L) {
                a[i] = (diff + 0x1_0000_0000L).toUInt()
                borrow = 1L
            } else {
                a[i] = diff.toUInt()
                borrow = 0L
            }
        }
        check(borrow == 0L)
    }

    private fun addMod(a: UIntArray, b: UIntArray, p: UIntArray): UIntArray {
        val n = p.size
        val res = UIntArray(n + 1)
        var carry = 0UL
        for (i in 0 until n) {
            val sum = a[i].toULong() + b[i].toULong() + carry
            res[i] = sum.toUInt()
            carry = sum shr 32
        }
        res[n] = carry.toUInt()
        if (compare(res, p) >= 0) {
            subInPlace(res, p)
        }
        return res.copyOf(n)
    }

    private fun mulMod(a: UIntArray, b: UIntArray, p: UIntArray): UIntArray {
        val n = p.size
        val prod = UIntArray(2 * n)
        for (i in 0 until n) {
            val ai = a[i].toULong()
            if (ai == 0UL) continue
            var carry = 0UL
            for (j in 0 until n) {
                val sum = prod[i + j].toULong() + ai * b[j].toULong() + carry
                prod[i + j] = sum.toUInt()
                carry = sum shr 32
            }
            prod[i + n] = carry.toUInt()
        }
        var topLimb = prod.size - 1
        while (topLimb >= 0 && prod[topLimb] == 0u) topLimb--
        if (topLimb < 0) return UIntArray(n)
        val totalBits = topLimb * 32 + (32 - prod[topLimb].countLeadingZeroBits())

        val rem = UIntArray(n + 1)
        for (bitIdx in totalBits - 1 downTo 0) {
            var carry = (prod[bitIdx ushr 5] shr (bitIdx and 31)) and 1u
            for (k in 0..n) {
                val nextCarry = rem[k] shr 31
                rem[k] = (rem[k] shl 1) or carry
                carry = nextCarry
            }
            if (compare(rem, p) >= 0) {
                subInPlace(rem, p)
            }
        }
        return rem.copyOf(n)
    }

    private fun modPow(base: UIntArray, exp: UIntArray, p: UIntArray): UIntArray {
        val n = p.size
        var topLimb = exp.size - 1
        while (topLimb >= 0 && exp[topLimb] == 0u) topLimb--
        val result = UIntArray(n)
        result[0] = 1u
        if (topLimb < 0) return result

        val totalBits = topLimb * 32 + (32 - exp[topLimb].countLeadingZeroBits())
        var acc = result
        for (bitIdx in totalBits - 1 downTo 0) {
            acc = mulMod(acc, acc, p)
            val bit = (exp[bitIdx ushr 5] shr (bitIdx and 31)) and 1u
            if (bit == 1u) {
                acc = mulMod(acc, base, p)
            }
        }
        return acc
    }
}
