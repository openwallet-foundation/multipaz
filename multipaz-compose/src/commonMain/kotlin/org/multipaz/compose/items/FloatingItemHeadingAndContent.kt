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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/**
 * An item showing a heading in bold with content below it.
 *
 * @param heading will be shown in bold at the top.
 * @param content will be shown below the heading.
 * @param modifier a [Modifier].
 * @param showChevron whether to show a right chevron icon on the right side.
 * @param image optional image, shown to the left of the text.
 * @param trailingContent optional trailing content.
 */
@Composable
fun FloatingItemHeadingAndContent(
    heading: String,
    content: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    showChevron: Boolean = false,
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
            Column(
                modifier = Modifier.weight(1.0f)
            ) {
                Text(
                    text = heading,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Bold
                )
                content()
            }
            trailingContent()
        }
    }
}
