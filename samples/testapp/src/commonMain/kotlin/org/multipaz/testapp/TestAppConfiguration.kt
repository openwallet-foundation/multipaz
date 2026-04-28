package org.multipaz.testapp

import io.ktor.client.engine.HttpClientEngineFactory
import org.jetbrains.compose.resources.DrawableResource
import org.multipaz.document.Document
import org.multipaz.document.DocumentBadge
import org.multipaz.presentment.PresentmentSource
import org.multipaz.storage.Storage

enum class TestAppPlatform(val displayName: String) {
    ANDROID("Android"),
    IOS("iOS"),
    WASMJS("WasmJS")
}

expect object TestAppConfiguration {

    val appName: String

    val appIcon: DrawableResource

    val platform: TestAppPlatform

    val storage: Storage

    val redirectPath: String

    suspend fun init()

    suspend fun cryptoInit(settingsModel: TestAppSettingsModel)

    val localIpAddress: String

    val httpClientEngineFactory: HttpClientEngineFactory<*>

    fun restartApp()

    val platformSecureAreaHasKeyAgreement: Boolean

    suspend fun getAppToAppOrigin(): String

    suspend fun launchQuickAccessWallet(
        source: PresentmentSource,
        initiallySelectedDocumentId: String?
    )
}