package org.multipaz.compose.provisioning

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.suspendCancellableCoroutine
import org.multipaz.SwiftBridge
import org.multipaz.util.Logger
import platform.AuthenticationServices.ASWebAuthenticationPresentationContextProvidingProtocol
import platform.AuthenticationServices.ASWebAuthenticationSession
import platform.AuthenticationServices.ASWebAuthenticationSessionErrorCode
import platform.Foundation.NSError
import platform.Foundation.NSURL
import platform.UIKit.UIApplication
import platform.UIKit.UIWindow
import platform.darwin.NSObject
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

private const val TAG = "OAuthBrowser"

@Composable
internal actual fun EvidenceRequestOAuthBrowser(
    url: String,
    redirectUrl: String,
    waitForRedirect: suspend () -> String?,
    onRedirectReceived: suspend (String?) -> Unit,
) {
    val parsedRedirectUrl = NSURL.URLWithString(redirectUrl)
    val redirectScheme = parsedRedirectUrl?.scheme
    val redirectHost = parsedRedirectUrl?.host
    val redirectPath = parsedRedirectUrl?.path

    // For HTTPS redirect URLs, use the SwiftBridge to access the iOS 17.4+
    // ASWebAuthenticationSession.Callback.httpsURL API, which can natively
    // intercept HTTPS redirects. For custom URL schemes, use the K/N-accessible
    // callbackURLScheme-based API directly. If neither is possible, fall back
    // to opening Safari and relying on Universal Links.
    val useHttpsCallback = (redirectScheme == "https" || redirectScheme == "http")
            && redirectHost != null && redirectPath != null
    val useCustomSchemeCallback = redirectScheme != null
            && redirectScheme != "https" && redirectScheme != "http"

    if (useHttpsCallback) {
        LaunchedEffect(url) {
            try {
                val result = launchOAuthViaSwiftBridge(
                    url = url,
                    callbackHost = redirectHost,
                    callbackPath = redirectPath
                )
                onRedirectReceived(result)
            } catch (e: OAuthBrowserCancelledException) {
                Logger.i(TAG, "User cancelled the authentication session")
                onRedirectReceived(null)
            } catch (e: OAuthBrowserUnsupportedException) {
                // iOS < 17.4: fall back to opening Safari + Universal Links
                Logger.i(TAG, "HTTPS callback not supported, falling back to Safari")
                UIApplication.sharedApplication.openURL(NSURL.URLWithString(url)!!)
                val redirectResult = waitForRedirect()
                onRedirectReceived(redirectResult)
            } catch (e: Exception) {
                Logger.e(TAG, "ASWebAuthenticationSession failed", e)
            }
        }
    } else if (useCustomSchemeCallback) {
        LaunchedEffect(url) {
            try {
                val result = launchASWebAuthSession(url, redirectScheme!!)
                onRedirectReceived(result)
            } catch (e: OAuthBrowserCancelledException) {
                Logger.i(TAG, "User cancelled the authentication session")
                onRedirectReceived(null)
            } catch (e: Exception) {
                Logger.e(TAG, "ASWebAuthenticationSession failed", e)
            }
        }
    } else {
        // No usable callback mechanism: open in Safari and rely on Universal Links
        LaunchedEffect(url) {
            val redirectResult = waitForRedirect()
            onRedirectReceived(redirectResult)
        }
        LaunchedEffect(url) {
            val nsUrl = NSURL.URLWithString(url)
            if (nsUrl != null) {
                UIApplication.sharedApplication.openURL(nsUrl)
            }
        }
    }
}

private class OAuthBrowserCancelledException : Exception("User cancelled authentication")
private class OAuthBrowserUnsupportedException : Exception("HTTPS callback requires iOS 17.4+")

/**
 * Launches ASWebAuthenticationSession via SwiftBridge, using the iOS 17.4+
 * Callback.httpsURL API that supports HTTPS redirect URLs.
 */
@OptIn(ExperimentalForeignApi::class)
private suspend fun launchOAuthViaSwiftBridge(
    url: String,
    callbackHost: String,
    callbackPath: String
): String = suspendCancellableCoroutine { continuation ->
    SwiftBridge.launchOAuthSession(
        url,
        callbackHost,
        callbackPath,
        false
    ) { redirectUrl: String?, error: NSError? ->
        if (redirectUrl != null) {
            continuation.resume(redirectUrl)
        } else if (error != null) {
            when {
                // Domain "org.multipaz", code 1 = unsupported iOS version
                error.domain == "org.multipaz" && error.code == 1L ->
                    continuation.resumeWithException(OAuthBrowserUnsupportedException())
                // ASWebAuthenticationSessionError.canceledLogin
                error.domain == "com.apple.AuthenticationServices.WebAuthenticationSession"
                        && error.code == 1L ->
                    continuation.resumeWithException(OAuthBrowserCancelledException())
                else ->
                    continuation.resumeWithException(
                        RuntimeException("OAuth session error: ${error.localizedDescription}")
                    )
            }
        } else {
            continuation.resumeWithException(
                IllegalStateException("No callback URL and no error")
            )
        }
    }
}

/**
 * Launches ASWebAuthenticationSession directly via K/N bindings, using the legacy
 * callbackURLScheme parameter. Only works with custom URL schemes (not HTTPS).
 */
@OptIn(BetaInteropApi::class)
private suspend fun launchASWebAuthSession(
    url: String,
    callbackScheme: String
): String = suspendCancellableCoroutine { continuation ->
    val authUrl = NSURL.URLWithString(url) ?: run {
        continuation.resumeWithException(IllegalArgumentException("Invalid URL: $url"))
        return@suspendCancellableCoroutine
    }

    val presentationContextProvider = object : NSObject(),
        ASWebAuthenticationPresentationContextProvidingProtocol {
        override fun presentationAnchorForWebAuthenticationSession(
            session: ASWebAuthenticationSession
        ): UIWindow {
            return UIApplication.sharedApplication.keyWindow
                ?: UIApplication.sharedApplication.windows.firstOrNull() as? UIWindow
                ?: UIWindow()
        }
    }

    val session = ASWebAuthenticationSession(
        uRL = authUrl,
        callbackURLScheme = callbackScheme,
        completionHandler = { callbackUrl: NSURL?, error: NSError? ->
            if (callbackUrl != null) {
                val resultUrl = callbackUrl.absoluteString
                if (resultUrl != null) {
                    continuation.resume(resultUrl)
                } else {
                    continuation.resumeWithException(
                        IllegalStateException("Callback URL has no string representation")
                    )
                }
            } else if (error != null) {
                if (error.code == 1L) {
                    continuation.resumeWithException(OAuthBrowserCancelledException())
                } else {
                    continuation.resumeWithException(
                        RuntimeException("ASWebAuthenticationSession error: ${error.localizedDescription}")
                    )
                }
            } else {
                continuation.resumeWithException(
                    IllegalStateException("No callback URL and no error")
                )
            }
        }
    )

    session.presentationContextProvider = presentationContextProvider
    session.prefersEphemeralWebBrowserSession = false

    continuation.invokeOnCancellation {
        session.cancel()
    }

    session.start()
}
