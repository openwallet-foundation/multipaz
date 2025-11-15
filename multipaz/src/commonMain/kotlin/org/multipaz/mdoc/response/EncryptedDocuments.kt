package org.multipaz.mdoc.response

import kotlinx.io.bytestring.ByteString
import org.multipaz.cbor.Bstr
import org.multipaz.cbor.Cbor
import org.multipaz.cbor.DataItem
import org.multipaz.cbor.Tagged
import org.multipaz.cbor.buildCborArray
import org.multipaz.cbor.buildCborMap
import org.multipaz.cose.CoseSign1
import org.multipaz.crypto.AsymmetricKey
import org.multipaz.crypto.Hpke
import org.multipaz.mdoc.credential.MdocCredential
import org.multipaz.mdoc.devicesigned.DeviceNamespaces
import org.multipaz.mdoc.devicesigned.buildDeviceNamespaces
import org.multipaz.mdoc.issuersigned.IssuerNamespaces
import org.multipaz.mdoc.request.EncryptionParameters
import org.multipaz.mdoc.zkp.ZkDocument
import org.multipaz.request.MdocRequestedClaim
import kotlin.time.Clock
import kotlin.time.Instant

/**
 * A structure for holding encrypted documents returned in a [DeviceResponse].
 *
 * @property enc the encapsulated key.
 * @property ciphertext the ciphertext.
 * @property docRequestId the document request ID.
 */
@ConsistentCopyVisibility
data class EncryptedDocuments internal constructor(
    val enc: ByteString,
    val ciphertext: ByteString,
    val docRequestId: Int,
) {
    internal fun toDataItem() = buildCborMap {
        put("enc", enc.toByteArray())
        put("cipherText", ciphertext.toByteArray())
        put("docRequestID", docRequestId)
    }

    /**
     * Decrypts the encrypted documents.
     *
     * After decryption, the same verification checks as in [DeviceResponse.verify] are performed to verify the
     * integrity of the returned documents.
     *
     * @param recipientPrivateKey the private key used for decryption.
     * @param encryptionParameters the same [EncryptionParameters] as transferred in the [org.multipaz.mdoc.request.DeviceRequest].
     * @param sessionTranscript the session transcript.
     * @param atTime the point in time for validating the whether returned documents are valid.
     * @return a [EncryptedDocumentsPlaintext].
     */
    suspend fun decrypt(
        recipientPrivateKey: AsymmetricKey,
        encryptionParameters: EncryptionParameters,
        sessionTranscript: DataItem,
        atTime: Instant = Clock.System.now()
    ): EncryptedDocumentsPlaintext {
        val encSessionTranscript = buildCborArray {
            add(sessionTranscript.asArray[0])
            add(Tagged(
                tagNumber = Tagged.ENCODED_CBOR,
                taggedItem = Bstr(Cbor.encode(encryptionParameters.toDataItem()))
            ))
            add(sessionTranscript.asArray[2])
        }
        val decrypter = Hpke.getDecrypter(
            cipherSuite = Hpke.CipherSuite.DHKEM_P256_HKDF_SHA256_HKDF_SHA256_AES_128_GCM,
            receiverPrivateKey = recipientPrivateKey,
            encapsulatedKey = enc.toByteArray(),
            info = Cbor.encode(encSessionTranscript)
        )
        val plaintext = decrypter.decrypt(
            ciphertext = ciphertext.toByteArray(),
            aad = byteArrayOf()
        )
        val encDocsPt = EncryptedDocumentsPlaintext.fromDataItem(Cbor.decode(plaintext))

        encDocsPt.documents.forEachIndexed { index, document ->
            try {
                document.verify(encSessionTranscript, null, atTime)
            } catch (e: Throwable) {
                throw IllegalStateException("Error verifying document $index in decrypted DeviceResponse", e)
            }
        }

        return encDocsPt
    }

    /**
     * A builder for [EncryptedDocuments].
     *
     * @param sessionTranscript the session transcript to use.
     * @param encryptionParameters the [EncryptionParameters] to use.
     * @param docRequestId the document request ID.
     */
    class Builder(
        private val sessionTranscript: DataItem,
        private val encryptionParameters: EncryptionParameters,
        private val docRequestId: Int,
    ) {
        private val encSessionTranscript = buildCborArray {
            add(sessionTranscript.asArray[0])
            add(Tagged(
                tagNumber = Tagged.ENCODED_CBOR,
                taggedItem = Bstr(Cbor.encode(encryptionParameters.toDataItem()))
            ))
            add(sessionTranscript.asArray[2])
        }
        private val builder = DeviceResponse.Builder(
            sessionTranscript = encSessionTranscript,
            status = DeviceResponse.STATUS_OK,
        )

        /**
         * Low-level function to add a [MdocDocument] to an encrypted documents structure.
         *
         * @param document the [MdocDocument] to add to the encrypted documents structure.
         * @return the builder.
         */
        fun addDocument(document: MdocDocument) = builder.addDocument(document = document)

        /**
         * Low-level function to add a [MdocDocument] to an encrypted documents structure.
         *
         * @param docType the type of the document, e.g. "org.iso.18013.5.1.mDL".
         * @param issuerAuth the issuer-signed MSO.
         * @param issuerNamespaces the issuer-signed data elements to return.
         * @param deviceNamespaces the device-signed data elements to return.
         * @param deviceKey a [AsymmetricKey] used to generate a signature or MAC.
         * @param errors the errors to return.
         * @return the builder.
         */
        suspend fun addDocument(
            docType: String,
            issuerAuth: CoseSign1,
            issuerNamespaces: IssuerNamespaces,
            deviceNamespaces: DeviceNamespaces,
            deviceKey: AsymmetricKey,
            errors: Map<String, Map<String, Int>> = emptyMap()
        ) = builder.addDocument(
            docType = docType,
            issuerAuth = issuerAuth,
            issuerNamespaces = issuerNamespaces,
            deviceNamespaces = deviceNamespaces,
            deviceKey = deviceKey,
            errors = errors
        )

        /**
         * Adds an [MdocCredential] to an encrypted documents structure.
         *
         * @param credential the [MdocCredential] to return
         * @param requestedClaims the claims in [credential] to return.
         * @param deviceNamespaces additional device-signed claims to return.
         * @param errors the errors to return.
         * @return the builder.
         */
        suspend fun addDocument(
            credential: MdocCredential,
            requestedClaims: List<MdocRequestedClaim>,
            deviceNamespaces: DeviceNamespaces = buildDeviceNamespaces {},
            errors: Map<String, Map<String, Int>> = emptyMap()
        ) = builder.addDocument(
            credential = credential,
            requestedClaims = requestedClaims,
            deviceNamespaces = deviceNamespaces,
            errors = errors
        )

        /**
         * Adds a Zero-Knowledge Proof to an encrypted documents structure.
         *
         * @param zkDocument the object with the Zero-Knowledge Proof and associated data.
         * @return the builder.
         */
        fun addZkDocument(zkDocument: ZkDocument) = builder.addZkDocument(zkDocument)

        /**
         * Builds the [EncryptedDocuments] structure.
         *
         * @return a [EncryptedDocuments] structure.
         */
        suspend fun build(): EncryptedDocuments {
            val encryptedDocumentsPlaintext = EncryptedDocumentsPlaintext(
                documents = builder.documents,
                zkDocuments = builder.zkDocuments
            )

            val encrypter = Hpke.getEncrypter(
                cipherSuite = Hpke.CipherSuite.DHKEM_P256_HKDF_SHA256_HKDF_SHA256_AES_128_GCM,
                receiverPublicKey = encryptionParameters.recipientPublicKey,
                info = Cbor.encode(encSessionTranscript),
            )
            val ciphertext = encrypter.encrypt(
                plaintext = Cbor.encode(encryptedDocumentsPlaintext.toDataItem()),
                aad = byteArrayOf()
            )
            return EncryptedDocuments(
                enc = encrypter.encapsulatedKey,
                ciphertext = ByteString(ciphertext),
                docRequestId = docRequestId
            )
        }
    }

    companion object {
        internal fun fromDataItem(dataItem: DataItem): EncryptedDocuments {
            return EncryptedDocuments(
                enc = ByteString(dataItem["enc"].asBstr),
                ciphertext = ByteString(dataItem["cipherText"].asBstr),
                docRequestId = dataItem["docRequestID"].asNumber.toInt()
            )
        }
    }
}
