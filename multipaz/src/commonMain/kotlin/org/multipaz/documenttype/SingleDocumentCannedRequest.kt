package org.multipaz.documenttype

/**
 * A well-known request for a single document.
 *
 * @param id an identifier for the well-known document request (unique only for the document type).
 * @param displayName a short string with the name of the request, short enough to be used
 *   for a button. For example "Age Over 21 and Portrait" or "Full mDL".
 * @param mdocRequest the requests for a ISO mdoc credential, if defined.
 * @param jsonRequest the requests for a JSON-based credential, if defined.
 */
data class SingleDocumentCannedRequest(
    override val id: String,
    override val displayName: String,
    val mdocRequest: MdocCannedRequest?,
    val jsonRequest: JsonCannedRequest?
): DocumentCannedRequest(id, displayName)
