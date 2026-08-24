package org.multipaz.cards

/**
 * Result of updating a drag gesture.
 *
 * @property reordered true if the card moved to a new index position during this drag update.
 * @property fromIndex original index if reordered, or -1.
 * @property toIndex new index if reordered, or -1.
 */
data class DragUpdateResult(
    val reordered: Boolean,
    val fromIndex: Int = -1,
    val toIndex: Int = -1
)

/**
 * Result of completing a drag gesture.
 *
 * @property cardIdentifier identifier of the card that was moved.
 * @property newIndex final index position of the card in the list.
 */
data class DragEndResult(
    val cardIdentifier: String,
    val newIndex: Int
)
