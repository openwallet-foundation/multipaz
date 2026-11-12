package org.multipaz.compose.items

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/**
 * An item showing a field heading with custom content below it.
 *
 * @param heading field label shown at the top.
 * @param content custom content to display below the heading.
 * @param modifier a [Modifier].
 * @param showChevron whether to show a right chevron icon on the right side.
 * @param image optional image, shown to the left of the text.
 * @param trailingContent optional trailing content.
 */
@Deprecated(
    message = "Use SegmentedItemHeadingAndContent instead.",
    replaceWith = ReplaceWith(
        expression = "SegmentedItemHeadingAndContent(heading = heading, content = content, modifier = modifier, showChevron = showChevron, image = image, trailingContent = trailingContent)",
        imports = ["org.multipaz.compose.items.SegmentedItemHeadingAndContent"]
    )
)
@Composable
fun FloatingItemHeadingAndContent(
    heading: String,
    content: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    showChevron: Boolean = false,
    image: @Composable () -> Unit = {},
    trailingContent: @Composable () -> Unit = {},
) {
    SegmentedItemHeadingAndContent(
        heading = heading,
        content = content,
        modifier = modifier,
        showChevron = showChevron,
        image = image,
        trailingContent = trailingContent
    )
}
