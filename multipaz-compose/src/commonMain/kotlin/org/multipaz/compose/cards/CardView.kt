package org.multipaz.compose.cards

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex

/**
 * A composable that displays a card's artwork with rounded corners, shadow elevation, and badges.
 *
 * @param cardInfo The [CardInfo] containing the artwork and badges to display.
 * @param modifier The modifier to be applied to the card container.
 * @param shape The shape for the rounded corners. Defaults to `RoundedCornerShape(24.dp)`.
 * @param elevation The shadow elevation for the card. Defaults to `12.dp`.
 * @param contentScale The content scale for the card art image. Defaults to `ContentScale.FillWidth`.
 */
@Composable
fun CardView(
    cardInfo: CardInfo,
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(24.dp),
    elevation: Dp = 12.dp,
    contentScale: ContentScale = ContentScale.FillWidth
) {
    Box(
        modifier = modifier
            .graphicsLayer {
                shadowElevation = elevation.toPx()
                this.shape = shape
                clip = false
            }
    ) {
        Image(
            bitmap = cardInfo.cardArt,
            contentDescription = "Card Image",
            contentScale = contentScale,
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    this.shape = shape
                    clip = true
                }
        )
        CardBadges(
            badges = cardInfo.badges,
            elevation = 8.dp,
            modifier = Modifier
                .align(Alignment.TopEnd)
                .zIndex(100f)
        )
    }
}
