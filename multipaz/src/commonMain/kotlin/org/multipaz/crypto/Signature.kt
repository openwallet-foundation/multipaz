package org.multipaz.crypto

/**
 * A digital signature produced by an asymmetric private key.
 */
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
}
