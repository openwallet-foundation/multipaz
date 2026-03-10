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
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.multipaz.compose.notifications.NotificationManagerAndroid
import org.multipaz.compose.prompt.PresentmentActivity
import org.multipaz.digitalcredentials.getAppOrigin
import org.multipaz.presentment.PresentmentSource
import org.multipaz.prompt.AndroidPromptModel
import org.multipaz.prompt.PromptDialogModel
import org.multipaz.prompt.PromptModel
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

    actual val promptModel: PromptModel by lazy {
        AndroidPromptModel.Builder(::uiLauncher).apply { addCommonDialogs() }.build()
    }

    private suspend fun uiLauncher(dialogModel: PromptDialogModel<*, *>) {
        // This is how we could start an activity:
        /*
    val intent = Intent(
        applicationContext,
        TestAppMdocNfcPresentmentActivity::class.java
    )
    intent.addFlags(
        Intent.FLAG_ACTIVITY_NEW_TASK or
                Intent.FLAG_ACTIVITY_NO_HISTORY or
                Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS or
                Intent.FLAG_ACTIVITY_NO_ANIMATION
    )
    Logger.i(TAG, "startActivity on $intent")
    applicationContext.startActivity(intent)
    // Poll waiting for the activity to launch (this works for an arbitrary activity).
    // TODO: we should be able to eliminate this polling by making bound property a flow
    //  (basically making it listenable).
    repeat(200) {
        if (dialogModel.bound) {
            Logger.i(TAG, "Activity is bound to PromptModel UI")
            return
        }
        delay(20.milliseconds)
    }
    Logger.i(TAG, "Failed to bind to PromptModel UI")
     */
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
        initiallySelectedDocumentId: String?
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
            preferredServices = listOf(
                ComponentName(applicationContext, TestAppMdocNdefService::class.java)
            )
        )
    }

    actual suspend fun launchQuickAccessWallet(
        source: PresentmentSource,
        initiallySelectedDocumentId: String?
    ) {
        getPendingIntentForLaunchingQuickAccessWallet(source, initiallySelectedDocumentId).send()
    }
}
