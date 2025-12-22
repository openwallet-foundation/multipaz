package org.multipaz.rpc.handler

import org.multipaz.crypto.Algorithm
import org.multipaz.crypto.Crypto
import kotlinx.io.bytestring.ByteStringBuilder
import kotlin.random.Random

/** SimpleCipher implementation for AES/GCM with 128/192/256 bit keys. */
class AesGcmCipher(val key: ByteArray) : SimpleCipher {
    private val alg = when (key.size) {
        16 -> Algorithm.A128GCM
        24 -> Algorithm.A192GCM
        32 -> Algorithm.A256GCM
        else -> throw IllegalArgumentException("key length must be 16, 24, or 32 bytes")
    }

    override suspend fun encrypt(plaintext: ByteArray): ByteArray {
        val ciphertext = ByteStringBuilder()
        val iv = Random.Default.nextBytes(12)
        ciphertext.append(iv)
        ciphertext.append(Crypto.encrypt(alg, key, iv, plaintext))
        return ciphertext.toByteString().toByteArray()
    }

    override suspend fun decrypt(ciphertext: ByteArray): ByteArray {
        if (ciphertext.size <= 12) {
            // Cannot be valid.
            throw SimpleCipher.DataTamperedException()
        }
        val iv = ByteArray(12)
        ciphertext.copyInto(iv, endIndex = iv.size)
        try {
            return Crypto.decrypt(
                alg, key, iv,
                ciphertext.sliceArray(iv.size..ciphertext.lastIndex)
            )
        } catch (ex: IllegalStateException) {
            throw SimpleCipher.DataTamperedException()
        }
    }
}
