package org.multipaz.documenttype

import org.multipaz.util.fromBase64Url

/**
 * Sample data for request using a particular transaction data type.
 *
 * @param transactionType transaction data type
 * @param payload transaction payload
 */
class CannedTransactionData<PayloadT: Any>(
    val transactionType: TransactionType<PayloadT>,
    val payload: PayloadT
) {
    /**
     * Gets this transaction data as serialized JSON to be used in a sample request.
     *
     * @param credentialIds a list of DCQL credential ids for credentials to which this transaction data is going to be applied
     * @return transaction data serialized as JSON (but **not** Base64Url-encoded)
     */
    fun getSerializedJson(credentialIds: List<String>): String =
        transactionType.serializeJson(payload, credentialIds, hashAlgorithms = null)
}