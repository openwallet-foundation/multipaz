package org.multipaz.backend.server

import org.multipaz.server.common.runServer

/**
 * Main entry point to launch the Multipaz back-end server.
 *
 * Build and start the server using
 *
 * ```./gradlew multipaz-backend-server:run```
 */
class Main {
    companion object {
        @JvmStatic
        fun main(args: Array<String>) {
            runServer(args) { configuration ->
                configureRouting(configuration)
            }
        }
    }
}