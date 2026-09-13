package org.multipaz.crypto

import kotlinx.io.bytestring.buildByteString
import kotlin.coroutines.cancellation.CancellationException
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
     * The "extract" part of HKDF with [SecureByteString] input key material.
     *
     * @param algorithm the KDF algorithm to use e.g. [Algorithm.HMAC_SHA256].
     * @param ikm input key material as a [SecureByteString].
     * @param salt optional salt value (a non-secret random value).
     * @return a pseudorandom key as a [SecretKey].
     * @throws IllegalArgumentException if the algorithm is not supported.
     * @throws IllegalStateException if [ikm] has been destroyed.
     */
    @Throws(
        IllegalArgumentException::class,
        IllegalStateException::class,
        CancellationException::class
    )
    suspend fun extract(
        algorithm: Algorithm,
        ikm: SecureByteString,
        salt: ByteArray?
    ): SecretKey {
        ikm.checkNotDestroyed()
        val saltBytes = salt ?: ByteArray(getHashLen(algorithm))
        val prkBytes = SecretKey(saltBytes).use { saltKey ->
            Crypto.mac(
                algorithm = algorithm,
                key = saltKey,
                message = ikm.data
            )
        }
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
     * @throws IllegalArgumentException if the algorithm is not supported or [length] is invalid.
     * @throws IllegalStateException if [prk] has been destroyed.
     */
    @Throws(
        IllegalArgumentException::class,
        IllegalStateException::class,
        CancellationException::class
    )
    suspend fun expand(
        algorithm: Algorithm,
        prk: SecretKey,
        info: ByteArray,
        length: Int
    ): SecretKey {
        prk.checkNotDestroyed()
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
        val okmBytes = ByteArray(length)
        combinedT.copyInto(okmBytes, 0, 0, length)
        val okm = SecretKey(okmBytes)
        okmBytes.secureZero()
        return okm
    }


    /**
     * Derives a symmetric encryption key according to HKDF as defined by
     * [RFC 5869](https://datatracker.ietf.org/doc/html/rfc5869), taking a
     * [SecureByteString] and returning a [SecretKey].
     *
     * @param algorithm the KDF algorithm to use e.g. [Algorithm.HMAC_SHA256].
     * @param ikm input key material as a [SecureByteString].
     * @param salt optional salt value (a non-secret random value).
     * @param info context and application specific information (can be zero-length).
     * @param length length of output keying material in octets.
     * @return output keying material of [length] octets as a [SecretKey].
     * @throws IllegalArgumentException if the algorithm is not supported or [length] is invalid.
     * @throws IllegalStateException if [ikm] has been destroyed.
     */
    @Throws(
        IllegalArgumentException::class,
        IllegalStateException::class,
        CancellationException::class
    )
    suspend fun deriveKey(
        algorithm: Algorithm,
        ikm: SecureByteString,
        salt: ByteArray?,
        info: ByteArray,
        length: Int
    ): SecretKey {
        ikm.checkNotDestroyed()
        val prk = extract(algorithm, ikm, salt)
        return prk.use { expand(algorithm, it, info, length) }
    }
}