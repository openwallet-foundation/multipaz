package org.multipaz.compose.items

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * A generic container that applies the standard list item styling (background, width, padding).
 *
 * Use this if you need to place a custom composable inside an [FloatingItemList].
 *
 * @param modifier a [Modifier].
 * @param showChevron whether to show a right chevron icon on the right side.
 * @param content the custom composable to display inside the standard item styling.
 */
@Composable
fun FloatingItemContainer(
    modifier: Modifier = Modifier,
    showChevron: Boolean = false,
    content: @Composable () -> Unit
) {
    Box(
        modifier = modifier
            .background(MaterialTheme.colorScheme.surfaceContainerLowest)
            .fillMaxWidth()
            .defaultMinSize(minHeight = 56.dp)
            .padding(16.dp),
        contentAlignment = Alignment.CenterStart
    ) {
        if (showChevron) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier.weight(1.0f),
                    contentAlignment = Alignment.CenterStart
                ) {
                    content()
                }
                Icon(
                    imageVector = Icons.AutoMirrored.Outlined.KeyboardArrowRight,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        } else {
            content()
        }
    }
}


