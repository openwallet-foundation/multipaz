package org.multipaz.compose.webview

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableIntState
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.UIKitInteropInteractionMode
import androidx.compose.ui.viewinterop.UIKitInteropProperties
import androidx.compose.ui.viewinterop.UIKitView
import org.multipaz.util.Logger
import org.multipaz.multipaz_compose.generated.resources.Res
import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.ObjCSignatureOverride
import kotlinx.cinterop.allocArrayOf
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.readValue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.launch
import kotlinx.io.bytestring.ByteString
import org.jetbrains.compose.resources.ExperimentalResourceApi
import platform.CoreGraphics.CGRectZero
import platform.Foundation.NSData
import platform.Foundation.NSError
import platform.Foundation.NSURL
import platform.Foundation.NSURLResponse
import platform.Foundation.create
import platform.UIKit.UIApplication
import platform.WebKit.WKNavigation
import platform.WebKit.WKNavigationAction
import platform.WebKit.WKNavigationActionPolicy
import platform.WebKit.WKNavigationDelegateProtocol
import platform.WebKit.WKNavigationTypeLinkActivated
import platform.WebKit.WKScriptMessage
import platform.WebKit.WKScriptMessageHandlerProtocol
import platform.WebKit.WKURLSchemeHandlerProtocol
import platform.WebKit.WKURLSchemeTaskProtocol
import platform.WebKit.WKUserContentController
import platform.WebKit.WKUserScript
import platform.WebKit.WKUserScriptInjectionTime
import platform.WebKit.WKWebView
import platform.WebKit.WKWebViewConfiguration
import platform.WebKit.javaScriptEnabled
import platform.darwin.NSObject
import kotlin.concurrent.Volatile

private const val SCHEME = "mpz"
private const val BASE_URL = "${SCHEME}://local/"
private const val TAG = "WebViewRender"

@OptIn(ExperimentalForeignApi::class, ExperimentalComposeUiApi::class)
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
    val client = remember { WebViewClient() }
    val coroutineScopeIO = rememberCoroutineScope { Dispatchers.IO }
    val urlHandler = remember { URLHandler(assets, coroutineScopeIO) }
    // Initial height is arbitrary, it will get updated from the content height.
    val contentHeight = remember { mutableIntStateOf(100) }
    Box(modifier = modifier) {
        UIKitView(
            modifier = Modifier.height(contentHeight.intValue.dp).fillMaxWidth(),
            factory = {
                val bootstrapHtml = renderingContext.getBootstrapHtml(
                    renderingContext.createStyle(
                        color = color,
                        primaryColor = primaryColor,
                        linkColor = linkColor,
                        backgroundColor = backgroundColor
                    ),
                    appInfo
                )

                val config =
                    WKWebViewConfiguration().apply {
                        allowsInlineMediaPlayback = false
                        defaultWebpagePreferences.allowsContentJavaScript = true
                        preferences.apply {
                            javaScriptEnabled = true
                        }
                        setURLSchemeHandler(urlHandler, SCHEME)
                        userContentController.addScriptMessageHandler(
                            ScriptHandler(contentHeight),
                            "Callback"
                        )
                        userContentController.addUserScript(WKUserScript(
                            "window.Callback = {updateHeight: function(height) { window.webkit.messageHandlers.Callback.postMessage(height); }}",
                            WKUserScriptInjectionTime.WKUserScriptInjectionTimeAtDocumentStart,
                            true
                        ))
                    }

                WKWebView(
                    frame = CGRectZero.readValue(),
                    configuration = config
                ).apply {
                    allowsBackForwardNavigationGestures = false

                    this.navigationDelegate = client

                    this.scrollView.bounces = false

                    loadHTMLString(
                        bootstrapHtml,
                        NSURL.URLWithString(BASE_URL)
                    )
                }
            },
            update = { webView ->
                urlHandler.assets = assets
                injectContent(webView, client, renderingContext, content, asset,
                    color, primaryColor, linkColor, backgroundColor)
            },
            onRelease = {
                it.navigationDelegate = null
            },
            properties = UIKitInteropProperties(
                interactionMode = UIKitInteropInteractionMode.NonCooperative,
                isNativeAccessibilityEnabled = true,
            ),
        )
    }
}

internal fun injectContent(
    webView: WKWebView,
    client: WebViewClient,
    renderingContext: WebViewRenderingContext,
    content: String?,
    asset: String?,
    color: Color,
    primaryColor: Color,
    linkColor: Color,
    backgroundColor: Color,
) {
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
    client.evaluateJavaScript(webView, command)
}

class URLHandler(
    @Volatile
    internal var assets: Map<String, ByteString>,
    private val coroutineScopeIO: CoroutineScope
): NSObject(), WKURLSchemeHandlerProtocol {
    @OptIn(ExperimentalResourceApi::class)
    @ObjCSignatureOverride
    override fun webView(webView: WKWebView, startURLSchemeTask: WKURLSchemeTaskProtocol) {
        val url = startURLSchemeTask.request.URL!!
        val urlString = url.absoluteString!!
        if (urlString.startsWith(BASE_URL)) {
            val path = urlString.substring(BASE_URL.length)
            val mediaType = WebViewRenderingContext.mediaTypeFromName(path)
            if (path.startsWith("res/")) {
                coroutineScopeIO.launch {
                    try {
                        val bytes = Res.readBytes("files/webview/" + path.substring(4))
                        val response = NSURLResponse(url, mediaType, bytes.size.toLong(), null)
                        startURLSchemeTask.didReceiveResponse(response)
                        startURLSchemeTask.didReceiveData(bytes.toNSData())
                    } catch (err: CancellationException) {
                        throw err
                    } catch (err: Exception) {
                        Logger.e(TAG, "Error loading resource '$path'", err)
                    }
                    startURLSchemeTask.didFinish()
                }
                return
            } else {
                val asset = assets[path]
                if (asset != null) {
                    val response = NSURLResponse(url, mediaType, asset.size.toLong(), null)
                    startURLSchemeTask.didReceiveResponse(response)
                    startURLSchemeTask.didReceiveData(asset.toNSData())
                    startURLSchemeTask.didFinish()
                    return
                }
            }
        }
        // TODO: startURLSchemeTask.didFailWithError(error) once we have utility to create NSError
        startURLSchemeTask.didFinish()
        Logger.e(TAG,"Asset `$urlString` not found")
    }

    @ObjCSignatureOverride
    override fun webView(webView: WKWebView, stopURLSchemeTask: WKURLSchemeTaskProtocol) {
    }
}

internal class WebViewClient : NSObject(), WKNavigationDelegateProtocol {
    private var loaded = false
    private var deferredCommand: String? = null

    @ObjCSignatureOverride
    override fun webView(
        webView: WKWebView,
        didStartProvisionalNavigation: WKNavigation?,
    ) {}

    @ObjCSignatureOverride
    override fun webView(
        webView: WKWebView,
        didCommitNavigation: WKNavigation?,
    ) {}

    /**
     * Called when the web view finishes loading.
     */
    @ObjCSignatureOverride
    override fun webView(
        webView: WKWebView,
        didFinishNavigation: WKNavigation?,
    ) {
        loaded = true
        webView.evaluateJavaScript(
            WebViewRenderingContext.renderResourceScript
        ) { _, _ ->
        }
        if (deferredCommand != null) {
            webView.evaluateJavaScript(deferredCommand!!) { _, _ -> }
            deferredCommand = null
        }
    }

    fun evaluateJavaScript(webView: WKWebView, script: String) {
        if (loaded) {
            webView.evaluateJavaScript(script) { _, _ -> }
        } else {
            deferredCommand = script
        }
    }

    @ObjCSignatureOverride
    override fun webView(
        webView: WKWebView,
        didFailProvisionalNavigation: WKNavigation?,
        withError: NSError,
    ) {}

    @ObjCSignatureOverride
    override fun webView(
        webView: WKWebView,
        decidePolicyForNavigationAction: WKNavigationAction,
        decisionHandler: (WKNavigationActionPolicy) -> Unit,
    ) {
        val url = decidePolicyForNavigationAction.request.URL!!
        val urlString = url.absoluteString!!
        if (decidePolicyForNavigationAction.navigationType == WKNavigationTypeLinkActivated) {
            decisionHandler(WKNavigationActionPolicy.WKNavigationActionPolicyCancel)
            UIApplication.sharedApplication.openURL(url)
        } else if (urlString.startsWith(BASE_URL)) {
            decisionHandler(WKNavigationActionPolicy.WKNavigationActionPolicyAllow)
        } else {
            decisionHandler(WKNavigationActionPolicy.WKNavigationActionPolicyCancel)
            Logger.e(TAG, "Attempt to access an external url: '$urlString'")
        }
    }
}

class ScriptHandler(
    private val contentHeight: MutableIntState
) : NSObject(), WKScriptMessageHandlerProtocol {
    override fun userContentController(
        userContentController: WKUserContentController,
        didReceiveScriptMessage: WKScriptMessage
    ) {
        contentHeight.intValue = (didReceiveScriptMessage.body as Double).toInt()
    }
}

private fun ByteString.toNSData(): NSData = toByteArray().toNSData()

@OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)
private fun ByteArray.toNSData(): NSData = memScoped {
    NSData.create(bytes = allocArrayOf(this@toNSData), length = size.toULong())
}