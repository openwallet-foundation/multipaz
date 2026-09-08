package org.multipaz.lokalize.engine

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.multipaz.lokalize.model.LocaleBundle
import org.multipaz.lokalize.model.ResourceEntry
import org.multipaz.lokalize.model.ResourceEntry.*
import java.io.File

/**
 * JSON resource writer strategy.
 * Generates JSON localization files with support for nested keys (e.g., "folder/subfolder/key")
 * and structured output for plurals and arrays.
 *
 * Example output structure:
 * ```
 * {
 *   "simpleKey": "Simple value",
 *   "nested": {
 *     "key": "Nested value"
 *   },
 *   "pluralKey": {
 *     "_type": "plural",
 *     "one": "%d item",
 *     "other": "%d items"
 *   },
 *   "arrayKey": {
 *     "_type": "array",
 *     "values": ["Item 1", "Item 2", "Item 3"]
 *   }
 * }
 * ```
 */
class JsonResourceWriterStrategy : ResourceWriterStrategy {

    private val json = Json {
        prettyPrint = true
        prettyPrintIndent = "  "
        encodeDefaults = true
    }

    override fun mergeBundle(
        file: File,
        bundle: LocaleBundle,
        preserveExisting: Boolean
    ) {
        file.parentFile?.mkdirs()

        val existingBundle = if (preserveExisting && file.exists()) {
            readExistingBundle(file)
        } else {
            LocaleBundle.empty(bundle.locale)
        }

        // Merge bundles: new entries override existing ones
        val mergedStrings = existingBundle.strings.toMutableMap()
        bundle.strings.forEach { (key, entry) ->
            mergedStrings[key] = entry
        }

        val mergedPlurals = existingBundle.plurals.toMutableMap()
        bundle.plurals.forEach { (key, entry) ->
            mergedPlurals[key] = entry
        }

        val mergedArrays = existingBundle.arrays.toMutableMap()
        bundle.arrays.forEach { (key, entry) ->
            mergedArrays[key] = entry
        }

        val mergedBundle = LocaleBundle(
            locale = bundle.locale,
            strings = mergedStrings,
            plurals = mergedPlurals,
            arrays = mergedArrays
        )

        writeBundle(file, mergedBundle)
    }

    override fun writeBundle(file: File, bundle: LocaleBundle) {
        file.parentFile?.mkdirs()

        val jsonObject = buildJsonObject {
            // Handle strings (including nested keys)
            val (flatStrings, nestedStrings) = bundle.strings.values.partition { entry ->
                !entry.key.contains("/")
            }

            // Write flat strings directly
            flatStrings.forEach { entry ->
                put(entry.key, entry.value)
            }

            // Group and write nested strings
            val nestedGroups = nestedStrings.groupBy { entry ->
                entry.key.substringBefore("/")
            }

            nestedGroups.forEach { (folder, entries) ->
                val folderObject = buildJsonObject {
                    entries.forEach { entry ->
                        val subKey = entry.key.substringAfter("/")
                        putNestedValue(this, subKey, entry.value)
                    }
                }
                put(folder, folderObject)
            }

            // Handle plurals
            bundle.plurals.values.forEach { pluralEntry ->
                val pluralObject = buildJsonObject {
                    put(TYPE_MARKER, PLURAL_TYPE)
                    pluralEntry.items.forEach { (quantity, value) ->
                        put(quantity, value)
                    }
                }
                put(pluralEntry.key, pluralObject)
            }

            // Handle arrays
            bundle.arrays.values.forEach { arrayEntry ->
                val arrayValues = buildJsonArray {
                    arrayEntry.items.forEach { value ->
                        add(JsonPrimitive(value))
                    }
                }
                val arrayObject = buildJsonObject {
                    put(TYPE_MARKER, ARRAY_TYPE)
                    put(VALUES_KEY, arrayValues)
                }
                put(arrayEntry.key, arrayObject)
            }
        }

        file.writeText(json.encodeToString(JsonObject.serializer(), jsonObject) + "\n")
    }

    /**
     * Reads an existing JSON file and converts it to a LocaleBundle.
     */
    private fun readExistingBundle(file: File): LocaleBundle {
        if (!file.exists()) {
            return LocaleBundle.empty("")
        }

        val content = file.readText()
        val jsonObject = json.parseToJsonElement(content).jsonObject

        val strings = mutableMapOf<String, ResourceEntry.StringEntry>()
        val plurals = mutableMapOf<String, ResourceEntry.PluralEntry>()
        val arrays = mutableMapOf<String, ResourceEntry.StringArrayEntry>()

        jsonObject.forEach { (key, element) ->
            when (element) {
                is JsonObject -> {
                    val typeMarker = element[TYPE_MARKER]?.jsonPrimitive?.content
                    when (typeMarker) {
                        PLURAL_TYPE -> {
                            // It's a plural
                            val items = element
                                .filter { it.key != TYPE_MARKER }
                                .mapValues { it.value.jsonPrimitive.content }
                            plurals[key] = PluralEntry(key, items)
                        }

                        ARRAY_TYPE -> {
                            // It's an array
                            val values = element[VALUES_KEY]?.let { valuesElement ->
                                if (valuesElement is JsonArray) {
                                    valuesElement.map { it.jsonPrimitive.content }
                                } else emptyList()
                            } ?: emptyList()
                            arrays[key] = StringArrayEntry(key, values)
                        }

                        else -> {
                            // It's a nested object - extract all leaf values
                            extractNestedStrings(key, element, strings)
                        }
                    }
                }

                is JsonPrimitive -> {
                    // It's a simple string
                    strings[key] = StringEntry(key, element.content)
                }

                is JsonArray -> Unit
            }
        }

        return LocaleBundle(
            locale = "",
            strings = strings,
            plurals = plurals,
            arrays = arrays
        )
    }

    /**
     * Recursively extracts nested string values from a JSON object.
     */
    private fun extractNestedStrings(
        prefix: String,
        jsonObject: JsonObject,
        result: MutableMap<String, ResourceEntry.StringEntry>
    ) {
        jsonObject.forEach { (key, element) ->
            val fullKey = "$prefix/$key"
            when {
                element is JsonPrimitive -> {
                    result[fullKey] = ResourceEntry.StringEntry(fullKey, element.content)
                }
                element is JsonObject -> {
                    extractNestedStrings(fullKey, element, result)
                }
            }
        }
    }

    /**
     * Puts a nested value into a JsonObject builder, creating intermediate objects as needed.
     */
    private fun putNestedValue(builder: kotlinx.serialization.json.JsonObjectBuilder, key: String, value: String) {
        if (!key.contains("/")) {
            builder.put(key, value)
            return
        }

        val parts = key.split("/")
        val firstPart = parts.first()
        val remaining = parts.drop(1).joinToString("/")

        // This is a simplified approach - for deep nesting, you'd need to build recursively
        // For now, we handle one level of nesting
        if (parts.size == 1) {
            builder.put(key, value)
        } else {
            // For deeper nesting, create a nested object
            val nestedObject = buildJsonObject {
                putNestedValue(this, remaining, value)
            }
            builder.put(firstPart, nestedObject)
        }
    }

    companion object {
        private const val TYPE_MARKER = "_type"
        private const val PLURAL_TYPE = "plural"
        private const val ARRAY_TYPE = "array"
        private const val VALUES_KEY = "values"
    }
}
