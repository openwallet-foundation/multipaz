package org.multipaz.compose.scrollbar

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.gestures.FlingBehavior
import androidx.compose.foundation.gestures.ScrollableDefaults
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * Renders a platform-appropriate vertical scrollbar bound to [scrollState].
 *
 * On Desktop JVM this renders a draggable vertical scrollbar styled for the current
 * [androidx.compose.material3.MaterialTheme]; on mobile and web targets this is a no-op.
 *
 * @param scrollState the [LazyListState] of the scrollable list.
 * @param modifier a [Modifier] applied to the scrollbar.
 */
@Composable
expect fun VerticalScrollbar(
    scrollState: LazyListState,
    modifier: Modifier = Modifier,
)

/**
 * Renders a platform-appropriate vertical scrollbar bound to [scrollState].
 *
 * On Desktop JVM this renders a draggable vertical scrollbar styled for the current
 * [androidx.compose.material3.MaterialTheme]; on mobile and web targets this is a no-op.
 *
 * @param scrollState the [ScrollState] of the scrollable container.
 * @param modifier a [Modifier] applied to the scrollbar.
 */
@Composable
expect fun VerticalScrollbar(
    scrollState: ScrollState,
    modifier: Modifier = Modifier,
)

/**
 * A [LazyColumn] paired with a platform [VerticalScrollbar] aligned to its trailing edge.
 *
 * @param modifier a [Modifier] applied to the outer container.
 * @param state the [LazyListState] controlling and observing the list's scroll position.
 * @param contentPadding padding around the whole content inside the scrollable viewport.
 * @param reverseLayout reverse the direction of scrolling and layout.
 * @param verticalArrangement vertical arrangement of the layout's children.
 * @param horizontalAlignment horizontal alignment applied to the items.
 * @param flingBehavior logic describing fling behavior.
 * @param userScrollEnabled whether scrolling via user gestures is allowed.
 * @param content a block describing the [LazyListScope] content.
 */
@Composable
fun LazyColumnWithScrollbar(
    modifier: Modifier = Modifier,
    state: LazyListState = rememberLazyListState(),
    contentPadding: PaddingValues = PaddingValues(0.dp),
    reverseLayout: Boolean = false,
    verticalArrangement: Arrangement.Vertical =
        if (!reverseLayout) Arrangement.Top else Arrangement.Bottom,
    horizontalAlignment: Alignment.Horizontal = Alignment.Start,
    flingBehavior: FlingBehavior = ScrollableDefaults.flingBehavior(),
    userScrollEnabled: Boolean = true,
    content: LazyListScope.() -> Unit,
) {
    Box(
        modifier = modifier.fillMaxWidth(),
        propagateMinConstraints = true,
    ) {
        LazyColumn(
            modifier = Modifier.fillMaxWidth(),
            state = state,
            contentPadding = contentPadding,
            reverseLayout = reverseLayout,
            verticalArrangement = verticalArrangement,
            horizontalAlignment = horizontalAlignment,
            flingBehavior = flingBehavior,
            userScrollEnabled = userScrollEnabled,
            content = content,
        )
        Box(modifier = Modifier.matchParentSize()) {
            VerticalScrollbar(
                scrollState = state,
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .fillMaxHeight(),
            )
        }
    }
}

/**
 * A vertically-scrollable [Column] paired with a platform [VerticalScrollbar] aligned to its
 * trailing edge.
 *
 * @param modifier a [Modifier] applied to the outer container.
 * @param scrollState the [ScrollState] controlling and observing the scroll position.
 * @param contentPadding padding around the content inside the scrollable viewport.
 * @param verticalArrangement vertical arrangement of the column's children.
 * @param horizontalAlignment horizontal alignment of the column's children.
 * @param content the [ColumnScope] content.
 */
@Composable
fun ColumnWithScrollbar(
    modifier: Modifier = Modifier,
    scrollState: ScrollState = rememberScrollState(),
    contentPadding: PaddingValues = PaddingValues(0.dp),
    verticalArrangement: Arrangement.Vertical = Arrangement.Top,
    horizontalAlignment: Alignment.Horizontal = Alignment.Start,
    content: @Composable ColumnScope.() -> Unit,
) {
    Box(
        modifier = modifier.fillMaxWidth(),
        propagateMinConstraints = true,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(scrollState)
                .padding(contentPadding),
            verticalArrangement = verticalArrangement,
            horizontalAlignment = horizontalAlignment,
            content = content,
        )
        Box(modifier = Modifier.matchParentSize()) {
            VerticalScrollbar(
                scrollState = scrollState,
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .fillMaxHeight(),
            )
        }
    }
}
