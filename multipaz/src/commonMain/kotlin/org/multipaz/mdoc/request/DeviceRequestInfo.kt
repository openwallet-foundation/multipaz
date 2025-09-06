package org.multipaz.mdoc.request

import org.multipaz.cbor.DataItem
import org.multipaz.cbor.buildCborMap
import org.multipaz.cbor.putCborArray


/**
 * Device request info according to ISO 18013-5.
 *
 * @property useCases list of use-cases.
 * @property otherInfo other request info.
 */
data class DeviceRequestInfo(
    val useCases: List<UseCase> = emptyList(),
    val otherInfo: Map<String, DataItem> = emptyMap()
) {
    internal fun toDataItem() = buildCborMap {
        if (useCases.isNotEmpty()) {
            putCborArray("useCases") {
                useCases.forEach {
                    add(it.toDataItem())
                }
            }
        }
        otherInfo.forEach { (key, value) ->
            put(key, value)
        }
    }

    companion object {
        internal fun fromDataItem(dataItem: DataItem): DeviceRequestInfo {
            val otherInfo = mutableMapOf<String, DataItem>()
            for ((otherKeyDataItem, otherValue) in dataItem.asMap) {
                val otherKey = otherKeyDataItem.asTstr
                when (otherKey) {
                    "useCases" -> continue
                    else -> otherInfo[otherKey] = otherValue
                }
            }
            return DeviceRequestInfo(
                useCases = dataItem.getOrNull("useCases")?.asArray?.map { UseCase.fromDataItem(it) }
                    ?: emptyList(),
                otherInfo = otherInfo
            )
        }
    }
}