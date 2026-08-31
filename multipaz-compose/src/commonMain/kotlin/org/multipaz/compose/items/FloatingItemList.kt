package org.multipaz.compose.items

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.dropShadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.shadow.Shadow
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp

/**
 * Draws a floating list of items.
 *
 * The list will be displayed in a list in a floating box with rounded corners.
 *
 * See [FloatingItemHeadingAndText], [FloatingItemText], [FloatingItemCenteredText] for things that are normally
 * used inside this container. For your own composable, simply wrap it in [FloatingItemContainer].
 *
 * The list is rendered to appear raised above the parent container. When using this composable,
 * make sure to leave 10.dp padding around it and 20.dp at the bottom, to leave enough room for
 * its shadow.
 *
 * @param modifier a [Modifier].
 * @param title the title to show above the list or `null`.
 * @param content the items to show inside the list.
 */
@Composable
fun FloatingItemList(
    modifier: Modifier = Modifier,
    title: String? = null,
    content: @Composable ColumnScope.() -> Unit
) {
    Column(modifier = modifier) {
        if (title != null) {
            Text(
                modifier = Modifier.padding(bottom = 8.dp),
                text = title,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary,
            )
        }

        Column(
            modifier = Modifier
                .dropShadow(
                    shape = RoundedCornerShape(16.dp),
                    shadow = Shadow(
                        radius = 10.dp,
                        spread = 7.5.dp,
                        color = Color.Black.copy(alpha = 0.065f),
                        offset = DpOffset(x = 0.dp, 2.dp)
                    )
                )
                .clip(RoundedCornerShape(16.dp))
                // The parent container acts as our "divider" color
                .background(MaterialTheme.colorScheme.outlineVariant),
        ) {
            CompositionLocalProvider(
                LocalContentColor provides MaterialTheme.colorScheme.onSurface
            ) {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(0.5.dp)
                ) {
                    content()
                }
            }
        }
    }
}