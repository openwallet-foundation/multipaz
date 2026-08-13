package org.multipaz.mdoc.request

import kotlinx.io.bytestring.ByteString
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull
import org.multipaz.cbor.Bstr
import org.multipaz.cbor.Cbor
import org.multipaz.cbor.CborInt
import org.multipaz.cbor.DataItem
import org.multipaz.cbor.Simple
import org.multipaz.cbor.Tagged
import org.multipaz.cbor.Tstr
import org.multipaz.cbor.addCborMap
import org.multipaz.cbor.buildCborMap
import org.multipaz.cbor.putCborArray
import org.multipaz.cbor.putCborMap

/**
 * Document request info according to ISO 18013-5.
 *
 * @property alternativeDataElements list of alternative data elements.
 * @property issuerIdentifiers list of issuer identifiers.
 * @property uniqueDocSetRequired whether a unique doc set is required or not or unspecified.
 * @property maximumResponseSize the maximum response size, if available.
 * @property zkRequest optional request for a Zero-Knowledge Proof.
 * @property docResponseEncryption optional request for encrypting the response.
 * @property docFormat optional document format.
 * @property dataElementIdentifierMapping optional data element identifier mapping.
 * @property transactions optional information about requested transactions.
 * @property otherInfo other request info.
 */
data class DocRequestInfo(
    val alternativeDataElements: List<AlternativeDataElementSet> = emptyList(),
    val issuerIdentifiers: List<ByteString> = emptyList(),
    val uniqueDocSetRequired: Boolean? = null,
    val maximumResponseSize: Long? = null,
    val zkRequest: ZkRequest? = null,
    val docResponseEncryption: EncryptionParameters? = null,
    val docFormat: String? = null,
    val dataElementIdentifierMapping: Map<String, JsonArray> = emptyMap(),
    val transactions: TransactionsInfo? = null,
    val otherInfo: Map<String, DataItem> = emptyMap(),
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
        docResponseEncryption?.let {
            put("docResponseEncryption", Tagged(
                tagNumber = Tagged.ENCODED_CBOR,
                taggedItem = Bstr(Cbor.encode(it.dataItem))
            ))
        }
        docFormat?.let {
            put("docFormat", docFormat)
        }
        if (dataElementIdentifierMapping.isNotEmpty()) {
            putCborMap("dataElementIdentifierMapping") {
                dataElementIdentifierMapping.forEach { (dataElementName, jsonPath) ->
                    putCborArray(dataElementName) {
                        jsonPath.forEach { jsonElement ->
                            when (jsonElement) {
                                is JsonNull -> add(Simple.NULL)
                                is JsonPrimitive -> {
                                    if (jsonElement.isString) {
                                        add(jsonElement.content)
                                    } else {
                                        jsonElement.intOrNull?.let {
                                            add(it)
                                        } ?: add(jsonElement.content)
                                    }
                                }
                                else -> throw IllegalArgumentException("Unsupported path $jsonPath")
                            }
                        }
                    }
                }
            }
        }
        transactions?.let {
            putCborArray("transactions") {
                for ((type, data) in it.data) {
                    addCborMap {
                        put("type", type)
                        put("data", data)
                    }
                }
            }
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
                zkRequest != null ||
                docResponseEncryption != null ||
                docFormat != null ||
                dataElementIdentifierMapping.isNotEmpty() ||
                transactions != null
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
            val docResponseEncryption = dataItem.getOrNull("docResponseEncryption")?.let {
                EncryptionParameters(it.asTaggedEncodedCbor)
            }
            val docFormat = dataItem.getOrNull("docFormat")?.asTstr
            val dataElementIdentifierMapping = dataItem.getOrNull("dataElementIdentifierMapping")?.let {
                it.asMap.map { (key, value) ->
                    key.asTstr to value.asArray.map { element ->
                        when (element) {
                            is Simple -> { require(element == Simple.NULL); JsonNull }
                            is Tstr -> JsonPrimitive(element.asTstr)
                            is CborInt -> JsonPrimitive(element.asNumber)
                            else -> throw IllegalStateException("Unexpected element")
                        }
                    }.let { JsonArray(it) }
                }.toMap()
            } ?: emptyMap()
            val transactions = dataItem.getOrNull("transactions")?.let {
                TransactionsInfo(
                    data = it.asArray.associate { item -> Pair(item["type"].asTstr, item["data"]) }
                )
            }
            val otherInfo = mutableMapOf<String, DataItem>()
            for ((otherKeyDataItem, otherValue) in dataItem.asMap) {
                when (val otherKey = otherKeyDataItem.asTstr) {
                    "alternativeDataElements",
                    "issuerIdentifiers",
                    "uniqueDocSetRequired",
                    "maximumResponseSize",
                    "zkRequest",
                    "docResponseEncryption",
                    "docFormat",
                    "dataElementIdentifierMapping",
                    "transactions" -> continue
                    else -> otherInfo[otherKey] = otherValue
                }
            }
            return DocRequestInfo(
                alternativeDataElements = alternativeDataElements,
                issuerIdentifiers = issuerIdentifiers,
                uniqueDocSetRequired = uniqueDocSetRequired,
                maximumResponseSize = maximumResponseSize,
                zkRequest = zkRequest,
                docResponseEncryption = docResponseEncryption,
                docFormat = docFormat,
                dataElementIdentifierMapping = dataElementIdentifierMapping,
                transactions = transactions,
                otherInfo = otherInfo
            )
        }
    }
}