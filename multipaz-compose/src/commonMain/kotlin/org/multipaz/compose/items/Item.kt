package org.multipaz.compose.items

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/**
 * A key/value pair intended to go into an [ItemList].
 *
 * @param key the key to show, will be shown in bold.
 * @param valueText the value to show, as text.
 * @param modifier a [Modifier].
 */
@Composable
fun Item(
    key: String,
    valueText: AnnotatedString,
    modifier: Modifier = Modifier
) {
    Column(
        modifier.fillMaxWidth().padding(8.dp)
    ) {
        Text(
            text = key,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Bold
        )
        SelectionContainer {
            Text(
                text = valueText,
                style = MaterialTheme.typography.bodyMedium
            )
        }
    }
}

/**
 * A key/value pair intended to go into an [ItemList].
 *
 * @param key the key to show, will be shown in bold.
 * @param valueText the value to show, as text.
 * @param modifier a [Modifier].
 */
@Composable
fun Item(
    key: String,
    valueText: String,
    modifier: Modifier = Modifier
) {
    return Item(
        key = key,
        valueText = AnnotatedString(valueText),
        modifier = modifier
    )
}
