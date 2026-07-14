package org.multipaz.documenttype

import kotlinx.io.bytestring.ByteString
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.buildJsonObject
import org.multipaz.cbor.DataItem
import org.multipaz.credential.Credential
import org.multipaz.crypto.Algorithm
import org.multipaz.document.Document
import org.multipaz.presentment.TransactionData
import org.multipaz.util.Logger

/**
 * An object that represents a particular transaction data type.
 *
 * All transaction types that are expected to be processed or rejected must be registered in a
 * [DocumentTypeRepository] object. In OpenID4VP unregistered transaction types cause the whole
 * request to be rejected. In ISO/IEC 18013-5:2021, unknown transaction types are not processed,
 * which may or may not fail at verification time.
 *
 * @param displayName human-readable transaction name
 * @param identifier unique transaction type identifier, corresponds to the `type` property in
 *  transaction data in OpenID4VP; all [TransactionType] objects must have distinct identifiers.
 * @param kbJwtResponseClaimName if transaction processing results in any data, it will be inserted
 *  in key binding JWT using this claim name; all [TransactionType] objects must have distinct
 *  values.
 * @param mdocRequestInfoKeyName key to use in `requestInfo` map in ISO/IEC 18013-5:2021 document
 *  request to represent this transaction data; all [TransactionType] objects must have distinct
 *  values.
 * @param mdocResponseNamespace namespace to use in `deviceSigned` namespace map in
 *  ISO/IEC 18013-5:2021 response to represent transaction hash and transaction processing
 *  results; all [TransactionType] objects must have distinct values.
 */
abstract class TransactionType<PayloadT: Any>(
    val displayName: String,
    val identifier: String,
    val kbJwtResponseClaimName: String = identifier,
    val mdocRequestInfoKeyName: String = identifier,
    val mdocResponseNamespace: String = identifier,
) {
    /**
     * Serializes transaction data for use in OpenID4VP protocol.
     *
     * @param payload transaction-specific data
     * @param credentialIds list of DCQL credential ids to which this transaction should be applied
     * @param hashAlgorithms optional list of hash algorithms that are accepted by the verifier
     * @return JSON-serialized (but **not** Base64Url-encoded!) transaction data
     */
    abstract fun serializeJson(
        payload: PayloadT,
        credentialIds: List<String>,
        hashAlgorithms: List<Algorithm>? = null
    ): String

    /**
     * Serializes transaction data for use in ISO/IEC 18013 protocols.
     *
     * @param payload transaction-specific data
     * @param hashAlgorithms optional list of hash algorithms that are accepted by the verifier
     * @return serialized transaction data
     */
    abstract fun serializeCbor(
        payload: PayloadT,
        hashAlgorithms: List<Algorithm>? = null
    ): ByteString

    /**
     * Parses transaction data serialized for use in OpenID4VP protocol.
     *
     * @param serialized serialized transaction data (Base64Url-encoded JSON)
     * @return [TransactionData] object that holds serialized and parsed transaction data representations
     */
    abstract fun parseJson(serialized: ByteString): TransactionData<PayloadT>

    /**
     * Parses transaction data serialized for use in ISO/IEC 18013 protocols.
     *
     * @param serialized serialized transaction data
     * @return [TransactionData] object that holds serialized and parsed transaction data representations
     */
    abstract fun parseCbor(serialized: ByteString): TransactionData<PayloadT>

    /**
     * Determines if this transaction is applicable to the given credential.
     *
     * When transaction cannot be processed, it removes a particular "use case" or credential
     * set option from consideration. If other options are available, presentment still may
     * succeed.
     *
     * @param transactionData transaction data being considered
     * @param credential one of the credentials in the [Document] being considered
     * @return true if transaction can be processed false if it cannot
     */
    abstract suspend fun isApplicable(
        transactionData: TransactionData<PayloadT>,
        credential: Credential
    ): Boolean

    /**
     * Applies transaction in the context of ISO/IEC 18013-5:2021 presentment.
     *
     * @param transactionData transaction data
     * @param credential credential being presented
     * @return transaction-specific data that should be added to the presentment (in `deviceSigned`
     *  namespace map using [mdocResponseNamespace]), `null` if no extra data should be added.
     */
    open suspend fun applyCbor(
        transactionData: TransactionData<PayloadT>,
        credential: Credential
    ): Map<String, DataItem>? {
        return null
    }

    /**
     * Applies transaction in the context of OpenID4VP presentment.
     *
     * Default implementation adds the same values as [applyCbor], which is the recommended
     * behavior.
     *
     * @param transactionData transaction data
     * @param credential credential being presented
     * @return transaction-specific data that should be added to the presentment (in key-binding
     *  JWT body using [kbJwtResponseClaimName]), `null` if no extra data should be added.
     */
    open suspend fun applyJson(
        transactionData: TransactionData<PayloadT>,
        credential: Credential
    ): JsonElement? {
        val extra = applyCbor(transactionData, credential) ?: return null
        return buildJsonObject {
            for ((key, value) in extra) {
                put(key, value.toJson())
            }
        }
    }

    companion object {
        private const val TAG = "TransactionType"

        /**
         * Converts the list of algorithms for use in ISO/IEC 18013 protocols.
         *
         * Only algorithms with well-defined COSE identifiers are passed through
         *
         * @return list of algorithms as COSE identifiers
         * @throws IllegalArgumentException if no algorithms can be represented
         */
        fun coseHashAlgorithms(transactionDataHashesAlg: List<Algorithm>?): List<Long>? =
            transactionDataHashesAlg
                ?.mapNotNull { it.coseAlgorithmIdentifier?.toLong() }
                ?.ifEmpty { throw IllegalArgumentException("No valid hash algorithms") }

        /**
         * Converts the list of algorithms for use in OpenID4VP protocol.
         *
         * Only algorithms with well-defined JOSE identifiers are passed through
         *
         * @return list of algorithms as JOSE identifiers
         * @throws IllegalArgumentException if no algorithms can be represented
         */
        fun joseHashAlgorithms(transactionDataHashesAlg: List<Algorithm>?): List<String>? =
            transactionDataHashesAlg
                ?.mapNotNull { it.joseAlgorithmIdentifier }
                ?.ifEmpty { throw IllegalArgumentException("No valid hash algorithms") }

        /**
         * Parses the list of JOSE algorithm identifiers.
         *
         * Unknown algorithm identifiers are skipped (and logged).
         *
         * @return list of algorithms
         * @throws IllegalArgumentException if there are no known algorithms in the list
         */
        fun parseJoseHashAlgorithms(transactionDataHashesAlg: List<String>?): List<Algorithm>? =
            transactionDataHashesAlg?.mapNotNull {
                try {
                    Algorithm.fromHashAlgorithmIdentifier(it)
                } catch (err: IllegalArgumentException) {
                    Logger.e(TAG, "Unknown hash algorithm '$it'", err)
                    null
                }
            }?.ifEmpty {
                throw IllegalArgumentException("No valid hash algorithms")
            }

        /**
         * Parses the list of COSE algorithm identifiers.
         *
         * Unknown algorithm identifiers are skipped (and logged).
         *
         * @return list of algorithms
         * @throws IllegalArgumentException if there are no known algorithms in the list
         */
        fun parseCoseHashAlgorithms(transactionDataHashesAlg: List<Long>?): List<Algorithm>? =
            transactionDataHashesAlg?.mapNotNull {
                try {
                    Algorithm.fromCoseAlgorithmIdentifier(it.toInt())
                } catch (err: IllegalArgumentException) {
                    Logger.e(TAG, "Unknown hash algorithm '$it'", err)
                    null
                }
            }?.ifEmpty {
                throw IllegalArgumentException("No valid hash algorithms")
            }
    }
}