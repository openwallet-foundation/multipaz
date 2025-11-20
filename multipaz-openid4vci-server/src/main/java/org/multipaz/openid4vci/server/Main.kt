package org.multipaz.openid4vci.server

import io.ktor.server.application.install
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.plugins.callloging.CallLogging
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.multipaz.server.ServerConfiguration
import org.multipaz.server.serverHost
import org.multipaz.server.serverPort
import org.multipaz.util.Logger

/**
 * Main entry point to launch the server.
 *
 * Build and start the server using
 *
 * ```
 * ./gradlew multipaz-openid4vci-server:run
 * ```
 *
 * or with a System of Record back-end:
 *
 * ```
 * ./gradlew multipaz-openid4vci-server:run --args="-param system_of_record_url=http://localhost:8004 -param system_of_record_jwk='$(cat key.jwk)'"
 * ```
 */
class Main {
    companion object {
        @JvmStatic
        fun main(args: Array<String>) {
            val configuration = ServerConfiguration(args)
            val jdbc = configuration.getValue("database_connection")
            if (jdbc != null) {
                if (jdbc.startsWith("jdbc:mysql:")) {
                    Logger.i("Main", "SQL driver: ${com.mysql.cj.jdbc.Driver()}")
                } else if (jdbc.startsWith("jdbc:postgresql:")) {
                    Logger.i("Main", "SQL driver: ${org.postgresql.Driver()}")
                }
            }
            val host = configuration.serverHost ?: "0.0.0.0"
            embeddedServer(Netty, port = configuration.serverPort, host = host, module = {
                install(CallLogging)
                traceCalls(configuration)
                configureRouting(configuration)
            }).start(wait = true)
        }
    }
}