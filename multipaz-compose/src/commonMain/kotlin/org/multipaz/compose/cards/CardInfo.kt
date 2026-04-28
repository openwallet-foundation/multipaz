package org.multipaz.compose.cards

import androidx.compose.ui.graphics.ImageBitmap

/**
 * Interface for information about a card to be displayed in a list.
 *
 * @property identifier a unique identifier for this card.
 * @property cardArt an image that represents this card to the user in the UI.
 * @property badges a list of badges to display on the card.
 */
interface CardInfo {
    val identifier: String
    val cardArt: ImageBitmap
    val badges: List<CardBadge>
}
