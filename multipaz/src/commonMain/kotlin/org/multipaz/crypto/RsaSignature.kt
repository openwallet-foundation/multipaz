package org.multipaz.crypto

/**
 * An RSA digital signature.
 *
 * @param signature the raw signature bytes.
 */
data class RsaSignature(
    val signature: ByteArray
) : Signature {
    override fun toCoseEncoded(): ByteArray = signature

    override fun toDerEncoded(): ByteArray = signature

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other == null || this::class != other::class) return false

        other as RsaSignature

        return signature.contentEquals(other.signature)
    }

    override fun hashCode(): Int {
        return signature.contentHashCode()
    }
}
