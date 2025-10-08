package org.multipaz.util

import android.os.Build
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.multipaz.context.applicationContext
import org.multipaz.prompt.AndroidPromptModel
import org.multipaz.prompt.PromptModel
import org.multipaz.securearea.AndroidKeystoreSecureArea
import org.multipaz.securearea.SecureArea
import org.multipaz.storage.Storage
import org.multipaz.storage.android.AndroidStorage
import org.multipaz.storage.ephemeral.EphemeralStorage
import java.io.File

actual object Platform {
    actual val name = "Android ${Build.VERSION.SDK_INT}"

    actual val version = BuildConfig.VERSION

    actual val promptModel: PromptModel by lazy {
        AndroidPromptModel.Builder().apply { addCommonDialogs() }.build()
    }

    actual val storage by lazy {
        AndroidStorage(File(applicationContext.dataDir.path, "storage.db").absolutePath) as Storage
    }

    actual val nonBackedUpStorage by lazy {
        AndroidStorage(File(applicationContext.noBackupFilesDir.path, "storage.db").absolutePath) as Storage
    }

    private var secureArea: SecureArea? = null
    private val secureAreaLock = Mutex()

    actual suspend fun getSecureArea(storage: Storage): SecureArea {
        secureAreaLock.withLock {
            if (secureArea == null) {
                secureArea = AndroidKeystoreSecureArea.create(storage)
            }
            return secureArea!!
        }
    }
}