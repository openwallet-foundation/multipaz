package org.multipaz.compose.items

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

/**
 * An item showing a text, with smaller secondary text below.
 *
 * @param text text to be shown.
 * @param secondary optional text to show below the main text, in smaller font and [secondaryColor].
 * @param secondaryColor the color to use for [secondaryColor], defaults to secondary color.
 * @param modifier a [Modifier].
 * @param image optional image, shown to the left of the text.
 * @param trailingContent optional trailing content.
 */
@Composable
fun FloatingItemText(
    text: AnnotatedString,
    modifier: Modifier = Modifier,
    secondary: String? = null,
    secondaryColor: Color = MaterialTheme.colorScheme.secondary,
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
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(16.dp, alignment = Alignment.Start),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (secondary == null) {
                    Text(
                        modifier = Modifier.weight(1.0f),
                        text = text,
                        textAlign = TextAlign.Start
                    )
                } else {
                    Column(
                        modifier = Modifier.weight(1.0f)
                    ) {
                        Text(
                            text = text,
                            textAlign = TextAlign.Start
                        )
                        Text(
                            text = secondary,
                            textAlign = TextAlign.Start,
                            style = MaterialTheme.typography.bodySmall,
                            color = secondaryColor
                        )
                    }
                }
                trailingContent()
            }
        }
    }
}

/**
 * An item showing a text, with smaller secondary text below.
 *
 * @param text text to be shown.
 * @param secondary optional text to show below the main text, in smaller font and [secondaryColor].
 * @param secondaryColor the color to use for [secondaryColor], defaults to secondary color.
 * @param modifier a [Modifier].
 * @param image optional image, shown to the left of the text.
 * @param trailingContent optional trailing content.
 */
@Composable
fun FloatingItemText(
    text: String,
    modifier: Modifier = Modifier,
    secondary: String? = null,
    secondaryColor: Color = MaterialTheme.colorScheme.secondary,
    image: @Composable () -> Unit = {},
    trailingContent: @Composable () -> Unit = {},
) {
    FloatingItemText(
        text = AnnotatedString(text),
        modifier = modifier,
        secondary = secondary,
        secondaryColor = secondaryColor,
        image = image,
        trailingContent = trailingContent
    )
}
