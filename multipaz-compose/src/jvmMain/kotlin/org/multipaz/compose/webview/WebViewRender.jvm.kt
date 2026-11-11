package org.multipaz.compose.webview

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import kotlinx.io.bytestring.ByteString

private val BLOCK_BREAK_TAG_REGEX = Regex("(?i)<\\s*(?:br\\s*/?|/p|/div|/li|/h[1-6]|/tr)\\s*>")
private val ANY_TAG_REGEX = Regex("<[^>]+>")
private val HORIZONTAL_WHITESPACE_REGEX = Regex("[ \\t\\u000B\\f\\r]+")
private val EXCESS_NEWLINES_REGEX = Regex("\\n{3,}")

@Composable
internal actual fun WebViewRender(
    renderingContext: WebViewRenderingContext,
    modifier: Modifier,
    content: String?,
    asset: String?,
    color: Color,
    primaryColor: Color,
    linkColor: Color,
    backgroundColor: Color,
    assets: Map<String, ByteString>,
    appInfo: Map<String, String>
) {
    val rawHtml = content ?: asset?.let { assets[it]?.toByteArray()?.decodeToString() } ?: ""
    val text = rawHtml
        .replace(BLOCK_BREAK_TAG_REGEX, "\n")
        .replace(ANY_TAG_REGEX, " ")
        .lines()
        .joinToString("\n") { line -> line.replace(HORIZONTAL_WHITESPACE_REGEX, " ").trim() }
        .replace(EXCESS_NEWLINES_REGEX, "\n\n")
        .trim()
    Box(modifier = modifier.padding(8.dp)) {
        Text(
            text = text.ifEmpty { asset ?: "" },
            color = color
        )
    }
}
