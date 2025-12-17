package org.multipaz.provisioning.openid4vci

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull
import org.multipaz.crypto.Algorithm

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
}