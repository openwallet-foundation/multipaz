package org.multipaz.compose.items

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString

/**
 * An item showing a primary text label, with optional secondary text below.
 *
 * @param text primary text to be shown.
 * @param modifier a [Modifier].
 * @param showChevron whether to show a right chevron icon on the right side.
 * @param secondary optional text to show below the main text, in smaller font and [secondaryColor].
 * @param secondaryColor the color to use for [secondary], defaults to [MaterialTheme.colorScheme.onSurfaceVariant].
 * @param image optional image, shown to the left of the text.
 * @param trailingContent optional trailing content.
 */
@Deprecated(
    message = "Use SegmentedItemText instead.",
    replaceWith = ReplaceWith(
        expression = "SegmentedItemText(text = text, modifier = modifier, showChevron = showChevron, secondary = secondary, secondaryColor = secondaryColor, image = image, trailingContent = trailingContent)",
        imports = ["org.multipaz.compose.items.SegmentedItemText"]
    )
)
@Composable
fun FloatingItemText(
    text: AnnotatedString,
    modifier: Modifier = Modifier,
    showChevron: Boolean = false,
    secondary: String? = null,
    secondaryColor: Color = MaterialTheme.colorScheme.onSurfaceVariant,
    image: @Composable () -> Unit = {},
    trailingContent: @Composable () -> Unit = {},
) {
    SegmentedItemText(
        text = text,
        modifier = modifier,
        showChevron = showChevron,
        secondary = secondary,
        secondaryColor = secondaryColor,
        image = image,
        trailingContent = trailingContent
    )
}

/**
 * An item showing a primary text label, with optional secondary text below.
 *
 * @param text primary text to be shown.
 * @param modifier a [Modifier].
 * @param showChevron whether to show a right chevron icon on the right side.
 * @param secondary optional text to show below the main text, in smaller font and [secondaryColor].
 * @param secondaryColor the color to use for [secondary], defaults to [MaterialTheme.colorScheme.onSurfaceVariant].
 * @param image optional image, shown to the left of the text.
 * @param trailingContent optional trailing content.
 */
@Deprecated(
    message = "Use SegmentedItemText instead.",
    replaceWith = ReplaceWith(
        expression = "SegmentedItemText(text = text, modifier = modifier, showChevron = showChevron, secondary = secondary, secondaryColor = secondaryColor, image = image, trailingContent = trailingContent)",
        imports = ["org.multipaz.compose.items.SegmentedItemText"]
    )
)
@Composable
fun FloatingItemText(
    text: String,
    modifier: Modifier = Modifier,
    showChevron: Boolean = false,
    secondary: String? = null,
    secondaryColor: Color = MaterialTheme.colorScheme.onSurfaceVariant,
    image: @Composable () -> Unit = {},
    trailingContent: @Composable () -> Unit = {},
) {
    SegmentedItemText(
        text = text,
        modifier = modifier,
        showChevron = showChevron,
        secondary = secondary,
        secondaryColor = secondaryColor,
        image = image,
        trailingContent = trailingContent
    )
}
