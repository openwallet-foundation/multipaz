package org.multipaz.records.request

import io.ktor.http.ContentType
import io.ktor.server.application.ApplicationCall
import io.ktor.server.response.respondText
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import org.multipaz.documenttype.DocumentAttributeType
import org.multipaz.documenttype.IntegerOption
import org.multipaz.documenttype.StringOption
import org.multipaz.records.data.Identity
import org.multipaz.records.data.RecordType
import org.multipaz.records.data.recordTypes
import org.multipaz.records.data.tokenToId

/**
 * Handles `schema` GET request.
 *
 * Returns field names, descriptions, and types for core data and records that are supported by
 * this system-of-record server.
 *
 * Response:
 * ```
 * { "schema": [
 *     {
 *       "identifier": <record type name>,  // or "core" for core data
 *       "display_name": <record type description>,
 *       "type": {  // "type" also can be one of the primitive types
 *         "type": "complex",
 *         "attributes": [
 *           {
 *             "identifier": <field name>,
 *             "display_name": <field description>,
 *             "type": "string"  // can also be "date", "number", "boolean", "blob", or "picture"
 *                  // also "type" can be an object describing a "complex" type using "attributes",
 *                  // or an "options" type, using "options" map (option ids -> labels)
 *                  // complex types can be described in the top-level types array and used by name
 *           },
 *           ...
 *         ]
 *       }
 *     },
 *     ...
 *   ],
 *   "types": {
 *     "typeName1": {  ... }  // named complex types
 *   }
 * }
 * ```
 */
suspend fun identitySchema(call: ApplicationCall) {
    val jsonEnv = JsonEnv()
    call.respondText(
        contentType = ContentType.Application.Json,
        text = buildJsonObject {
            putJsonArray("schema") {
                for (recordType in recordTypes.values) {
                    add(recordType.toJson(
                        jsonEnv,
                        includeInstanceTitle = recordType.attribute.identifier != "core"
                    ))
                }
            }
            put("types", JsonObject(jsonEnv.namedTypes))
        }.toString()
    )
}

private class JsonEnv {
    val namedTypes = mutableMapOf<String, JsonObject>()
    val stringOptions = mutableMapOf<List<StringOption>, String>()
    val intOptions = mutableMapOf<List<IntegerOption>, String>()
}

private fun RecordType.toJson(
    jsonEnv: JsonEnv,
    listItem: Boolean = false,
    includeInstanceTitle: Boolean = false
): JsonObject {
    return buildJsonObject {
        if (!listItem) {
            put("identifier", attribute.identifier)
            put("display_name", attribute.displayName)
            val icon = attribute.icon
            if (icon != null) {
                put("icon", icon.iconName)
            }
        }
        when (val type = attribute.type) {
            DocumentAttributeType.String -> put("type", "string")
            DocumentAttributeType.Date -> put("type", "date")
            DocumentAttributeType.DateTime -> put("type", "datetime")
            DocumentAttributeType.Number -> put("type", "number")
            DocumentAttributeType.Boolean -> put("type", "boolean")
            DocumentAttributeType.Blob -> put("type", "blob")
            DocumentAttributeType.Picture -> put("type", "picture")
            is DocumentAttributeType.StringOptions -> {
                val existingName = jsonEnv.stringOptions[type.options]
                if (existingName != null) {
                    put("type", existingName)
                } else {
                    val newName = "complex" + jsonEnv.namedTypes.size
                    jsonEnv.namedTypes[newName] = buildJsonObject {
                        put("type", "options")
                        putJsonObject("options") {
                            for (option in type.options) {
                                put(option.value ?: "", option.displayName)
                            }
                        }
                    }
                    jsonEnv.stringOptions[type.options] = newName
                    put("type", newName)
                }
            }
            is DocumentAttributeType.IntegerOptions -> {
                val existingName = jsonEnv.intOptions[type.options]
                if (existingName != null) {
                    put("type", existingName)
                } else {
                    val newName = "complex" + jsonEnv.namedTypes.size
                    jsonEnv.namedTypes[newName] = buildJsonObject {
                        put("type", "int_options")
                        putJsonObject("options") {
                            for (option in type.options) {
                                if (option.value == null) {
                                    put("", option.displayName)
                                } else {
                                    put(option.value.toString(), option.displayName)
                                }
                            }
                        }
                    }
                    jsonEnv.intOptions[type.options] = newName
                    put("type", newName)
                }
            }
            is DocumentAttributeType.ComplexType -> putJsonObject("type") {
                if (isList) {
                    put("type", "list")
                    put("elements", listElement.toJson(jsonEnv, listItem = true))
                } else {
                    put("type", "complex")
                    putJsonArray("attributes") {
                        if (includeInstanceTitle) {
                            addJsonObject {
                                put("identifier", "instance_title")
                                put("display_name", "Instance Title")
                                put("type", "string")
                            }
                        }
                        for (recordType in subAttributes.values) {
                            add(recordType.toJson(jsonEnv))
                        }
                    }
                }
            }
        }
    }
}