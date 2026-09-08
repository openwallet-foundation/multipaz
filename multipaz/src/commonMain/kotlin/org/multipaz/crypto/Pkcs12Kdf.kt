package org.multipaz.crypto

/**
 * PKCS#12 Key Derivation Function according to
 * [RFC 7292 Appendix B](https://datatracker.ietf.org/doc/html/rfc7292#appendix-B).
 */
internal object Pkcs12Kdf {

    const val ID_ENCRYPTION_KEY: Byte = 1
    const val ID_IV: Byte = 2
    const val ID_MAC_KEY: Byte = 3

    private fun getHashSizes(algorithm: Algorithm): Pair<Int, Int> =
        when (algorithm) {
            Algorithm.INSECURE_SHA1 -> Pair(20, 64)   // u = 20, v = 64
            Algorithm.SHA256 -> Pair(32, 64)          // u = 32, v = 64
            Algorithm.SHA384 -> Pair(48, 128)         // u = 48, v = 128
            Algorithm.SHA512 -> Pair(64, 128)         // u = 64, v = 128
            else -> throw IllegalArgumentException("$algorithm is not a supported hash algorithm for PKCS#12 KDF")
        }

    /**
     * Converts a passphrase string into BMPString bytes with two trailing NULL bytes,
     * as required by PKCS#12 (RFC 7292 Appendix B.1).
     *
     * If [passphrase] is `null`, an empty byte array is returned.
     */
    fun passwordToPkcs12Bytes(passphrase: String?): ByteArray {
        if (passphrase == null) return ByteArray(0)
        val bytes = ByteArray((passphrase.length + 1) * 2)
        for (i in passphrase.indices) {
            val code = passphrase[i].code
            bytes[i * 2] = (code ushr 8).toByte()
            bytes[i * 2 + 1] = (code and 0xff).toByte()
        }
        bytes[bytes.size - 2] = 0
        bytes[bytes.size - 1] = 0
        return bytes
    }

    /**
     * Derives a key of [keyLength] bytes according to RFC 7292 Appendix B.
     *
     * @param idByte the diversifier byte (1 for encryption key, 2 for IV, 3 for MAC key).
     * @param passphrase the passphrase string, or `null`.
     * @param salt the salt bytes.
     * @param iterationCount the iteration count (c >= 1).
     * @param keyLength the requested key length in bytes.
     * @param algorithm the hash algorithm to use.
     * @return the derived key bytes.
     */
    suspend fun deriveKey(
        idByte: Byte,
        passphrase: String?,
        salt: ByteArray,
        iterationCount: Int,
        keyLength: Int,
        algorithm: Algorithm = Algorithm.SHA256
    ): ByteArray {
        require(iterationCount >= 1) { "Iteration count must be at least 1" }
        require(keyLength >= 1) { "Key length must be at least 1" }

        val (u, v) = getHashSizes(algorithm)
        val p = passwordToPkcs12Bytes(passphrase)

        // 1. Construct diversifier D of length v
        val d = ByteArray(v) { idByte }

        // 2. Pad salt to a multiple of v bytes
        val sPad = if (salt.isNotEmpty()) {
            val sPadLen = v * ((salt.size + v - 1) / v)
            ByteArray(sPadLen) { idx -> salt[idx % salt.size] }
        } else {
            ByteArray(0)
        }

        // 3. Pad password to a multiple of v bytes
        val pPad = if (p.isNotEmpty()) {
            val pPadLen = v * ((p.size + v - 1) / v)
            ByteArray(pPadLen) { idx -> p[idx % p.size] }
        } else {
            ByteArray(0)
        }

        // 4. I = S_pad || P_pad
        val iBuf = ByteArray(sPad.size + pPad.size)
        sPad.copyInto(iBuf, 0, 0, sPad.size)
        pPad.copyInto(iBuf, sPad.size, 0, pPad.size)

        // 5. Generate output blocks
        val c = (keyLength + u - 1) / u
        val result = ByteArray(keyLength)
        var outOffset = 0

        for (i in 1..c) {
            var a = Crypto.digest(algorithm, d + iBuf)
            for (j in 2..iterationCount) {
                a = Crypto.digest(algorithm, a)
            }

            val copyLen = minOf(u, keyLength - outOffset)
            a.copyInto(result, outOffset, 0, copyLen)
            outOffset += copyLen

            if (i == c) break

            // Construct B of length v by repeating A
            val b = ByteArray(v) { idx -> a[idx % a.size] }

            // Add B + 1 to each v-byte block in I (big-endian addition modulo 2^(8*v))
            for (k in iBuf.indices step v) {
                var carry = 1
                for (offset in v - 1 downTo 0) {
                    val sum = (iBuf[k + offset].toInt() and 0xff) + (b[offset].toInt() and 0xff) + carry
                    iBuf[k + offset] = sum.toByte()
                    carry = sum ushr 8
                }
            }
        }

        return result
    }
}
