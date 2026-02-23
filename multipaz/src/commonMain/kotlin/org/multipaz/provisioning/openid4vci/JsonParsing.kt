package org.multipaz.provisioning.openid4vci

import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.statement.readRawBytes
import io.ktor.http.HttpStatusCode
import kotlinx.io.bytestring.ByteString
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import org.multipaz.crypto.Algorithm
import org.multipaz.provisioning.Display
import org.multipaz.rpc.backend.BackendEnvironment
import org.multipaz.util.fromBase64

internal open class JsonParsing(val source: String) {
    private fun hexValue(ch: Char): Int = when (ch) {
        in '0'..'9' -> ch.code - '0'.code
        in 'a'..'f' -> ch.code - 'a'.code + 10
        in 'A'..'F' -> ch.code - 'A'.code + 10
        else -> -1
    }

    private fun decodePercentEncodedBytes(input: String): ByteArray {
        val bytes = mutableListOf<Byte>()
        var index = 0
        while (index < input.length) {
            val ch = input[index]
            if (ch == '%') {
                if (index + 2 >= input.length) {
                    throw IllegalStateException("$source: malformed percent encoding in data URI")
                }
                val high = hexValue(input[index + 1])
                val low = hexValue(input[index + 2])
                if (high < 0 || low < 0) {
                    throw IllegalStateException("$source: malformed percent encoding in data URI")
                }
                bytes.add(((high shl 4) or low).toByte())
                index += 3
            } else {
                bytes.addAll(ch.toString().encodeToByteArray().toList())
                index += 1
            }
        }
        return bytes.toByteArray()
    }

    fun preferredAlgorithm(
        available: JsonArray?,
        clientPreferences: OpenID4VCIClientPreferences
    ): Algorithm {
        if (available == null) {
            return Algorithm.ESP256
        }
        // Accept both JOSE and COSE identifiers
        val availableJoseSet = available
            .filterIsInstance<JsonPrimitive>()
            .filter { it.isString }
            .map { it.content }
            .toSet()
        val availableCoseSet = available
            .filterIsInstance<JsonPrimitive>()
            .filter { !it.isString }
            .map { it.content.toInt() }
            .toSet()
        return clientPreferences.signingAlgorithms.firstOrNull {
            val cose = it.coseAlgorithmIdentifier
            val jose = it.joseAlgorithmIdentifier
            (cose != null && availableCoseSet.contains(cose)) ||
                    (jose != null && availableJoseSet.contains(jose))
        } ?: throw IllegalStateException("$source: No supported signing algorithm")
    }

    fun JsonObject.string(name: String): String {
        val value = this[name]
        if (value !is JsonPrimitive) {
            throw IllegalStateException("$source: $name must be a string")
        }
        return value.content
    }

    fun JsonObject.stringOrNull(name: String): String? {
        val value = this[name] ?: return null
        if (value !is JsonPrimitive) {
            throw IllegalStateException("$source: $name must be a string")
        }
        return value.content
    }

    fun JsonObject.integer(name: String): Int {
        val value = this[name]
        if (value is JsonPrimitive && !value.isString) {
            val intValue = value.intOrNull
            if (intValue != null) {
                return intValue
            }
        }
        throw IllegalStateException("$source: $name must be an integer")
    }

    fun JsonObject.integerOrNull(name: String): Int {
        val value = this[name]
        if (value is JsonPrimitive && !value.isString) {
            val intValue = value.intOrNull
            if (intValue != null) {
                return intValue
            }
        }
        throw IllegalStateException("$source: $name must be an integer")
    }

    fun JsonObject.obj(name: String): JsonObject {
        val value = this[name]
        if (value !is JsonObject) {
            throw IllegalStateException("$source: $name must be an object")
        }
        return value
    }

    fun JsonObject.objOrNull(name: String): JsonObject? {
        val value = this[name] ?: return null
        if (value !is JsonObject) {
            throw IllegalStateException("$source: $name must be an object")
        }
        return value
    }

    fun JsonObject.array(name: String): JsonArray {
        val value = this[name]
        if (value !is JsonArray) {
            throw IllegalStateException("$source: $name must be an array")
        }
        return value
    }

    fun JsonObject.arrayOrNull(name: String): JsonArray? {
        val value = this[name] ?: return null
        if (value !is JsonArray) {
            throw IllegalStateException("$source: $name must be an array")
        }
        return value
    }

    suspend fun extractDisplay(
        element: JsonObject?,
        clientPreferences: OpenID4VCIClientPreferences
    ): Display {
        val displayJson = element?.arrayOrNull("display")
        if (displayJson == null || displayJson.isEmpty()) {
            return Display("Untitled", null)
        }
        var bestMatch: JsonObject? = null
        var bestRank = Int.MAX_VALUE
        for (displayObj in displayJson) {
            if (displayObj !is JsonObject) {
                throw IllegalStateException("Invalid display object in metadata")
            }
            val locale = displayObj["locale"]
            val localeText = if (locale == null) {
                "unknown"
            } else {
                if (locale !is JsonPrimitive) {
                    throw IllegalStateException("Invalid display object in metadata")
                }
                locale.jsonPrimitive.content
            }
            // TODO: we only do exact locale matches now, that's too restrictive
            val index = clientPreferences.locales.indexOf(localeText)
            val rank = if (index >= 0) index else clientPreferences.locales.size
            if (bestRank > rank) {
                bestRank = rank
                bestMatch = displayObj
            }
        }
        val text = bestMatch!!.string("name")
        val logoObj = bestMatch.objOrNull("logo")
        var logo: ByteString? = null
        if (logoObj != null) {
            val uri = logoObj.stringOrNull("uri")
            if (uri != null) {
                if (uri.startsWith("data:")) {
                    val separator = uri.indexOf(",")
                    if (separator > 0) {
                        val metadata = uri.substring(5, separator)
                        val payload = uri.substring(separator + 1)
                        val isBase64 = metadata.split(";").any { it.equals("base64", ignoreCase = true) }
                        val logoBytes = if (isBase64) {
                            runCatching { payload.fromBase64() }
                                .getOrElse { decodePercentEncodedBytes(payload) }
                        } else {
                            decodePercentEncodedBytes(payload)
                        }
                        logo = ByteString(logoBytes)
                    }
                } else {
                    val httpClient = BackendEnvironment.getInterface(HttpClient::class)!!
                    val response = httpClient.get(uri)
                    if (response.status == HttpStatusCode.OK) {
                        logo = ByteString(response.readRawBytes())
                    }
                }
            }
        }
        return Display(text, logo)
    }
}
