package org.multipaz.crypto

import org.multipaz.cbor.CborMap
import org.multipaz.cbor.DataItem
import org.multipaz.cbor.annotation.CborSerializationImplemented
import org.multipaz.cbor.buildCborMap

/**
 * An RSA digital signature.
 *
 * @param signature the raw signature bytes.
 */
@CborSerializationImplemented(schemaId = "")
data class RsaSignature(
    val signature: ByteArray
) : Signature {
    override fun toCoseEncoded(): ByteArray = signature

    override fun toDerEncoded(): ByteArray = signature

    override fun toDataItem(): DataItem = buildCborMap {
        put("rsa", signature)
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other == null || this::class != other::class) return false

        other as RsaSignature

        return signature.contentEquals(other.signature)
    }

    override fun hashCode(): Int {
        return signature.contentHashCode()
    }

    companion object {
        /**
         * Parses an [RsaSignature] from a CBOR [DataItem].
         *
         * @param dataItem the CBOR map containing the signature.
         * @return the parsed [RsaSignature].
         */
        fun fromDataItem(dataItem: DataItem): RsaSignature {
            require(dataItem is CborMap)
            return RsaSignature(dataItem["rsa"].asBstr)
        }
    }
}

