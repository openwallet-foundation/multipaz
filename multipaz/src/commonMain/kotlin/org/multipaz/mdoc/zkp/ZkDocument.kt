package org.multipaz.mdoc.zkp

import kotlinx.io.bytestring.ByteString
import org.multipaz.cbor.Bstr
import org.multipaz.cbor.Cbor
import org.multipaz.cbor.DataItem
import org.multipaz.cbor.Tagged
import org.multipaz.cbor.buildCborMap
import org.multipaz.cbor.putCborArray
import org.multipaz.cbor.toDataItem
import org.multipaz.mdoc.util.asDocRequestIds
import org.multipaz.mdoc.util.validateDocRequestIds

/**
 * Represents a document that contains a zero-knowledge (ZK) proof.
 *
 * @property documentData The structured data of the document.
 * @property proof The ZK proof that attests to the integrity and validity of the document data.
 * @property docRequestIds Optional list of `DocRequest` indices in `DeviceRequest.docRequests`
 *   that this document responds to, or `null` if not specified.
 */
data class ZkDocument(
    val documentData: ZkDocumentData,
    val proof: ByteString,
    val docRequestIds: List<Int>? = null,
) {
    init {
        if (docRequestIds != null) {
            validateDocRequestIds(docRequestIds)
        }
    }

    /**
     * Converts this ZkDocument instance to a CBOR DataItem representation.
     *
     * The resulting DataItem will be a CBOR map containing:
     * - "proof": The proof as a CBOR byte string
     * - "documentData": The document data serialized to its CBOR representation
     * - "docRequestIds" (optional): The list of `DocRequest` indices satisfied by this document
     *
     * @return A DataItem representing this ZkDocument in CBOR format
     */
    fun toDataItem(): DataItem {
        return buildCborMap {
            put("proof", proof.toByteArray().toDataItem())
            put("documentData", Tagged(Tagged.ENCODED_CBOR, Bstr(Cbor.encode(documentData.toDataItem()))))
            if (docRequestIds != null) {
                putCborArray("docRequestIds") {
                    docRequestIds.forEach { add(it) }
                }
            }
        }
    }

    companion object {
        /**
         * Creates a ZkDocument instance from a CBOR DataItem.
         *
         * This deserializes a CBOR representation back into a ZkDocument object.
         * It expects the DataItem to be a CBOR map with the following required fields:
         * - "proof": A CBOR byte string containing the ZK proof
         * - "documentData": A CBOR structure that can be deserialized into ZkDocumentData
         * and optional field:
         * - "docRequestIds": A non-empty CBOR array of unsigned integers
         *
         * @param dataItem The CBOR DataItem to deserialize
         * @return A new ZkDocument instance
         * @throws IllegalArgumentException if required fields are missing or have invalid types
         */
        fun fromDataItem(dataItem: DataItem): ZkDocument {
            val proof = dataItem.getOrNull("proof")?.asBstr
                ?: throw IllegalArgumentException("Missing or invalid 'proof' field parsing ZkDocument.")
            val zkDocumentDataBytes = dataItem.getOrNull("documentData")
                ?: throw IllegalArgumentException("Missing or invalid 'documentData' field parsing ZkDocument.")
            require(
                zkDocumentDataBytes is Tagged && zkDocumentDataBytes.tagNumber == Tagged.ENCODED_CBOR &&
                        zkDocumentDataBytes.asTagged is Bstr
            ) { "zkDocumentDataBytes is not a tagged ByteString" }
            val docRequestIds = dataItem.getOrNull("docRequestIds")?.asDocRequestIds()
            return ZkDocument(
                documentData = ZkDocumentData.fromDataItem(Cbor.decode(zkDocumentDataBytes.asTagged.asBstr)),
                proof = ByteString(proof),
                docRequestIds = docRequestIds,
            )
        }
    }
}


