package org.multipaz.document

/**
 * Data type for the color of a badge.
 *
 * @property red the red component, between 0 and 255.
 * @property green the green component, between 0 and 255.
 * @property blue the blue component, between 0 and 255.
 */
data class DocumentBadgeColor(
    val red: Int,
    val green: Int,
    val blue: Int
)