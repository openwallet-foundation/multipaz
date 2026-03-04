package org.multipaz.request

import kotlinx.serialization.json.JsonArray
import org.multipaz.documenttype.DocumentAttribute

/**
 * A request for a claim in a JSON-based credential.
 *
 * @property vctValues the Verifiable Credential Types that can satisfy the request.
 * @property claimPath the claims path pointer.
 */
data class JsonRequestedClaim(
    override val id: String? = null,
    val vctValues: List<String>,
    val claimPath: JsonArray,
    override val values: JsonArray? = null
): RequestedClaim(id = id, values = values) {
    companion object
}
