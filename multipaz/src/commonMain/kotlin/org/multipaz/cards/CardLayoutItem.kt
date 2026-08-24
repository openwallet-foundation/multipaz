package org.multipaz.cards

/**
 * Information about a card required for layout and positioning calculations.
 *
 * @property identifier a unique identifier for the card.
 * @property aspectRatio the width-to-height aspect ratio of the card (e.g. 1.586 for credit card, 1.0 for square).
 */
data class CardLayoutItem(
    val identifier: String,
    val aspectRatio: Double = DEFAULT_ASPECT_RATIO
) {
    companion object {
        /**
         * Default card aspect ratio (ISO/IEC 7810 ID-1 format: 85.60mm / 53.98mm ≈ 1.586).
         */
        const val DEFAULT_ASPECT_RATIO: Double = 1.586
    }
}
