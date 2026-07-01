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

    fun JsonObject.integerOrNull(name: String): Int? {
        val value = this[name]
        if (value is JsonPrimitive && !value.isString) {
            val intValue = value.intOrNull
            if (intValue != null) {
                return intValue
            }
        }
        return null
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
        httpClient: HttpClient,
        clientPreferences: OpenID4VCIClientPreferences
    ): Display {
        val displayJson = element?.arrayOrNull("display")
        if (displayJson == null || displayJson.isEmpty()) {
            return Display("Untitled")
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
        return Display(
            text = bestMatch!!.string("name"),
            logo = loadImage(
                logoObj = bestMatch.objOrNull("logo"),
                httpClient = httpClient
            ),
            description = bestMatch.stringOrNull("description"),
            backgroundColor = bestMatch.stringOrNull("background_color"),
            textColor = bestMatch.stringOrNull("text_color"),
            backgroundImage = loadImage(
                logoObj = bestMatch.objOrNull("background_image"),
                httpClient = httpClient
            )
        )
    }

    private suspend fun loadImage(
        logoObj: JsonObject?,
        httpClient: HttpClient
    ): ByteString? {
        val uri = logoObj?.stringOrNull("uri") ?: return null
        if (uri.startsWith("data:")) {
            val start = uri.indexOf(",")
            if (start > 0) {
                return ByteString(uri.substring(start + 1).fromBase64())
            }
        } else {
            val response = httpClient.get(uri)
            if (response.status == HttpStatusCode.OK) {
                return ByteString(response.readRawBytes())
            }
        }
        return null
    }

}