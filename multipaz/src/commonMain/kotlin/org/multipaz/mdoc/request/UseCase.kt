package org.multipaz.mdoc.request

import org.multipaz.cbor.DataItem
import org.multipaz.cbor.buildCborMap
import org.multipaz.cbor.putCborArray
import org.multipaz.cbor.putCborMap

/**
 * Use-cases according to ISO 18013-5.
 *
 * @property mandatory whether this use-case is mandatory to fulfill.
 * @property documentSets list of document sets in the use-case.
 * @property purposeHints a map of purpose-hints, namespaced.
 */
data class UseCase(
    val mandatory: Boolean,
    val documentSets: List<DocumentSet>,
    val purposeHints: Map<String, Int>
) {
    internal fun toDataItem() = buildCborMap {
        put("mandatory", mandatory)
        putCborArray("documentSets") {
            documentSets.forEach {
                add(it.toDataItem())
            }
        }
        if (purposeHints.isNotEmpty()) {
            putCborMap("purposeHints") {
                for ((controllerId, purposeHintCode) in purposeHints) {
                    put(controllerId, purposeHintCode)
                }
            }
        }
    }

    companion object {
        internal fun fromDataItem(dataItem: DataItem): UseCase {
            val mandatory = dataItem["mandatory"].asBoolean
            val documentSets = dataItem["documentSets"].asArray.map { DocumentSet.fromDataItem(it) }
            val purposeHints = dataItem.getOrNull("purposeHints")?.asMap?.entries?.associate { (key, value) ->
                key.asTstr to value.asNumber.toInt()
            } ?: emptyMap()
            return UseCase(
                mandatory = mandatory,
                documentSets = documentSets,
                purposeHints = purposeHints
            )
        }
    }
}