package org.multipaz.util

import kotlinx.browser.window
import org.multipaz.prompt.PromptModel
import org.multipaz.securearea.SecureArea
import org.multipaz.storage.Storage

actual object Platform {
    actual val name: String by lazy {
        "JavaScript (${window.navigator.userAgent})"
    }

    actual val version: String
        get() = BuildConfig.VERSION

    actual val promptModel: PromptModel
        get() = TODO("Platform.promptModel not yet implemented")

    actual val storage: Storage
        get() = TODO("Platform.storage not yet implemented")

    actual val nonBackedUpStorage: Storage
        get() = TODO("Platform.nonBackedUpStorage not yet implemented")

    actual suspend fun getSecureArea(storage: Storage): SecureArea {
        TODO("Platform.getSecureArea not yet implemented")
    }
}