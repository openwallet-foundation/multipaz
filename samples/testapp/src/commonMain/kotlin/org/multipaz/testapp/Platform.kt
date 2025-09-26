package org.multipaz.testapp

import androidx.compose.ui.graphics.painter.Painter
import io.ktor.client.engine.HttpClientEngineFactory
import org.multipaz.securearea.CreateKeySettings
import org.multipaz.securearea.SecureArea
import org.multipaz.securearea.SecureAreaProvider
import org.multipaz.storage.Storage
import kotlin.time.Instant
import kotlinx.io.bytestring.ByteString
import org.jetbrains.compose.resources.DrawableResource
import org.multipaz.crypto.Algorithm
import org.multipaz.nfc.NfcTagReader
import org.multipaz.prompt.PromptModel

enum class Platform(val displayName: String) {
    ANDROID("Android"),
    IOS("iOS")
}

expect val platformAppName: String

expect val platformAppIcon: DrawableResource

expect val platformPromptModel: PromptModel

expect val platform: Platform

expect suspend fun platformInit()

expect suspend fun platformCryptoInit(settingsModel: TestAppSettingsModel)

expect fun getLocalIpAddress(): String

expect val platformIsEmulator: Boolean

expect fun platformHttpClientEngineFactory(): HttpClientEngineFactory<*>

expect fun platformRestartApp()

expect val platformSecureAreaHasKeyAgreement: Boolean

expect fun getAppToAppOrigin(): String

expect suspend fun getExternalNfcTagReaders(): List<NfcTagReader>
