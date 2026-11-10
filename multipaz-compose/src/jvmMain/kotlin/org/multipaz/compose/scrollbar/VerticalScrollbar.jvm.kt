package org.multipaz.compose.scrollbar

import androidx.compose.foundation.LocalScrollbarStyle
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

@Composable
actual fun VerticalScrollbar(
    scrollState: LazyListState,
    modifier: Modifier,
) {
    val onSurface = MaterialTheme.colorScheme.onSurface
    val style = LocalScrollbarStyle.current.copy(
        unhoverColor = onSurface.copy(alpha = 0.25f),
        hoverColor = onSurface.copy(alpha = 0.55f),
    )
    androidx.compose.foundation.VerticalScrollbar(
        adapter = rememberScrollbarAdapter(scrollState),
        modifier = modifier,
        style = style,
    )
}

@Composable
actual fun VerticalScrollbar(
    scrollState: ScrollState,
    modifier: Modifier,
) {
    val onSurface = MaterialTheme.colorScheme.onSurface
    val style = LocalScrollbarStyle.current.copy(
        unhoverColor = onSurface.copy(alpha = 0.25f),
        hoverColor = onSurface.copy(alpha = 0.55f),
    )
    androidx.compose.foundation.VerticalScrollbar(
        adapter = rememberScrollbarAdapter(scrollState),
        modifier = modifier,
        style = style,
    )
}
