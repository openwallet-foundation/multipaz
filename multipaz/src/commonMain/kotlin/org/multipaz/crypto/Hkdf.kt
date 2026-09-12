package org.multipaz.crypto

import kotlinx.io.bytestring.buildByteString
import kotlin.math.ceil

/**
 * HMAC-based Extract-and-Expand Key Derivation Function (HKDF) according
 * to [RFC 5869](https://datatracker.ietf.org/doc/html/rfc5869).
 */
object Hkdf {

    private fun getHashLen(kdfAlgorithm: Algorithm): Int {
        return when (kdfAlgorithm) {
            Algorithm.HMAC_INSECURE_SHA1 -> 20
            Algorithm.HMAC_SHA256 -> 32
            Algorithm.HMAC_SHA384 -> 48
            Algorithm.HMAC_SHA512 -> 64
            else -> throw IllegalArgumentException("$kdfAlgorithm is not a KDF algorithm")
        }
    }

    /**
     * The "extract" part of HKDF.
     *
     * If `null` is passed for the [salt] parameter, an array the same length
     * as the hash function for [algorithm] is used, with all entries set to zero.
     *
     * @param algorithm the KDF algorithm to use e.g. [Algorithm.HMAC_SHA256].
     * @param ikm input key material.
     * @param salt optional salt value (a non-secret random value).
     * @return a pseudorandom key, the same length as the hash function for [algorithm].
     */
    suspend fun extract(
        algorithm: Algorithm,
        ikm: ByteArray,
        salt: ByteArray?
    ): ByteArray {
        return Crypto.mac(
            algorithm = algorithm,
            key = salt ?: ByteArray(getHashLen(algorithm)),
            message = ikm
        )
    }

    /**
     * The "expand" part of HKDF.
     *
     * @param algorithm the KDF algorithm to use e.g. [Algorithm.HMAC_SHA256].
     * @param prk a pseudorandom key of at least the length for the hash function for [algorithm].
     * @param info context and application specific information (can be zero-length).
     * @param length length of output keying material in octets.
     * @return output keying material of [length] octets.
     */
    suspend fun expand(
        algorithm: Algorithm,
        prk: ByteArray,
        info: ByteArray,
        length: Int
    ): ByteArray {
        val hashLen = getHashLen(algorithm)
        if (length > 255 * hashLen) {
            throw IllegalArgumentException("HKDF length $length is too large")
        }
        val n = (length + hashLen - 1) / hashLen
        val combinedT = buildByteString {
            var prevT = byteArrayOf()
            for (i in 1..n) {
                val counter = byteArrayOf(i.toByte())
                val message = prevT + info + counter
                val newT = Crypto.mac(algorithm, prk, message)
                append(newT)
                prevT = newT
            }
        }
        val okm = ByteArray(length)
        combinedT.copyInto(okm, 0, 0, length)
        return okm
    }

    /**
     * The "extract" part of HKDF with [SecretKey] input key material.
     *
     * @param algorithm the KDF algorithm to use e.g. [Algorithm.HMAC_SHA256].
     * @param ikm input key material as a [SecretKey].
     * @param salt optional salt value (a non-secret random value).
     * @return a pseudorandom key as a [SecretKey].
     */
    suspend fun extract(
        algorithm: Algorithm,
        ikm: SecretKey,
        salt: ByteArray?
    ): SecretKey {
        ikm.checkNotDestroyed()
        val prkBytes = Crypto.mac(
            algorithm = algorithm,
            key = salt ?: ByteArray(getHashLen(algorithm)),
            message = ikm.data
        )
        val prk = SecretKey(prkBytes)
        prkBytes.secureZero()
        return prk
    }

    /**
     * The "expand" part of HKDF with [SecretKey] pseudorandom key.
     *
     * @param algorithm the KDF algorithm to use e.g. [Algorithm.HMAC_SHA256].
     * @param prk a pseudorandom key as a [SecretKey].
     * @param info context and application specific information (can be zero-length).
     * @param length length of output keying material in octets.
     * @return output keying material as a [SecretKey].
     */
    suspend fun expand(
        algorithm: Algorithm,
        prk: SecretKey,
        info: ByteArray,
        length: Int
    ): SecretKey {
        prk.checkNotDestroyed()
        val okmBytes = expand(algorithm, prk.data, info, length)
        val okm = SecretKey(okmBytes)
        okmBytes.secureZero()
        return okm
    }

    /**
     * Derives a symmetric encryption key according to HKDF as defined by
     * [RFC 5869](https://datatracker.ietf.org/doc/html/rfc5869).
     *
     * @param algorithm the KDF algorithm to use e.g. [Algorithm.HMAC_SHA256].
     * @param ikm input key material.
     * @param salt optional salt value (a non-secret random value).
     * @param info context and application specific information (can be zero-length).
     * @param length length of output keying material in octets.
     * @return output keying material of [length] octets.
     */
    suspend fun deriveKey(
        algorithm: Algorithm,
        ikm: ByteArray,
        salt: ByteArray?,
        info: ByteArray,
        length: Int
    ): ByteArray {
        val prk = extract(
            algorithm = algorithm,
            ikm = ikm,
            salt = salt
        )
        val okm = expand(
            algorithm = algorithm,
            prk = prk,
            info = info,
            length = length
        )
        prk.secureZero()
        return okm
    }

    /**
     * Derives a symmetric encryption key according to HKDF as defined by
     * [RFC 5869](https://datatracker.ietf.org/doc/html/rfc5869), taking and
     * returning a [SecretKey].
     *
     * @param algorithm the KDF algorithm to use e.g. [Algorithm.HMAC_SHA256].
     * @param ikm input key material as a [SecretKey].
     * @param salt optional salt value (a non-secret random value).
     * @param info context and application specific information (can be zero-length).
     * @param length length of output keying material in octets.
     * @return output keying material of [length] octets as a [SecretKey].
     */
    suspend fun deriveKey(
        algorithm: Algorithm,
        ikm: SecretKey,
        salt: ByteArray?,
        info: ByteArray,
        length: Int
    ): SecretKey {
        ikm.checkNotDestroyed()
        val prk = extract(algorithm, ikm, salt)
        return prk.use { expand(algorithm, it, info, length) }
    }
}