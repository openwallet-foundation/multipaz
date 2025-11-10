package org.multipaz.mdoc.zkp

import kotlinx.io.bytestring.ByteString
import org.multipaz.cbor.Bstr
import org.multipaz.cbor.Cbor
import org.multipaz.cbor.DataItem
import org.multipaz.cbor.Tagged
import org.multipaz.cbor.buildCborMap
import org.multipaz.cbor.toDataItem
import org.multipaz.util.Logger

/**
 * Represents a document that contains a zero-knowledge (ZK) proof.
 *
 * @property documentData The structured data of the document.
 * @property proof The ZK proof that attests to the integrity and validity of the document data.
 */
data class ZkDocument(
    val documentData: ZkDocumentData,
    val proof: ByteString
) {
    /**
     * Converts this ZkDocument instance to a CBOR DataItem representation.
     *
     * The resulting DataItem will be a CBOR map containing two entries:
     * - "proof": The proof as a CBOR byte string
     * - "documentData": The document data serialized to its CBOR representation
     *
     * @return A DataItem representing this ZkDocument in CBOR format
     */
    fun toDataItem(): DataItem {
        return buildCborMap {
            put("proof", proof.toByteArray().toDataItem())
            put("documentData", Tagged(Tagged.ENCODED_CBOR, Bstr(Cbor.encode(documentData.toDataItem()))))
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
            return ZkDocument(
                documentData = ZkDocumentData.fromDataItem(Cbor.decode(zkDocumentDataBytes.asTagged.asBstr)),
                proof = ByteString(proof)
            )
        }
    }
}


