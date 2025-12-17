package org.multipaz.openid4vci.server

import org.multipaz.server.common.ServerConfiguration
import org.multipaz.server.common.runServer

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
            runServer(
                args = args,
                needAdminPassword = true,
                checkConfiguration = ::checkConfiguration
            ) { serverEnvironment ->
                configureRouting(serverEnvironment)
            }
        }

        private fun checkConfiguration(configuration: ServerConfiguration) {
            val supportClientAssertion = configuration.getValue("support_client_assertion") != "false"
            val supportClientAttestation = configuration.getValue("support_client_attestation") != "false"
            if (!supportClientAssertion && !supportClientAttestation) {
                throw IllegalArgumentException("No client authentication methods supported")
            }
        }
    }
}