package org.multipaz.cards

/**
 * Visual presentation state for a card at a given layout/interaction frame.
 *
 * @property y the vertical position in coordinate space in dp/points.
 * @property scale the scale factor to apply (e.g. 1.0, 1.025, 1.05, 0.6..0.95).
 * @property elevation the shadow elevation in dp/points.
 * @property zIndex the drawing order z-index.
 * @property alpha the opacity of the card (0.0 to 1.0).
 */
data class CardVisualState(
    val y: Double,
    val scale: Double,
    val elevation: Double,
    val zIndex: Double,
    val alpha: Double
)
