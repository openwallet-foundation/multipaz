package org.multipaz.compose.provisioning

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.platform.LocalUriHandler
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import org.multipaz.util.Logger
import java.net.URI

private const val TAG = "EvidenceRequestOAuthBrowser.jvm"

@Composable
internal actual fun EvidenceRequestOAuthBrowser(
    url: String,
    redirectUrl: String,
    waitForRedirect: suspend () -> String?,
    onRedirectReceived: suspend (String?) -> Unit,
) {
    val uriHandler = LocalUriHandler.current

    LaunchedEffect(url) {
        val scheme = try {
            URI(url).scheme?.lowercase()
        } catch (e: Exception) {
            Logger.w(TAG, "Invalid OAuth browser URL", e)
            null
        }
        if (scheme != "http" && scheme != "https") {
            Logger.w(TAG, "Refusing to open OAuth browser URL with unsupported scheme: $scheme")
            onRedirectReceived(null)
            return@LaunchedEffect
        }
        val waitDeferred = async(start = CoroutineStart.UNDISPATCHED) {
            waitForRedirect()
        }
        try {
            uriHandler.openUri(url)
        } catch (e: CancellationException) {
            waitDeferred.cancel()
            throw e
        } catch (e: Exception) {
            Logger.w(TAG, "Failed to open external browser for OAuth flow", e)
            waitDeferred.cancel()
            onRedirectReceived(null)
            return@LaunchedEffect
        }
        val redirectResult = waitDeferred.await()
        onRedirectReceived(redirectResult)
    }
}
