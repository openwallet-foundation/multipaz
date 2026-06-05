package org.multipaz.request

import kotlinx.serialization.json.JsonArray
import org.multipaz.cbor.DataItem
import org.multipaz.cbor.annotation.CborSerializationImplemented
import org.multipaz.documenttype.DocumentAttribute

/**
 * A request for a claim in an ISO mdoc credential.
 *
 * @property docType the document type.
 * @property namespaceName the mdoc namespace.
 * @property dataElementName the data element name.
 * @property intentToRetain `true` if the requester intends to retain the value.
 */
@CborSerializationImplemented(schemaId = "")
data class MdocRequestedClaim(
    override val id: String? = null,
    val docType: String,
    val namespaceName: String,
    val dataElementName: String,
    val intentToRetain: Boolean,
    override val values: JsonArray? = null
): RequestedClaim(id = id, values = values) {
    companion object {
        /**
         * Creates a [MdocRequestedClaim] from a [DataItem].
         *
         * @param dataItem a [DataItem].
         * @return a [MdocRequestedClaim].
         */
        fun fromDataItem(dataItem: DataItem): MdocRequestedClaim =
            RequestedClaim.fromDataItem(dataItem) as MdocRequestedClaim
    }
}