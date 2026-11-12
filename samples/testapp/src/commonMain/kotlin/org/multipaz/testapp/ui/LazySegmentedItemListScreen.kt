package org.multipaz.testapp.ui

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Star
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import org.multipaz.compose.items.SegmentedItemHeadingAndText
import org.multipaz.compose.items.SegmentedItemText
import org.multipaz.compose.items.segmentedItemList

@Composable
fun LazySegmentedItemListScreen(
    showToast: (message: String) -> Unit,
) {
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(10.dp),
    ) {
        item {
            Text(
                modifier = Modifier.padding(bottom = 16.dp),
                text = "This screen contains text at the top, a segmented list in the middle with 50 items, and a button at the bottom to illustrate that the whole screen scrolls."
            )
        }

        segmentedItemList(
            count = 50,
            title = "LazySegmentedItemList (50 items)",
            key = { it }
        ) { index ->
            if (index % 3 == 0) {
                SegmentedItemHeadingAndText(
                    heading = "Heading #$index",
                    text = "This is item $index in a LazySegmentedItemList with a total of 50 items.",
                    showChevron = true
                )
            } else if (index % 3 == 1) {
                SegmentedItemText(
                    text = "Item #$index",
                    secondary = "Secondary detail for item $index",
                    image = { Icon(Icons.Outlined.Star, contentDescription = null) },
                    showChevron = true
                )
            } else {
                SegmentedItemText(
                    text = "Item #$index",
                    showChevron = false
                )
            }
        }

        item {
            Button(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 16.dp, bottom = 16.dp),
                onClick = { showToast("Button clicked!") }
            ) {
                Text("Button at bottom of screen")
            }
        }
    }
}
