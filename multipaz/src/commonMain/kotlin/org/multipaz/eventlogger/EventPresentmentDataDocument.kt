package org.multipaz.eventlogger

import org.multipaz.cbor.DataItem
import org.multipaz.cbor.annotation.CborSerializationImplemented
import org.multipaz.cbor.buildCborMap
import org.multipaz.cbor.putCborMap
import org.multipaz.claim.Claim
import org.multipaz.documenttype.DocumentAttribute
import org.multipaz.documenttype.DocumentTypeRepository
import org.multipaz.request.RequestedClaim

/**
 * A document requested in a presentment event.
 *
 * @property documentId the document identifier.
 * @property documentName the name of the document or `null`.
 * @property claims the requested claims.
 */
@CborSerializationImplemented(schemaId = "")
data class EventPresentmentDataDocument(
    val documentId: String,
    val documentName: String?,
    val claims: Map<RequestedClaim, Claim>,
) {
    /**
     * Serializes [EventPresentmentDataDocument] to CBOR.
     *
     * Note that [DocumentAttribute] values in [claims] won't be serialized.
     *
     * @return a [DataItem].
     */
    fun toDataItem() = buildCborMap {
        put("documentId", documentId)
        documentName?.let {
            put("documentName", documentName)
        }
        putCborMap("requestedClaims") {
            claims.forEach { (requestedClaim, claim) ->
                put(requestedClaim.toDataItem(), claim.toDataItem())
            }
        }
    }

    companion object {
        /**
         * Creates a [EventPresentmentDataDocument] previously serialized with [EventPresentmentDataDocument.toDataItem].
         *
         * @param dataItem a [DataItem].
         * @param documentTypeRepository if not `null`, will be used to look up a [DocumentAttribute] for the claim.
         * @return a new [EventPresentmentDataDocument].
         */
        fun fromDataItem(
            dataItem: DataItem,
            documentTypeRepository: DocumentTypeRepository? = null
        ): EventPresentmentDataDocument {
            val documentId = dataItem["documentId"].asTstr
            val documentName = dataItem.getOrNull("documentName")?.asTstr
            val claims = dataItem["requestedClaims"].asMap.entries.associate { (requestedClaimDataItem, claimDataItem) ->
                RequestedClaim.fromDataItem(requestedClaimDataItem) to
                        Claim.fromDataItem(claimDataItem, documentTypeRepository)
            }
            return EventPresentmentDataDocument(
                documentId = documentId,
                documentName = documentName,
                claims = claims
            )
        }
    }
}
