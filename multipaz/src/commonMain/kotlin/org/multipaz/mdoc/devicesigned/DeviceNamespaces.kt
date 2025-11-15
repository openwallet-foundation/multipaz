package org.multipaz.mdoc.devicesigned

import org.multipaz.cbor.DataItem
import org.multipaz.cbor.buildCborMap
import org.multipaz.cbor.putCborMap
import org.multipaz.util.Logger

/**
 * A data structure for representing `DeviceNameSpaces` in ISO/IEC 18013-5:2021.
 *
 * Use [fromDataItem] to parse CBOR and [toDataItem] to generate CBOR.
 *
 * @property data map from namespace name to a map from data element name to [DataItem].
 */
data class DeviceNamespaces(
    val data: Map<String, Map<String, DataItem>>
) {
    /**
     * Generate `DeviceNameSpaces` CBOR
     *
     * @return a [DataItem] for `DeviceNameSpaces` CBOR.
     */
    fun toDataItem(): DataItem {
        return buildCborMap {
            for ((namespaceName, innerMap) in data) {
                putCborMap(namespaceName) {
                    for ((dataElementName, dataElementValue) in innerMap) {
                        put(dataElementName, dataElementValue)
                    }
                }
            }
        }
    }

    companion object {
        /**
         * Parse `DeviceNameSpaces` CBOR.
         *
         * @param nameSpaces a [DataItem] for `DeviceNameSpaces` CBOR.
         * @return the parsed representation.
         */
        fun fromDataItem(nameSpaces: DataItem): DeviceNamespaces {
            Logger.iCbor("TAG", "nameSpaces", nameSpaces)
            val ret = mutableMapOf<String, MutableMap<String, DataItem>>()
            for ((namespaceDataItemKey, namespaceDataItemValue) in nameSpaces.asMap) {
                val namespaceName = namespaceDataItemKey.asTstr
                val innerMap = mutableMapOf<String, DataItem>()
                for ((dataElementDataItemKey, dataElementDataItemValue) in namespaceDataItemValue.asMap) {
                    innerMap[dataElementDataItemKey.asTstr] = dataElementDataItemValue
                }
                ret[namespaceName] = innerMap
            }
            return DeviceNamespaces(ret)
        }
    }

    internal data class DataElements(
        val namespaceName: String,
        val dataElements: List<Pair<String, DataItem>>
    )

    /**
     * A builder for populating a namespace in a [DeviceNamespaces].
     *
     * @param namespaceName the namespace name.
     */
    data class DataElementBuilder(
        val namespaceName: String
    ) {
        private val dataElements = mutableListOf<Pair<String, DataItem>>()

        /**
         * Adds a data element to the builder.
         *
         * @param dataElementName the data element name.
         * @param value the data element value.
         * @return the builder
         */
        fun addDataElement(dataElementName: String, value: DataItem): DataElementBuilder {
            dataElements.add(Pair(dataElementName, value))
            return this
        }

        internal fun build(): DataElements {
            return DataElements(namespaceName, dataElements)
        }
    }

    /**
     * A builder for [DeviceNamespaces].
     */
    class Builder {
        private val builtNamespaces = mutableListOf<DataElements>()

        /**
         * Adds a new namespace.
         *
         * @param namespaceName the namespace name.
         * @param builderAction the builder action.
         * @return the builder
         */
        fun addNamespace(namespaceName: String, builderAction: DataElementBuilder.() -> Unit): DeviceNamespaces.Builder {
            val builder = DataElementBuilder(namespaceName)
            builder.builderAction()
            builtNamespaces.add(builder.build())
            return this
        }

        /**
         * Builds the [DeviceNamespaces].
         *
         * @return the built [DeviceNamespaces].
         */
        fun build(): DeviceNamespaces {
            val ret = mutableMapOf<String, Map<String, DataItem>>()
            for (ns in builtNamespaces) {
                val items = mutableMapOf<String, DataItem>()
                for ((deName, deValue) in ns.dataElements) {
                    items.put(deName, deValue)
                }
                ret.put(ns.namespaceName, items)
            }
            return DeviceNamespaces(ret)
        }
    }
}

/**
 * A builder for [DeviceNamespaces].
 *
 * @param builderAction the builder action.
 * @return the built [DeviceNamespaces].
 */
fun buildDeviceNamespaces(
    builderAction: DeviceNamespaces.Builder.() -> Unit
): DeviceNamespaces {
    val builder = DeviceNamespaces.Builder()
    builder.builderAction()
    return builder.build()
}
