package org.multipaz.crypto

import org.multipaz.cbor.CborMap
import org.multipaz.cbor.DataItem
import org.multipaz.cbor.annotation.CborSerializationImplemented

/**
 * A digital signature produced by an asymmetric private key.
 */
@CborSerializationImplemented(schemaId = "")
sealed interface Signature {
    /**
     * Encodes the signature for use in COSE or JWT.
     *
     * For EC signatures, this produces the IEEE P1363 (r || s) encoding.
     * For RSA signatures, this produces the raw RSA signature bytes.
     */
    fun toCoseEncoded(): ByteArray

    /**
     * Encodes the signature for use in ASN.1 DER structures such as X.509 certificates and CRLs.
     *
     * For ECDSA signatures, this produces an ASN.1 DER SEQUENCE of r and s integers.
     * For EdDSA signatures, this produces r || s.
     * For RSA signatures, this produces the raw RSA signature bytes.
     */
    fun toDerEncoded(): ByteArray

    /**
     * Serializes this signature as a CBOR [DataItem].
     */
    fun toDataItem(): DataItem

    companion object {
        /**
         * Parses a [Signature] from a CBOR [DataItem].
         */
        fun fromDataItem(dataItem: DataItem): Signature {
            require(dataItem is CborMap)
            return when {
                dataItem.hasKey("r") && dataItem.hasKey("s") -> EcSignature.fromDataItem(dataItem)
                dataItem.hasKey("rsa") -> RsaSignature.fromDataItem(dataItem)
                dataItem.hasKey("mldsa") -> MlDsaSignature.fromDataItem(dataItem)
                else -> throw IllegalArgumentException("Unknown signature format: $dataItem")
            }
        }
    }
}

