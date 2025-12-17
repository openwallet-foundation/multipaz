package org.multipaz.server.common

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.multipaz.rpc.backend.Configuration
import java.io.File
import kotlin.collections.iterator

/**
 * Server-side configuration implementation.
 *
 * Reads configuration from default_configuration.json resource and files/parameters
 * specified on the command line.
 */
class ServerConfiguration(args: Array<String>) : Configuration {
    private val values: Map<String, String>

    init {
        val map = mutableMapOf<String, String>()
        val jsonText = ServerResources.getStringResource("default_configuration.json")
        applyJsonConfiguration(map, jsonText)
        var i = 0
        while (i < args.size) {
            val arg = args[i++]
            val value = if (i < args.size) args[i++] else ""
            when (arg) {
                "-config" -> applyJsonConfiguration(map, File(value).readText())
                "-param" -> {
                    val index = value.indexOf('=')
                    if (index < 0) {
                        throw IllegalArgumentException("No '=' in param: '$value'")
                    }
                    map[value.take(index)] = value.substring(index + 1)
                }
                else -> {
                    println("Unknown command-line argument: $arg")
                    break
                }
            }
        }
        values = map.toMap()
    }

    private fun applyJsonConfiguration(map: MutableMap<String, String>, jsonText: String?) {
        if (jsonText == null) {
            return
        }
        val json = Json.parseToJsonElement(jsonText) as JsonObject
        for (entry in json) {
            val value = entry.value
            map[entry.key] = if (value is JsonPrimitive) {
                value.content
            } else {
                value.toString()
            }
        }
    }

    override fun getValue(key: String): String? = values[key]
}