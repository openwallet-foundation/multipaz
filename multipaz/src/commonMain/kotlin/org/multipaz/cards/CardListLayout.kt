package org.multipaz.cards

/**
 * Computed layout metadata for a card list container.
 *
 * @property cardDimensionsMap map from card identifier to its computed [CardDimensions].
 * @property defaultCardDimensions default dimensions for placeholder / empty state.
 * @property listTopOffset top offset of the list items including padding and effective top content.
 * @property totalHeight total scrollable content height.
 * @property maxStackIndex index of the bottom-most stack card.
 * @property maxVisibleStackOffsets number of visible depth steps in the bottom stack.
 * @property detailBottomPadding bottom padding for the focused detail view to avoid stack overlap.
 * @property isTopContentEffectivelyVisible whether top content is currently visible.
 * @property effectiveTopContentHeight effective height of the top content including spacing.
 */
data class CardListLayout(
    val cardDimensionsMap: Map<String, CardDimensions>,
    val defaultCardDimensions: CardDimensions,
    val listTopOffset: Double,
    val totalHeight: Double,
    val maxStackIndex: Int,
    val maxVisibleStackOffsets: Int,
    val detailBottomPadding: Double,
    val isTopContentEffectivelyVisible: Boolean,
    val effectiveTopContentHeight: Double
) {
    /**
     * Gets dimensions for a specific card, or falls back to [defaultCardDimensions].
     */
    fun getDimensions(cardIdentifier: String): CardDimensions {
        return cardDimensionsMap[cardIdentifier] ?: defaultCardDimensions
    }
}
