package org.multipaz.testapp

import android.app.PendingIntent
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import com.jakewharton.processphoenix.ProcessPhoenix
import io.ktor.client.engine.HttpClientEngineFactory
import io.ktor.client.engine.android.Android
import org.multipaz.context.applicationContext
import org.multipaz.securearea.AndroidKeystoreSecureArea
import multipazproject.samples.testapp.generated.resources.Res
import multipazproject.samples.testapp.generated.resources.app_icon
import multipazproject.samples.testapp.generated.resources.app_icon_red
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.multipaz.compose.notifications.NotificationManagerAndroid
import org.multipaz.compose.prompt.PresentmentActivity
import org.multipaz.digitalcredentials.getAppOrigin
import org.multipaz.document.Document
import org.multipaz.document.DocumentBadge
import org.multipaz.presentment.PresentmentSource
import org.multipaz.util.Logger
import java.net.NetworkInterface
import java.security.Security

private const val TAG = "TestAppPlatform"

actual object TestAppConfiguration {
    actual val appName = applicationContext.getString(R.string.app_name)

    actual val appIcon = if (appName.endsWith("(Red)")) {
        Res.drawable.app_icon_red
    } else {
        Res.drawable.app_icon
    }

    actual val platform = TestAppPlatform.ANDROID

    actual val storage = org.multipaz.util.Platform.nonBackedUpStorage

    actual val redirectPath: String = "/redirect/${applicationContext.packageName}/"

    actual suspend fun init() {
        NotificationManagerAndroid.setSmallIcon(R.drawable.ic_stat_name)
        NotificationManagerAndroid.setChannelTitle(
            applicationContext.getString(R.string.notification_channel_title)
        )
    }

    actual suspend fun cryptoInit(settingsModel: TestAppSettingsModel) {
        if (settingsModel.cryptoPreferBouncyCastle.value) {
            Logger.i(TAG, "Forcing BouncyCastle to the top of the list")
            Security.removeProvider(BouncyCastleProvider.PROVIDER_NAME)
            Security.insertProviderAt(BouncyCastleProvider(), 1)
        }
    }

    actual fun restartApp() {
        ProcessPhoenix.triggerRebirth(applicationContext)
    }

    actual val localIpAddress: String by lazy {
        for (iface in NetworkInterface.getNetworkInterfaces()) {
            for (inetAddress in iface.inetAddresses) {
                if (!inetAddress.isLoopbackAddress) {
                    val address = inetAddress.hostAddress
                    if (address != null && address.indexOf(':') < 0) {
                        return@lazy address
                    }
                }
            }
        }
        throw IllegalStateException("Unable to determine address")
    }

    actual val httpClientEngineFactory: HttpClientEngineFactory<*> by lazy {
        Android
    }

    actual val platformSecureAreaHasKeyAgreement by lazy {
        AndroidKeystoreSecureArea.Capabilities().keyAgreementSupported
    }

    @Suppress("DEPRECATION")
    actual suspend fun getAppToAppOrigin(): String {
        val packageInfo = applicationContext.packageManager
            .getPackageInfo(applicationContext.packageName, PackageManager.GET_SIGNATURES)
        return getAppOrigin(packageInfo.signatures!![0].toByteArray())
    }

    const val ACTION_VIEW_DOCUMENT = "org.multipaz.testapp.action.viewDocument"

    fun getPendingIntentForLaunchingQuickAccessWallet(
        source: PresentmentSource,
        initiallySelectedDocumentId: String?,
        onDocumentSelected: ((documentId: String?) -> Unit)? = { documentId ->
            Logger.i(TAG, "Quick Access Wallet card selected: $documentId")
        },
        documentSelectedContent: (@Composable (documentId: String) -> Unit)? = @Composable { documentId ->
            var dummyState by remember { mutableStateOf(true) }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Example option for document",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.weight(1.0f)
                )
                Switch(
                    checked = dummyState,
                    onCheckedChange = { dummyState = it }
                )
            }
        }
    ): PendingIntent {
        return PresentmentActivity.getPendingIntent(
            source = source,
            initiallySelectedDocumentId = initiallySelectedDocumentId,
            openWalletAppPendingIntentFn = { document ->
                PendingIntent.getActivity(
                    /* context = */ applicationContext,
                    /* requestCode = */ 0,
                    /* intent = */ Intent(applicationContext, MainActivity::class.java).apply {
                        addFlags(
                            Intent.FLAG_ACTIVITY_NEW_TASK or
                                    Intent.FLAG_ACTIVITY_CLEAR_TOP or
                                    Intent.FLAG_ACTIVITY_SINGLE_TOP
                        )
                        action = ACTION_VIEW_DOCUMENT
                        putExtra("documentId", document.identifier)
                    },
                    /* flags = */ PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
            },
            preferredService = ComponentName(applicationContext, TestAppCombinedNfcService::class.java),
            onDocumentSelected = onDocumentSelected,
            documentSelectedContent = documentSelectedContent
        )
    }

    actual suspend fun launchQuickAccessWallet(
        source: PresentmentSource,
        initiallySelectedDocumentId: String?
    ) {
        getPendingIntentForLaunchingQuickAccessWallet(source, initiallySelectedDocumentId).send()
    }
}
