package org.multipaz.presentment

import kotlinx.io.bytestring.ByteString
import kotlinx.serialization.json.JsonElement
import org.multipaz.cbor.DataItem
import org.multipaz.credential.Credential
import org.multipaz.crypto.Algorithm
import org.multipaz.crypto.Crypto
import org.multipaz.document.Document
import org.multipaz.documenttype.TransactionType
import org.multipaz.documenttype.TransactionUserInput

/**
 * Protocol through which transaction data was received.
 */
enum class TransactionProtocol {
    /** Transaction data received via ISO/IEC 18013-5 presentment. */
    ISO_18013_5,

    /** Transaction data received via OpenID4VP presentment. */
    OPENID4VP
}

/**
 * An object that holds transaction data.
 *
 * Transaction data is held in two representation: serialized and parsed. Serialized representation
 * is raw sequence of bytes that reflects how transaction data is encoded in the verification
 * protocol. Parsed representation includes transaction type that describes what kind of
 * transaction this is, the list of hash algorithms that the verifier accepts for this transaction,
 * and transaction payload which is transaction-type-specific data.
 *
 * @param type type of the transaction data item
 * @param payload transaction payload
 * @param protocol protocol context in which the transaction data was received
 * @param rawBytes raw sequence of bytes representing the transaction data in the request
 * @param hashAlgorithms accepted hash algorithm override list for this transaction data
 * @param intentToRetain whether the reader intends to retain the transaction data
 */
class TransactionData<PayloadT: Any>(
    val type: TransactionType<PayloadT>,
    val payload: PayloadT,
    val protocol: TransactionProtocol,
    val rawBytes: ByteString,
    val hashAlgorithms: List<Algorithm>? = null,
    val intentToRetain: Boolean = type.defaultIntentToRetain,
) {
    /**
     * Computes hash of the transaction data.
     *
     * @return hash of the serialized transaction data
     */
    suspend fun computeHash(algorithm: Algorithm = Algorithm.SHA256): ByteString =
        ByteString(Crypto.digest(algorithm, rawBytes.toByteArray()))

    /**
     * Determines if this transaction is applicable to the given credential.
     *
     * @param credential one of the credentials in the [Document] being considered
     * @return true if transaction can be processed, false if it cannot
     */
    suspend fun isApplicable(credential: Credential) = type.isApplicable(this, credential)

    /**
     * Generates device-signed data elements for an Mdoc credential.
     *
     * @param credential credential being presented
     * @param userInput additional data specified by the user
     * @param docRequestId document request index in ISO 18013-5 presentment, null for OpenID4VP
     * @return map of data elements for `DeviceSigned.nameSpaces` under `ISO_18013_TRANSACTION_DATA_NAMESPACE`
     */
    suspend fun generateMdocResponseElements(
        credential: Credential,
        userInput: TransactionUserInput?,
        docRequestId: Int? = null
    ): Map<String, DataItem> = type.generateMdocResponseElements(this, credential, userInput, docRequestId)

    /**
     * Generates Key Binding JWT claims for an SD-JWT credential.
     *
     * @param credential credential being presented
     * @param userInput additional data specified by the user
     * @param docRequestId document request index in ISO 18013-5 presentment, null for OpenID4VP
     * @return map of claims to include in the KB-JWT payload
     */
    suspend fun generateSdJwtResponseClaims(
        credential: Credential,
        userInput: TransactionUserInput?,
        docRequestId: Int? = null
    ): Map<String, JsonElement> = type.generateSdJwtResponseClaims(this, credential, userInput, docRequestId)

    /**
     * Verifies transaction response returned in an Mdoc presentation.
     *
     * @param responseElements key-value-map for values returned in the mdoc presentation
     */
    suspend fun verifyMdocResponse(responseElements: Map<String, DataItem>) =
        type.verifyMdocResponse(this, responseElements)

    /**
     * Verifies transaction response returned in an SD-JWT presentation.
     *
     * @param responseClaims claims returned in the Key Binding JWT
     */
    suspend fun verifySdJwtResponse(responseClaims: Map<String, JsonElement>) =
        type.verifySdJwtResponse(this, responseClaims)

    /**
     * Creates equivalent transaction data for use in ISO 18013-5 protocols.
     */
    fun serializeIso18013Request(): DataItem = type.serializeIso18013Request(payload)
}