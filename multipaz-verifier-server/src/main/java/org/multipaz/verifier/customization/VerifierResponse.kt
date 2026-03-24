package org.multipaz.verifier.customization

import kotlinx.io.bytestring.ByteString
import kotlinx.serialization.json.JsonObject

/**
 * Represents the result of a successful presentment.
 */
interface VerifierResponse {
    /** Response protocol prefixed by "dcapi:" or "custom-url:" */
    val responseProtocol: String
    /** Raw response data */
    val rawResponse: ByteString
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