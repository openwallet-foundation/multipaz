package org.multipaz.crypto

import org.multipaz.cbor.CborMap
import org.multipaz.cbor.DataItem
import org.multipaz.cbor.annotation.CborSerializationImplemented
import org.multipaz.cbor.buildCborMap

/**
 * A digital signature produced by a PQ/T hybrid composite private key.
 *
 * The raw signature bytes consist of the fixed-size ML-DSA signature immediately followed by
 * the traditional signature component (`mlDsaSignature || traditionalSignature`) as specified
 * in `draft-ietf-jose-pq-composite-sigs-07`.
 *
 * @property signature the raw composite signature bytes.
 */
@CborSerializationImplemented(schemaId = "")
class HybridSignature(
    val signature: ByteArray
) : Signature {

    /**
     * Encodes the signature for use in COSE or JOSE.
     *
     * @return the composite signature bytes.
     */
    override fun toCoseEncoded(): ByteArray = signature.copyOf()

    /**
     * Encodes the signature for use in X.509 certificates and other ASN.1 DER structures.
     *
     * @return the composite signature bytes.
     */
    override fun toDerEncoded(): ByteArray = signature.copyOf()

    /**
     * Serializes this signature as a CBOR [DataItem].
     *
     * @return a CBOR map containing the composite signature bytes under key `"hybrid"`.
     */
    override fun toDataItem(): DataItem {
        return buildCborMap {
            put("hybrid", signature)
        }
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other == null || this::class != other::class) return false
        other as HybridSignature
        return signature.contentEquals(other.signature)
    }

    override fun hashCode(): Int = signature.contentHashCode()

    override fun toString(): String = "HybridSignature(size=${signature.size})"

    companion object {
        /**
         * Parses a [HybridSignature] from COSE or JOSE encoded bytes.
         *
         * @param encoded the raw composite signature bytes.
         * @return the parsed [HybridSignature].
         */
        fun fromCoseEncoded(encoded: ByteArray): HybridSignature = HybridSignature(encoded.copyOf())

        /**
         * Parses a [HybridSignature] from ASN.1 BIT STRING bytes in a DER structure.
         *
         * @param encoded the raw composite signature bytes.
         * @return the parsed [HybridSignature].
         */
        fun fromDerEncoded(encoded: ByteArray): HybridSignature = HybridSignature(encoded.copyOf())

        /**
         * Parses a [HybridSignature] from a CBOR [DataItem].
         *
         * @param dataItem the CBOR map serialized by [toDataItem].
         * @return the parsed [HybridSignature].
         * @throws IllegalArgumentException if [dataItem] is malformed or missing the `"hybrid"` field.
         */
        @Throws(IllegalArgumentException::class)
        fun fromDataItem(dataItem: DataItem): HybridSignature {
            require(dataItem is CborMap && dataItem.hasKey("hybrid")) {
                "Invalid HybridSignature CBOR: $dataItem"
            }
            return HybridSignature(dataItem["hybrid"].asBstr)
        }
    }
}
