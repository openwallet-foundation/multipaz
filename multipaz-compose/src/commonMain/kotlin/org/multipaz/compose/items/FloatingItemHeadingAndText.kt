package org.multipaz.compose.items

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/**
 * An item showing a heading in bold with text below it.
 *
 * @param heading will be shown in bold at the top.
 * @param text will be shown below the heading.
 * @param modifier a [Modifier].
 * @param image optional image, shown to the left of the text.
 * @param trailingContent optional trailing content.
 */
@Composable
fun FloatingItemHeadingAndText(
    heading: String,
    text: AnnotatedString,
    modifier: Modifier = Modifier,
    image: @Composable () -> Unit = {},
    trailingContent: @Composable () -> Unit = {},
) {
    FloatingItemContainer(modifier = modifier) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(16.dp, alignment = Alignment.Start),
            verticalAlignment = Alignment.CenterVertically
        ) {
            image()
            Column(
                modifier = Modifier.weight(1.0f)
            ) {
                Text(
                    text = heading,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Bold
                )
                SelectionContainer {
                    Text(
                        text = text,
                        style = MaterialTheme.typography.bodyMedium
                    )
                }

            }
            trailingContent()
        }
    }
}

/**
 * An item showing a heading in bold with text below it.
 *
 * @param heading will be shown in bold at the top.
 * @param text will be shown below the heading.
 * @param modifier a [Modifier].
 * @param image optional image, shown to the left of the text.
 * @param trailingContent optional trailing content.
 */
@Composable
fun FloatingItemHeadingAndText(
    heading: String,
    text: String,
    modifier: Modifier = Modifier,
    image: @Composable () -> Unit = {},
    trailingContent: @Composable () -> Unit = {},
) {
    FloatingItemHeadingAndText(
        heading = heading,
        text = AnnotatedString(text),
        modifier = modifier,
        image = image,
        trailingContent = trailingContent
    )
}