package org.multipaz.server

import io.ktor.client.HttpClient
import io.ktor.client.engine.java.Java
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.io.bytestring.ByteString
import org.multipaz.rpc.backend.BackendEnvironment
import org.multipaz.rpc.backend.Configuration
import org.multipaz.rpc.backend.Resources
import org.multipaz.rpc.handler.AesGcmCipher
import org.multipaz.rpc.handler.RpcAuthInspector
import org.multipaz.rpc.handler.RpcAuthInspectorAssertion
import org.multipaz.rpc.handler.RpcNotifications
import org.multipaz.rpc.handler.RpcNotificationsLocalPoll
import org.multipaz.rpc.handler.SimpleCipher
import org.multipaz.securearea.SecureArea
import org.multipaz.securearea.SecureAreaProvider
import org.multipaz.securearea.SecureAreaRepository
import org.multipaz.securearea.software.SoftwareSecureArea
import org.multipaz.storage.Storage
import org.multipaz.storage.StorageTableSpec
import org.multipaz.storage.ephemeral.EphemeralStorage
import org.multipaz.storage.jdbc.JdbcStorage
import java.io.File
import kotlin.random.Random
import kotlin.reflect.KClass
import kotlin.reflect.cast

/**
 * [BackendEnvironment] implementation for the server.
 */
class ServerEnvironment(
    private val configuration: Configuration,
    private val storage: Storage,
    private val httpClient: HttpClient,
    private val secureAreaProvider: SecureAreaProvider<SecureArea>,
    private val secureAreaRepository: SecureAreaRepository,
    val notifications: RpcNotificationsLocalPoll,
    val cipher: SimpleCipher
): BackendEnvironment {

    override fun <T : Any> getInterface(clazz: KClass<T>): T? {
        return clazz.cast(when(clazz) {
            Configuration::class -> configuration
            Resources::class -> ServerResources
            Storage::class -> storage
            RpcNotifications::class -> notifications
            HttpClient::class -> httpClient
            SecureAreaProvider::class -> secureAreaProvider
            SecureAreaRepository::class -> secureAreaRepository
            RpcAuthInspector::class -> RpcAuthInspectorAssertion.Default
            SimpleCipher::class -> cipher
            else -> return null
        })
    }
    
    companion object {
        private val flowRootStateTableSpec = StorageTableSpec(
            name = "RpcRootState",
            supportPartitions = false,
            supportExpiration = false
        )

        fun create(configuration: Configuration): Deferred<ServerEnvironment> {
            return CoroutineScope(Dispatchers.Default).async {
                initialize(configuration)
            }
        }

        private suspend fun initialize(
            configuration: Configuration,
            additionalSecureAreas: List<SecureAreaProvider<SecureArea>> = listOf()
        ): ServerEnvironment {

            val storage = when (val engine = configuration.getValue("database_engine")) {
                "jdbc", null -> JdbcStorage(
                    configuration.getValue("database_connection") ?: defaultDatabase(),
                    configuration.getValue("database_user") ?: "",
                    configuration.getValue("database_password") ?: ""
                )
                "ephemeral" -> EphemeralStorage()
                else -> throw IllegalArgumentException("Unknown database engine: $engine")
            }

            val httpClient = HttpClient(Java) {
                followRedirects = false
            }

            val secureAreaProvider = SecureAreaProvider(Dispatchers.Default) {
                SoftwareSecureArea.create(storage)
            }

            val secureAreaRepository = SecureAreaRepository.Builder()
                .add(secureAreaProvider.get())
                .also {
                    for (secureArea in additionalSecureAreas) {
                        it.add(secureArea.get())
                    }
                }
                .build()

            val table = storage.getTable(flowRootStateTableSpec)
            val key = table.get("messageEncryptionKey")
            val messageEncryptionKey = if (key != null) {
                key.toByteArray()
            } else {
                val newKey = Random.nextBytes(16)
                table.insert(
                    key = "messageEncryptionKey",
                    data = ByteString(newKey),
                )
                newKey
            }
            val cipher = AesGcmCipher(messageEncryptionKey)

            val localPoll = RpcNotificationsLocalPoll(cipher)

            val env = ServerEnvironment(
                configuration,
                storage,
                httpClient,
                secureAreaProvider,
                secureAreaRepository,
                localPoll,
                cipher
            )

            return env
        }

        private fun defaultDatabase(): String {
            val dbFile = File("environment/db/db.hsqldb").absoluteFile
            if (!dbFile.canRead()) {
                val parent = File(dbFile.parent)
                if (!parent.exists()) {
                    if (!parent.mkdirs()) {
                        throw Exception("Cannot create database folder ${parent.absolutePath}")
                    }
                }
            }
            return "jdbc:hsqldb:file:${dbFile.absolutePath}"
        }
    }
}