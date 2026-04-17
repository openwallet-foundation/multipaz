package org.multipaz.verifier.customization

import kotlinx.serialization.json.JsonObject
import org.multipaz.server.presentment.PresentmentRecord
import org.multipaz.server.presentment.PresentmentResult

/**
 * Represents the original request and the result of a successful presentment.
 */
interface VerifierPresentment {
    /** DCQL request that describes the desired credentials and claims */
    val dcql: JsonObject

    /** transaction data */
    val transactions: List<JsonObject>

    /** Serializable data that fully describes the presentation */
    val presentmentRecord: PresentmentRecord

    /**
     * Per-document presentment verification result.
     */
    val presentmentResults: List<PresentmentResult>

    /**
     * Processed response; this will have an object for each returned for each credential
     * in the request DCQL indexed by the request id. Each object has the following fields:
     *  - `trusted` - boolean that indicates if the credential was signed by a trusted party
     *  - `claims` - map of the claim values indexed by the DCQL claim ids
     *  - `transactions` - map of data returned by transaction processing if any, indexed
     *     by transaction type identifier.
     */
    val response: JsonObject
}