package org.multipaz.crypto

import kotlinx.io.bytestring.ByteString
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonPrimitive
import org.multipaz.cbor.Bstr
import org.multipaz.cbor.CborArray
import org.multipaz.cbor.DataItem
import org.multipaz.cbor.annotation.CborSerializationImplemented
import org.multipaz.cbor.buildCborArray
import org.multipaz.util.fromBase64
import kotlin.io.encoding.Base64
import kotlin.time.Clock
import kotlin.time.Instant

/**
 * A chain of certificates.
 *
 * @param certificates the certificates in the chain.
 */
@CborSerializationImplemented(schemaId = "62socrrUk8v-bXSe8dniBNlhAT06JKs8_DpQlH53sZQ")
data class X509CertChain(
    val certificates: List<X509Cert>
) {

    /**
     * Encodes the certificate chain as CBOR.
     *
     * If the chain has only one item a [Bstr] with the sole certificate is returned.
     * Otherwise an array of [Bstr] is returned.
     *
     * Use [fromDataItem] to decode the returned data item.
     */
    fun toDataItem(): DataItem {
        if (certificates.size == 1) {
            return certificates[0].toDataItem()
        } else {
            return buildCborArray {
                certificates.forEach { certificate -> add(certificate.toDataItem()) }
            }
        }
    }

    /**
     * Encodes the certificate as JSON Array according to RFC 7515 Section 4.1.6.
     *
     * Current draft of HAIP spec states "The X.509 certificate of the trust anchor MUST NOT be
     * included in the x5c JOSE header of the Status List Token. The X.509 certificate signing
     * the request MUST NOT be self-signed.". [excludeRoot] parameter helps to enforce this.
     * Note that including trust root is always redundant, as both the key and the issuer identity
     * must be known to the party that validates the certificate chain.
     *
     * @param excludeRoot if the last certificate is root (self-signed), exclude it
     * @return a [JsonElement].
     */
    fun toX5c(excludeRoot: Boolean = true): JsonElement {
        val last = certificates.last()
        val certs = if (excludeRoot && last.subject == last.issuer) {
            certificates.subList(0, certificates.size - 1)
        } else {
            certificates
        }
        return JsonArray(
            // NB: must keep '=' padding at the end!
            certs.map { certificate ->
                JsonPrimitive( Base64.encode(certificate.encoded.toByteArray()))
            }
        ) as JsonElement
    }

    /**
     * Performs basic certificate chain validation.
     *
     * Specifically, these checks are performed:
     *  - every certificate in the chain is signed by the next one,
     *  - signer certificate's subject matches signed certificate's issuer,
     *  - certificates are within their validity period (already valid and not yet expired),
     *  - signer certificate have `CERT_SIGN` key usage
     *  - non-leaf certificate must have basic constrains extension with
     *    - CA flag set to true
     *    - path length constraint that is sufficient for number of certificates in the chain
     *
     * This method does not check certificate revocation lists.
     *
     * @param validateAt time of the validation
     * @param requireBasicConstraints if non-leaf certificates must use basic constrains extension
     * @throws [X509CertChainValidationException] if validation fails.
     */
    suspend fun validate(
        validateAt: Instant = Clock.System.now(),
        requireBasicConstraints: Boolean = true
    ) {
        if (!Crypto.validateCertChainSignatures(this)) {
            throw X509CertChainValidationException.Signature()
        }
        var previous: X509Cert? = null
        for ((index, certificate) in certificates.withIndex()) {
            if (previous != null) {
                if (previous.issuer != certificate.subject) {
                    throw X509CertChainValidationException.SubjectIssuerMismatch()
                }
                if (!certificate.keyUsage.contains(X509KeyUsage.KEY_CERT_SIGN)) {
                    throw X509CertChainValidationException.KeyUsageMissing()
                }
                val basicConstraints = certificate.basicConstraints
                if (basicConstraints == null) {
                    if (requireBasicConstraints) {
                        throw X509CertChainValidationException.BasicConstraintsMissing()
                    }
                } else {
                    if (!basicConstraints.first) {
                        throw X509CertChainValidationException.BasicConstraintsNotCA()
                    }
                    val maxPathLength = basicConstraints.second
                    if (maxPathLength != null) {
                        // the leaf is not counted in path length constraints
                        val pathLength = index - 1
                        if (pathLength > maxPathLength) {
                            throw X509CertChainValidationException.BasicConstraintsPathLength()
                        }
                    }
                }
            }
            previous = certificate
            if (certificate.validityNotAfter < validateAt) {
                throw X509CertChainValidationException.Expired(certificate.validityNotAfter)
            }
            if (certificate.validityNotBefore > validateAt) {
                throw X509CertChainValidationException.NotYetValid(certificate.validityNotBefore)
            }
        }
    }

    companion object {
        /**
         * Decodes a certificate chain from CBOR.
         *
         * See [Certificate.toDataItem] for the expected encoding.
         *
         * @param dataItem the CBOR data item to decode.
         * @return the certificate chain.
         */
        fun fromDataItem(dataItem: DataItem): X509CertChain {
            val certificates: List<X509Cert> =
                if (dataItem is CborArray) {
                    dataItem.items.map { item -> item.asX509Cert }.toList()
                } else {
                    listOf(dataItem.asX509Cert)
                }
            return X509CertChain(certificates)
        }

        /**
         * Decodes a certificate chain encoded according to RFC 7515 Section 4.1.6.
         *
         * @return the certificate chain.
         */
        fun fromX5c(x5c: JsonElement): X509CertChain {
            require(x5c is JsonArray)
            // NB: expected encoding is base64 (not base64url) with '=' padding. We are more lax
            // and accept base64 with or without padding.
            return X509CertChain(x5c.map { X509Cert(ByteString(it.jsonPrimitive.content.fromBase64())) })
        }
    }
}
