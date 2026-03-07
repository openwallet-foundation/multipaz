package org.multipaz.records.request

import io.ktor.http.ContentType
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.receiveText
import io.ktor.server.response.respondText
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.putJsonObject
import org.multipaz.records.data.Identity
import org.multipaz.records.data.TokenType
import org.multipaz.records.data.toJsonRecord
import org.multipaz.records.data.tokenToId

/**
 * Handles `get` POST request.
 *
 * Fetches requested data for identity with the given token (see [tokenToId]).
 *
 * Request format:
 * ```
 * {
 *     "token": "...."
 *     "core": ["field1", "field2", ...],
 *     "records": { "recordType1": [ "recordId1", ... ], ... }
 * }
 * ```
 *
 * Empty array for as a value for a given record type means all records of the given type must be
 * returned.
 *
 * Response:
 * ```
 * {
 *     "core": { "field1": "value1", ... }
 *     "records": {
 *        "recordType1": record1,
 *        ...
 *     }
 * }
 * ```
 */
suspend fun identityGet(call: ApplicationCall) {
    val request = Json.parseToJsonElement(call.receiveText()) as JsonObject
    val feToken = request["token"]!!.jsonPrimitive.content
    val id = tokenToId(TokenType.FE_TOKEN, feToken)
    val identity = Identity.findById(id)
    val fields = request["core"]!!.jsonArray.map { it.jsonPrimitive.content }
    val records = request["records"]!!.jsonObject.asIterable().associate { (key, list) ->
        Pair(key, list.jsonArray.map { it.jsonPrimitive.content})
    }
    call.respondText (
        contentType = ContentType.Application.Json,
        text = identityToJson(identity, fields, records).toString()
    )
}

internal fun identityToJson(
    identity: Identity,
    fields: List<String>,
    records: Map<String, List<String>>
) = buildJsonObject {
    putJsonObject("core") {
        for (field in fields) {
            val value = identity.data.core[field]
            if (value != null) {
                put(field, value.toJsonRecord())
            }
        }
    }
    putJsonObject("records") {
        for ((recordType, list) in records) {
            val recordMap = identity.data.records[recordType]
            if (recordMap != null) {
                val recordIds = list.ifEmpty { recordMap.keys }
                putJsonObject(recordType) {
                    for (recordId in recordIds) {
                        val record = recordMap[recordId]
                        if (record != null) {
                            put(recordId, record.toJsonRecord())
                        }
                    }
                }
            }
        }
    }
}