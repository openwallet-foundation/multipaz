package org.multipaz.compose.cards

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import org.multipaz.document.DocumentBadge

/**
 * A badge to be displayed on a card.
 *
 * @property text the text to display.
 * @property color the color of the badge.
 */
data class CardBadge(
    val text: String,
    val color: Color
) {
    companion object {
        /**
         * Generates a [CardBadge] from a [DocumentBadge].
         *
         * @param badge the [DocumentBadge] to convert.
         * @return the generated [CardBadge].
         */
        fun fromDocumentBadge(badge: DocumentBadge): CardBadge {
            return CardBadge(
                text = badge.text,
                color = Color(
                    red = badge.color.red,
                    green = badge.color.green,
                    blue = badge.color.blue
                )
            )
        }
    }
}

/**
 * Renders a list of [CardBadge]s as pill-shaped rectangles in the top-right corner.
 *
 * @param badges the list of badges to render.
 * @param elevation the elevation of the badges.
 * @param modifier the modifier for the container.
 */
@Composable
fun CardBadges(
    badges: List<CardBadge>,
    elevation: Dp = 8.dp,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier.padding(12.dp),
        horizontalAlignment = Alignment.End
    ) {
        badges.forEach { badge ->
            val textColor = if (badge.color.luminance() > 0.5f) Color.Black else Color.White
            Box(
                modifier = Modifier
                    .padding(bottom = 8.dp)
                    .graphicsLayer {
                        this.shadowElevation = elevation.toPx()
                        this.shape = RoundedCornerShape(50)
                        this.clip = false
                        this.ambientShadowColor = Color.Black
                        this.spotShadowColor = Color.Black
                    }
                    .background(badge.color, RoundedCornerShape(50))
                    .border(BorderStroke(1.dp, Color.Black.copy(alpha = 0.1f)), RoundedCornerShape(50))
                    .padding(horizontal = 10.dp, vertical = 4.dp)
            ) {
                Text(
                    text = badge.text,
                    color = textColor,
                    style = MaterialTheme.typography.labelMedium.copy(
                        fontFeatureSettings = "smcp",
                        fontWeight = FontWeight.Bold,
                        shadow = Shadow(
                            color = Color.Black.copy(alpha = 0.6f),
                            offset = Offset(0f, 2f),
                            blurRadius = 4f
                        )
                    )
                )
            }
        }
    }
}
