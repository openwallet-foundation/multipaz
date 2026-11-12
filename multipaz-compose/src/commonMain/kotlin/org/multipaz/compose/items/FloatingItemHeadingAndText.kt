package org.multipaz.compose.items

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.AnnotatedString

/**
 * An item showing a field heading with selectable text below it.
 *
 * @param heading field label shown at the top.
 * @param text value shown below the heading.
 * @param modifier a [Modifier].
 * @param showChevron whether to show a right chevron icon on the right side.
 * @param image optional image, shown to the left of the text.
 * @param trailingContent optional trailing content.
 */
@Deprecated(
    message = "Use SegmentedItemHeadingAndText instead.",
    replaceWith = ReplaceWith(
        expression = "SegmentedItemHeadingAndText(heading = heading, text = text, modifier = modifier, showChevron = showChevron, image = image, trailingContent = trailingContent)",
        imports = ["org.multipaz.compose.items.SegmentedItemHeadingAndText"]
    )
)
@Composable
fun FloatingItemHeadingAndText(
    heading: String,
    text: AnnotatedString,
    modifier: Modifier = Modifier,
    showChevron: Boolean = false,
    image: @Composable () -> Unit = {},
    trailingContent: @Composable () -> Unit = {},
) {
    SegmentedItemHeadingAndText(
        heading = heading,
        text = text,
        modifier = modifier,
        showChevron = showChevron,
        image = image,
        trailingContent = trailingContent
    )
}

/**
 * An item showing a field heading with selectable text below it.
 *
 * @param heading field label shown at the top.
 * @param text value shown below the heading.
 * @param modifier a [Modifier].
 * @param showChevron whether to show a right chevron icon on the right side.
 * @param image optional image, shown to the left of the text.
 * @param trailingContent optional trailing content.
 */
@Deprecated(
    message = "Use SegmentedItemHeadingAndText instead.",
    replaceWith = ReplaceWith(
        expression = "SegmentedItemHeadingAndText(heading = heading, text = text, modifier = modifier, showChevron = showChevron, image = image, trailingContent = trailingContent)",
        imports = ["org.multipaz.compose.items.SegmentedItemHeadingAndText"]
    )
)
@Composable
fun FloatingItemHeadingAndText(
    heading: String,
    text: String,
    modifier: Modifier = Modifier,
    showChevron: Boolean = false,
    image: @Composable () -> Unit = {},
    trailingContent: @Composable () -> Unit = {},
) {
    SegmentedItemHeadingAndText(
        heading = heading,
        text = text,
        modifier = modifier,
        showChevron = showChevron,
        image = image,
        trailingContent = trailingContent
    )
}
