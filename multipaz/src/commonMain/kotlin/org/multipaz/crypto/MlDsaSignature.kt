package org.multipaz.crypto

import org.multipaz.cbor.CborMap
import org.multipaz.cbor.DataItem
import org.multipaz.cbor.annotation.CborSerializationImplemented
import org.multipaz.cbor.buildCborMap

/**
 * An ML-DSA digital signature (FIPS 204).
 *
 * @param signature the raw signature bytes.
 */
@CborSerializationImplemented(schemaId = "")
data class MlDsaSignature(
    val signature: ByteArray
) : Signature {
    override fun toCoseEncoded(): ByteArray = signature

    override fun toDerEncoded(): ByteArray = signature

    override fun toDataItem(): DataItem = buildCborMap {
        put("mldsa", signature)
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other == null || this::class != other::class) return false

        other as MlDsaSignature

        return signature.contentEquals(other.signature)
    }

    override fun hashCode(): Int {
        return signature.contentHashCode()
    }

    companion object {
        /**
         * Parses an [MlDsaSignature] from a CBOR [DataItem].
         *
         * @param dataItem the CBOR map containing the signature.
         * @return the parsed [MlDsaSignature].
         */
        fun fromDataItem(dataItem: DataItem): MlDsaSignature {
            require(dataItem is CborMap)
            return MlDsaSignature(dataItem["mldsa"].asBstr)
        }
    }
}

