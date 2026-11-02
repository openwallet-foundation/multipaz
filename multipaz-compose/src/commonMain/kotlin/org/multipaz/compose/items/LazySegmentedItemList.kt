package org.multipaz.compose.items

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.FlingBehavior
import androidx.compose.foundation.gestures.ScrollableDefaults
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyItemScope
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp

/**
 * Adds a lazy segmented list of items into an outer [LazyColumn].
 *
 * Each item in the list is lazily composed as it enters the viewport. The first item has
 * `16.dp` top corners, the last item has `16.dp` bottom corners, intermediate corners are
 * `4.dp`, and adjacent items are separated by a `2.dp` vertical gap.
 *
 * @param count the number of items in this list.
 * @param title optional title to show above the list.
 * @param key a factory of stable and unique keys for each item.
 * @param contentType a factory of content types for each item.
 * @param itemContent the content for each item at index.
 */
fun LazyListScope.segmentedItemList(
    count: Int,
    title: String? = null,
    key: ((index: Int) -> Any)? = null,
    contentType: (index: Int) -> Any? = { null },
    itemContent: @Composable LazyItemScope.(index: Int) -> Unit
) {
    if (title != null) {
        item(
            key = if (key != null) "${title}_header" else null,
            contentType = "SegmentedItemListTitle"
        ) {
            Text(
                modifier = Modifier.padding(bottom = 8.dp),
                text = title,
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
    }

    items(
        count = count,
        key = key,
        contentType = contentType
    ) { index ->
        val isLast = index == count - 1
        val shape = segmentedItemShape(index, count)
        val containerColor = if (isSystemInDarkTheme()) {
            MaterialTheme.colorScheme.surfaceContainerHigh
        } else {
            MaterialTheme.colorScheme.surfaceContainerLowest
        }

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = if (isLast) 0.dp else SEGMENT_GAP)
                .clip(shape)
                .background(containerColor)
        ) {
            CompositionLocalProvider(
                LocalContentColor provides MaterialTheme.colorScheme.onSurface,
                LocalSegmentedItemShape provides shape,
            ) {
                itemContent(index)
            }
        }
    }
}

/**
 * Adds a lazy segmented list of items into an outer [LazyColumn].
 *
 * @param items the data list.
 * @param title optional title to show above the list.
 * @param key a factory of stable and unique keys for each item.
 * @param contentType a factory of content types for each item.
 * @param itemContent the content for each item.
 */
fun <T> LazyListScope.segmentedItemList(
    items: List<T>,
    title: String? = null,
    key: ((item: T) -> Any)? = null,
    contentType: (item: T) -> Any? = { null },
    itemContent: @Composable LazyItemScope.(item: T) -> Unit
) {
    segmentedItemList(
        count = items.size,
        title = title,
        key = if (key != null) { index -> key(items[index]) } else null,
        contentType = { index -> contentType(items[index]) }
    ) { index ->
        itemContent(items[index])
    }
}

/**
 * Draws a lazy segmented list of items.
 *
 * Uses a [LazyColumn] internally to only compose and lay out the items currently visible in the viewport.
 *
 * @param modifier a [Modifier].
 * @param title the title to show above the list or `null`.
 * @param state the state object to be used to control or observe the list's state.
 * @param contentPadding a padding around the whole content.
 * @param reverseLayout reverse the direction of scroll and layout.
 * @param horizontalAlignment the horizontal alignment applied to the items.
 * @param flingBehavior logic describing fling behavior.
 * @param userScrollEnabled whether the scrolling via user gestures is enabled.
 * @param content a block which describes the content.
 */
@Composable
fun LazySegmentedItemList(
    modifier: Modifier = Modifier,
    title: String? = null,
    state: LazyListState = rememberLazyListState(),
    contentPadding: PaddingValues = PaddingValues(0.dp),
    reverseLayout: Boolean = false,
    horizontalAlignment: Alignment.Horizontal = Alignment.Start,
    flingBehavior: FlingBehavior = ScrollableDefaults.flingBehavior(),
    userScrollEnabled: Boolean = true,
    content: LazyListScope.() -> Unit
) {
    LazyColumn(
        modifier = modifier,
        state = state,
        contentPadding = contentPadding,
        reverseLayout = reverseLayout,
        horizontalAlignment = horizontalAlignment,
        flingBehavior = flingBehavior,
        userScrollEnabled = userScrollEnabled
    ) {
        if (title != null) {
            item(key = "${title}_header") {
                Text(
                    modifier = Modifier.padding(bottom = 8.dp),
                    text = title,
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
        }
        content()
    }
}
