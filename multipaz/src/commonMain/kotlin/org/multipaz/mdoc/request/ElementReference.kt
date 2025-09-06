package org.multipaz.mdoc.request

import org.multipaz.cbor.DataItem
import org.multipaz.cbor.buildCborArray

/**
 * A reference to a data element.
 *
 * @property namespace the namespace of the data element, e.g. "org.iso.18013.5.1".
 * @property dataElement the identifier of the data element, e.g. "age_over_18".
 */
data class ElementReference(
    val namespace: String,
    val dataElement: String
) {
    internal fun toDataItem() = buildCborArray {
        add(namespace)
        add(dataElement)
    }

    companion object {
        internal fun fromDataItem(dataItem: DataItem): ElementReference {
            check(dataItem.asArray.size == 2)
            val namespace = dataItem.asArray[0].asTstr
            val dataElement = dataItem.asArray[1].asTstr
            return ElementReference(
                namespace = namespace,
                dataElement = dataElement
            )
        }
    }
}