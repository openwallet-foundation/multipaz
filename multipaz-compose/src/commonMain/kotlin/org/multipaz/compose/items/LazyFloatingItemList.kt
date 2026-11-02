package org.multipaz.compose.items

import androidx.compose.foundation.gestures.FlingBehavior
import androidx.compose.foundation.gestures.ScrollableDefaults
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyItemScope
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * Adds a lazy segmented list of items into an outer [LazyColumn].
 *
 * @param count the number of items in this list.
 * @param title optional title to show above the list.
 * @param key a factory of stable and unique keys for each item.
 * @param contentType a factory of content types for each item.
 * @param itemContent the content for each item at index.
 */
@Deprecated(
    message = "Use segmentedItemList instead.",
    replaceWith = ReplaceWith(
        expression = "segmentedItemList(count = count, title = title, key = key, contentType = contentType, itemContent = itemContent)",
        imports = ["org.multipaz.compose.items.segmentedItemList"]
    )
)
fun LazyListScope.floatingItemList(
    count: Int,
    title: String? = null,
    key: ((index: Int) -> Any)? = null,
    contentType: (index: Int) -> Any? = { null },
    itemContent: @Composable LazyItemScope.(index: Int) -> Unit
) {
    segmentedItemList(
        count = count,
        title = title,
        key = key,
        contentType = contentType,
        itemContent = itemContent
    )
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
@Deprecated(
    message = "Use segmentedItemList instead.",
    replaceWith = ReplaceWith(
        expression = "segmentedItemList(items = items, title = title, key = key, contentType = contentType, itemContent = itemContent)",
        imports = ["org.multipaz.compose.items.segmentedItemList"]
    )
)
fun <T> LazyListScope.floatingItemList(
    items: List<T>,
    title: String? = null,
    key: ((item: T) -> Any)? = null,
    contentType: (item: T) -> Any? = { null },
    itemContent: @Composable LazyItemScope.(item: T) -> Unit
) {
    segmentedItemList(
        items = items,
        title = title,
        key = key,
        contentType = contentType,
        itemContent = itemContent
    )
}

/**
 * Draws a lazy segmented list of items.
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
@Deprecated(
    message = "Use LazySegmentedItemList instead.",
    replaceWith = ReplaceWith(
        expression = "LazySegmentedItemList(modifier = modifier, title = title, state = state, contentPadding = contentPadding, reverseLayout = reverseLayout, horizontalAlignment = horizontalAlignment, flingBehavior = flingBehavior, userScrollEnabled = userScrollEnabled, content = content)",
        imports = ["org.multipaz.compose.items.LazySegmentedItemList"]
    )
)
@Composable
fun LazyFloatingItemList(
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
    LazySegmentedItemList(
        modifier = modifier,
        title = title,
        state = state,
        contentPadding = contentPadding,
        reverseLayout = reverseLayout,
        horizontalAlignment = horizontalAlignment,
        flingBehavior = flingBehavior,
        userScrollEnabled = userScrollEnabled,
        content = content
    )
}
