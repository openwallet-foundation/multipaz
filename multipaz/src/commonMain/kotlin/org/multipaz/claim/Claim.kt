package org.multipaz.claim

import org.multipaz.documenttype.DocumentAttribute
import org.multipaz.request.RequestedClaim
import kotlinx.datetime.TimeZone
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import org.multipaz.cbor.DataItem
import org.multipaz.cbor.annotation.CborSerializationImplemented
import org.multipaz.cbor.buildCborMap
import org.multipaz.documenttype.DocumentTypeRepository
import org.multipaz.request.JsonRequestedClaim
import org.multipaz.request.MdocRequestedClaim

/**
 * Base class used for representing a claim.
 *
 * @property displayName a short human readable string describing the claim.
 * @property attribute a [DocumentAttribute], if the claim is for a well-known attribute.
 */
@CborSerializationImplemented(schemaId = "")
sealed class Claim(
    open val displayName: String,
    open val attribute: DocumentAttribute?
) {
    /**
     * Returns the value of a claim as a human readable string.
     *
     * If [Claim.attribute] is set, its type is used when rendering for example to resolve integer options to strings.
     *
     * @param timeZone the time zone to use for rendering dates and times.
     * @return textual representation of the claim.
     */
    abstract fun render(timeZone: TimeZone = TimeZone.currentSystemDefault()): String

    /**
     * Serializes [Claim] to CBOR.
     *
     * Note that [attribute] won't be serialized.
     *
     * @return a [DataItem].
     */
    fun toDataItem() = buildCborMap {
        put("displayName", displayName)
        when (this@Claim) {
            is JsonClaim -> {
                put("type", TYPE_JSON_CLAIM)
                put("vct", vct)
                put("claimPath", Json.encodeToString(claimPath))
                put("value", Json.encodeToString(value))
            }
            is MdocClaim -> {
                put("type", TYPE_MDOC_CLAIM)
                put("docType", docType)
                put("namespaceName", namespaceName)
                put("dataElementName", dataElementName)
                put("value", value)
            }
        }
    }

    companion object {
        private const val TYPE_JSON_CLAIM = 0
        private const val TYPE_MDOC_CLAIM = 1

        /**
         * Creates a [Claim] previously serialized with [Claim.toDataItem].
         *
         * @param dataItem a [DataItem].
         * @param documentTypeRepository if not `null`, will be used to look up a [DocumentAttribute] for the claim.
         * @return a [Claim].
         */
        fun fromDataItem(
            dataItem: DataItem,
            documentTypeRepository: DocumentTypeRepository? = null
        ): Claim {

            val displayName = dataItem["displayName"].asTstr
            when (dataItem["type"].asNumber.toInt()) {
                TYPE_JSON_CLAIM -> {
                    val vct = dataItem["vct"].asTstr
                    val claimPath = Json.decodeFromString<JsonArray>(dataItem["claimPath"].asTstr)
                    val attribute = documentTypeRepository
                        ?.getDocumentTypeForJson(vct)
                        ?.jsonDocumentType
                        ?.claims[claimPath.joinToString(".") { it.jsonPrimitive.content }]
                    return JsonClaim(
                        displayName = displayName,
                        attribute = attribute,
                        vct = vct,
                        claimPath = claimPath,
                        value = Json.decodeFromString<JsonElement>(dataItem["value"].asTstr),
                    )
                }
                TYPE_MDOC_CLAIM -> {
                    val docType = dataItem["docType"].asTstr
                    val namespaceName = dataItem["namespaceName"].asTstr
                    val dataElementName = dataItem["dataElementName"].asTstr
                    val mdocDocType = documentTypeRepository?.getDocumentTypeForMdoc(docType)
                        ?: documentTypeRepository?.getDocumentTypeForMdocNamespace(namespaceName)
                    val attribute = mdocDocType?.mdocDocumentType
                        ?.namespaces[namespaceName]
                        ?.dataElements[dataElementName]
                        ?.attribute
                    return MdocClaim(
                        displayName = displayName,
                        attribute = attribute,
                        docType = docType,
                        namespaceName = namespaceName,
                        dataElementName = dataElementName,
                        value = dataItem["value"]
                    )
                }
                else -> throw IllegalStateException("Unexpected type")
            }
        }
    }
}

/**
 * Resolve a claim request against a list of claims.
 *
 * @param requestedClaim the claim that is requested.
 * @return a [Claim] instance with the value or `null` if not available in the list of claims.
 */
fun List<Claim>.findMatchingClaim(requestedClaim: RequestedClaim): Claim? {
    return when (requestedClaim) {
        is MdocRequestedClaim -> {
            mdocFindMatchingClaimValue(this, requestedClaim)
        }
        is JsonRequestedClaim -> {
            jsonFindMatchingClaimValue(this, requestedClaim)
        }
    }
}

/**
 * Resolves a list of claim requests against a list of claims
 *
 * @param requestedClaims a list of claim requests.
 * @return a list of [Claim] for the resolved claim requests.
 */
fun List<Claim>.findMatchingClaims(requestedClaims: List<RequestedClaim>): List<Claim> {
    return requestedClaims.mapNotNull { findMatchingClaim(it) }
}


private fun Claim.filterValueMatch(
    values: JsonArray?,
): Claim? {
    if (values == null) {
        return this
    }
    when (this) {
        is JsonClaim -> if (values.contains(value)) return this
        is MdocClaim -> if (values.contains(value.toJson())) return this
    }
    return null
}

private fun mdocFindMatchingClaimValue(
    claims: List<Claim>,
    requestedClaim: RequestedClaim
): MdocClaim? {
    if (requestedClaim !is MdocRequestedClaim) {
        return null
    }
    for (credentialClaim in claims) {
        credentialClaim as MdocClaim
        if (credentialClaim.namespaceName == requestedClaim.namespaceName &&
            credentialClaim.dataElementName == requestedClaim.dataElementName) {
            return credentialClaim.filterValueMatch(requestedClaim.values) as MdocClaim?
        }
    }
    return null
}

private fun jsonFindMatchingClaimValue(
    claims: List<Claim>,
    requestedClaim: RequestedClaim,
): JsonClaim? {
    if (requestedClaim !is JsonRequestedClaim) {
        return null
    }
    check(requestedClaim.claimPath.size >= 1)
    check(requestedClaim.claimPath[0].isString)
    var ret: JsonClaim? = null
    for (credentialClaim in claims) {
        credentialClaim as JsonClaim
        if (credentialClaim.claimPath[0].jsonPrimitive.content == requestedClaim.claimPath[0].jsonPrimitive.content) {
            ret = credentialClaim
            break
        }
    }
    if (ret == null) {
        return null
    }
    if (requestedClaim.claimPath.size == 1) {
        return ret.filterValueMatch(requestedClaim.values) as JsonClaim?
    }

    // OK, path>1 so we descend into the object...
    var currentObject: JsonElement? = ret.value
    var currentAttribute: DocumentAttribute? = ret.attribute

    for (n in IntRange(1, requestedClaim.claimPath.size - 1)) {
        val pathComponent = requestedClaim.claimPath[n]
        if (pathComponent.isString) {
            if (currentObject is JsonArray) {
                val newObject = buildJsonArray {
                    for (element in currentObject.jsonArray) {
                        add(element.jsonObject[pathComponent.jsonPrimitive.content]!!)
                    }
                }
                currentObject = newObject
                currentAttribute = null
            } else if (currentObject is JsonObject) {
                currentObject = currentObject.jsonObject[pathComponent.jsonPrimitive.content]
                currentAttribute = currentAttribute?.embeddedAttributes?.find {
                    it.identifier == pathComponent.jsonPrimitive.content
                }
            } else {
                throw Error("Can only select from object or array of objects")
            }
        } else if (pathComponent.isNumber) {
            currentObject = currentObject!!.jsonArray[pathComponent.jsonPrimitive.int]
            currentAttribute = null
        } else if (pathComponent.isNull) {
            currentObject = currentObject!!.jsonArray
            currentAttribute = null
        }
    }
    if (currentObject == null) {
        return null
    }

    val (displayName, attribute) = if (currentAttribute != null) {
        Pair(currentAttribute.displayName, currentAttribute)
    } else {
        // Fall back, use path elements as the display name (e.g. address.street_address)
        val combinedPath = requestedClaim.claimPath.joinToString(".", transform = { it.jsonPrimitive.content })
        Pair(combinedPath, null)
    }
    return JsonClaim(
        displayName = displayName,
        attribute = attribute,
        vct = ret.vct,
        claimPath = requestedClaim.claimPath,
        value = currentObject
    ).filterValueMatch(requestedClaim.values) as JsonClaim?
}


private val JsonElement.isNull: Boolean
    get() = this is JsonNull

private val JsonElement.isNumber: Boolean
    get() = this is JsonPrimitive && !isString && longOrNull != null

private val JsonElement.isString: Boolean
    get() = this is JsonPrimitive && isString
