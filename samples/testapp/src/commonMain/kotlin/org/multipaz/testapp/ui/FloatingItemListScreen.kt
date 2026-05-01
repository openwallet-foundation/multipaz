package org.multipaz.testapp.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
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
import org.multipaz.compose.items.FloatingItemCenteredText
import org.multipaz.compose.items.FloatingItemHeadingAndContent
import org.multipaz.compose.items.FloatingItemHeadingAndDate
import org.multipaz.compose.items.FloatingItemHeadingAndDateTime
import org.multipaz.compose.items.FloatingItemHeadingAndText
import org.multipaz.compose.items.FloatingItemList
import org.multipaz.compose.items.FloatingItemText
import org.multipaz.datetime.FormatStyle
import kotlin.time.Clock
import kotlin.time.Duration.Companion.days

@Composable
fun FloatingItemListScreen(
    showToast: (message: String) -> Unit,
) {
    LazyColumn(
        modifier = Modifier.padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        item {
            Text("This screen contains examples of FloatingItemList and all the various things that can be put in it")
        }

        item {
            FloatingItemList(title = "FloatingItemText") {
                FloatingItemText(text = "Primary text")
                FloatingItemText(text = "Primary text", secondary = "Secondary text")
                FloatingItemText(text = "Primary text",
                    secondary = "Secondary text, pay attention",
                    secondaryColor = MaterialTheme.colorScheme.error
                )
                FloatingItemText(
                    text = "Primary text and image",
                    image = { Icon(Icons.Outlined.Star, null) })
                FloatingItemText(
                    text = "Primary text and image",
                    secondary = "Secondary text",
                    image = { Icon(Icons.Outlined.Star, null) })
                FloatingItemText(
                    text = "Primary text and trailing content",
                    trailingContent = { Button(onClick = {}) { Text("Press me") } }
                )
                FloatingItemText(
                    text = "Primary text and trailing content",
                    secondary = "Secondary text",
                    trailingContent = { Button(onClick = {}) { Icon(Icons.Outlined.Star, null) } }
                )
                FloatingItemText(
                    text = "Primary text and trailing content",
                    image = { Icon(Icons.Outlined.Star, null) },
                    trailingContent = { Button(onClick = {}) { Text("Press me") } }
                )
                FloatingItemText(
                    text = "Primary text and trailing content",
                    secondary = "Secondary text",
                    image = { Icon(Icons.Outlined.Star, null) },
                    trailingContent = { Button(onClick = {}) { Icon(Icons.Outlined.Star, null) } }
                )
            }
        }

        item {
            FloatingItemList(title = "FloatingItemHeadingAndText") {
                FloatingItemHeadingAndText(
                    heading = "Heading",
                    text = "Text"
                )
                FloatingItemHeadingAndText(
                    heading = "Heading with image",
                    text = "Text",
                    image = { Icon(Icons.Outlined.Star, null) },
                )
                FloatingItemHeadingAndText(
                    heading = "Heading",
                    text = "Text",
                    trailingContent = { Button(onClick = {}) { Text("Press me") } }
                )
                FloatingItemHeadingAndText(
                    heading = "Heading with image",
                    text = "Text",
                    image = { Icon(Icons.Outlined.Star, null) },
                    trailingContent = { Button(onClick = {}) { Icon(Icons.Outlined.Star, null) } }
                )
                FloatingItemHeadingAndContent(
                    heading = "FloatingItemHeadingAndContent",
                    content = {
                        Image(
                            modifier = Modifier.height(100.dp),
                            painter = painterResource(Res.drawable.card_utopia_wholesale),
                            contentDescription = null
                        )
                    },
                )
                FloatingItemHeadingAndDate(
                    heading = "FloatingItemHeadingAndDate",
                    date = Clock.System.now() - 5.days
                )
                FloatingItemHeadingAndDate(
                    heading = "FloatingItemHeadingAndDate (full)",
                    date = Clock.System.now() - 5.days,
                    dateStyle = FormatStyle.FULL
                )
                FloatingItemHeadingAndDateTime(
                    heading = "FloatingItemHeadingAndDateTime",
                    dateAndTime = Clock.System.now() + 5.days
                )
                FloatingItemHeadingAndDateTime(
                    heading = "FloatingItemHeadingAndDateTime (full)",
                    dateAndTime = Clock.System.now() + 5.days,
                    dateStyle = FormatStyle.FULL,
                    timeStyle = FormatStyle.FULL
                )
            }
        }

        item {
            FloatingItemList(title = "FloatingItemCenteredText") {
                FloatingItemCenteredText(
                    text = "Nothing to see here, move along. " +
                            "This line is really long so should broken across at least two lines"
                )
            }
        }

        item {
            FloatingItemList(
                modifier = Modifier.padding(bottom = 20.dp)
            ) {
                FloatingItemCenteredText(
                    text = "Titleless FloatingItemList"
                )
            }
        }
    }
}