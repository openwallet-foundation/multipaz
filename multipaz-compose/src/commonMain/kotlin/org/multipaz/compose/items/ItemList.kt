package org.multipaz.compose.items

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.dropShadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.shadow.Shadow
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp

/**
 * Draws a list of items belonging together.
 *
 * The list will be displayed in a list in a floating box with rounded corners.
 *
 * @param modifier a [Modifier].
 * @param title the title to show above the list or `null`.
 * @param items the list of items to show.
 */
@Composable
fun ItemList(
    modifier: Modifier = Modifier,
    title: String?,
    items: List<@Composable () -> Unit>,
) {
    Column {
        if (title != null) {
            Text(
                modifier = modifier.padding(start = 15.dp, top = 15.dp, end = 15.dp, bottom = 0.dp),
                text = title,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.secondary,
            )
        }

        Column(
            modifier = Modifier
                .padding(15.dp)
                .dropShadow(
                    shape = RoundedCornerShape(16.dp),
                    shadow = Shadow(
                        radius = 10.dp,
                        spread = 5.dp,
                        color = Color.Black.copy(alpha = 0.05f),
                        offset = DpOffset(x = 0.dp, 2.dp)
                    )
                ),
        ) {
            for (n in items.indices) {
                val section = items[n]
                val isFirst = (n == 0)
                val isLast = (n == items.size - 1)
                val rounded = 16.dp
                val firstRounded = if (isFirst) rounded else 0.dp
                val endRound = if (isLast) rounded else 0.dp
                Column(
                    modifier = modifier
                        .fillMaxWidth()
                        .clip(
                            shape = RoundedCornerShape(
                                firstRounded,
                                firstRounded,
                                endRound,
                                endRound
                            )
                        )
                        .background(MaterialTheme.colorScheme.surfaceContainerLowest)
                        .padding(8.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    CompositionLocalProvider(
                        LocalContentColor provides MaterialTheme.colorScheme.onSurface
                    ) {
                        section()
                    }
                }
                if (!isLast) {
                    Spacer(modifier = Modifier.height(1.dp))
                }
            }
        }
    }
}
