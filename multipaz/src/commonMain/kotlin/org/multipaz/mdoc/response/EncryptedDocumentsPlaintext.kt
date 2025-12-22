package org.multipaz.mdoc.response

import org.multipaz.cbor.DataItem
import org.multipaz.cbor.buildCborMap
import org.multipaz.cbor.putCborArray
import org.multipaz.mdoc.zkp.ZkDocument

/**
 * Structure containing decrypted documents from a [EncryptedDocuments] structure.
 *
 * @property documents a list of returned documents.
 * @property zkDocuments a list of returned documents with ZKP.
 */
@ConsistentCopyVisibility
data class EncryptedDocumentsPlaintext internal constructor(
    val documents: List<MdocDocument>,
    val zkDocuments: List<ZkDocument>
) {

    internal fun toDataItem() = buildCborMap {
        if (documents.isNotEmpty()) {
            putCborArray("documents") {
                documents.forEach {
                    add(it.toDataItem())
                }
            }
        }
        if (zkDocuments.isNotEmpty()) {
            putCborArray("zkDocuments") {
                zkDocuments.forEach {
                    add(it.toDataItem())
                }
            }
        }
    }

    companion object {

        internal suspend fun fromDataItem(dataItem: DataItem): EncryptedDocumentsPlaintext {
            val documents = dataItem.getOrNull("documents")?.asArray?.map {
                MdocDocument.fromDataItem(it)
            }
            val zkDocuments = dataItem.getOrNull("zkDocuments")?.asArray?.map {
                ZkDocument.fromDataItem(it)
            }
            return EncryptedDocumentsPlaintext(
                documents = documents ?: emptyList(),
                zkDocuments = zkDocuments ?: emptyList()
            )
        }
    }
}
