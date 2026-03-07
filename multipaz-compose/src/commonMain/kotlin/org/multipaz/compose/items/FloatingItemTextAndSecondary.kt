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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

/**
 * An item showing a text, with smaller secondary text below.
 *
 * @param text main text, will be shown at the top.
 * @param secondary will be shown below the heading, in secondary color and smaller font.
 * @param modifier a [Modifier].
 * @param image optional image, shown to the left of the text.
 */
@Composable
fun FloatingItemTextAndSecondary(
    text: String,
    secondary: String,
    modifier: Modifier = Modifier,
    image: @Composable () -> Unit = {}
) {
    FloatingItemContainer(modifier = modifier) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(16.dp, alignment = Alignment.Start),
            verticalAlignment = Alignment.CenterVertically
        ) {
            image()
            Column(
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = text,
                    textAlign = TextAlign.Start
                )
                Text(
                    text = secondary,
                    textAlign = TextAlign.Start,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.secondary
                )
            }
        }
    }
}