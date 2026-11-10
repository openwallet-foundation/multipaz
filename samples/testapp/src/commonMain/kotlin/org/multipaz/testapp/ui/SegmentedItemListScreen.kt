package org.multipaz.testapp.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import org.multipaz.compose.scrollbar.LazyColumnWithScrollbar
import androidx.compose.material.icons.outlined.Star
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import multipazproject.samples.testapp.generated.resources.Res
import multipazproject.samples.testapp.generated.resources.card_utopia_wholesale
import org.jetbrains.compose.resources.painterResource
import org.multipaz.compose.items.SegmentedItemCenteredText
import org.multipaz.compose.items.SegmentedItemHeadingAndContent
import org.multipaz.compose.items.SegmentedItemHeadingAndDate
import org.multipaz.compose.items.SegmentedItemHeadingAndDateTime
import org.multipaz.compose.items.SegmentedItemHeadingAndText
import org.multipaz.compose.items.SegmentedItemList
import org.multipaz.compose.items.SegmentedItemText
import org.multipaz.datetime.FormatStyle
import kotlin.time.Clock
import kotlin.time.Duration.Companion.days

@Composable
fun SegmentedItemListScreen(
    showToast: (message: String) -> Unit,
    onNavigateToLazySegmentedItemList: () -> Unit = {},
) {
    LazyColumnWithScrollbar(
        modifier = Modifier.padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        item {
            Text("This screen contains examples of SegmentedItemList and all the various things that can be put in it")
        }

        item {
            SegmentedItemList(title = "Lazy list variant") {
                SegmentedItemText(
                    modifier = Modifier.clickable { onNavigateToLazySegmentedItemList() },
                    text = "LazySegmentedItemList (50 items)",
                    secondary = "Tap to open lazy-loaded scrolling list",
                    showChevron = true
                )
            }
        }

        item {
            SegmentedItemList(title = "SegmentedItemText") {
                SegmentedItemText(text = "Primary text")
                SegmentedItemText(text = "Primary text", showChevron = true)
                SegmentedItemText(text = "Primary text", secondary = "Secondary text")
                SegmentedItemText(text = "Primary text", secondary = "Secondary text", showChevron = true)
                SegmentedItemText(
                    text = "Primary text",
                    secondary = "Secondary text, pay attention",
                    secondaryColor = MaterialTheme.colorScheme.error
                )
                SegmentedItemText(
                    text = "Primary text and image",
                    image = { Icon(Icons.Outlined.Star, null) }
                )
                SegmentedItemText(
                    text = "Primary text and image",
                    showChevron = true,
                    image = { Icon(Icons.Outlined.Star, null) }
                )
                SegmentedItemText(
                    text = "Primary text and image",
                    secondary = "Secondary text",
                    image = { Icon(Icons.Outlined.Star, null) }
                )
                SegmentedItemText(
                    text = "Primary text and image",
                    secondary = "Secondary text",
                    showChevron = true,
                    image = { Icon(Icons.Outlined.Star, null) }
                )
                SegmentedItemText(
                    text = "Primary text and trailing content",
                    trailingContent = { Button(onClick = {}) { Text("Press me") } }
                )
                SegmentedItemText(
                    text = "Primary text and trailing content",
                    showChevron = true,
                    trailingContent = { Button(onClick = {}) { Text("Press me") } }
                )
                SegmentedItemText(
                    text = "Primary text and trailing content",
                    secondary = "Secondary text",
                    trailingContent = { Button(onClick = {}) { Icon(Icons.Outlined.Star, null) } }
                )
                SegmentedItemText(
                    text = "Primary text and trailing content",
                    secondary = "Secondary text",
                    showChevron = true,
                    trailingContent = { Button(onClick = {}) { Icon(Icons.Outlined.Star, null) } }
                )
                SegmentedItemText(
                    text = "Primary text and trailing content",
                    image = { Icon(Icons.Outlined.Star, null) },
                    trailingContent = { Button(onClick = {}) { Text("Press me") } }
                )
                SegmentedItemText(
                    text = "Primary text and trailing content",
                    secondary = "Secondary text",
                    image = { Icon(Icons.Outlined.Star, null) },
                    trailingContent = { Button(onClick = {}) { Icon(Icons.Outlined.Star, null) } }
                )
                SegmentedItemText(
                    text = "Primary text and trailing content",
                    secondary = "Secondary text",
                    showChevron = true,
                    image = { Icon(Icons.Outlined.Star, null) },
                    trailingContent = { Button(onClick = {}) { Icon(Icons.Outlined.Star, null) } }
                )
            }
        }

        item {
            SegmentedItemList(title = "SegmentedItemHeadingAndText") {
                SegmentedItemHeadingAndText(
                    heading = "Heading",
                    text = "Text"
                )
                SegmentedItemHeadingAndText(
                    heading = "Heading",
                    text = "Text",
                    showChevron = true
                )
                SegmentedItemHeadingAndText(
                    heading = "Heading with image",
                    text = "Text",
                    image = { Icon(Icons.Outlined.Star, null) },
                )
                SegmentedItemHeadingAndText(
                    heading = "Heading with image",
                    text = "Text",
                    showChevron = true,
                    image = { Icon(Icons.Outlined.Star, null) },
                )
                SegmentedItemHeadingAndText(
                    heading = "Heading",
                    text = "Text",
                    trailingContent = { Button(onClick = {}) { Text("Press me") } }
                )
                SegmentedItemHeadingAndText(
                    heading = "Heading",
                    text = "Text",
                    showChevron = true,
                    trailingContent = { Button(onClick = {}) { Text("Press me") } }
                )
                SegmentedItemHeadingAndText(
                    heading = "Heading with image",
                    text = "Text",
                    image = { Icon(Icons.Outlined.Star, null) },
                    trailingContent = { Button(onClick = {}) { Icon(Icons.Outlined.Star, null) } }
                )
                SegmentedItemHeadingAndText(
                    heading = "Heading with image",
                    text = "Text",
                    showChevron = true,
                    image = { Icon(Icons.Outlined.Star, null) },
                    trailingContent = { Button(onClick = {}) { Icon(Icons.Outlined.Star, null) } }
                )
                SegmentedItemHeadingAndContent(
                    heading = "SegmentedItemHeadingAndContent",
                    content = {
                        Image(
                            modifier = Modifier.height(100.dp),
                            painter = painterResource(Res.drawable.card_utopia_wholesale),
                            contentDescription = null
                        )
                    },
                )
                SegmentedItemHeadingAndContent(
                    heading = "SegmentedItemHeadingAndContent (with chevron)",
                    showChevron = true,
                    content = {
                        Image(
                            modifier = Modifier.height(100.dp),
                            painter = painterResource(Res.drawable.card_utopia_wholesale),
                            contentDescription = null
                        )
                    },
                )
                SegmentedItemHeadingAndDate(
                    heading = "SegmentedItemHeadingAndDate",
                    date = Clock.System.now() - 5.days
                )
                SegmentedItemHeadingAndDate(
                    heading = "SegmentedItemHeadingAndDate (with chevron)",
                    date = Clock.System.now() - 5.days,
                    showChevron = true
                )
                SegmentedItemHeadingAndDate(
                    heading = "SegmentedItemHeadingAndDate (full)",
                    date = Clock.System.now() - 5.days,
                    dateStyle = FormatStyle.FULL
                )
                SegmentedItemHeadingAndDateTime(
                    heading = "SegmentedItemHeadingAndDateTime",
                    dateAndTime = Clock.System.now() + 5.days
                )
                SegmentedItemHeadingAndDateTime(
                    heading = "SegmentedItemHeadingAndDateTime (with chevron)",
                    dateAndTime = Clock.System.now() + 5.days,
                    showChevron = true
                )
                SegmentedItemHeadingAndDateTime(
                    heading = "SegmentedItemHeadingAndDateTime (full)",
                    dateAndTime = Clock.System.now() + 5.days,
                    dateStyle = FormatStyle.FULL,
                    timeStyle = FormatStyle.FULL
                )
            }
        }

        item {
            SegmentedItemList(title = "SegmentedItemCenteredText") {
                SegmentedItemCenteredText(
                    text = "Nothing to see here, move along. " +
                        "This line is really long so should broken across at least two lines"
                )
                SegmentedItemCenteredText(
                    text = "Centered text with chevron",
                    showChevron = true
                )
            }
        }

        item {
            SegmentedItemList(
                modifier = Modifier.padding(bottom = 20.dp)
            ) {
                SegmentedItemCenteredText(
                    text = "Titleless SegmentedItemList"
                )
            }
        }
    }
}
