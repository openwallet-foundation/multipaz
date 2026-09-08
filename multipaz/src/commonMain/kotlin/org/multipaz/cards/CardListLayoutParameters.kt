package org.multipaz.cards

/**
 * Parameters configuring the layout calculations for a vertical card list.
 *
 * @property viewportWidth the available viewport width in dp/points.
 * @property viewportHeight the available viewport height in dp/points.
 * @property cardMaxHeight optional maximum height constraint for cards.
 * @property paddingTop top padding for the card list in dp/points.
 * @property paddingBottom bottom padding for the card list in dp/points.
 * @property paddingHorizontal horizontal padding for the cards in dp/points.
 * @property unfocusedVisiblePercent percentage of each card visible when not focused (0 to 100).
 * @property showStackWhileFocused whether unfocused cards collapse into a bottom stack when a card is focused.
 * @property topContentHeight measured height of the top content slot in dp/points.
 * @property isTopContentVisible whether top content should be displayed when no card is focused.
 * @property topContentProgress animation progress for top content (0.0 = fully hidden, 1.0 = fully visible).
 * @property scrollOffset current vertical scroll offset in dp/points.
 * @property stackOffset vertical offset between cards in the bottom 3D stack in dp/points.
 * @property maxVisibleCardsInStack maximum number of visible card layers in the 3D stack.
 * @property frontCardVisibleHeightFraction fraction of the front card visible in the 3D stack.
 * @property spacing vertical spacing between elements in dp/points (e.g. 16.0).
 */
data class CardListLayoutParameters(
    val viewportWidth: Double,
    val viewportHeight: Double,
    val cardMaxHeight: Double? = null,
    val paddingTop: Double = 16.0,
    val paddingBottom: Double = 16.0,
    val paddingHorizontal: Double = 16.0,
    val unfocusedVisiblePercent: Int = 25,
    val showStackWhileFocused: Boolean = true,
    val topContentHeight: Double = 0.0,
    val isTopContentVisible: Boolean = true,
    val topContentProgress: Double = 1.0,
    val scrollOffset: Double = 0.0,
    val stackOffset: Double = 14.0,
    val maxVisibleCardsInStack: Int = 5,
    val frontCardVisibleHeightFraction: Double = 0.25,
    val spacing: Double = 16.0
) {
    init {
        require(unfocusedVisiblePercent in 0..100) {
            "unfocusedVisiblePercent must be between 0 and 100, got $unfocusedVisiblePercent"
        }
        require(topContentProgress in 0.0..1.0) {
            "topContentProgress must be between 0.0 and 1.0, got $topContentProgress"
        }
    }
}
