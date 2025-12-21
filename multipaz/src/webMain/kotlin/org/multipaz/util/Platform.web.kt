package org.multipaz.util

import org.multipaz.prompt.PromptModel
import org.multipaz.securearea.SecureArea
import org.multipaz.storage.Storage

actual object Platform {
    actual val name: String
        get() = TODO("Not yet implemented")
    actual val version: String
        get() = TODO("Not yet implemented")
    actual val promptModel: PromptModel
        get() = TODO("Not yet implemented")
    actual val storage: Storage
        get() = TODO("Not yet implemented")
    actual val nonBackedUpStorage: Storage
        get() = TODO("Not yet implemented")

    actual suspend fun getSecureArea(): SecureArea {
        TODO("Not yet implemented")
    }
}