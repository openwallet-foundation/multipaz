package org.multipaz.mdoc.request

import kotlinx.io.bytestring.ByteString
import org.multipaz.cbor.DataItem
import org.multipaz.cbor.buildCborMap
import org.multipaz.cbor.putCborArray
import org.multipaz.mdoc.zkp.ZkSystem

/**
 * Document request info according to ISO 18013-5.
 *
 * @property alternativeDataElements list of alternative data elements.
 * @property issuerIdentifiers list of issuer identifiers.
 * @property uniqueDocSetRequired whether a unique doc set is required or not or unspecified.
 * @property maximumResponseSize the maximum response size, if available.
 * @property zkRequest optional request for a Zero-Knowledge Proof.
 * @property otherInfo other request info.
 */
data class DocRequestInfo(
    val alternativeDataElements: List<AlternativeDataElementSet> = emptyList(),
    val issuerIdentifiers: List<ByteString> = emptyList(),
    val uniqueDocSetRequired: Boolean? = null,
    val maximumResponseSize: Long? = null,
    val zkRequest: ZkRequest? = null,
    val otherInfo: Map<String, DataItem> = emptyMap()
) {
    internal fun toDataItem() = buildCborMap {
        if (alternativeDataElements.isNotEmpty()) {
            putCborArray("alternativeDataElements") {
                alternativeDataElements.forEach {
                    add(it.toDataItem())
                }
            }
        }
        if (issuerIdentifiers.isNotEmpty()) {
            putCborArray("issuerIdentifiers") {
                issuerIdentifiers.forEach {
                    add(it.toByteArray())
                }
            }
        }
        uniqueDocSetRequired?.let {
            put("uniqueDocSetRequired", uniqueDocSetRequired)
        }
        maximumResponseSize?.let {
            put("maximumResponseSize", it)
        }
        zkRequest?.let {
            put("zkRequest", it.toDataItem())
        }
        otherInfo.forEach { (key, value) ->
            put(key, value)
        }
    }


    internal fun isUsingSecondEditionFeature(): Boolean {
        return alternativeDataElements.isNotEmpty() ||
                issuerIdentifiers.isNotEmpty() ||
                uniqueDocSetRequired != null ||
                maximumResponseSize != null ||
                zkRequest != null
    }

    companion object {
        internal fun fromDataItem(dataItem: DataItem): DocRequestInfo {
            val alternativeDataElements = dataItem.getOrNull("alternativeDataElements")?.asArray?.map {
                AlternativeDataElementSet.fromDataItem(it)
            } ?: emptyList()
            val issuerIdentifiers = dataItem.getOrNull("issuerIdentifiers")?.asArray?.map {
                ByteString(it.asBstr)
            } ?: emptyList()
            val uniqueDocSetRequired = dataItem.getOrNull("uniqueDocSetRequired")?.asBoolean
            val maximumResponseSize = dataItem.getOrNull("maximumResponseSize")?.asNumber
            val zkRequest = dataItem.getOrNull("zkRequest")?.let {
                ZkRequest.fromDataItem(it)
            }
            val otherInfo = mutableMapOf<String, DataItem>()
            for ((otherKeyDataItem, otherValue) in dataItem.asMap) {
                val otherKey = otherKeyDataItem.asTstr
                when (otherKey) {
                    "alternativeDataElements",
                    "issuerIdentifiers",
                    "uniqueDocSetRequired",
                    "maximumResponseSize",
                    "zkRequest" -> continue
                    else -> otherInfo[otherKey] = otherValue
                }
            }
            return DocRequestInfo(
                alternativeDataElements = alternativeDataElements,
                issuerIdentifiers = issuerIdentifiers,
                uniqueDocSetRequired = uniqueDocSetRequired,
                maximumResponseSize = maximumResponseSize,
                zkRequest = zkRequest,
                otherInfo = otherInfo
            )
        }
    }
}