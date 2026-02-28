package org.multipaz.compose.items

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

/**
 * A pair of strings intended to go into an [ItemList].
 *
 * @param heading will be shown in bold at the top.
 * @param text will be shown below the heading.
 * @param modifier a [Modifier].
 */
@Composable
fun Item(
    heading: String,
    text: AnnotatedString,
    modifier: Modifier = Modifier
) {
    Column(
        modifier.fillMaxWidth().padding(8.dp)
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
}

/**
 * A pair of strings intended to go into an [ItemList].
 *
 * @param heading will be shown in bold at the top.
 * @param text will be shown below the heading.
 * @param modifier a [Modifier].
 */
@Composable
fun Item(
    heading: String,
    text: String,
    modifier: Modifier = Modifier
) {
    return Item(
        heading = heading,
        text = AnnotatedString(text),
        modifier = modifier
    )
}

/**
 * An item showing an image and a pair of strings
 *
 * @param image the image to shown, will be left of the text.
 * @param heading will be shown at the top.
 * @param text will be shown below the heading, in secondary color.
 * @param modifier a [Modifier].
 */
@Composable
fun ItemWithImageAndText(
    image: @Composable () -> Unit,
    heading: String,
    text: String,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(8.dp, alignment = Alignment.Start),
        verticalAlignment = Alignment.CenterVertically
    ) {
        image()
        Column(
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(
                text = heading,
                textAlign = TextAlign.Start
            )
            Text(
                text = text,
                textAlign = TextAlign.Start,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.secondary
            )
        }
    }
}
