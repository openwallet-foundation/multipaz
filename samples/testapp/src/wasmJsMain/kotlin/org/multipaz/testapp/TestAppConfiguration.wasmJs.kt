package org.multipaz.testapp

import io.ktor.client.engine.HttpClientEngineFactory
import io.ktor.client.engine.js.Js
import multipazproject.samples.testapp.generated.resources.Res
import multipazproject.samples.testapp.generated.resources.app_icon
import org.multipaz.nfc.NfcTagReader
import org.multipaz.prompt.PromptModel
import org.multipaz.prompt.WebPromptModel
import org.multipaz.util.Platform

actual object TestAppConfiguration {

    actual val appName = "Multipaz Test App"

    actual val appIcon = Res.drawable.app_icon

    actual val promptModel: PromptModel by lazy {
        WebPromptModel.Builder().apply { addCommonDialogs() }.build()
    }

    actual val platform = TestAppPlatform.WASMJS

    actual val storage = Platform.nonBackedUpStorage

    actual val redirectPath: String = "/landing/"

    actual suspend fun init() {
        // Nothing to do.
    }

    actual suspend fun cryptoInit(settingsModel: TestAppSettingsModel) {
        // Nothing to do.
    }

    actual fun restartApp() {
        // Currently only needed on Android so no need to implement for now.
        TODO()
    }

    actual val localIpAddress: String by lazy {
        TODO("localIpAddress not implemented")
    }

    actual val httpClientEngineFactory: HttpClientEngineFactory<*> by lazy {
        Js
    }

    actual val platformSecureAreaHasKeyAgreement = true

    actual suspend fun getAppToAppOrigin(): String {
        TODO("Add support for WasmJS")
    }

    actual suspend fun getExternalNfcTagReaders(): List<NfcTagReader> = emptyList()
}