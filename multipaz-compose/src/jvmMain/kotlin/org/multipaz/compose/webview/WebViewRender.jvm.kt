package org.multipaz.compose.webview

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import kotlinx.io.bytestring.ByteString

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
    val text = (content ?: "")
        .replace(Regex("<[^>]+>"), " ")
        .replace(Regex("\\s+"), " ")
        .trim()
    Box(modifier = modifier.padding(8.dp)) {
        Text(
            text = text.ifEmpty { asset ?: "" },
            color = color
        )
    }
}
