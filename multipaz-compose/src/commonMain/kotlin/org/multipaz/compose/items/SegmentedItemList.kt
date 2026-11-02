package org.multipaz.compose.items

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
import androidx.compose.ui.unit.dp

/**
 * Draws a segmented list of items.
 *
 * The list is displayed as a vertical stack of segmented containers with `16.dp` outer corners,
 * `4.dp` inner corners between adjacent items, and a `2.dp` vertical gap.
 *
 * See [SegmentedItemHeadingAndText], [SegmentedItemText], and [SegmentedItemCenteredText] for
 * items that are normally used inside this container. For a custom composable, wrap it in
 * [SegmentedItemContainer].
 *
 * @param modifier a [Modifier].
 * @param title the title to show above the list or `null`.
 * @param content the items to show inside the list.
 */
@Composable
fun SegmentedItemList(
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
                color = MaterialTheme.colorScheme.onSurface,
            )
        }

        CompositionLocalProvider(
            LocalContentColor provides MaterialTheme.colorScheme.onSurface
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(SEGMENT_OUTER_CORNER)),
                verticalArrangement = Arrangement.spacedBy(SEGMENT_GAP)
            ) {
                content()
            }
        }
    }
}
