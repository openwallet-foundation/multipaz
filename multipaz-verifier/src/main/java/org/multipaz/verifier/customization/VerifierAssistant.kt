package org.multipaz.verifier.customization

import kotlinx.io.bytestring.ByteString
import kotlinx.serialization.json.JsonObject

/**
 * In interface which is called when verifier request is created and response is received.
 */
interface VerifierAssistant {
    /**
     * Checks verifier request from the front-end to ensure that only valid requests are
     * built, signed and sent to the presenter (i.e. wallet app).
     *
     * @param request JSON request as sent by the front-end
     * @returns if non-null, rewritten request that will actually get processed
     */
    suspend fun processRequest(request: JsonObject): ExpandedRequest?

    /**
     * Processes a successful presentment.
     *
     * @param presentment interface that represents the request to the credential holder (e.g.
     *  the wallet app) and its response
     * @returns if non-null, the result that should be returned to the front-end
     */
    suspend fun processResponse(presentment: VerifierPresentment): JsonObject?

    class ExpandedRequest(
        val request: JsonObject,
        val nonce: ByteString?
    )
}