package org.multipaz.testapp

import io.ktor.client.engine.HttpClientEngineFactory
import io.ktor.client.engine.cio.CIO
import multipazproject.samples.testapp.generated.resources.Res
import multipazproject.samples.testapp.generated.resources.app_icon
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.multipaz.digitalcredentials.DigitalCredentials
import org.multipaz.document.Document
import org.multipaz.facematch.FaceMatcher
import org.multipaz.presentment.PresentmentSource
import org.multipaz.util.Logger
import org.multipaz.util.Platform
import java.net.NetworkInterface
import java.security.Security

private const val TAG = "TestAppConfiguration.jvm"

actual object TestAppConfiguration {

    actual val appName = "Multipaz Test App"

    actual val appIcon = Res.drawable.app_icon

    actual val platform = TestAppPlatform.JVM

    actual val storage = Platform.nonBackedUpStorage

    actual val redirectPath: String = "/landing/"

    actual suspend fun init() {
    }

    actual suspend fun cryptoInit(settingsModel: TestAppSettingsModel) {
        if (settingsModel.cryptoPreferBouncyCastle.value) {
            Logger.i(TAG, "Forcing BouncyCastle to the top of the list")
            Security.removeProvider(BouncyCastleProvider.PROVIDER_NAME)
            Security.insertProviderAt(BouncyCastleProvider(), 1)
        }
    }

    actual fun restartApp() {
        Logger.w(TAG, "restartApp() is not supported on Desktop JVM; please restart the process.")
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
        "127.0.0.1"
    }

    actual val httpClientEngineFactory: HttpClientEngineFactory<*> by lazy {
        CIO
    }

    actual val platformSecureAreaHasKeyAgreement = true

    actual suspend fun getAppToAppOrigin(): String {
        return "http://localhost"
    }

    actual suspend fun launchQuickAccessWallet(
        source: PresentmentSource,
        initiallySelectedDocumentId: String?
    ) {
        throw NotImplementedError("QuickAccessWallet is not implemented on Desktop JVM")
    }

    actual suspend fun getDigitalCredentials(): DigitalCredentials? {
        return null
    }

    actual val supportedCreateProtocols: Set<String> = emptySet()

    actual suspend fun registerForCredentialOffers(protocols: Set<String>) {
    }

    actual fun getAndroidCredmanOmitClaims(document: Document): Boolean {
        return false
    }

    actual suspend fun setAndroidCredmanOmitClaims(document: Document, value: Boolean) {
        throw NotImplementedError("Not implemented on this platform")
    }

    actual fun getPlatformFaceMatchers(): List<FaceMatcher> = emptyList()
}
