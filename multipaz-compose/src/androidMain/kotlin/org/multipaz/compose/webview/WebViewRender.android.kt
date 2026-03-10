package org.multipaz.compose.webview

import android.annotation.SuppressLint
import android.content.Intent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.CancellationException
import org.multipaz.util.Logger
import org.multipaz.multipaz_compose.generated.resources.Res
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.io.bytestring.ByteString
import org.jetbrains.compose.resources.ExperimentalResourceApi
import java.io.ByteArrayInputStream
import java.io.FileNotFoundException
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.net.URLDecoder
import kotlin.math.roundToInt

private const val TAG = "WebViewRender"

@SuppressLint("ClickableViewAccessibility", "SetJavaScriptEnabled")
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
    // Initial height is arbitrary, it will get updated from the content height.
    val contentHeight = remember { mutableIntStateOf(100) }
    val coroutineScopeIO = rememberCoroutineScope { Dispatchers.IO }
    Box(modifier = modifier) {
        val bootstrapHtml = renderingContext.getBootstrapHtml(
            renderingContext.createStyle(
                color = color,
                primaryColor = primaryColor,
                linkColor = linkColor,
                backgroundColor = backgroundColor
            ),
            appInfo
        )
        AndroidView(
            modifier = Modifier.height(contentHeight.intValue.dp).fillMaxWidth(),
            factory = { context ->
                val webView = WebView(context).apply {
                    layoutParams = ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
                    )

                    setBackgroundColor(backgroundColor.toArgb())

                    settings.javaScriptEnabled = true

                    webViewClient = ClientImpl(bootstrapHtml, assets, coroutineScopeIO)
                }

                val mainHandler = android.os.Handler(context.mainLooper)

                val callback = object {
                    @JavascriptInterface
                    fun updateHeight(height: Float) {
                        mainHandler.post {
                            contentHeight.intValue = height.roundToInt()
                        }
                    }
                }

                webView.loadUrl("https://local/")
                webView.addJavascriptInterface(callback, "Callback")

                webView
            },
            update = { webView ->
                val client = webView.webViewClient as ClientImpl
                client.assets = assets
                webView.isHorizontalScrollBarEnabled = false
                webView.isVerticalScrollBarEnabled = false
                webView.setOnTouchListener(scrollPreventionDragListener)

                val cssStr = WebViewRenderingContext.escape(
                    renderingContext.createStyle(
                        color = color,
                        primaryColor = primaryColor,
                        linkColor = linkColor,
                        backgroundColor = backgroundColor
                    )
                )

                val command = if (content != null) {
                    "render(\"${WebViewRenderingContext.escape(content)}\", \"$cssStr\")"
                } else {
                    "renderResource(\"/${WebViewRenderingContext.escape(asset!!)}\", \"$cssStr\")"
                }
                if (client.loaded) {
                    webView.evaluateJavascript(command) {}
                } else {
                    client.deferredCommand = command
                }
            }
        )
    }
}

internal class ClientImpl(
    private val bootstrapHtml: String,
    var assets: Map<String, ByteString>,
    private val coroutineScopeIO: CoroutineScope
) : WebViewClient() {

    var loaded = false
    var deferredCommand = ""

    override fun onPageFinished(webView: WebView, url: String) {
        webView.evaluateJavascript(WebViewRenderingContext.renderResourceScript) {}
        if (deferredCommand.isNotEmpty()) {
            webView.evaluateJavascript(deferredCommand) {}
            deferredCommand = ""
        }
        loaded = true
    }

    override fun shouldOverrideUrlLoading(
        webView: WebView, request: WebResourceRequest
    ): Boolean {
        val intent = Intent(Intent.ACTION_VIEW, request.url)
        webView.context.startActivity(intent)
        return true
    }

    @OptIn(ExperimentalResourceApi::class)
    override fun shouldInterceptRequest(
        webView: WebView, request: WebResourceRequest
    ): WebResourceResponse {
        return when (val encodedPath = request.url.encodedPath) {
            "/" -> WebResourceResponse(
                "text/html", "utf8", ByteArrayInputStream(bootstrapHtml.encodeToByteArray())
            )

            null -> resourceNotFound()
            else -> try {
                val assets = this.assets
                val path = URLDecoder.decode(encodedPath, "UTF-8")
                val stream = if (path.startsWith("/res/")) {
                    PipedInputStream().also {
                        val output = PipedOutputStream(it)
                        coroutineScopeIO.launch {
                            try {
                                val bytes = Res.readBytes("files/webview/" + path.substring(5))
                                output.write(bytes)
                            } catch (err: CancellationException) {
                                throw err
                            } catch (err: Exception) {
                                Logger.e(TAG, "Error loading resource '$path'", err)
                            } finally {
                                output.close()
                            }
                        }
                    }
                } else {
                    // remove leading '/'
                    val strippedPath = path.substring(1)
                    val asset = assets[strippedPath] ?: throw FileNotFoundException()
                    ByteArrayInputStream(asset.toByteArray())
                }
                val mediaType = WebViewRenderingContext.mediaTypeFromName(path)
                WebResourceResponse(mediaType, "", stream)
            } catch (err: FileNotFoundException) {
                resourceNotFound()
            }
        }
    }
}

@SuppressLint("ClickableViewAccessibility")
private val scrollPreventionDragListener =
    View.OnTouchListener { _, event -> event.action == MotionEvent.ACTION_MOVE }

private fun resourceNotFound() = WebResourceResponse(
    "text/plain", "utf8", 404, "Resource not found", mapOf(), ByteArrayInputStream(byteArrayOf())
)

