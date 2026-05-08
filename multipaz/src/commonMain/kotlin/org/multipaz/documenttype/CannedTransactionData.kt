package org.multipaz.documenttype

import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import org.multipaz.cbor.CborMap
import kotlin.collections.component1
import kotlin.collections.component2
import kotlin.collections.iterator

/**
 * Sample data for request using a particular transaction data type.
 *
 * @param transactionType transaction data type
 * @param attributes map of attribute names to sample attribute values
 */
class CannedTransactionData(
    val transactionType: TransactionType,
    val attributes: CborMap
) {
    /**
     * Converts transaction data to OpenID4VP JSON format (before Base64Url encoding).
     *
     * @param credentialId id of the credential in DCQL that this transaction targets.
     * @return transaction data in OpenID4VP JSON format (before Base64Url encoding).
     */
    fun toJsonText(credentialId: String): String {
        val transactionData = buildJsonObject {
            put("type", transactionType.identifier)
            putJsonArray("credential_ids") {
                add(credentialId)
            }
            for ((name, value) in attributes.asMap) {
                put(name.asTstr, value.toJson())
            }
        }
        return transactionData.toString()
    }
}