package org.multipaz.compose.items

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.dp

internal val SEGMENT_OUTER_CORNER = 16.dp
internal val SEGMENT_INNER_CORNER = 4.dp
internal val SEGMENT_GAP = 2.dp

internal val LocalSegmentedItemShape = compositionLocalOf<Shape?> { null }

/**
 * Returns the [RoundedCornerShape] for an item at [index] in a segmented list of [count] items,
 * with `16.dp` outer corners on the first/last item and `4.dp` inner corners between adjacent items.
 *
 * @param index the zero-based index of the item in the list.
 * @param count the total number of items in the list.
 */
fun segmentedItemShape(
    index: Int,
    count: Int,
): RoundedCornerShape = RoundedCornerShape(
    topStart = if (index == 0) SEGMENT_OUTER_CORNER else SEGMENT_INNER_CORNER,
    topEnd = if (index == 0) SEGMENT_OUTER_CORNER else SEGMENT_INNER_CORNER,
    bottomStart = if (index == count - 1) SEGMENT_OUTER_CORNER else SEGMENT_INNER_CORNER,
    bottomEnd = if (index == count - 1) SEGMENT_OUTER_CORNER else SEGMENT_INNER_CORNER,
)

/**
 * A generic container that applies the standard segmented list item styling (rounded corners,
 * container surface background, width, and padding).
 *
 * Use this if you need to place a custom composable inside a [SegmentedItemList] or
 * [LazySegmentedItemList].
 *
 * @param modifier a [Modifier].
 * @param showChevron whether to show a right chevron icon on the right side.
 * @param shape the [Shape] used to clip the container. Defaults to `4.dp` rounded corners (which
 *   when placed inside a [SegmentedItemList] combines with the list's outer `16.dp` clip to form
 *   the outer/inner segmented corners), or the per-item shape when inside a [segmentedItemList].
 * @param selected whether to draw a `1.dp` selection border around the container.
 * @param content the custom composable to display inside the standard item styling.
 */
@Composable
fun SegmentedItemContainer(
    modifier: Modifier = Modifier,
    showChevron: Boolean = false,
    shape: Shape = LocalSegmentedItemShape.current ?: RoundedCornerShape(SEGMENT_INNER_CORNER),
    selected: Boolean = false,
    content: @Composable () -> Unit
) {
    val containerColor = if (isSystemInDarkTheme()) {
        MaterialTheme.colorScheme.surfaceContainerHigh
    } else {
        MaterialTheme.colorScheme.surfaceContainerLowest
    }
    Box(
        modifier = modifier
            .clip(shape)
            .background(containerColor)
            .then(
                if (selected) {
                    Modifier.border(1.dp, MaterialTheme.colorScheme.primary, shape)
                } else {
                    Modifier
                }
            )
            .fillMaxWidth()
            .defaultMinSize(minHeight = 56.dp)
            .padding(16.dp),
        contentAlignment = Alignment.CenterStart
    ) {
        if (showChevron) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier.weight(1.0f),
                    contentAlignment = Alignment.CenterStart
                ) {
                    content()
                }
                Icon(
                    imageVector = Icons.AutoMirrored.Outlined.KeyboardArrowRight,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        } else {
            content()
        }
    }
}

/**
 * A generic container that applies the standard segmented list item styling for an item at
 * [index] out of [count] items.
 *
 * @param index the zero-based index of the item in the segmented group.
 * @param count the total number of items in the segmented group.
 * @param modifier a [Modifier].
 * @param showChevron whether to show a right chevron icon on the right side.
 * @param selected whether to draw a `1.dp` selection border around the container.
 * @param content the custom composable to display inside the standard item styling.
 */
@Composable
fun SegmentedItemContainer(
    index: Int,
    count: Int,
    modifier: Modifier = Modifier,
    showChevron: Boolean = false,
    selected: Boolean = false,
    content: @Composable () -> Unit
) {
    SegmentedItemContainer(
        modifier = modifier,
        showChevron = showChevron,
        shape = segmentedItemShape(index, count),
        selected = selected,
        content = content
    )
}
