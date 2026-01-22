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
 * A class representing a request for claims.
 *
 * @param vct the verifiable credential type.
 * @param claimsToRequest the claims to request.
 */
data class JsonCannedRequest(
    val vct: String,
    val claimsToRequest: List<DocumentAttribute>
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
                put("format", JsonPrimitive("dc+sd-jwt"))
                putJsonObject("meta") {
                    put(
                        "vct_values",
                        buildJsonArray {
                            add(JsonPrimitive(vct))
                        }
                    )
                }
                putJsonArray("claims") {
                    for (claim in claimsToRequest) {
                        addJsonObject {
                            putJsonArray("path") {
                                claim.parentAttribute?.let { add(JsonPrimitive(it.identifier)) }
                                add(JsonPrimitive(claim.identifier))
                            }
                        }
                    }
                }
            }
        }
    }

    fun toDcqlString() = Json.encodeToString(toDcql())
}
