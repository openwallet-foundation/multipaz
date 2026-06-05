package org.multipaz.request

import kotlinx.serialization.json.JsonArray
import org.multipaz.cbor.DataItem
import org.multipaz.cbor.annotation.CborSerializationImplemented
import org.multipaz.documenttype.DocumentAttribute

/**
 * A request for a claim in a JSON-based credential.
 *
 * @property vctValues the Verifiable Credential Types that can satisfy the request.
 * @property claimPath the claims path pointer.
 */
@CborSerializationImplemented(schemaId = "")
data class JsonRequestedClaim(
    override val id: String? = null,
    val vctValues: List<String>,
    val claimPath: JsonArray,
    override val values: JsonArray? = null
): RequestedClaim(id = id, values = values) {
    companion object {
        /**
         * Creates a [JsonRequestedClaim] from a [DataItem].
         *
         * @param dataItem a [DataItem].
         * @return a [JsonRequestedClaim].
         */
        fun fromDataItem(dataItem: DataItem): JsonRequestedClaim =
            RequestedClaim.fromDataItem(dataItem) as JsonRequestedClaim
    }
}
