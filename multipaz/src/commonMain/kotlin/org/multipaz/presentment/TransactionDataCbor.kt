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
    private val decoded = data.asTaggedEncodedCbor as CborMap

    override fun getHashAlgorithm(): Algorithm? =
        if (decoded.hasKey("transaction_data_hashes_alg")) {
            decoded["transaction_data_hashes_alg"].asArray.firstNotNullOfOrNull { alg ->
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
        ByteString(Crypto.digest(algorithm, data.taggedItem.asBstr))

    override fun getString(name: String): String? =
        if (decoded.hasKey(name)) decoded[name].asTstr else null

    override fun getLong(name: String): Long? =
        if (decoded.hasKey(name)) decoded[name].asNumber else null

    override fun getBoolean(name: String): Boolean? =
        if (decoded.hasKey(name)) decoded[name].asBoolean else null

    override fun getBlob(name: String): ByteString? =
        if (decoded.hasKey(name)) ByteString(decoded[name].asBstr) else null
}