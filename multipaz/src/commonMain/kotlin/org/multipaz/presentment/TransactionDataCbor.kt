package org.multipaz.presentment

import kotlinx.io.bytestring.ByteString
import org.multipaz.cbor.CborMap
import org.multipaz.cbor.Tagged
import org.multipaz.crypto.Algorithm
import org.multipaz.crypto.Crypto
import org.multipaz.documenttype.TransactionType

/**
 * [TransactionData] in CBOR format as used in ISO/IEC 18013-5:2021.
 *
 * @param type transaction type
 * @param data transaction data, must be tagged with [Tagged.ENCODED_CBOR]; transaction hash is
 *  computed from the serialized data wrapped in this [Tagged].
 */
class TransactionDataCbor(
    type: TransactionType,
    val data: Tagged
): TransactionData(type) {
    override val attributes = AttributesCbor(this@TransactionDataCbor.data.asTaggedEncodedCbor as CborMap)

    override fun getHashAlgorithm(): Algorithm? =
        if (attributes.data.hasKey("transaction_data_hashes_alg")) {
            attributes.data["transaction_data_hashes_alg"].asArray.firstNotNullOfOrNull { alg ->
                try {
                    Algorithm.fromCoseAlgorithmIdentifier(alg.asNumber.toInt()).let {
                        if (it.hashAlgorithmName != null) it else null
                    }
                } catch (_: IllegalArgumentException) {
                    null
                }
            }
        } else {
            null
        }

    override suspend fun getHash(algorithm: Algorithm) =
        ByteString(Crypto.digest(algorithm, this@TransactionDataCbor.data.taggedItem.asBstr))

    /**
     * Implementation of [TransactionData.Attributes] for CBOR-formatted transactions.
     *
     * @param data transaction data as CBOR map.
     */
    class AttributesCbor(val data: CborMap): Attributes {
        override fun getString(name: String): String? =
            if (data.hasKey(name)) data[name].asTstr else null

        override fun getLong(name: String): Long? =
            if (data.hasKey(name)) data[name].asNumber else null

        override fun getDouble(name: String): Double? =
            if (data.hasKey(name)) data[name].asDouble else null

        override fun getBoolean(name: String): Boolean? =
            if (data.hasKey(name)) data[name].asBoolean else null

        override fun getBlob(name: String): ByteString? =
            if (data.hasKey(name)) ByteString(data[name].asBstr) else null

        override fun getCompound(name: String): Attributes? =
            if (data.hasKey(name)) AttributesCbor(data[name] as CborMap) else null
    }
}