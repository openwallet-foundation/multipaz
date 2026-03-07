package org.multipaz.compose.items

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.style.TextAlign

/**
 * An item showing centered italicized text in the secondary color.
 *
 * This is intended to be used in an [FloatingItemList] for showing that it's empty, e.g. [text] would
 * be "Gizmos will appear here, use + to add one".
 *
 * @param text the text to show.
 * @param modifier a [androidx.compose.ui.Modifier].
 */
@Composable
fun FloatingItemCenteredText(
    text: AnnotatedString,
    modifier: Modifier = Modifier
) {
    FloatingItemContainer(modifier = modifier) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = text,
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.secondary,
                fontStyle = FontStyle.Italic
            )
        }
    }
}

/**
 * An item showing centered italicized text in the secondary color.
 *
 * This is intended to be used in an [FloatingItemList] for showing that it's empty, e.g. [text] would
 * be "Gizmos will appear here, use + to add one".
 *
 * @param text the text to show.
 * @param modifier a [Modifier].
 */
@Composable
fun FloatingItemCenteredText(
    text: String,
    modifier: Modifier = Modifier
) {
    FloatingItemCenteredText(
        text = androidx.compose.ui.text.AnnotatedString(text),
        modifier = modifier
    )
}