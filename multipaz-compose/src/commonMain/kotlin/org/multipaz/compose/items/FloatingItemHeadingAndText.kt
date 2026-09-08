package org.multipaz.compose.items

import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.AnnotatedString

/**
 * An item showing a field heading with selectable text below it.
 *
 * This is intended for key-value inspector and detail views where the value text should be
 * user-selectable (wrapped in a [SelectionContainer]).
 *
 * @param heading field label shown at the top in semi-bold.
 * @param text value shown below the heading.
 * @param modifier a [Modifier].
 * @param showChevron whether to show a right chevron icon on the right side.
 * @param image optional image, shown to the left of the text.
 * @param trailingContent optional trailing content.
 */
@Composable
fun FloatingItemHeadingAndText(
    heading: String,
    text: AnnotatedString,
    modifier: Modifier = Modifier,
    showChevron: Boolean = false,
    image: @Composable () -> Unit = {},
    trailingContent: @Composable () -> Unit = {},
) {
    FloatingItemHeadingAndContent(
        heading = heading,
        content = {
            SelectionContainer {
                Text(
                    text = text,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        modifier = modifier,
        showChevron = showChevron,
        image = image,
        trailingContent = trailingContent
    )
}

/**
 * An item showing a field heading with selectable text below it.
 *
 * This is intended for key-value inspector and detail views where the value text should be
 * user-selectable (wrapped in a [SelectionContainer]).
 *
 * @param heading field label shown at the top in semi-bold.
 * @param text value shown below the heading.
 * @param modifier a [Modifier].
 * @param showChevron whether to show a right chevron icon on the right side.
 * @param image optional image, shown to the left of the text.
 * @param trailingContent optional trailing content.
 */
@Composable
fun FloatingItemHeadingAndText(
    heading: String,
    text: String,
    modifier: Modifier = Modifier,
    showChevron: Boolean = false,
    image: @Composable () -> Unit = {},
    trailingContent: @Composable () -> Unit = {},
) {
    FloatingItemHeadingAndText(
        heading = heading,
        text = AnnotatedString(text),
        modifier = modifier,
        showChevron = showChevron,
        image = image,
        trailingContent = trailingContent
    )
}