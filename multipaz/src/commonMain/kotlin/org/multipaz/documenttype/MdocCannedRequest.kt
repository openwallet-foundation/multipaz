package org.multipaz.documenttype

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import org.multipaz.mdoc.zkp.ZkSystemSpec
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
     * @param zkSystemSpecs list of Zero-Knowledge system specs that can handle the request; only
     *   used when [useZkp] is `true`.
     * @return a [JsonObject] with the DCQL for the request.
     */
    fun toDcql(zkSystemSpecs: List<ZkSystemSpec>) = buildJsonObject {
        putJsonArray("credentials") {
            addJsonObject {
                put("id", JsonPrimitive("cred1"))
                if (useZkp) {
                    put("format", JsonPrimitive("mso_mdoc_zk"))
                } else {
                    put("format", JsonPrimitive("mso_mdoc"))
                }
                putJsonObject("meta") {
                    put("doctype_value", JsonPrimitive(docType))
                    if (useZkp) {
                        putJsonArray("zk_system_type") {
                            for (spec in zkSystemSpecs) {
                                addJsonObject {
                                    put("system", spec.system)
                                    put("id", spec.id)
                                    spec.params.forEach { param ->
                                        put(param.key, param.value.toJson())
                                    }
                                }
                            }
                        }
                    }
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

    /**
     * Generates DCQL for the request.
     *
     * @param zkSystemSpecs list of Zero-Knowledge system specs that can handle the request; only
     *   used when [useZkp] is `true`.
     * @return a string with serialized [JsonObject] with the DCQL for the request.
     */
    fun toDcqlString(zkSystemSpecs: List<ZkSystemSpec>) = Json.encodeToString(toDcql(zkSystemSpecs))
}