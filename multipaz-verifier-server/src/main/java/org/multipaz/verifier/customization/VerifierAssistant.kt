package org.multipaz.verifier.customization

import kotlinx.serialization.json.JsonObject

/**
 * In interface which is called when verifier request is created and response is received.
 */
interface VerifierAssistant {
    /**
     * Checks verifier request from the front-end to ensure that only valid requests are
     * built, signed and sent to the presenter (i.e. wallet app).
     *
     * @param request interface that represents the request
     */
    suspend fun checkRequest(request: VerifierRequest)

    /**
     * Processes a successful presentment.
     *
     * @param request interface that represents the original request
     * @param response interface that represents the response from the presenter
     * @returns if non-null, the result that should be returned to the front-end
     */
    suspend fun processResponse(
        request: VerifierRequest,
        response: VerifierResponse
    ): JsonObject?
}