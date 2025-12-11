package org.multipaz.compose.document

import androidx.compose.ui.graphics.ImageBitmap
import org.multipaz.document.Document

/**
 * Information about a [Document] in a [DocumentModel].
 *
 * @property document the [Document] instance as stored in the [org.multipaz.document.DocumentStore]
 * @property cardArt an image that represents this document to the user in the UI.
 * @property credentialInfos list of [CredentialInfo]
 */
data class DocumentInfo(
    val document: Document,
    val cardArt: ImageBitmap,
    val credentialInfos: List<CredentialInfo>
)
