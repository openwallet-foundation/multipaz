package org.multipaz.crypto

/**
 * Result of a Key Encapsulation Mechanism (KEM) encapsulation operation.
 *
 * @property sharedSecret the shared secret as a [SecretKey].
 * @property ciphertext the encapsulated ciphertext to be sent to the recipient.
 */
class KemResult(
    val sharedSecret: SecretKey,
    val ciphertext: ByteArray
) : AutoCloseable {

    constructor(
        sharedSecret: ByteArray,
        ciphertext: ByteArray
    ) : this(SecretKey(sharedSecret), ciphertext)

    override fun close() {
        sharedSecret.close()
    }

    /**
     * Destroys this result by destroying the underlying shared secret.
     */
    fun destroy() {
        sharedSecret.destroy()
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other == null || this::class != other::class) return false

        other as KemResult

        return sharedSecret == other.sharedSecret &&
                ciphertext.contentEquals(other.ciphertext)
    }

    override fun hashCode(): Int {
        var result = sharedSecret.hashCode()
        result = 31 * result + ciphertext.contentHashCode()
        return result
    }

    override fun toString(): String {
        return "KemResult(sharedSecret=$sharedSecret, ciphertextLength=${ciphertext.size})"
    }
}
