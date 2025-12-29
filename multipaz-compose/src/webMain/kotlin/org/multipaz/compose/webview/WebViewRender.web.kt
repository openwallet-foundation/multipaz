package org.multipaz.compose.webview

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
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
    TODO()
}