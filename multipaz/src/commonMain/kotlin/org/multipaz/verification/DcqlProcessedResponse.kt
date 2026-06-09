package org.multipaz.verification

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.multipaz.claim.Claim
import org.multipaz.claim.JsonClaim
import org.multipaz.claim.MdocClaim

/**
 * Helper object that organizes verified presentation response, so that presentation
 * documents and claims can be accessed in terms of DCQL ids.
 *
 * TODO: currently id mapping for ISO 18013 protocols is built based on docType/VCT. Once/if
 *  docRequestId is generally available, this can be improved.  
 *
 * @param dcql DCQL that was used to generate the request
 * @param presentations a list of verified credential presentations generated in the response
 */
class DcqlProcessedResponse(
    dcql: String,
    presentations: List<VerifiedPresentation>
) {
    /**
     * Mapping of DCQL credential query id to the verified credential presentation returned for that
     * query.
     */
    val credentials: Map<String, Credential> = buildMap {
        val byDocType = mutableMapOf<String, MutableList<JsonObject>>()
        val byVct = mutableMapOf<String, MutableList<JsonObject>>()
        val byId = mutableMapOf<String, JsonObject>()
        for (query in Json.parseToJsonElement(dcql).jsonObject["credentials"]!!.jsonArray) {
            query as JsonObject
            val meta = query["meta"]!!.jsonObject
            byId[query["id"]!!.jsonPrimitive.content] = query
            when (val format = query["format"]!!.jsonPrimitive.content) {
                "dc+sd-jwt" ->
                    for (vct in meta["vct_values"]!!.jsonArray) {
                        byVct.getOrPut(vct.jsonPrimitive.content) { mutableListOf() }.add(query)
                    }
                "mso_mdoc", "mso_mdoc_zk" -> {
                    val docType = meta["doctype_value"]!!.jsonPrimitive.content
                    byDocType.getOrPut(docType) { mutableListOf() }.add(query)
                }
                else -> throw IllegalArgumentException("unknown format: '$format'")
            }
        }
        for (presentation in presentations) {
            val query = if (presentation.vpTokenIdentifier != null) {
                byId[presentation.vpTokenIdentifier]
                    ?: throw IllegalStateException("query not found")
            } else {
                val list = when (presentation) {
                    is JsonVerifiedPresentation -> byVct[presentation.vct]
                    is MdocVerifiedPresentation -> byDocType[presentation.docType]
                } ?: throw IllegalStateException("query not found")
                if (list.size > 1) {
                    throw IllegalStateException("duplicate vct or docTypes are not yet supported")
                }
                list.first()
            }
            put(query["id"]!!.jsonPrimitive.content, Credential(presentation, query))
        }
    }

    /**
     * Container object that contains original credential presentation as [VerifiedPresentation]
     * and the mapping from DCQL claim ids to the [Claim] in the presentation.
     *
     * @param presentation credential presentation
     */
    class Credential internal constructor(
        val presentation: VerifiedPresentation,
        query: JsonObject
    ) {
        /**
         * Mapping of DCQL ids (and simple claim name if id is not given) to the
         * corresponding [Claim].
         *
         * Simple claim name is defined as [MdocClaim.dataElementName] for ISO mdoc and the last
         * name in the path for IETF SD-JWT credentials.
         */
        val claims: Map<String, Claim> = buildMap {
            val idMap = mutableMapOf<List<Any?>, String>()
            for (claim in query["claims"]!!.jsonArray) {
                claim as JsonObject
                val id = claim["id"]?.jsonPrimitive?.content ?: continue
                idMap[pathKey(claim["path"]!!.jsonArray)] = id
            }
            for (claim in presentation.issuerSignedClaims) {
                val pathKey = when (claim) {
                    is MdocClaim -> listOf<Any?>(claim.namespaceName, claim.dataElementName)
                    is JsonClaim -> pathKey(claim.claimPath)
                }
                val name = idMap[pathKey] ?: when (claim) {
                    is MdocClaim -> claim.dataElementName
                    is JsonClaim -> claim.claimPath.last().jsonPrimitive.content
                }
                // ids take precedence over simple claim name
                if (!containsKey(name) || idMap.containsKey(pathKey)) {
                    put(name, claim)
                }
            }
        }

        companion object {
            private fun pathKey(path: JsonArray): List<Any?> =
                path.map { item ->
                    item as JsonPrimitive
                    if (item == JsonNull) { null } else { item.intOrNull ?: item.content }
                }
        }
    }
}