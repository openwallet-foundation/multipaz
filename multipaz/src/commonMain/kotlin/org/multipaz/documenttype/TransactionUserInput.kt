package org.multipaz.documenttype

import kotlinx.serialization.json.JsonElement
import org.multipaz.cbor.DataItem
import org.multipaz.credential.Credential
import org.multipaz.presentment.TransactionData

/**
 * Object that encapsulates additional explicit user input for transaction processing for certain
 * transaction type.
 *
 * For instance, this can hold user-selected tip amount for a payment transaction.
 */
abstract class TransactionUserInput {

    /**
     * Returns the list of data elements to add to the transaction response in ISO mdoc presentment.
     *
     * @param transactionData transaction data
     * @param credential credential being presented
     * @return transaction-specific data that should be added to the presentment
     */
    abstract fun generateMdocResponseElements(
        transactionData: TransactionData<*>,
        credential: Credential
    ): Map<String, DataItem>

    /**
     * Returns the list of claims to add to the transaction response in SD-JWT presentment.
     *
     * @param transactionData transaction data
     * @param credential credential being presented
     * @return transaction-specific data that should be added to the presentment
     */
    abstract fun generateSdJwtResponseClaims(
        transactionData: TransactionData<*>,
        credential: Credential
    ): Map<String, JsonElement>
}