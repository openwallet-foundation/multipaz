package org.multipaz.records.server

import org.multipaz.server.common.runServer

/**
 * Main entry point to launch the server.
 *
 * Build and start the server using
 *
 * ```./gradlew multipaz-records-server:run```
 */
class Main {
    companion object {
        @JvmStatic
        fun main(args: Array<String>) {
            runServer(
                args = args,
                needAdminPassword = true
            ) { serverEnvironment ->
                configureRouting(serverEnvironment)
            }
        }
    }
}