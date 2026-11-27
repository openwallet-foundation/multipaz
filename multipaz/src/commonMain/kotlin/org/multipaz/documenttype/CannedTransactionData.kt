package org.multipaz.documenttype

/**
 * Sample data for request using a particular transaction data type.
 *
 * A canned request is usually created once and kept for the lifetime of the process, but some
 * transaction types embed an expiry or a single-use request ID in their payload. Such types should
 * supply a [payloadProvider] that generates a fresh payload on every access instead of a fixed
 * [payload], so serializing an old canned request never uses an expired sample.
 *
 * @param transactionType transaction data type
 * @param payloadProvider provider of the transaction payload, invoked on every access
 */
class CannedTransactionData<PayloadT: Any>(
    val transactionType: TransactionType<PayloadT>,
    private val payloadProvider: () -> PayloadT
) {
    /**
     * Sample data for a request using this transaction type with a fixed payload.
     *
     * @param transactionType transaction data type
     * @param payload transaction payload
     */
    constructor(
        transactionType: TransactionType<PayloadT>,
        payload: PayloadT
    ) : this(transactionType, { payload })

    /**
     * Sample payload for this transaction type.
     *
     * For types with time-limited sample data this is a new payload on every access.
     */
    val payload: PayloadT
        get() = payloadProvider()

    /**
     * Gets this transaction data as serialized JSON to be used in a sample request.
     *
     * @param credentialIds a list of DCQL credential ids for credentials to which this transaction data is going to be applied
     * @return transaction data serialized as JSON (but **not** Base64Url-encoded)
     */
    fun getSerializedJson(credentialIds: List<String>): String =
        transactionType.serializeOpenId4VpRequest(payload, credentialIds, hashAlgorithms = null)
}