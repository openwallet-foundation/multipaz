package org.multipaz.mdoc.request

import org.multipaz.cbor.DataItem
import org.multipaz.cbor.buildCborArray

/**
 * A document set according to ISO 18013-5.
 *
 * @property docRequestIds the document requests in the set.
 */
data class DocumentSet(
    val docRequestIds: List<Int>
) {
    internal fun toDataItem() = buildCborArray {
        docRequestIds.forEach {
            add(it)
        }
    }

    companion object {
        internal fun fromDataItem(dataItem: DataItem): DocumentSet {
            return DocumentSet(
                docRequestIds = dataItem.asArray.map { it.asNumber.toInt() }
            )
        }
    }
}