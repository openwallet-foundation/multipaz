package org.multipaz.openid

import kotlinx.io.bytestring.ByteString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.multipaz.crypto.Algorithm
import org.multipaz.crypto.Crypto
import org.multipaz.util.fromBase64Url

/**
 * An object that describes transaction data item in the context of OpenID4VP.
 *
 * TODO: perhaps we need to make this a base sealed class and have one variant for JSON-based
 *  workflows and another for CBOR ones?
 *
 * @param hash hash of encoded transaction data item, calculated using [hashAlgorithm] or SHA256
 *  if not explicitly specified
 * @param hashAlgorithm algorithm, only if it was explicitly specified in transaction data
 * @param type type of the transaction data item
 * @param data JSON object that represents the transaction data item
 */
class TransactionData(
    val hash: ByteString,
    val hashAlgorithm: Algorithm?,
    val type: String,
    val data: JsonObject
) {
    companion object {
        /**
         * Parses encoded transaction data.
         *
         * @param transactionData encoded transaction data (array of base64url-encoded items)
         * @return map of credential id to the list of applicable transaction data items
         */
        suspend fun parse(
            transactionData: JsonElement
        ): Map<String, List<TransactionData>> {
            transactionData as? JsonArray
                ?: throw IllegalArgumentException("Invalid transaction_data")
            return parse(transactionData.map { it.jsonPrimitive.content })
        }

        /**
         * Parses encoded transaction data.
         *
         * @param transactionData encoded transaction data (array of base64url-encoded items)
         * @param hashAlgorithmOverrides map of credential id to the hash algorithm that must be
         *  used for that specific credential
         * @return map of credential id to the list of applicable transaction data items
         */
        suspend fun parse(
            transactionData: List<String>,
            hashAlgorithmOverrides: Map<String, Algorithm?>? = null
        ): Map<String, List<TransactionData>> {
            val map = mutableMapOf<String, MutableList<TransactionData>>()
            for (encoded in transactionData) {
                val jsonText = encoded.fromBase64Url().decodeToString()
                val data = Json.parseToJsonElement(jsonText).jsonObject
                val credentialIds = data["credential_ids"]!!.jsonArray
                val type = data["type"]!!.jsonPrimitive.content
                if (type != "org.multipaz.transaction_data.test") {
                    throw IllegalArgumentException("Unsupported transaction type: '$type'")
                }
                val hashAlgorithm = if (hashAlgorithmOverrides != null) {
                    // hashAlgorithmOverrides is not null when parsing for verification. At that
                    // point the presenter picked and algorithm, we must use that same algorithm.
                    hashAlgorithmOverrides[credentialIds.first().jsonPrimitive.content]
                } else {
                    data["transaction_data_hashes_alg"]?.let { algs ->
                        algs.jsonArray.firstNotNullOf { alg ->
                            (alg as? JsonPrimitive)?.let {
                                try {
                                    Algorithm.fromHashAlgorithmIdentifier(it.content)
                                } catch (_: IllegalArgumentException) {
                                    null
                                }
                            }
                        }
                    }
                }
                val hash = Crypto.digest(hashAlgorithm ?: Algorithm.SHA256, encoded.encodeToByteArray())
                val parsed = TransactionData(ByteString(hash), hashAlgorithm, type, data)
                for (id in credentialIds) {
                    map.getOrPut(id.jsonPrimitive.content) { mutableListOf() }.add(parsed)
                }
            }
            return map.mapValues { (_, list) -> list.toList() }
        }
    }
}