package org.multipaz.verification

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonPrimitive
import org.multipaz.cbor.annotation.CborSerializable
import org.multipaz.mdoc.request.DeviceRequest

/**
 * Details about query for a particular credential in DCQL query.
 *
 * @property id id of the query
 * @property multiple true if multiple credentials of the given type should be returned
 */
@CborSerializable
sealed class QueryData {
    abstract val id: String?
    abstract val multiple: Boolean

    companion object {
        /**
         * Extract [QueryData] for each requested document from DCQL query.
         *
         * @param dcql DCQL query
         * @return [QueryData] for each requested document
         */
        fun fromDcql(dcql: JsonObject): List<QueryData> {
            val credentials = dcql["credentials"] as? JsonArray
                ?: throw IllegalArgumentException("'credentials' is missing or invalid")
            return credentials.map { credential ->
                credential as? JsonObject
                    ?: throw IllegalArgumentException("only objects are allowed in 'credentials' array")
                val id = (credential["id"] as? JsonPrimitive)?.content
                    ?: throw IllegalArgumentException("'id' is missing or invalid in a credential query")
                val format = (credential["format"] as? JsonPrimitive)?.content
                    ?: throw IllegalArgumentException("'format' is missing or invalid in credential query '$id'")
                val meta = credential["meta"] as? JsonObject
                    ?: throw IllegalArgumentException("'meta' is missing or invalid in credential query '$id'")
                val claims = credential["claims"] as? JsonArray
                    ?: throw IllegalArgumentException("'claims' is missing or invalid in credential query '$id'")
                val multiple = (credential["multiple"] as JsonPrimitive?)?.booleanOrNull ?: false
                when (format) {
                    "dc+sd-jwt" -> {
                        val vctArray = meta["vct_values"] as? JsonArray
                            ?: throw IllegalArgumentException("'meta.vct_values' is missing or invalid in credential query '$id'")
                        SdJwtQueryData(id, multiple, vctArray.map {
                            (it as? JsonPrimitive)?.content
                                ?: throw IllegalArgumentException("'meta.vct_values' is not an array of string in credential query '$id'")
                        })
                    }
                    "mso_mdoc", "mso_mdoc_zk" -> {
                        val docType = (meta["doctype_value"] as? JsonPrimitive)?.content
                            ?: throw IllegalArgumentException("'meta.doctype_value' is missing or invalid in credential query '$id'")
                        val namespaces = claims.map { claim ->
                            claim as? JsonObject
                                ?: throw IllegalArgumentException("invalid claim in credential query '$id'")
                            val path = claim["path"] as? JsonArray
                                ?: throw IllegalArgumentException("claim path is missing or invalid in credential query '$id'")
                            if (path.size != 2 || path[0] !is JsonPrimitive || path[1] !is JsonPrimitive) {
                                throw IllegalArgumentException("invalid claim path in credential query '$id'")
                            }
                            path[0].jsonPrimitive.content to MdocQueryData.QueryClaim(
                                name = path[1].jsonPrimitive.content,
                                identifier = (claim["id"] as? JsonPrimitive)?.content
                            )
                        }.groupBy(keySelector = { it.first }).values.map { list ->
                            MdocQueryData.QueryNamespace(
                                namespace = list.first().first,
                                claims = list.map { it.second }
                            )
                        }
                        MdocQueryData(id, multiple, docType, namespaces)
                    }
                    else -> throw IllegalArgumentException("'format' value '$format' is invalid in credential query '$id'")
                }
            }
        }

        /**
         * Extract [QueryData] for each requested document from ISO 18013 credential request.
         *
         * Note: [MdocQueryData.id] and [MdocQueryData.QueryClaim.identifier] will be set to null
         * as ISO 18013 credential request does not contain this info.
         *
         * @param deviceRequest ISO 18013 credential request
         * @return [QueryData] for each requested document
         */
        fun fromDeviceRequest(deviceRequest: DeviceRequest): List<MdocQueryData> {
            return deviceRequest.docRequests.map { docRequest ->
                MdocQueryData(
                    id = null,
                    multiple = false,
                    docType = docRequest.docType,
                    namespaces = docRequest.nameSpaces.map { (namespace, claims) ->
                        MdocQueryData.QueryNamespace(
                            namespace = namespace,
                            claims = claims.map { (name, _) ->
                                MdocQueryData.QueryClaim(
                                    name = name,
                                    identifier = null
                                )
                            }
                        )
                    }
                )
            }
        }
    }
}