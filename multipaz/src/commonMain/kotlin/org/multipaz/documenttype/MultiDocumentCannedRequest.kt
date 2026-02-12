package org.multipaz.documenttype

/**
 * A well-known request for a multiple documents.
 *
 * @param id an identifier for the well-known document request (unique only for the document type).
 * @param displayName a short string with the name of the request, short enough to be used
 *   for a button. For example "Age Over 21 and Portrait" or "Full mDL".
 * @param dcqlString a text string with the DCQL for the request.
 */
data class MultiDocumentCannedRequest(
    override val id: String,
    override val displayName: String,
    val dcqlString: String
): DocumentCannedRequest(id, displayName)
