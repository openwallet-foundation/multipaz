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
import java.io.IOException
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.attribute.PosixFilePermissions
import java.util.WeakHashMap

actual object Platform {
    private const val TAG = "Platform"

    actual val name = "JVM ${System.getProperty("java.vm.version")} / Java ${System.getProperty("java.version")}"

    actual val version = BuildConfig.VERSION

    actual val promptModel: PromptModel by lazy {
        JvmPromptModel.Builder().apply { addCommonDialogs() }.build()
    }

    private fun ensureSecureDirectory(dir: File) {
        val dirPath = dir.toPath()
        if (Files.isSymbolicLink(dirPath)) {
            throw IOException("Refusing to use symlinked storage directory: $dirPath")
        }
        val posixPerms = PosixFilePermissions.fromString("rwx------")
        try {
            Files.createDirectories(dirPath, PosixFilePermissions.asFileAttribute(posixPerms))
            if (Files.isSymbolicLink(dirPath)) {
                throw IOException("Refusing to use symlinked storage directory: $dirPath")
            }
            Files.setPosixFilePermissions(dirPath, posixPerms)
        } catch (_: UnsupportedOperationException) {
            // Non-POSIX filesystem (e.g. Windows NTFS)
            if (!dir.exists() && !dir.mkdirs() && !dir.isDirectory) {
                throw IOException("Failed to create storage directory: $dir")
            }
            dir.setReadable(false, false)
            dir.setWritable(false, false)
            dir.setExecutable(false, false)
            dir.setReadable(true, true)
            dir.setWritable(true, true)
            dir.setExecutable(true, true)
        }
    }

    private fun ensureSecureFile(file: File) {
        val filePath = file.toPath()
        if (Files.isSymbolicLink(filePath)) {
            throw IOException("Refusing to use symlinked storage file: $filePath")
        }
        val posixPerms = PosixFilePermissions.fromString("rw-------")
        try {
            if (!Files.exists(filePath, LinkOption.NOFOLLOW_LINKS)) {
                try {
                    Files.createFile(filePath, PosixFilePermissions.asFileAttribute(posixPerms))
                } catch (_: FileAlreadyExistsException) {
                }
            }
            if (Files.isSymbolicLink(filePath)) {
                throw IOException("Refusing to use symlinked storage file: $filePath")
            }
            Files.setPosixFilePermissions(filePath, posixPerms)
        } catch (_: UnsupportedOperationException) {
            // Non-POSIX filesystem (e.g. Windows NTFS)
            if (!file.exists()) {
                file.createNewFile()
            }
            file.setReadable(false, false)
            file.setWritable(false, false)
            file.setExecutable(false, false)
            file.setReadable(true, true)
            file.setWritable(true, true)
        }
    }

    private fun createDefaultStorage(filename: String): Storage {
        return try {
            Class.forName("org.sqlite.JDBC")
            // Per XDG Base Directory Specification, relative paths in XDG_DATA_HOME must be ignored.
            val xdgDataHome = System.getenv("XDG_DATA_HOME")
                ?.takeIf { it.isNotBlank() }
                ?.let { File(it) }
                ?.takeIf { it.isAbsolute }
            val baseDir = if (xdgDataHome != null) {
                File(xdgDataHome, "multipaz")
            } else {
                File(System.getProperty("user.home", "."), ".local/share/multipaz")
            }
            ensureSecureDirectory(baseDir)
            val dbFile = File(baseDir, filename)
            ensureSecureFile(dbFile)
            JdbcStorage("jdbc:sqlite:${dbFile.absolutePath}")
        } catch (_: ClassNotFoundException) {
            Logger.w(TAG, "org.sqlite.JDBC driver not found on classpath; falling back to EphemeralStorage ($filename)")
            EphemeralStorage()
        } catch (e: Exception) {
            Logger.w(TAG, "Failed to initialize persistent JdbcStorage ($filename); falling back to EphemeralStorage", e)
            EphemeralStorage()
        }
    }

    actual val storage: Storage by lazy {
        createDefaultStorage("storage.db")
    }

    actual val nonBackedUpStorage: Storage by lazy {
        createDefaultStorage("storage_no_backup.db")
    }

    private val secureAreas = WeakHashMap<Storage, SecureArea>()
    private val secureAreaLock = Mutex()

    actual suspend fun getSecureArea(storage: Storage): SecureArea {
        secureAreaLock.withLock {
            return secureAreas.getOrPut(storage) {
                SoftwareSecureArea.create(storage)
            }
        }
    }
}
