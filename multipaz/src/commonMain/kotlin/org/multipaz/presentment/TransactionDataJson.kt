package org.multipaz.presentment

import kotlinx.io.bytestring.ByteString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.multipaz.crypto.Algorithm
import org.multipaz.crypto.Crypto
import org.multipaz.documenttype.DocumentTypeRepository
import org.multipaz.documenttype.TransactionType
import org.multipaz.util.fromBase64Url

/**
 * [TransactionData] in JSON format as used in OpenID4VP.
 *
 * @param type transaction type
 * @param base64UrlEncodedJson transaction data as JSON which is then Base64Url-encoded; this is
 *  what is used to compute transaction data hash in OpenID4VP
 * @param data JSON transaction data; must be the same data as in [base64UrlEncodedJson]
 */
class TransactionDataJson(
    type: TransactionType,
    val base64UrlEncodedJson: String,
    val data: JsonObject =
        Json.parseToJsonElement(base64UrlEncodedJson.fromBase64Url().decodeToString()).jsonObject
): TransactionData(type) {

    override fun getHashAlgorithm(): Algorithm? =
        data["transaction_data_hashes_alg"]?.let { algs ->
            algs.jsonArray.firstNotNullOfOrNull { alg ->
                (alg as? JsonPrimitive)?.let {
                    try {
                        Algorithm.fromHashAlgorithmIdentifier(it.content)
                    } catch (_: IllegalArgumentException) {
                        null
                    }
                }
            } ?: throw IllegalArgumentException(
                "No supported algorithms in transaction_data_hashes_alg")
        }

    override suspend fun getHash(algorithm: Algorithm) =
        ByteString(Crypto.digest(algorithm, base64UrlEncodedJson.encodeToByteArray()))

    override fun getString(name: String): String? =
        data[name]?.jsonPrimitive?.content

    override fun getLong(name: String): Long? =
        data[name]?.jsonPrimitive?.long

    override fun getBoolean(name: String): Boolean? =
        data[name]?.jsonPrimitive?.boolean

    override fun getBlob(name: String): ByteString? =
        data[name]?.let { ByteString(it.jsonPrimitive.content.fromBase64Url()) }

    companion object {
        /**
         * Parses OpenID4VP JSON-encoded transaction data.
         *
         * @param transactionData encoded transaction data (array of base64url-encoded JSON items)
         * @param documentTypeRepository [DocumentTypeRepository] to look up transaction types
         * @return map of credential id to the list of applicable transaction data items
         */
        fun parse(
            transactionData: JsonElement,
            documentTypeRepository: DocumentTypeRepository
        ): Map<String, List<TransactionDataJson>> {
            transactionData as? JsonArray
                ?: throw IllegalArgumentException("Invalid transaction_data")
            return parse(
                base64UrlEncodedJson = transactionData.map { it.jsonPrimitive.content },
                documentTypeRepository = documentTypeRepository
            )
        }

        /**
         * Parses OpenID4VP JSON-encoded transaction data.
         *
         * @param base64UrlEncodedJson encoded transaction data (array of base64url-encoded items)
         * @param documentTypeRepository [DocumentTypeRepository] to look up transaction types
         * @return map of credential id to the list of applicable transaction data items
         */
        fun parse(
            base64UrlEncodedJson: List<String>,
            documentTypeRepository: DocumentTypeRepository
        ): Map<String, List<TransactionDataJson>> {
            val map = mutableMapOf<String, MutableList<TransactionDataJson>>()
            for (base64UrlText in base64UrlEncodedJson) {
                val data = Json.parseToJsonElement(
                    base64UrlText.fromBase64Url().decodeToString()
                ).jsonObject
                val credentialIds = (data["credential_ids"] as? JsonArray)
                    ?: throw IllegalArgumentException("Missing 'credential_ids' in transaction data")
                val typeId = (data["type"] as? JsonPrimitive)?.contentOrNull
                    ?: throw IllegalArgumentException("Missing or invalid 'type' in transaction data")
                val type = documentTypeRepository.getTransactionTypeByIdentifier(typeId)
                    ?: throw IllegalArgumentException("Unknown transaction type '$typeId'")
                val parsed = TransactionDataJson(type, base64UrlText, data)
                for (id in credentialIds) {
                    map.getOrPut(id.jsonPrimitive.content) { mutableListOf() }.add(parsed)
                }
            }
            return map.mapValues { (_, list) -> list.toList() }
        }
    }
}