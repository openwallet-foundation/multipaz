package org.multipaz.rpc.handler

/**
 * [SimpleCipher] that does not encrypt or decrypt messages.
 */
object NoopCipher: SimpleCipher {
    override suspend fun encrypt(plaintext: ByteArray): ByteArray = plaintext
    override suspend fun decrypt(ciphertext: ByteArray): ByteArray = ciphertext
}