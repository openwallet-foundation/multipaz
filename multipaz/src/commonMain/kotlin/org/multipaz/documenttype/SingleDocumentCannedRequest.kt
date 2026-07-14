package org.multipaz.documenttype

import kotlinx.io.bytestring.encodeToByteString
import org.multipaz.presentment.TransactionData
import org.multipaz.util.toBase64Url

/**
 * A well-known request for a single document.
 *
 * @property id an identifier for the well-known document request (unique only for the document type).
 * @property displayName a short string with the name of the request, short enough to be used
 *   for a button. For example "Age Over 21 and Portrait" or "Full mDL".
 * @property mdocRequest the requests for a ISO mdoc credential, if defined.
 * @property jsonRequest the requests for a JSON-based credential, if defined.
 * @property transactionData transaction data list for this request
 */
data class SingleDocumentCannedRequest(
    override val id: String,
    override val displayName: String,
    val mdocRequest: MdocCannedRequest?,
    val jsonRequest: JsonCannedRequest?,
    val transactionData: List<CannedTransactionData<*>> = listOf()
): DocumentCannedRequest(id, displayName) {
    /**
     * @param credentialId DCQL id of the (single) requested credential
     * @return a single-element map that maps the given [credentialId] to the list of
     *   [TransactionData] objects created from [transactionData] in this request.
     */
    fun toTransactionDataMap(credentialId: String): Map<String, List<TransactionData<*>>> = mapOf(
        credentialId to transactionData.map {
            val json = it.getSerializedJson(listOf(credentialId))
            it.transactionType.parseJson(json.encodeToByteArray().toBase64Url().encodeToByteString())
        })

    /**
     * @param credentialId DCQL id of the credential for which this transaction data is given
     * @return list of OpenID4VCI JSON-formatted (but not Base64Url-encoded) transactions
     */
    fun toJsonTransactionData(credentialId: String): List<String> = transactionData.map {
        it.getSerializedJson(listOf(credentialId))
    }
}
