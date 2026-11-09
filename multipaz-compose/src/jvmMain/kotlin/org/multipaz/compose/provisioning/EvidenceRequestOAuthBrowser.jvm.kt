package org.multipaz.compose.provisioning

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.platform.LocalUriHandler

@Composable
internal actual fun EvidenceRequestOAuthBrowser(
    url: String,
    redirectUrl: String,
    waitForRedirect: suspend () -> String?,
    onRedirectReceived: suspend (String?) -> Unit,
) {
    val uriHandler = LocalUriHandler.current

    LaunchedEffect(url) {
        val redirectResult = waitForRedirect()
        onRedirectReceived(redirectResult)
    }

    LaunchedEffect(url) {
        uriHandler.openUri(url)
    }
}
