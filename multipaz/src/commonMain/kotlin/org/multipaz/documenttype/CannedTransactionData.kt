package org.multipaz.documenttype

import org.multipaz.cbor.CborMap

/**
 * Sample data for request using a particular transaction data type.
 *
 * @param transactionType transaction data type
 * @param attributes map of attribute names to sample attribute values
 */
class CannedTransactionData(
    val transactionType: TransactionType,
    val attributes: CborMap
)