package org.multipaz.mdoc.request

import org.multipaz.cbor.DataItem
import org.multipaz.cbor.buildCborArray
import org.multipaz.mdoc.util.asDocRequestIds
import org.multipaz.mdoc.util.validateDocRequestIds

/**
 * A document set according to ISO 18013-5.
 *
 * @property docRequestIds the document requests in the set.
 */
data class DocumentSet(
    val docRequestIds: List<Int>
) {
    init {
        validateDocRequestIds(docRequestIds)
    }

    internal fun toDataItem() = buildCborArray {
        docRequestIds.forEach {
            add(it)
        }
    }

    companion object {
        internal fun fromDataItem(dataItem: DataItem): DocumentSet {
            return DocumentSet(
                docRequestIds = dataItem.asDocRequestIds("DocumentSet")
            )
        }
    }
}