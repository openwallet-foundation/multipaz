package org.multipaz.util

import kotlinx.browser.window
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.multipaz.prompt.PromptModel
import org.multipaz.prompt.WebPromptModel
import org.multipaz.securearea.SecureArea
import org.multipaz.securearea.software.SoftwareSecureArea
import org.multipaz.storage.Storage
import org.multipaz.storage.ephemeral.EphemeralStorage
import kotlin.getValue

actual object Platform {
    actual val name: String by lazy {
        "WebAssembly (${window.navigator.userAgent})"
    }

    actual val version: String
        get() = BuildConfig.VERSION

    actual val promptModel: PromptModel by lazy {
        WebPromptModel.Builder().apply { addCommonDialogs() }.build()
    }

    actual val storage: Storage by lazy {
        // TODO
        EphemeralStorage()
    }

    actual val nonBackedUpStorage: Storage by lazy {
        // TODO
        EphemeralStorage()
    }

    private var secureArea: SecureArea? = null
    private val secureAreaLock = Mutex()

    actual suspend fun getSecureArea(storage: Storage): SecureArea {
        // TODO
        secureAreaLock.withLock {
            if (secureArea == null) {
                secureArea = SoftwareSecureArea.create(storage)
            }
            return secureArea!!
        }
    }
}