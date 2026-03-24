package org.multipaz.verifier.customization

import kotlinx.serialization.json.JsonObject

/**
 * Represents data supplied by the front-end to generate a presentation request.
 */
interface VerifierRequest {
    /** @returns DCQL request that describes the desired credentials and claims */
    suspend fun getDcql(): JsonObject
    /** @returns transactions that should be preformed using required credentials */
    suspend fun getTransactions(): List<JsonObject>
}