package org.multipaz.compose.document

import androidx.compose.ui.graphics.ImageBitmap
import org.multipaz.compose.cards.CardBadge
import org.multipaz.compose.cards.CardInfo
import org.multipaz.document.Document

/**
 * Information about a [Document] in a [DocumentModel].
 *
 * @property document the [Document] instance as stored in the [org.multipaz.document.DocumentStore]
 * @property cardArt an image that represents this document to the user in the UI.
 * @property badges badges for the document.
 * @property credentialInfos list of [CredentialInfo]
 */
data class DocumentInfo(
    val document: Document,
    override val cardArt: ImageBitmap,
    override val badges: List<CardBadge>,
    val credentialInfos: List<CredentialInfo>
): CardInfo {
    override val identifier: String
        get() = document.identifier
}
