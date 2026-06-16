package org.multipaz.mdoc.request

import org.multipaz.cbor.DataItem
import org.multipaz.cbor.buildCborMap
import org.multipaz.cbor.putCborArray


/**
 * Device request info according to ISO 18013-5 Second Edition.
 *
 * This is represented as a [DataItem] to preserve the order for serialization and deserialization.
 *
 * @property dataItem a [DataItem] which is a map as defined in ISO/IEC 18013-5 Second Edition.
 */
data class DeviceRequestInfo(
    val dataItem: DataItem
) {
    /** @property useCases list of use-cases.
     */
    val useCases: List<UseCase>
        get() = dataItem.getOrNull("useCases")?.asArray?.map { UseCase.fromDataItem(it) } ?: emptyList()

    companion object {
        /**
         * Constructs a [org.multipaz.mdoc.request.DeviceRequestInfo] from values.
         *
         * @property useCases list of use-cases.
         * @property otherInfo other request info.
         * @return a [org.multipaz.mdoc.request.DeviceRequestInfo].
         */
        fun fromValues(
            useCases: List<UseCase> = emptyList(),
            otherInfo: Map<String, DataItem> = emptyMap()
        ): DeviceRequestInfo {
            val dataItem = buildCborMap {
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
            return DeviceRequestInfo(dataItem)
        }
    }
}