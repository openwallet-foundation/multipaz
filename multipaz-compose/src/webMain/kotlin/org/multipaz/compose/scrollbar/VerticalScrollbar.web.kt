package org.multipaz.compose.scrollbar

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

@Composable
actual fun VerticalScrollbar(
    scrollState: LazyListState,
    modifier: Modifier,
) {
}

@Composable
actual fun VerticalScrollbar(
    scrollState: ScrollState,
    modifier: Modifier,
) {
}
