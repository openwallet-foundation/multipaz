package org.multipaz.verifier.server

import kotlinx.io.bytestring.ByteString
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import org.multipaz.server.common.runServer
import org.multipaz.util.Logger
import org.multipaz.verifier.customization.VerifierAssistant
import org.multipaz.verifier.customization.VerifierRequest
import org.multipaz.verifier.customization.VerifierResponse

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
            runServer(args, environmentInitializer = {
                add(VerifierAssistant::class, object: VerifierAssistant {
                    override suspend fun checkRequest(request: VerifierRequest) {
                        // accept all transactions for testing
                    }

                    override suspend fun processResponse(
                        request: VerifierRequest,
                        response: VerifierResponse
                    ): JsonObject? {
                        // no actual processing, just print
                        Logger.i("VerifierAssistant", "Result: ${response.response}")
                        return null
                    }
                })
            }) { environment ->
                configureRouting(environment)
            }
        }
    }
}