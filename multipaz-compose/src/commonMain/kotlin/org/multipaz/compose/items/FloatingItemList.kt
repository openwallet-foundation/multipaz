package org.multipaz.compose.items

import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/**
 * Draws a segmented list of items.
 *
 * @param modifier a [Modifier].
 * @param title the title to show above the list or `null`.
 * @param content the items to show inside the list.
 */
@Deprecated(
    message = "Use SegmentedItemList instead.",
    replaceWith = ReplaceWith(
        expression = "SegmentedItemList(modifier = modifier, title = title, content = content)",
        imports = ["org.multipaz.compose.items.SegmentedItemList"]
    )
)
@Composable
fun FloatingItemList(
    modifier: Modifier = Modifier,
    title: String? = null,
    content: @Composable ColumnScope.() -> Unit
) {
    SegmentedItemList(
        modifier = modifier,
        title = title,
        content = content
    )
}
