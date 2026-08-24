package org.multipaz.cards

/**
 * Result of handling a tap on a card item.
 */
sealed interface CardTapAction {
    /**
     * Tap should be ignored (e.g. during an active drag or within the drag cooldown window).
     */
    data object Ignore : CardTapAction

    /**
     * A card in standard list mode was tapped to focus it.
     *
     * @property cardIdentifier identifier of the card to focus.
     */
    data class Focus(val cardIdentifier: String) : CardTapAction

    /**
     * The currently focused card was tapped.
     *
     * @property cardIdentifier identifier of the focused card.
     */
    data class FocusedCardTapped(val cardIdentifier: String) : CardTapAction

    /**
     * A card in the unfocused background stack was tapped while another card is focused.
     *
     * @property cardIdentifier identifier of the currently focused card.
     */
    data class FocusedStackTapped(val cardIdentifier: String) : CardTapAction
}
