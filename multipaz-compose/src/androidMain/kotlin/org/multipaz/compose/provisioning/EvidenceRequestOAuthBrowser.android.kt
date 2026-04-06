package org.multipaz.compose.provisioning

import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.ContextWrapper
import android.net.Uri
import android.util.Log
import androidx.browser.customtabs.CustomTabsClient
import androidx.browser.customtabs.CustomTabsIntent
import androidx.browser.customtabs.CustomTabsServiceConnection
import androidx.browser.customtabs.CustomTabsSession
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri

private const val TAG = "OAuthBrowser"

@Composable
internal actual fun EvidenceRequestOAuthBrowser(
    url: String,
    redirectUrl: String,
    waitForRedirect: suspend () -> String?,
    onRedirectReceived: suspend (String?) -> Unit,
) {
    val context = LocalContext.current
    // Partial-height Custom Tabs (bottom sheet presentation) require a CustomTabsSession
    // obtained by binding to the browser's Custom Tabs service. Without a session,
    // setInitialActivityHeightPx() is silently ignored and the tab opens full screen.
    // The session starts as null and is set asynchronously once the service connects.
    var session by remember { mutableStateOf<CustomTabsSession?>(null) }

    // Connection callback for the Custom Tabs service binding. This is invoked
    // asynchronously by the system after bindCustomTabsService() is called:
    // - onCustomTabsServiceConnected: the browser service is ready. We call warmup()
    //   to pre-initialize the browser rendering engine and create a CustomTabsSession
    //   that enables partial-height presentation.
    // - onServiceDisconnected: the browser service process crashed or was killed.
    //   We null out the session to prevent using a stale reference.
    val connection = remember {
        object : CustomTabsServiceConnection() {
            override fun onCustomTabsServiceConnected(
                name: ComponentName,
                client: CustomTabsClient
            ) {
                client.warmup(0)
                session = client.newSession(null)
            }

            override fun onServiceDisconnected(name: ComponentName?) {
                session = null
            }
        }
    }

    // Bind to the Custom Tabs service when this composable enters composition,
    // and unbind when it leaves. The binding is async: the connection callback
    // above will fire once the service is ready, populating the session state.
    DisposableEffect(Unit) {
        val packageName = CustomTabsClient.getPackageName(context, null)
        if (packageName != null) {
            CustomTabsClient.bindCustomTabsService(context, packageName, connection)
        } else {
            Log.w(TAG, "No Custom Tabs provider found")
        }
        onDispose {
            try {
                context.unbindService(connection)
            } catch (_: IllegalArgumentException) {
                // Service was not bound
            }
        }
    }

    // Wait for the redirect URL to arrive via the app's intent filter / deep link
    // pipeline.
    LaunchedEffect(url) {
        val redirectResult = waitForRedirect()
        onRedirectReceived(redirectResult)
    }

    val containerSize = LocalWindowInfo.current.containerSize.height
    // Launch the Custom Tab once the session is available. This effect is keyed on
    // both url and session: it skips (returns) while session is null, and re-runs
    // once the service connection provides a session. We use startActivityForResult()
    // instead of CustomTabsIntent.launchUrl() because partial-height Custom Tabs
    // require this launch method.
    LaunchedEffect(url, session) {
        val currentSession = session ?: return@LaunchedEffect
        val activity = context.findActivity()
        if (activity == null) {
            Log.w(TAG, "Could not find Activity in context chain, cannot launch Custom Tab")
            return@LaunchedEffect
        }
        val initialHeightPx = (containerSize * 7) / 10

        val customTabsIntent = CustomTabsIntent.Builder(currentSession)
            .setInitialActivityHeightPx(
                initialHeightPx,
                CustomTabsIntent.ACTIVITY_HEIGHT_ADJUSTABLE
            )
            .build()
        customTabsIntent.intent.data = url.toUri()
        activity.startActivityForResult(customTabsIntent.intent, 0)
    }
}

private fun Context.findActivity(): Activity? {
    var current = this
    while (current is ContextWrapper) {
        if (current is Activity) return current
        current = current.baseContext
    }
    return null
}
