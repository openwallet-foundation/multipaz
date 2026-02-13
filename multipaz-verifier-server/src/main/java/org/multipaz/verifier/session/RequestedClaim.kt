package org.multipaz.verifier.session

import org.multipaz.cbor.DataItem
import org.multipaz.cbor.annotation.CborSerializable

@CborSerializable
data class RequestedClaim(
    val id: String?,
    val path: List<DataItem>
) {
    companion object
}