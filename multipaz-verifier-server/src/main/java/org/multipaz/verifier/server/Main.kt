package org.multipaz.verifier.server

import org.multipaz.documenttype.DocumentTypeRepository
import org.multipaz.server.common.runServer
import org.multipaz.trustmanagement.TrustManagerInterface
import org.multipaz.verifier.customization.VerifierAssistant
import org.multipaz.verifier.request.documentTypeRepo
import org.multipaz.verifier.request.getIssuerTrustManager

/**
 * Main entry point to launch the server.
 *
 * Build and start the server using
 *
 * ```./gradlew multipaz-verifier-server:run```
 *
 * Build and start with local records server:
 *
 * ```./gradlew multipaz-verifier-server:run --args="-param enrollment_server_url=http://localhost:8004"```
 */
class Main {
    companion object {
        @JvmStatic
        fun main(args: Array<String>) {
            runServer(args, environmentInitializer = {
                add(DocumentTypeRepository::class, documentTypeRepo)
                add(TrustManagerInterface::class, getIssuerTrustManager())
                add(VerifierAssistant::class, VerifierAssistantImpl)
            }) { environment ->
                configureRouting(environment)
            }
        }
    }
}