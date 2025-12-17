package org.multipaz.verifier.server

import org.multipaz.server.common.runServer

/**
 * Main entry point to launch the server.
 *
 * Build and start the server using
 *
 * ```./gradlew multipaz-verifier-server:run```
 */
class Main {
    companion object {
        @JvmStatic
        fun main(args: Array<String>) {
            runServer(args) { environment ->
                configureRouting(environment)
            }
        }
    }
}