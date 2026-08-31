package org.multipaz.compose.items

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.FlingBehavior
import androidx.compose.foundation.gestures.ScrollableDefaults
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyItemScope
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.dropShadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.shadow.Shadow
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp

private val floatingItemShadow = Shadow(
    radius = 10.dp,
    spread = 7.5.dp,
    color = Color.Black.copy(alpha = 0.065f),
    offset = DpOffset(x = 0.dp, 2.dp)
)

/**
 * Adds a lazy floating list of items into an outer [LazyColumn].
 *
 * Each item in the list is lazily composed as it enters the viewport. The first item has
 * rounded top corners, the last item has rounded bottom corners, and intermediate items have
 * square corners.
 *
 * @param count the number of items in this list.
 * @param title optional title to show above the list.
 * @param key a factory of stable and unique keys for each item.
 * @param contentType a factory of content types for each item.
 * @param itemContent the content for each item at index.
 */
fun LazyListScope.floatingItemList(
    count: Int,
    title: String? = null,
    key: ((index: Int) -> Any)? = null,
    contentType: (index: Int) -> Any? = { null },
    itemContent: @Composable LazyItemScope.(index: Int) -> Unit
) {
    if (title != null) {
        item(
            key = if (key != null) "${title}_header" else null,
            contentType = "FloatingItemListTitle"
        ) {
            Text(
                modifier = Modifier.padding(bottom = 8.dp),
                text = title,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary,
            )
        }
    }

    items(
        count = count,
        key = key,
        contentType = contentType
    ) { index ->
        val isFirst = index == 0
        val isLast = index == count - 1

        val shape = when {
            count == 1 -> RoundedCornerShape(16.dp)
            isFirst -> RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp)
            isLast -> RoundedCornerShape(bottomStart = 16.dp, bottomEnd = 16.dp)
            else -> RectangleShape
        }

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .drawWithContent {
                    val topBound = if (isFirst) -1000f else 0f
                    val bottomBound = if (isLast) size.height + 1000f else size.height
                    clipRect(
                        left = -1000f,
                        top = topBound,
                        right = size.width + 1000f,
                        bottom = bottomBound
                    ) {
                        this@drawWithContent.drawContent()
                    }
                }
                .dropShadow(
                    shape = shape,
                    shadow = floatingItemShadow
                )
                .clip(shape)
                .background(MaterialTheme.colorScheme.surfaceContainerLowest)
        ) {
            CompositionLocalProvider(
                LocalContentColor provides MaterialTheme.colorScheme.onSurface
            ) {
                itemContent(index)
            }
            if (!isLast) {
                HorizontalDivider(
                    modifier = Modifier.align(Alignment.BottomCenter),
                    thickness = 0.5.dp,
                    color = MaterialTheme.colorScheme.outlineVariant
                )
            }
        }
    }
}

/**
 * Adds a lazy floating list of items into an outer [LazyColumn].
 *
 * @param items the data list.
 * @param title optional title to show above the list.
 * @param key a factory of stable and unique keys for each item.
 * @param contentType a factory of content types for each item.
 * @param itemContent the content for each item.
 */
fun <T> LazyListScope.floatingItemList(
    items: List<T>,
    title: String? = null,
    key: ((item: T) -> Any)? = null,
    contentType: (item: T) -> Any? = { null },
    itemContent: @Composable LazyItemScope.(item: T) -> Unit
) {
    floatingItemList(
        count = items.size,
        title = title,
        key = if (key != null) { index -> key(items[index]) } else null,
        contentType = { index -> contentType(items[index]) }
    ) { index ->
        itemContent(items[index])
    }
}

/**
 * Draws a lazy floating list of items.
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
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }
        content()
    }
}
