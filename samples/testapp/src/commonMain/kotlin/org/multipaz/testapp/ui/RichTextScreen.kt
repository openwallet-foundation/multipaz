package org.multipaz.testapp.ui

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import org.multipaz.compose.scrollbar.LazyColumnWithScrollbar
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import kotlinx.io.bytestring.encodeToByteString
import org.multipaz.compose.webview.RichText

@Composable
fun RichTextScreen() {
    val content = remember { mutableStateOf(TEST_MARKDOWN) }
    val onSurface = remember { mutableStateOf(false) }
    val primary = remember { mutableStateOf(false) }
    LazyColumnWithScrollbar(
        modifier = Modifier.padding(8.dp)
    ) {
        item {
            Row(modifier = Modifier.fillMaxWidth()) {
                Button(onClick = {content.value = TEST_MARKDOWN}) {
                    Text("md")
                }
                Spacer(Modifier.weight(1f))
                Button(onClick = {content.value = TEST_HTML}) {
                    Text("html")
                }
                Spacer(Modifier.weight(1f))
                Text("text", modifier = Modifier.align(Alignment.CenterVertically))
                Checkbox(
                    onSurface.value,
                    onCheckedChange = {onSurface.value = it},
                )
                Spacer(Modifier.weight(1f))
                Text("heading", modifier = Modifier.align(Alignment.CenterVertically))
                Checkbox(
                    primary.value,
                    onCheckedChange = {primary.value = it},
                )
            }
        }
        item {
            TextField(
                value = content.value,
                onValueChange = { content.value = it },
                minLines = 8,
                maxLines = 8
            )
        }
        item {
            HorizontalDivider()
        }
        item {
            RichText(
                content = content.value,
                assets = mapOf(
                    "circle.svg" to CIRCLE_SVG.encodeToByteString()
                ),
                color = if (onSurface.value) {
                    Color.Green
                } else {
                    MaterialTheme.colorScheme.onSurface
                },
                primaryColor = if (primary.value) {
                    Color.Magenta
                } else {
                    MaterialTheme.colorScheme.primary
                },
                appInfo = mapOf(
                    "foo" to "[Item Foo]",
                    "bar" to "[Item Bar]"
                )
            )
        }
        item {
            HorizontalDivider()
        }
    }
}

private val CIRCLE_SVG = """
    <svg width="120px" height="120px" viewBox="0 0 120 120" xmlns="http://www.w3.org/2000/svg">
        <circle cx="60" cy="60" r="50" fill="red"/>
    </svg>
""".trimIndent()

private val TEST_MARKDOWN = """
        # Test markdown content
        
        List:
         * First item
         * App info: __placeholder__{appinfo=foo}
         * Last item
         
        ## Subheading
        
        ![alt text](circle.svg "A red circle") ![alt text](/res/images/star.svg "A blue star")
        
        Link to [google.com](https://www.google.com)
    """.trimIndent()

private val TEST_HTML = """
        <h1>Test HTML content</h1> 
        <p>List:
        <ul>
        <li>First item</li>
        <li>App info: <b appinfo="foo"></b></li>
        <li>Last item</li>
        </ul>
        </p>
        <h2>Subheading</h2>
        <img src="circle.svg"/>
        <img src="/res/images/star.svg"/>
        <p>Link to <a href="https://www.google.com">google.com</a></p>
    """.trimIndent()

