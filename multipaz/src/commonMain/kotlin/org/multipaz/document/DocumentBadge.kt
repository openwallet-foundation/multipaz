package org.multipaz.document

/**
 * A badge to display on a document.
 *
 * @param text the text in the badge.
 * @param color the color of the badge.
 */
data class DocumentBadge(
    val text: String,
    val color: DocumentBadgeColor
)
