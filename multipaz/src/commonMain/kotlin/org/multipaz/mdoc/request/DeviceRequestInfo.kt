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
    init {
        val useCasesItem = dataItem.getOrNull("useCases")
            ?: throw IllegalArgumentException("DeviceRequestInfo must contain 'useCases'")
        require(useCasesItem.asArray.isNotEmpty()) {
            "DeviceRequestInfo 'useCases' must not be empty"
        }
        useCasesItem.asArray.forEach { UseCase.fromDataItem(it) }
    }

    /** @property useCases list of use-cases.
     */
    val useCases: List<UseCase>
        get() = dataItem["useCases"].asArray.map { UseCase.fromDataItem(it) }

    companion object {
        /**
         * Constructs a [org.multipaz.mdoc.request.DeviceRequestInfo] from values.
         *
         * @property useCases non-empty list of use-cases.
         * @property otherInfo other request info.
         * @return a [org.multipaz.mdoc.request.DeviceRequestInfo].
         */
        fun fromValues(
            useCases: List<UseCase>,
            otherInfo: Map<String, DataItem> = emptyMap()
        ): DeviceRequestInfo {
            require(useCases.isNotEmpty()) {
                "DeviceRequestInfo 'useCases' must not be empty"
            }
            val dataItem = buildCborMap {
                putCborArray("useCases") {
                    useCases.forEach {
                        add(it.toDataItem())
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