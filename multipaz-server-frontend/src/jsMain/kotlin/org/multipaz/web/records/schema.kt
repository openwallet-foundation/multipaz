package org.multipaz.web.records

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.multipaz.web.common.jsonGet

/**
 * Describes a single item in the System of Records data schema.
 */
class SchemaItem(
    val displayName: String,
    val icon: String?,
    lazyType: Lazy<SchemaType>
) {
    val type: SchemaType by lazyType
}

/**
 * Supported data types in the System of Records data schema
 */
sealed class SchemaType {
    data object String: SchemaType()
    data object Date: SchemaType()
    data object DateTime: SchemaType()
    data object Number: SchemaType()
    data object Boolean: SchemaType()
    data object Blob: SchemaType()
    data object Picture: SchemaType()
    class Compound(
        val attributes: Map<kotlin.String, SchemaItem>
    ): SchemaType()
    data class List(
        val element: SchemaType
    ): SchemaType()
    data class Options(
        val base: SchemaType,
        val options: Map<kotlin.String, JsonElement>
    ): SchemaType()
}

/**
 * Load and parse schema from the System of Records service
 *
 * @param serviceUrl base url of the System of Records service
 */
suspend fun fetchSchema(serviceUrl: String): Map<String, SchemaItem> {
    val schema = jsonGet("$serviceUrl/identity/schema", "schema").jsonObject
    val namedTypes = mutableMapOf<String, Lazy<SchemaType>>(
        "string" to lazy { SchemaType.String },
        "date" to lazy { SchemaType.Date },
        "datetime" to lazy { SchemaType.DateTime },
        "number" to lazy { SchemaType.Number },
        "boolean" to lazy { SchemaType.Boolean },
        "blob" to lazy { SchemaType.Blob },
        "picture" to lazy { SchemaType.Picture }
    )
    schema["types"]!!.jsonObject.forEach { (name, definition) ->
        namedTypes[name] = parseType(namedTypes, definition)
    }
    return parseCompound(namedTypes, schema["schema"]!!.jsonArray).attributes
}

private fun parseType(
    namedTypes: Map<String, Lazy<SchemaType>>,
    definition: JsonElement
): Lazy<SchemaType> = lazy {
    when (definition) {
        is JsonPrimitive -> namedTypes[definition.content]?.value
            ?: throw IllegalArgumentException("No such type: '${definition.content}'")
        is JsonObject -> when (val type = definition["type"]!!.jsonPrimitive.content) {
            "complex" -> parseCompound(namedTypes, definition["attributes"]!!.jsonArray)
            "list" -> parseList(namedTypes, definition["elements"]!!.jsonObject["type"]!!)
            "int_options" -> parseOptions(SchemaType.Number, definition["options"]!!.jsonObject)
            "options" -> parseOptions(SchemaType.String, definition["options"]!!.jsonObject)
            else -> throw IllegalArgumentException("Illegal complex type definition '$type'")
        }
        else -> throw IllegalArgumentException("Illegal type definition")
    }
}

private fun parseCompound(
    namedTypes: Map<String, Lazy<SchemaType>>,
    attributes: JsonArray
) = SchemaType.Compound(
    attributes = attributes.associate { item ->
        val itemDef = item.jsonObject
        val identifier = itemDef["identifier"]!!.jsonPrimitive.content
        val displayName = itemDef["display_name"]?.jsonPrimitive?.content ?: identifier
        val type = parseType(namedTypes, itemDef["type"]!!)
        val icon = itemDef["icon"]?.jsonPrimitive?.content
        Pair(identifier, SchemaItem(displayName, icon, type))
    }
)

private fun parseList(
    namedTypes: Map<String, Lazy<SchemaType>>,
    definition: JsonElement
) = SchemaType.List(parseType(namedTypes, definition).value)

private fun parseOptions(
    baseType: SchemaType,
    options: JsonObject
) = SchemaType.Options(baseType, options)