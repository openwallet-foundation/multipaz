package org.multipaz.request

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import org.multipaz.cbor.DataItem
import org.multipaz.cbor.annotation.CborSerializationImplemented
import org.multipaz.cbor.buildCborMap
import org.multipaz.cbor.putCborArray
import org.multipaz.claim.Claim
import org.multipaz.documenttype.DocumentAttribute

/**
 * Base class used for representing a request for a claim.
 *
 * @property id the identifier for the claim or `null`.
 * @property values A set of acceptable values or `null` to not match on value.
 */
@CborSerializationImplemented(schemaId = "")
sealed class RequestedClaim(
    open val id: String? = null,
    open val values: JsonArray? = null,
) {
    /**
     * Serializes [RequestedClaim] to CBOR.
     *
     * @return a [DataItem].
     */
    fun toDataItem() = buildCborMap {
        id?.let {
            put("id", it)
        }
        values?.let {
            put("values", Json.encodeToString(it))
        }
        when (this@RequestedClaim) {
            is JsonRequestedClaim -> {
                put("type", TYPE_JSON_REQUESTED_CLAIM)
                putCborArray("vctValues") {
                    vctValues.forEach { add(it) }
                }
                put("claimPath", Json.encodeToString(claimPath))
            }
            is MdocRequestedClaim -> {
                put("type", TYPE_MDOC_REQUESTED_CLAIM)
                put("docType", docType)
                put("namespaceName", namespaceName)
                put("dataElementName", dataElementName)
                put("intentToRetain", intentToRetain)
            }
        }
    }

    companion object {
        private const val TYPE_JSON_REQUESTED_CLAIM = 0
        private const val TYPE_MDOC_REQUESTED_CLAIM = 1

        /**
         * Creates a [RequestedClaim] previously serialized with [RequestedClaim.toDataItem].
         *
         * @param dataItem a [DataItem].
         * @return a [RequestedClaim].
         */
        fun fromDataItem(dataItem: DataItem): RequestedClaim {
            val id = dataItem.getOrNull("id")?.asTstr
            val values = dataItem.getOrNull("values")?.let {
                Json.decodeFromString<JsonArray>(it.asTstr)
            }
            when (dataItem["type"].asNumber.toInt()) {
                TYPE_JSON_REQUESTED_CLAIM -> {
                    return JsonRequestedClaim(
                        id = id,
                        values = values,
                        vctValues = dataItem["vctValues"].asArray.map { it.asTstr },
                        claimPath = Json.decodeFromString<JsonArray>(dataItem["claimPath"].asTstr)
                    )
                }
                TYPE_MDOC_REQUESTED_CLAIM -> {
                    return MdocRequestedClaim(
                        id = id,
                        values = values,
                        docType = dataItem["docType"].asTstr,
                        namespaceName = dataItem["namespaceName"].asTstr,
                        dataElementName = dataItem["dataElementName"].asTstr,
                        intentToRetain = dataItem["intentToRetain"].asBoolean
                    )
                }
                else -> throw IllegalStateException("Unexpected type")
            }
        }
    }
}
