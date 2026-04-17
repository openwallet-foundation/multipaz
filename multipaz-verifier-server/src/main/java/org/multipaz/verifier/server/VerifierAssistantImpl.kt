package org.multipaz.verifier.server

import kotlinx.serialization.json.JsonObject
import org.multipaz.util.Logger
import org.multipaz.verifier.customization.VerifierAssistant
import org.multipaz.verifier.customization.VerifierPresentment

internal object VerifierAssistantImpl: VerifierAssistant {
    // accept all transactions for testing
    override suspend fun processRequest(request: JsonObject) = null

    override suspend fun processResponse(
        presentment: VerifierPresentment
    ): JsonObject? {
        // no actual processing, just print
        Logger.i("VerifierAssistant", "Result: ${presentment.response}")
        return null
    }
}