package org.multipaz.server.common

import io.ktor.client.HttpClient
import io.ktor.client.engine.java.Java
import io.ktor.client.plugins.HttpTimeout
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.withContext
import org.multipaz.rpc.backend.BackendEnvironment
import org.multipaz.rpc.backend.Configuration
import org.multipaz.rpc.backend.Resources
import org.multipaz.rpc.handler.AesGcmCipher
import org.multipaz.rpc.handler.RpcAuthInspector
import org.multipaz.rpc.handler.RpcAuthInspectorAssertion
import org.multipaz.rpc.handler.RpcNotifications
import org.multipaz.rpc.handler.RpcNotificationsLocalPoll
import org.multipaz.rpc.handler.RpcPoll
import org.multipaz.rpc.handler.SimpleCipher
import org.multipaz.securearea.SecureArea
import org.multipaz.securearea.SecureAreaProvider
import org.multipaz.securearea.SecureAreaRepository
import org.multipaz.securearea.software.SoftwareSecureArea
import org.multipaz.storage.Storage
import org.multipaz.storage.ephemeral.EphemeralStorage
import org.multipaz.storage.jdbc.JdbcStorage
import java.io.File
import kotlin.reflect.KClass
import kotlin.reflect.cast

/**
 * [BackendEnvironment] implementation for the server.
 */
class ServerEnvironment(): BackendEnvironment {
    private val instances = mutableMapOf<KClass<*>, Any>()

    override fun <T : Any> getInterface(clazz: KClass<T>): T? {
        return instances[clazz]?.let { clazz.cast(it) }
    }

    private class Initializer(
        private val instances: MutableMap<KClass<*>, Any>
    ): ServerEnvironmentInitializer {
        override fun<T: Any> add(clazz: KClass<T>, instance: T) {
            check(!instances.containsKey(clazz))
            instances[clazz] = instance
        }
    }

    companion object {
        /**
         * Creates and asynchronously initializes a [ServerEnvironment].
         *
         * Initialization sets up storage (JDBC or ephemeral based on `database_engine` config),
         * an HTTP client, a [SoftwareSecureArea], RPC cipher and notification infrastructure,
         * and then runs the caller-provided [initializer] for server-specific setup.
         *
         * @param configuration server configuration to use.
         * @param initializer optional block for registering additional interfaces into the
         *  environment via [ServerEnvironmentInitializer.add].
         * @return a [Deferred] that completes when initialization is finished.
         */
        fun create(
            configuration: Configuration,
            initializer: suspend ServerEnvironmentInitializer.() -> Unit = {}
        ): Deferred<ServerEnvironment> {
            return CoroutineScope(Dispatchers.Default).async {
                initialize(configuration, initializer = initializer)
            }
        }

        private suspend fun initialize(
            configuration: Configuration,
            additionalSecureAreas: List<SecureAreaProvider<SecureArea>> = listOf(),
            initializer: suspend ServerEnvironmentInitializer.() -> Unit
        ): ServerEnvironment {
            val env = ServerEnvironment()
            val init = Initializer(env.instances)
            init.add(Configuration::class, configuration)
            init.add(Resources::class, ServerResources)
            init.add(RpcAuthInspector::class, RpcAuthInspectorAssertion.Default)

            withContext(env) {
                val storage = when (val engine = configuration.getValue("database_engine")) {
                    "jdbc", null -> JdbcStorage(
                        configuration.getValue("database_connection") ?: defaultDatabase(),
                        configuration.getValue("database_user") ?: "",
                        configuration.getValue("database_password") ?: ""
                    )

                    "ephemeral" -> EphemeralStorage()
                    else -> throw IllegalArgumentException("Unknown database engine: $engine")
                }
                init.add(Storage::class, storage)

                val httpClient = HttpClient(Java) {
                    install(HttpTimeout)
                    followRedirects = false
                }
                init.add(HttpClient::class, httpClient)

                val secureAreaProvider = SecureAreaProvider(Dispatchers.Default) {
                    SoftwareSecureArea.create(storage)
                }
                init.add(SecureAreaProvider::class, secureAreaProvider)

                val secureAreaRepository = SecureAreaRepository.Builder()
                    .add(secureAreaProvider.get())
                    .also {
                        for (secureArea in additionalSecureAreas) {
                            it.add(secureArea.get())
                        }
                    }
                    .build()
                init.add(SecureAreaRepository::class, secureAreaRepository)

                val messageEncryptionKey = persistentServerKey(name = "rpc")
                val cipher = AesGcmCipher(messageEncryptionKey.toByteArray())
                init.add(SimpleCipher::class, cipher)

                val localPoll = RpcNotificationsLocalPoll(cipher)
                init.add(RpcPoll::class, localPoll)
                init.add(RpcNotifications::class, localPoll)

                initializer.invoke(init)
            }

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