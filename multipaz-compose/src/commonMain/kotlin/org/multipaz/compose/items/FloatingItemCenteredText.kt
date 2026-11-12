package org.multipaz.compose.items

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.AnnotatedString

/**
 * An item showing centered italicized text in the secondary/muted color.
 *
 * @param text the text to show.
 * @param modifier a [Modifier].
 * @param showChevron whether to show a right chevron icon on the right side.
 */
@Deprecated(
    message = "Use SegmentedItemCenteredText instead.",
    replaceWith = ReplaceWith(
        expression = "SegmentedItemCenteredText(text = text, modifier = modifier, showChevron = showChevron)",
        imports = ["org.multipaz.compose.items.SegmentedItemCenteredText"]
    )
)
@Composable
fun FloatingItemCenteredText(
    text: AnnotatedString,
    modifier: Modifier = Modifier,
    showChevron: Boolean = false,
) {
    SegmentedItemCenteredText(
        text = text,
        modifier = modifier,
        showChevron = showChevron,
    )
}

/**
 * An item showing centered italicized text in the secondary/muted color.
 *
 * @param text the text to show.
 * @param modifier a [Modifier].
 * @param showChevron whether to show a right chevron icon on the right side.
 */
@Deprecated(
    message = "Use SegmentedItemCenteredText instead.",
    replaceWith = ReplaceWith(
        expression = "SegmentedItemCenteredText(text = text, modifier = modifier, showChevron = showChevron)",
        imports = ["org.multipaz.compose.items.SegmentedItemCenteredText"]
    )
)
@Composable
fun FloatingItemCenteredText(
    text: String,
    modifier: Modifier = Modifier,
    showChevron: Boolean = false,
) {
    SegmentedItemCenteredText(
        text = text,
        modifier = modifier,
        showChevron = showChevron,
    )
}
