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
     * Transaction attributes
     */
    abstract val attributes: Attributes
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

    /**
     * A set of attributes either in the transaction data itself or in one of the compound
     * objects that it contains.
     *
     * Transaction can come in either JSON or CBOR format depending on the presentment protocol.
     * This helps writing code generically, so it works with either format.
     */
    interface Attributes {
        /** @return string attribute */
        fun getString(name: String): String?

        /** @return integer attribute */
        fun getLong(name: String): Long?

        /** @return number attribute */
        fun getDouble(name: String): Double?

        /** @return boolean attribute */
        fun getBoolean(name: String): Boolean?

        /** @return binary attribute */
        fun getBlob(name: String): ByteString?

        /** @return compound attribute */
        fun getCompound(name: String): Attributes?
    }
}