package org.multipaz.documenttype

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonArray
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
 * @param portraitEquivalenceRequest if not `null`, request portrait equivalence data elements.
 * @param namespacesToRequest the namespaces to request.
 * @param portraitEquivalenceData if not `null`, information about portrait image equivalence support for the doctype.
 */
data class MdocCannedRequest(
    val docType: String,
    val useZkp: Boolean,
    val portraitEquivalenceRequest: PortraitEquivalenceRequest?,
    val namespacesToRequest: List<MdocNamespaceRequest>,
    val portraitEquivalenceData: MdocPortraitEquivalenceData? = null,
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
                var claimCount = 0
                putJsonArray("claims") {
                    for (ns in namespacesToRequest) {
                        for ((de, intentToRetain) in ns.dataElementsToRequest) {
                            addJsonObject {
                                if (portraitEquivalenceRequest != null && portraitEquivalenceRequest.includePortrait) {
                                    put("id", "c${claimCount++}")
                                }
                                putJsonArray("path") {
                                    add(JsonPrimitive(ns.namespace))
                                    add(JsonPrimitive(de.attribute.identifier))
                                }
                                put("intent_to_retain", JsonPrimitive(intentToRetain))
                            }
                        }
                    }
                    portraitEquivalenceRequest?.let { portraitEquivalenceRequest ->
                        addJsonObject {
                            if (portraitEquivalenceRequest.includePortrait) {
                                put("id", "pe_chv1")
                            }
                            putJsonArray("path") {
                                add(JsonPrimitive(ISO_23220_5_CHV_1_NAMESPACE))
                                add(JsonPrimitive(ISO_23220_5_CHV_1_DATA_ELEMENT))
                            }
                            put("intent_to_retain", JsonPrimitive(portraitEquivalenceRequest.intentToRetain))
                        }
                        if (portraitEquivalenceRequest.includePortrait) {
                            val peData = checkNotNull(portraitEquivalenceData) {
                                "portraitEquivalenceData must be set when includePortrait is true"
                            }
                            addJsonObject {
                                put("id", "pe_portrait")
                                putJsonArray("path") {
                                    add(JsonPrimitive(peData.namespace))
                                    add(JsonPrimitive(peData.dataElementName))
                                }
                                put("intent_to_retain", JsonPrimitive(portraitEquivalenceRequest.intentToRetain))
                            }
                        }
                    }
                }
                if (portraitEquivalenceRequest != null && portraitEquivalenceRequest.includePortrait) {
                    putJsonArray("claim_sets") {
                        addJsonArray {
                            for (n in 0 until claimCount) {
                                add("c$n")
                            }
                            add("pe_chv1")
                        }
                        addJsonArray {
                            for (n in 0 until claimCount) {
                                add("c$n")
                            }
                            add("pe_portrait")
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