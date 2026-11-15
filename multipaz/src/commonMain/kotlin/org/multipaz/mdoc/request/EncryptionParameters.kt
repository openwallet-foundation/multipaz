package org.multipaz.mdoc.request

import kotlinx.io.bytestring.ByteString
import org.multipaz.cbor.DataItem
import org.multipaz.cbor.buildCborMap
import org.multipaz.cbor.putCborArray
import org.multipaz.crypto.EcPublicKey
import org.multipaz.crypto.X509Cert
import org.multipaz.crypto.X509CertChain
import kotlin.time.Clock
import kotlin.time.Instant

/**
 * Parameters to use when requesting to encrypt a document response.
 *
 * @param dataItem a [DataItem] which is a map with keys `recipientPublicKey`, `recipientCertificate`, and `nonce`
 * as defined in ISO/IEC 18013-5 Second Edition.
 */
data class EncryptionParameters(
    val dataItem: DataItem
) {
    init {
        val pubKey = dataItem["recipientPublicKey"].asCoseKey.ecPublicKey
        if (dataItem.hasKey("recipientCertificate")) {
            val certs = dataItem["recipientCertificate"].asArray.map {
                X509Cert(ByteString(it.asBstr))
            }
            require(certs.isNotEmpty()) {
                "EncryptionParameters recipientCertificate array must not be empty when present"
            }
            require(certs.first().publicKey == pubKey) {
                "EncryptionParameters recipientPublicKey does not match leaf certificate in recipientCertificate"
            }
        }
        dataItem.getOrNull("nonce")?.asBstr
    }

    /**
     * Validates [recipientCertificates] if present.
     *
     * Note that this method does not perform X.509 CRL or OCSP revocation checking; the caller is
     * expected to check certificate revocation separately when required.
     *
     * @param atTime the point in time at which to validate certificate validity periods.
     */
    suspend fun validateCertificateChain(atTime: Instant = Clock.System.now()) {
        val certs = recipientCertificates
        if (certs.isNotEmpty()) {
            X509CertChain(certs).validate(validateAt = atTime)
        }
    }

    /**
     * @param recipientPublicKey the public key to encrypt the response against.
     */
    val recipientPublicKey: EcPublicKey
        get() = dataItem["recipientPublicKey"].asCoseKey.ecPublicKey

    /**
     * @param recipientCertificates zero or more certificates for [recipientPublicKey].
     */
    val recipientCertificates: List<X509Cert>
        get() = dataItem.getOrNull("recipientCertificate")?.asArray?.map {
            X509Cert(ByteString(it.asBstr))
        } ?: emptyList()

    /**
     * Optional nonce to use.
     */
    val nonce: ByteString?
        get() = dataItem.getOrNull("nonce")?.asBstr?.let { ByteString(it) }

    companion object {
        /**
         * Creates a new [EncryptionParameters] from values.
         *
         * @param recipientPublicKey the public key to encrypt the response against.
         * @param recipientCertificates zero or more certificates for [recipientPublicKey].
         * @param nonce optional nonce to use.
         * @return a [EncryptionParameters] instance.
         */
        fun fromValues(
            recipientPublicKey: EcPublicKey,
            recipientCertificates: List<X509Cert> = emptyList(),
            nonce: ByteString? = null
        ) = EncryptionParameters(buildCborMap {
            put("recipientPublicKey", recipientPublicKey.toCoseKey().toDataItem())
            if (recipientCertificates.isNotEmpty()) {
                putCborArray("recipientCertificate") {
                    for (cert in recipientCertificates) {
                        add(cert.toDataItem())
                    }
                }
            }
            nonce?.let {
                put("nonce", nonce.toByteArray())
            }
        })
    }
}