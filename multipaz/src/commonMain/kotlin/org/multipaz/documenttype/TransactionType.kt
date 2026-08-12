package org.multipaz.documenttype

import kotlinx.io.bytestring.ByteString
import kotlinx.serialization.json.JsonElement
import org.multipaz.cbor.Bstr
import org.multipaz.cbor.DataItem
import org.multipaz.cbor.toDataItem
import org.multipaz.cbor.Tagged
import org.multipaz.credential.Credential
import org.multipaz.crypto.Algorithm
import org.multipaz.document.Document
import org.multipaz.mdoc.credential.MdocCredential
import org.multipaz.mdoc.mso.MobileSecurityObject
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
 * @param mdocRequestInfoIdentifier transaction type to use in `transactions` array in
 *  `requestInfo` map in ISO/IEC 18013-5:2021 document request to represent this transaction
 *  data; all [TransactionType] objects must have distinct values.
 * @param mdocResponseNamespace namespace to use in `deviceSigned` namespace map in
 *  ISO/IEC 18013-5:2021 response to represent transaction hash and transaction processing
 *  results; all [TransactionType] objects must have distinct values.
 */
abstract class TransactionType<PayloadT: Any>(
    val displayName: String,
    val identifier: String,
    val kbJwtResponseClaimName: String = identifier,
    val mdocRequestInfoIdentifier: String = identifier,
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
    ): DataItem

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
     * @param serialized transaction data as it is represented in the request (specifically,
     *  value of the `data` field in the transaction object in the `transactions` array inside
     *  `requestInfo`); in many cases [serialized] is expected to be [Tagged] with
     *  [Tagged.tagNumber] equal to [Tagged.ENCODED_CBOR]
     * @return [TransactionData] object that holds serialized and parsed transaction data representations
     */
    abstract fun parseCbor(serialized: DataItem): TransactionData<PayloadT>

    /**
     * Determines if this transaction is applicable to the given credential.
     *
     * When transaction cannot be processed, it removes a particular "use case" or credential
     * set option from consideration. If other options are available, presentment still may
     * succeed.
     *
     * For mdoc credentials this method must check [mdocResponseNamespace] against
     * [MobileSecurityObject.deviceKeyAuthorizedNamespaces] and possibly
     * [MobileSecurityObject.deviceKeyAuthorizedDataElements] to determine if the transaction is
     * applicable for this specific credential.
     *
     * @param transactionData transaction data being considered
     * @param credential one of the credentials in the [Document] being considered
     * @return true if transaction can be processed false if it cannot
     */
    open suspend fun isApplicable(
        transactionData: TransactionData<PayloadT>,
        credential: Credential
    ): Boolean {
        return if (credential is MdocCredential) {
            // For mdoc there is a per-credential KeyAuthorizations section. We need to check
            // it to determine if this transaction can be applied to this credential
            credential.mso.deviceKeyAuthorizedNamespaces.contains(mdocResponseNamespace)
        } else {
            true
        }
    }

    /**
     * Applies transaction in the context of ISO mdoc presentment.
     *
     * Note: unlike OpenID4VP, ISO/IEC 18013-5:2021 does not impose a particular requirement on
     * transaction response (e.g. responding at least with transaction data hash). Each transaction
     * type should define its own **verifiable** response. This response then will be validated
     * by the verifier the using [verifyCborResponse] method.
     *
     * Default implementation computes transaction data hash, similar to how OpenID4VP does it.
     *
     * Note: one should not assume that [transactionData] will be in CBOR format. Transaction data
     * is formatted according to the presentment protocol.
     *
     * @param transactionData transaction data
     * @param credential credential being presented
     * @return transaction-specific data that should be added to the presentment (in `deviceSigned`
     *  namespace map using [mdocResponseNamespace]), `null` if no extra data should be added.
     */
    open suspend fun applyCbor(
        transactionData: TransactionData<PayloadT>,
        credential: Credential
    ): Map<String, DataItem> = buildMap {
        val alg = transactionData.hashAlgorithms?.first()?.also {
            put("transactionDataHashAlg", it.coseAlgorithmIdentifier!!.toDataItem())
        }
        put("transactionDataHash",
            transactionData.computeHash(alg ?: Algorithm.SHA256).toByteArray().toDataItem())
    }

    /**
     * Applies transaction in the context of IETF SD-JWT presentment.
     *
     * Default implementation does not add any transaction-specific data.
     *
     * @param transactionData transaction data
     * @param credential credential being presented
     * @return transaction-specific data that should be added to the presentment (in key-binding
     *  JWT body using [kbJwtResponseClaimName]), `null` if no extra data should be added.
     */
    open suspend fun applyJson(
        transactionData: TransactionData<PayloadT>,
        credential: Credential
    ): JsonElement? = null

    /**
     * Verify transaction response for mdoc presentment.
     *
     * Note: unlike OpenID4VP, ISO/IEC 18013-5:2021 does not impose a particular requirement on
     * transaction response (e.g. responding at least with transaction data hash). Each transaction
     * type should define its own **verifiable** response and implement verification in this
     * method.
     *
     * Default implementation verifies transaction data hash computed by default implementation
     * of [applyCbor].
     *
     * @param transactionData transaction data
     * @param transactionResponse key-value-map for values returned in [mdocResponseNamespace]
     *  namespace in the credential presentation
     * @throws IllegalStateException if response does not pass verification
     */
    open suspend fun verifyCborResponse(
        transactionData: TransactionData<PayloadT>,
        transactionResponse: Map<String, DataItem>
    ) {
        val hashAlg = transactionResponse["transactionDataHashAlg"]?.let {
            Algorithm.fromCoseAlgorithmIdentifier(it.asNumber.toInt())
        }
        val hash = transactionResponse["transactionDataHash"] as? Bstr
            ?: throw IllegalStateException("Invalid response for transaction '$identifier'")
        val expectedHash = transactionData.computeHash(hashAlg ?: Algorithm.SHA256)
        if (ByteString(hash.asBstr) != expectedHash) {
            throw IllegalStateException("Transaction hash failed to verify for '$identifier'")
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