package org.multipaz.cards

/**
 * Computed dimensions and horizontal offset for a card in the layout.
 *
 * @property width the rendered width of the card in dp/points.
 * @property height the rendered height of the card in dp/points.
 * @property xOffset the horizontal offset (for centering within viewport) in dp/points.
 */
data class CardDimensions(
    val width: Double,
    val height: Double,
    val xOffset: Double
)
