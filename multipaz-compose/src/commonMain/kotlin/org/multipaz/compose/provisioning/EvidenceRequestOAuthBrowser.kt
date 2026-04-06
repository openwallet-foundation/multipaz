package org.multipaz.compose.provisioning

import androidx.compose.runtime.Composable

/**
 * Platform-specific in-app OAuth browser that shares the user's browser session (cookies).
 *
 * On Android, this launches a Chrome Custom Tab as a partial-height bottom sheet.
 * On iOS 17.4+, this uses ASWebAuthenticationSession with native HTTPS callback handling.
 * On older iOS and web, this falls back to opening the system browser.
 *
 * @param url the OAuth authorization page URL to open
 * @param redirectUrl the redirect URL that the authorization server will redirect to
 * @param waitForRedirect suspending callback that waits for the redirect URL to arrive via
 *   the app's deep link / intent filter pipeline; used on platforms where redirect handling
 *   is external (Android, older iOS, web)
 * @param onRedirectReceived called with the full redirect URL (including query parameters)
 *   when the redirect is captured, regardless of which mechanism captured it
 */
@Composable
internal expect fun EvidenceRequestOAuthBrowser(
    url: String,
    redirectUrl: String,
    waitForRedirect: suspend () -> String?,
    onRedirectReceived: suspend (String?) -> Unit,
)
