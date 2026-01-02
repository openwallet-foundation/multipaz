package org.multipaz.testapp

import io.ktor.client.engine.HttpClientEngineFactory
import org.jetbrains.compose.resources.DrawableResource
import org.multipaz.nfc.NfcTagReader
import org.multipaz.prompt.PromptModel

enum class Platform(val displayName: String) {
    ANDROID("Android"),
    IOS("iOS"),
    WASMJS("WasmJs")
}

expect val platformAppName: String

expect val platformAppIcon: DrawableResource

expect val platformRedirectPath: String

expect val platformPromptModel: PromptModel

expect val platform: Platform

expect suspend fun platformInit()

expect suspend fun platformCryptoInit(settingsModel: TestAppSettingsModel)

expect fun getLocalIpAddress(): String

expect val platformIsEmulator: Boolean

expect fun platformHttpClientEngineFactory(): HttpClientEngineFactory<*>

expect fun platformRestartApp()

expect val platformSecureAreaHasKeyAgreement: Boolean

expect suspend fun getAppToAppOrigin(): String

expect suspend fun getExternalNfcTagReaders(): List<NfcTagReader>
