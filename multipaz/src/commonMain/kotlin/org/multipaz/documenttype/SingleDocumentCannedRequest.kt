package org.multipaz.documenttype

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
    val transactionData: List<CannedTransactionData> = listOf()
): DocumentCannedRequest(id, displayName)
