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
 * An item showing a primary text label, with optional secondary text below.
 *
 * This is intended for standard list items and interactive navigation rows.
 *
 * @param text primary text to be shown.
 * @param modifier a [Modifier].
 * @param showChevron whether to show a right chevron icon on the right side.
 * @param secondary optional text to show below the main text, in smaller font and [secondaryColor].
 * @param secondaryColor the color to use for [secondaryColor], defaults to [MaterialTheme.colorScheme.onSurfaceVariant].
 * @param image optional image, shown to the left of the text.
 * @param trailingContent optional trailing content.
 */
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
    FloatingItemContainer(
        modifier = modifier,
        showChevron = showChevron,
    ) {
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
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurface,
                        textAlign = TextAlign.Start
                    )
                } else {
                    Column(
                        modifier = Modifier.weight(1.0f),
                        verticalArrangement = Arrangement.spacedBy(2.dp)
                    ) {
                        Text(
                            text = text,
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurface,
                            textAlign = TextAlign.Start
                        )
                        Text(
                            text = secondary,
                            textAlign = TextAlign.Start,
                            style = MaterialTheme.typography.bodyMedium,
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
 * An item showing a primary text label, with optional secondary text below.
 *
 * This is intended for standard list items and interactive navigation rows.
 *
 * @param text primary text to be shown.
 * @param modifier a [Modifier].
 * @param showChevron whether to show a right chevron icon on the right side.
 * @param secondary optional text to show below the main text, in smaller font and [secondaryColor].
 * @param secondaryColor the color to use for [secondaryColor], defaults to [MaterialTheme.colorScheme.onSurfaceVariant].
 * @param image optional image, shown to the left of the text.
 * @param trailingContent optional trailing content.
 */
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
    FloatingItemText(
        text = AnnotatedString(text),
        modifier = modifier,
        showChevron = showChevron,
        secondary = secondary,
        secondaryColor = secondaryColor,
        image = image,
        trailingContent = trailingContent
    )
}
