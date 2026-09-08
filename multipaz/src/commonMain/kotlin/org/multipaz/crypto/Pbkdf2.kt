package org.multipaz.crypto

/**
 * Password-Based Key Derivation Function 2 (PBKDF2) according to
 * [RFC 8018 Section 5.2](https://datatracker.ietf.org/doc/html/rfc8018#section-5.2) / PKCS #5 v2.1.
 */
internal object Pbkdf2 {

    private fun getHashLen(prfAlgorithm: Algorithm): Int =
        when (prfAlgorithm) {
            Algorithm.HMAC_INSECURE_SHA1 -> 20
            Algorithm.HMAC_SHA256 -> 32
            Algorithm.HMAC_SHA384 -> 48
            Algorithm.HMAC_SHA512 -> 64
            else -> throw IllegalArgumentException("$prfAlgorithm is not a supported PRF algorithm")
        }

    /**
     * Derives a key of [keyLength] bytes using PBKDF2.
     *
     * @param prfAlgorithm the pseudo-random function HMAC algorithm (e.g. [Algorithm.HMAC_SHA256]).
     * @param password the password bytes.
     * @param salt the salt bytes.
     * @param iterationCount the iteration count (c >= 1).
     * @param keyLength the requested derived key length in bytes.
     * @return the derived key bytes.
     */
    suspend fun deriveKey(
        prfAlgorithm: Algorithm,
        password: ByteArray,
        salt: ByteArray,
        iterationCount: Int,
        keyLength: Int
    ): ByteArray {
        require(iterationCount >= 1) { "Iteration count must be at least 1" }
        require(keyLength >= 1) { "Key length must be at least 1" }

        val hLen = getHashLen(prfAlgorithm)
        val l = (keyLength + hLen - 1) / hLen
        val derivedKey = ByteArray(keyLength)
        var outOffset = 0

        for (i in 1..l) {
            // Salt || INT_32_BE(i)
            val saltAndIndex = ByteArray(salt.size + 4).apply {
                salt.copyInto(this, 0, 0, salt.size)
                this[salt.size] = (i ushr 24).toByte()
                this[salt.size + 1] = (i ushr 16).toByte()
                this[salt.size + 2] = (i ushr 8).toByte()
                this[salt.size + 3] = i.toByte()
            }

            var u = Crypto.mac(prfAlgorithm, password, saltAndIndex)
            val f = u.copyOf()

            for (j in 2..iterationCount) {
                u = Crypto.mac(prfAlgorithm, password, u)
                for (k in f.indices) {
                    f[k] = (f[k].toInt() xor u[k].toInt()).toByte()
                }
            }

            val bytesToCopy = minOf(hLen, keyLength - outOffset)
            f.copyInto(derivedKey, outOffset, 0, bytesToCopy)
            outOffset += bytesToCopy
        }

        return derivedKey
    }
}
