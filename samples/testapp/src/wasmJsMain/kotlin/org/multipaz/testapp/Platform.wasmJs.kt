package org.multipaz.testapp

import io.ktor.client.engine.HttpClientEngineFactory
import io.ktor.client.engine.js.Js
import multipazproject.samples.testapp.generated.resources.Res
import multipazproject.samples.testapp.generated.resources.app_icon
import org.multipaz.nfc.NfcTagReader
import org.multipaz.prompt.PromptModel
import org.multipaz.prompt.WebPromptModel

actual val platformAppName = "Multipaz Test App"

actual val platformAppIcon = Res.drawable.app_icon

actual val platformPromptModel: PromptModel by lazy {
    WebPromptModel.Builder().apply { addCommonDialogs() }.build()
}

actual val platform = Platform.WASMJS

actual val platformRedirectPath: String = "/redirect/"

actual suspend fun platformInit() {
    // Nothing to do
}

actual suspend fun platformCryptoInit(settingsModel: TestAppSettingsModel) {
    // Nothing to do
}

actual fun platformRestartApp() {
    // Currently only needed on Android
    TODO()
}

actual fun getLocalIpAddress(): String {
    TODO("getLocalIpAddress() not yet implemented")
}


actual fun platformHttpClientEngineFactory(): HttpClientEngineFactory<*> = Js

actual val platformSecureAreaHasKeyAgreement = false

actual val platformIsEmulator: Boolean = false

actual suspend fun getAppToAppOrigin(): String {
    TODO("Add support for Web")
}

actual suspend fun getExternalNfcTagReaders(): List<NfcTagReader> = emptyList()
