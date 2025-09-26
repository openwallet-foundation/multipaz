package org.multipaz.testapp

import androidx.sqlite.SQLiteConnection
import androidx.sqlite.driver.NativeSQLiteDriver
import io.ktor.client.engine.HttpClientEngineFactory
import io.ktor.client.engine.cio.CIO
import io.ktor.client.engine.darwin.Darwin
import org.multipaz.crypto.EcCurve
import org.multipaz.securearea.CreateKeySettings
import org.multipaz.securearea.PassphraseConstraints
import org.multipaz.securearea.SecureArea
import org.multipaz.securearea.SecureAreaProvider
import org.multipaz.securearea.SecureEnclaveCreateKeySettings
import org.multipaz.securearea.SecureEnclaveSecureArea
import org.multipaz.securearea.SecureEnclaveUserAuthType
import org.multipaz.securearea.software.SoftwareCreateKeySettings
import org.multipaz.securearea.software.SoftwareSecureArea
import org.multipaz.storage.Storage
import org.multipaz.storage.sqlite.SqliteStorage
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.newSingleThreadContext
import kotlin.time.Instant
import kotlinx.io.bytestring.ByteString
import multipazproject.samples.testapp.generated.resources.Res
import multipazproject.samples.testapp.generated.resources.app_icon
import org.jetbrains.compose.resources.painterResource
import org.multipaz.compose.decodeImage
import org.multipaz.crypto.Algorithm
import org.multipaz.mdoc.zkp.ZkSystemRepository
import org.multipaz.nfc.NfcTagReader
import org.multipaz.prompt.IosPromptModel
import org.multipaz.prompt.PromptModel
import platform.Foundation.NSDocumentDirectory
import platform.Foundation.NSFileManager
import platform.Foundation.NSUserDomainMask
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

actual val platformAppName = "Multipaz Test App"

actual val platformAppIcon = Res.drawable.app_icon

actual val platformPromptModel: PromptModel by lazy {
    IosPromptModel()
}

actual val platform = Platform.IOS

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

@OptIn(ExperimentalForeignApi::class)
actual fun getLocalIpAddress(): String {
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
                    inet_ntop(it.pointed.sa_family.convert(), addr.ptr, dst, len.convert())?.toKString()
                }
            }
            .toList()
    } finally {
        freeifaddrs(interfaces)
    }

    for (address in addresses) {
        if (address.startsWith("192.168") || address.startsWith("10.") || address.startsWith("172.")) {
            return address
        }
    }
    throw IllegalStateException("Unable to determine local address")
}

@OptIn(ExperimentalForeignApi::class)
private fun openDatabase(): SQLiteConnection {
    val fileManager = NSFileManager.defaultManager
    val rootPath = fileManager.URLForDirectory(
        NSDocumentDirectory,
        NSUserDomainMask,
        appropriateForURL = null,
        create = false,
        error = null)
        ?: throw RuntimeException("could not get documents directory url")
    println("Root path: $rootPath")
    return NativeSQLiteDriver().open(rootPath.path() + "/storage.db")
}

actual fun platformHttpClientEngineFactory(): HttpClientEngineFactory<*> = Darwin

actual val platformSecureAreaHasKeyAgreement = true

actual fun getAppToAppOrigin(): String {
    TODO("Add support for iOS")
}

actual suspend fun getExternalNfcTagReaders(): List<NfcTagReader> = emptyList()
