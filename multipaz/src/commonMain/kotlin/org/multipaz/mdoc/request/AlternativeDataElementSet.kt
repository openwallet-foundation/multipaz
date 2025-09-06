package org.multipaz.mdoc.request

import org.multipaz.cbor.DataItem
import org.multipaz.cbor.addCborArray
import org.multipaz.cbor.buildCborMap
import org.multipaz.cbor.putCborArray

/**
 * An alternative data element set.
 *
 * @property requestedElement the data element for which alternative data elements exist.
 * @property alternativeElementSets the alternative data elements which can be used instead.
 */
data class AlternativeDataElementSet(
    val requestedElement: ElementReference,
    val alternativeElementSets: List<List<ElementReference>>
) {
    internal fun toDataItem() = buildCborMap {
        put("requestedElement", requestedElement.toDataItem())
        putCborArray("alternativeElementSets") {
            alternativeElementSets.forEach { listOfElementReferences ->
                addCborArray {
                    listOfElementReferences.forEach { elementReference ->
                        add(elementReference.toDataItem())
                    }
                }
            }
        }
    }

    companion object {
        internal fun fromDataItem(dataItem: DataItem): AlternativeDataElementSet {
            return AlternativeDataElementSet(
                requestedElement = ElementReference.fromDataItem(dataItem["requestedElement"]),
                alternativeElementSets = dataItem["alternativeElementSets"].asArray.map {
                    it.asArray.map { elementReference ->
                        ElementReference.fromDataItem(elementReference)
                    }
                }
            )
        }
    }
}