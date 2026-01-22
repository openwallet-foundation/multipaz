package org.multipaz.documenttype

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import kotlin.collections.component1
import kotlin.collections.component2
import kotlin.collections.iterator

/**
 * A class representing a request for a particular set of namespaces and data elements for a particular document type.
 *
 * @param docType the ISO mdoc doctype.
 * @param useZkp `true` if the canned request should indicate a preference for use of Zero-Knowledge Proofs.
 * @param namespacesToRequest the namespaces to request.
 */
data class MdocCannedRequest(
    val docType: String,
    val useZkp: Boolean,
    val namespacesToRequest: List<MdocNamespaceRequest>
) {
    /**
     * Generates DCQL for the request.
     *
     * @return a [JsonObject] with the DCQL for the request.
     */
    fun toDcql() = buildJsonObject {
        putJsonArray("credentials") {
            addJsonObject {
                put("id", JsonPrimitive("cred1"))
                put("format", JsonPrimitive("mso_mdoc"))
                putJsonObject("meta") {
                    put("doctype_value", JsonPrimitive(docType))
                }
                putJsonArray("claims") {
                    for (ns in namespacesToRequest) {
                        for ((de, intentToRetain) in ns.dataElementsToRequest) {
                            addJsonObject {
                                putJsonArray("path") {
                                    add(JsonPrimitive(ns.namespace))
                                    add(JsonPrimitive(de.attribute.identifier))
                                }
                                put("intent_to_retain", JsonPrimitive(intentToRetain))
                            }
                        }
                    }
                }
            }
        }
    }

    fun toDcqlString() = Json.encodeToString(toDcql())

}