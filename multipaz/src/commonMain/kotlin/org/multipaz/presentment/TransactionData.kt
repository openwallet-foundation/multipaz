package org.multipaz.presentment

import kotlinx.io.bytestring.ByteString
import org.multipaz.credential.Credential
import org.multipaz.crypto.Algorithm
import org.multipaz.crypto.Crypto
import org.multipaz.document.Document
import org.multipaz.documenttype.TransactionType

/**
 * An object that holds transaction data.
 *
 * Transaction data is held in two representation: serialized and parsed. Serialized representation
 * is raw sequence of bytes that reflects how transaction data is encoded in the verification
 * protocol (transaction response includes hash of the serialized representation). Parsed
 * representation includes transaction type that describes what kind of transaction this is,
 * the list of hash algorithms that the verifier accepts for this transaction in the order of
 * preference, and transaction payload which is transaction-type-specific data.
 *
 * @param type type of the transaction data item
 * @param serialized serialized representation of the transaction data
 * @param hashAlgorithms accepted hash algorithm override list for this transaction data in
 *   the order of preference
 * @param payload transaction payload
 */
class TransactionData<PayloadT: Any>(
    val type: TransactionType<PayloadT>,
    val serialized: ByteString,
    val hashAlgorithms: List<Algorithm>?,
    val payload: PayloadT,
) {
    /**
     * Computes hash of the transaction data.
     *
     * It is important that the verifier uses the same algorithm as the presenter (NB: the set
     * of supported hash algorithms may differ!).
     *
     * @return hash of the serialized transaction data
     */
    suspend fun computeHash(algorithm: Algorithm = Algorithm.SHA256): ByteString =
        ByteString(Crypto.digest(algorithm, serialized.toByteArray()))

    /**
     * Determines if this transaction is applicable to the given credential.
     *
     * When transaction cannot be processed, it removes a particular "use case" or credential
     * set option from consideration. If other options are available, presentment still may
     * succeed.
     *
     * @param credential one of the credentials in the [Document] being considered
     * @return true if transaction can be processed false if it cannot
     */
    suspend fun isApplicable(credential: Credential) = type.isApplicable(this, credential)

    /**
     * Applies transaction in the context of OpenID4VP presentment.
     *
     * See [TransactionType.applyJson]
     *
     * @param credential credential being presented
     * @return transaction-specific data that should be added to the presentment.
     */
    suspend fun applyJson(credential: Credential) = type.applyJson(this, credential)

    /**
     * Applies transaction in the context of ISO ISO/IEC 18013 presentment.
     *
     * See [TransactionType.applyCbor]
     *
     * @param credential credential being presented
     * @return transaction-specific data that should be added to the presentment.
     */
    suspend fun applyCbor(credential: Credential) = type.applyCbor(this, credential)

    /**
     * Creates equivalent transaction data for use in ISO ISO/IEC 18013 protocols.
     *
     * @return new [TransactionData] object that holds the same payload and hash algorithm, but
     *  its [TransactionData.serialized] is formatted for use in ISO ISO/IEC 18013 protocols.
     */
    fun convertToCbor(): TransactionData<PayloadT> =
        type.parseCbor(type.serializeCbor(payload, hashAlgorithms))
}