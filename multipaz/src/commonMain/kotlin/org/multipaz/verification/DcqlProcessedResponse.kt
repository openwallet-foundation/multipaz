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
import org.multipaz.claim.findMatchingClaim
import org.multipaz.documenttype.DocumentTypeRepository
import org.multipaz.openid.dcql.DcqlCredentialQuery
import org.multipaz.openid.dcql.DcqlQuery
import org.multipaz.request.JsonRequestedClaim

/**
 * Helper object that organizes verified presentation response, so that presentation
 * documents and claims can be accessed in terms of DCQL ids.
 *
 * @param dcql DCQL that was used to generate the request
 * @param presentations a list of verified credential presentations generated in the response
 * @param documentTypeRepository optional [DocumentTypeRepository] used to resolve verifier-approved
 *   `vct` aliases via [org.multipaz.documenttype.JsonDocumentType.otherVcts].
 */
class DcqlProcessedResponse(
    dcql: String,
    presentations: List<VerifiedPresentation>,
    documentTypeRepository: DocumentTypeRepository? = null,
) {
    /**
     * Set of DCQL credential query ids that have `multiple` set to `true`.
     */
    val multipleQueryIds: Set<String>

    /**
     * Mapping of DCQL credential query id to the list of verified credential presentations returned
     * for that query.
     */
    val credentialLists: Map<String, List<Credential>>

    /**
     * Mapping of DCQL credential query id to the verified credential presentation returned for that
     * query.
     */
    val credentials: Map<String, Credential>

    init {
        val dcqlJson = Json.parseToJsonElement(dcql).jsonObject
        val dcqlQuery = DcqlQuery.fromJson(dcqlJson)
        val credQueryById = dcqlQuery.credentialQueries.associateBy { it.id }
        multipleQueryIds = dcqlQuery.credentialQueries.filter { it.multiple }.map { it.id }.toSet()
        val credentialsJsonArray = dcqlJson["credentials"]!!.jsonArray
        val byDocType = mutableMapOf<String, MutableList<JsonObject>>()
        val byVct = mutableMapOf<String, MutableList<JsonObject>>()
        val byId = mutableMapOf<String, JsonObject>()
        for (query in credentialsJsonArray) {
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
        val presentationsByQueryId = mutableMapOf<String, MutableList<VerifiedPresentation>>()
        val credentialListsMap = mutableMapOf<String, MutableList<Credential>>()
        for (presentation in presentations) {
            val explicitIds = presentation.docRequestIds
            val matchedQueries: List<JsonObject> = if (presentation.vpTokenIdentifier != null) {
                val query = byId[presentation.vpTokenIdentifier]
                    ?: throw IllegalStateException("query not found")
                listOf(query)
            } else if (explicitIds != null) {
                if (explicitIds.isEmpty() || explicitIds.distinct().size != explicitIds.size) {
                    throw IllegalStateException("invalid docRequestIds: $explicitIds")
                }
                explicitIds.map { reqId ->
                    if (reqId !in credentialsJsonArray.indices) {
                        throw IllegalStateException("docRequestId $reqId out of bounds for DCQL credentials")
                    }
                    credentialsJsonArray[reqId].jsonObject
                }
            } else {
                val list = when (presentation) {
                    is JsonVerifiedPresentation -> byVct[presentation.vct]
                        ?: if (documentTypeRepository != null) {
                            byVct.entries
                                .filter { (reqVct, _) -> documentTypeRepository.vctMatches(presentation.vct, reqVct) }
                                .flatMap { it.value }
                                .distinct()
                                .ifEmpty { null }
                        } else {
                            null
                        }
                    is MdocVerifiedPresentation -> byDocType[presentation.docType]
                } ?: throw IllegalStateException("query not found")
                if (list.size > 1) {
                    throw IllegalStateException("duplicate vct or docTypes require docRequestIds")
                }
                listOf(list.first())
            }
            for (query in matchedQueries) {
                val queryId = query["id"]!!.jsonPrimitive.content
                val credQuery = credQueryById[queryId]
                    ?: throw IllegalStateException("query not found")
                presentationsByQueryId.getOrPut(queryId) { mutableListOf() }.add(presentation)
                credentialListsMap.getOrPut(queryId) { mutableListOf() }.add(
                    Credential(presentation, query, credQuery)
                )
            }
        }
        dcqlQuery.checkResponseSatisfiesQuery(
            presentationsByQueryId = presentationsByQueryId,
            documentTypeRepository = documentTypeRepository,
        )
        credentialLists = credentialListsMap
        credentials = credentialListsMap.mapValues { (_, list) -> list.last() }
    }

    /**
     * Container object that contains original credential presentation as [VerifiedPresentation]
     * and the mapping from DCQL claim ids to the [Claim] in the presentation.
     *
     * @param presentation credential presentation
     */
    class Credential internal constructor(
        val presentation: VerifiedPresentation,
        query: JsonObject,
        credQuery: DcqlCredentialQuery,
    ) {
        /**
         * Mapping of DCQL ids (and simple claim name if id is not given) to the
         * corresponding issuer-signed [Claim].
         *
         * Simple claim name is defined as [MdocClaim.dataElementName] for ISO mdoc and the last
         * name in the path for IETF SD-JWT credentials.
         */
        val issuerSignedClaims: Map<String, Claim>

        /**
         * Mapping of DCQL ids (and simple claim name if id is not given) to the
         * corresponding device-signed [Claim].
         *
         * Simple claim name is defined as [MdocClaim.dataElementName] for ISO mdoc and the last
         * name in the path for IETF SD-JWT Key Binding JWT claims. For `dc+sd-jwt` credentials,
         * DCQL claim ids apply only to [issuerSignedClaims].
         */
        val deviceSignedClaims: Map<String, Claim>

        init {
            val idMap = mutableMapOf<List<Any?>, String>()
            for (claim in query["claims"]?.jsonArray ?: emptyList()) {
                claim as JsonObject
                val id = claim["id"]?.jsonPrimitive?.content ?: continue
                idMap[pathKey(claim["path"]!!.jsonArray)] = id
            }
            val mappedIssuerClaims = mapClaims(presentation.issuerSignedClaims, idMap).toMutableMap()
            if (presentation is JsonVerifiedPresentation) {
                for (requestedClaim in credQuery.claims) {
                    if (requestedClaim is JsonRequestedClaim && requestedClaim.claimPath.size > 1) {
                        val matchedClaim = presentation.issuerSignedClaims.findMatchingClaim(requestedClaim)
                            ?: continue
                        if (requestedClaim.id != null) {
                            mappedIssuerClaims[requestedClaim.id] = matchedClaim
                        } else {
                            val lastSegment = requestedClaim.claimPath.last() as? JsonPrimitive
                            if (lastSegment != null && lastSegment.isString &&
                                !mappedIssuerClaims.containsKey(lastSegment.content)
                            ) {
                                mappedIssuerClaims[lastSegment.content] = matchedClaim
                            }
                        }
                    }
                }
            }
            issuerSignedClaims = mappedIssuerClaims
            deviceSignedClaims = mapClaims(
                claimsList = presentation.deviceSignedClaims,
                idMap = if (presentation is JsonVerifiedPresentation) emptyMap() else idMap
            )
        }

        companion object {
            private fun mapClaims(
                claimsList: List<Claim>,
                idMap: Map<List<Any?>, String>
            ): Map<String, Claim> = buildMap {
                for (claim in claimsList) {
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

            private fun pathKey(path: JsonArray): List<Any?> =
                path.map { item ->
                    item as JsonPrimitive
                    if (item == JsonNull) { null } else { item.intOrNull ?: item.content }
                }
        }
    }
}