package org.multipaz.util

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.multipaz.prompt.JvmPromptModel
import org.multipaz.prompt.PromptModel
import org.multipaz.securearea.SecureArea
import org.multipaz.securearea.software.SoftwareSecureArea
import org.multipaz.storage.Storage
import org.multipaz.storage.ephemeral.EphemeralStorage
import org.multipaz.storage.jdbc.JdbcStorage
import java.io.File
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermissions

actual object Platform {
    actual val name = "JVM ${System.getProperty("java.vm.version")} / Java ${System.getProperty("java.version")}"

    actual val version = BuildConfig.VERSION

    actual val promptModel: PromptModel by lazy {
        JvmPromptModel.Builder().apply { addCommonDialogs() }.build()
    }

    private fun restrictPosixPermissions(file: File, perms: String) {
        try {
            if (file.exists()) {
                Files.setPosixFilePermissions(file.toPath(), PosixFilePermissions.fromString(perms))
            }
        } catch (_: UnsupportedOperationException) {
            // Non-POSIX filesystem (e.g. Windows NTFS)
        } catch (_: Throwable) {
        }
    }

    private fun createDefaultStorage(filename: String): Storage {
        return try {
            Class.forName("org.sqlite.JDBC")
            val baseDir = System.getenv("XDG_DATA_HOME")?.takeIf { it.isNotBlank() }
                ?.let { File(it, "multipaz") }
                ?: File(System.getProperty("user.home", "."), ".local/share/multipaz")
            baseDir.mkdirs()
            restrictPosixPermissions(baseDir, "rwx------")
            val dbFile = File(baseDir, filename)
            if (!dbFile.exists()) {
                dbFile.createNewFile()
            }
            restrictPosixPermissions(dbFile, "rw-------")
            JdbcStorage("jdbc:sqlite:${dbFile.absolutePath}")
        } catch (_: Throwable) {
            EphemeralStorage()
        }
    }

    actual val storage: Storage by lazy {
        createDefaultStorage("storage.db")
    }

    actual val nonBackedUpStorage: Storage by lazy {
        createDefaultStorage("storage_no_backup.db")
    }

    private var secureArea: SecureArea? = null
    private val secureAreaLock = Mutex()

    actual suspend fun getSecureArea(storage: Storage): SecureArea {
        secureAreaLock.withLock {
            if (secureArea == null) {
                secureArea = SoftwareSecureArea.create(storage)
            }
            return secureArea!!
        }
    }
}
