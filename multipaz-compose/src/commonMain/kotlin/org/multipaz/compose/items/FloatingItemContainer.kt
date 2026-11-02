package org.multipaz.compose.items

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/**
 * A generic container that applies the standard list item styling (background, width, padding).
 *
 * Use this if you need to place a custom composable inside a [SegmentedItemList].
 *
 * @param modifier a [Modifier].
 * @param showChevron whether to show a right chevron icon on the right side.
 * @param content the custom composable to display inside the standard item styling.
 */
@Deprecated(
    message = "Use SegmentedItemContainer instead.",
    replaceWith = ReplaceWith(
        expression = "SegmentedItemContainer(modifier = modifier, showChevron = showChevron, content = content)",
        imports = ["org.multipaz.compose.items.SegmentedItemContainer"]
    )
)
@Composable
fun FloatingItemContainer(
    modifier: Modifier = Modifier,
    showChevron: Boolean = false,
    content: @Composable () -> Unit
) {
    SegmentedItemContainer(
        modifier = modifier,
        showChevron = showChevron,
        content = content
    )
}
