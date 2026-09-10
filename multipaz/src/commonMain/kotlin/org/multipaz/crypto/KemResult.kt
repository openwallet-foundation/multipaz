package org.multipaz.crypto

/**
 * Result of a Key Encapsulation Mechanism (KEM) encapsulation operation.
 *
 * @property sharedSecret the shared secret bytes.
 * @property ciphertext the encapsulated ciphertext to be sent to the recipient.
 */
data class KemResult(
    val sharedSecret: ByteArray,
    val ciphertext: ByteArray
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other == null || this::class != other::class) return false

        other as KemResult

        return sharedSecret.contentEquals(other.sharedSecret) &&
                ciphertext.contentEquals(other.ciphertext)
    }

    override fun hashCode(): Int {
        var result = sharedSecret.contentHashCode()
        result = 31 * result + ciphertext.contentHashCode()
        return result
    }
}
