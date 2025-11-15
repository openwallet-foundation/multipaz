package org.multipaz.mdoc.request

import kotlinx.io.bytestring.ByteString
import org.multipaz.cbor.DataItem
import org.multipaz.cbor.buildCborMap
import org.multipaz.cbor.putCborArray
import org.multipaz.crypto.EcPublicKey
import org.multipaz.crypto.X509Cert

/**
 * Parameters to use when requesting to encrypt a document response.
 *
 * @property recipientPublicKey the public key to encrypt the response against.
 * @property recipientCertificates zero or more certificates for [recipientPublicKey].
 * @property nonce optional nonce to use.
 */
data class EncryptionParameters(
    val recipientPublicKey: EcPublicKey,
    val recipientCertificates: List<X509Cert> = emptyList(),
    val nonce: ByteString? = null
) {

    internal fun toDataItem() = buildCborMap {
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
    }

    companion object {
        internal fun fromDataItem(dataItem: DataItem): EncryptionParameters {
            val recipientPublicKey = dataItem["recipientPublicKey"].asCoseKey.ecPublicKey
            val recipientCertificate = dataItem.getOrNull("recipientCertificate")?.asArray?.map {
                X509Cert(ByteString(it.asBstr))
            } ?: emptyList()
            val nonce = dataItem.getOrNull("nonce")?.asBstr?.let { ByteString(it) }
            return EncryptionParameters(
                recipientPublicKey = recipientPublicKey,
                recipientCertificates = recipientCertificate,
                nonce = nonce
            )
        }
    }
}