package org.multipaz.presentment

import kotlinx.io.bytestring.ByteString
import org.multipaz.crypto.Algorithm
import org.multipaz.documenttype.TransactionType

/**
 * An abstract object that describes transaction data item.
 *
 * Exact format of the transaction data and hashing rules depend on the presentation protocol.
 *
 * @param type type of the transaction data item
 */
sealed class TransactionData(
    val type: TransactionType
) {
    /**
     * Hash algorithm override for this transaction data.
     *
     * By default [Algorithm.SHA256] is used, but transaction data can specify a list of the desired
     * algorithms.
     *
     * @return the first supported algorithm in the list specified by the transaction data.
     */
    abstract fun getHashAlgorithm(): Algorithm?

    /**
     * Computes hash of the transaction data.
     *
     * It is important that the verifier uses the same algorithm as the presenter (NB: the set
     * of supported hash algorithms may differ!).
     *
     * @return hash of the transaction data
     */
    abstract suspend fun getHash(algorithm: Algorithm = Algorithm.SHA256): ByteString

    /** @return string attribute */
    abstract fun getString(name: String): String?

    /** @return integer attribute */
    abstract fun getLong(name: String): Long?

    /** @return boolean attribute */
    abstract fun getBoolean(name: String): Boolean?

    /** @return binary attribute */
    abstract fun getBlob(name: String): ByteString?
}