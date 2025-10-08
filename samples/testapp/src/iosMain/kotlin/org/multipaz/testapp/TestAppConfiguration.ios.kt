package org.multipaz.testapp

import androidx.sqlite.driver.NativeSQLiteDriver
import io.ktor.client.engine.HttpClientEngineFactory
import io.ktor.client.engine.darwin.Darwin
import org.multipaz.storage.Storage
import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.allocPointerTo
import kotlinx.cinterop.convert
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.pointed
import kotlinx.cinterop.ptr
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.toKString
import kotlinx.cinterop.value
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.newSingleThreadContext
import multipazproject.samples.testapp.generated.resources.Res
import multipazproject.samples.testapp.generated.resources.app_icon
import org.multipaz.nfc.NfcTagReader
import org.multipaz.prompt.IosPromptModel
import org.multipaz.prompt.PromptModel
import org.multipaz.storage.ios.IosStorage
import org.multipaz.storage.sqlite.SqliteStorage
import platform.Foundation.NSFileManager
import platform.Foundation.NSURLIsExcludedFromBackupKey
import platform.darwin.freeifaddrs
import platform.darwin.getifaddrs
import platform.darwin.ifaddrs
import platform.darwin.inet_ntop
import platform.posix.AF_INET
import platform.posix.AF_INET6
import platform.posix.INET_ADDRSTRLEN
import platform.posix.INET6_ADDRSTRLEN
import platform.posix.sa_family_t
import platform.posix.sockaddr_in
import platform.posix.sockaddr_in6

actual object TestAppConfiguration {

    actual val appName = "Multipaz Test App"

    actual val appIcon = Res.drawable.app_icon

    actual val promptModel: PromptModel by lazy {
        IosPromptModel.Builder().apply { addCommonDialogs() }.build()
    }

    actual val platform = TestAppPlatform.IOS

    @OptIn(
        DelicateCoroutinesApi::class,
        ExperimentalForeignApi::class,
        ExperimentalCoroutinesApi::class
    )
    actual val storage: Storage = IosStorage(
        storageFileUrl = NSFileManager.defaultManager.containerURLForSecurityApplicationGroupIdentifier(
            groupIdentifier = "group.org.multipaz.testapp.sharedgroup"
        )!!.URLByAppendingPathComponent("storageNoBackup.db")!!,
        excludeFromBackup = true
    )

    // No flavors for iOS, just use generic name.
    // TODO: replace with "/redirect/" for consistency with Android once we configure it on the server.
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

    @OptIn(ExperimentalForeignApi::class)
    actual val localIpAddress: String by lazy {
        val (status, interfaces) = memScoped {
            val ifap = allocPointerTo<ifaddrs>()
            getifaddrs(ifap.ptr) to ifap.value
        }
        if (status != 0) {
            freeifaddrs(interfaces)
            throw IllegalStateException("getifaddrs() returned $status, expected 0")
        }
        val addresses = try {
            generateSequence(interfaces) { it.pointed.ifa_next }
                .mapNotNull { it.pointed.ifa_addr }
                .mapNotNull {
                    val addr = when (it.pointed.sa_family) {
                        AF_INET.convert<sa_family_t>() -> it.reinterpret<sockaddr_in>().pointed.sin_addr
                        AF_INET6.convert<sa_family_t>() -> it.reinterpret<sockaddr_in6>().pointed.sin6_addr
                        else -> return@mapNotNull null
                    }
                    memScoped {
                        val len = maxOf(INET_ADDRSTRLEN, INET6_ADDRSTRLEN)
                        val dst = allocArray<ByteVar>(len)
                        inet_ntop(
                            it.pointed.sa_family.convert(),
                            addr.ptr,
                            dst,
                            len.convert()
                        )?.toKString()
                    }
                }
                .toList()
        } finally {
            freeifaddrs(interfaces)
        }

        for (address in addresses) {
            if (address.startsWith("192.168") || address.startsWith("10.") || address.startsWith("172.")) {
                address
            }
        }
        throw IllegalStateException("Unable to determine local address")
    }

    actual val httpClientEngineFactory: HttpClientEngineFactory<*> by lazy {
        Darwin
    }

    actual val platformSecureAreaHasKeyAgreement = true

    actual suspend fun getAppToAppOrigin(): String {
        TODO("Add support for iOS")
    }

    actual suspend fun getExternalNfcTagReaders(): List<NfcTagReader> = emptyList()
}