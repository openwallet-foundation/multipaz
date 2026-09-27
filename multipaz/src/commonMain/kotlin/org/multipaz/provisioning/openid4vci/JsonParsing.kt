package org.multipaz.provisioning.openid4vci

import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.statement.readRawBytes
import io.ktor.http.HttpStatusCode
import kotlinx.io.bytestring.ByteString
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import org.multipaz.claim.ClaimDescription
import org.multipaz.claim.ClaimDisplay
import org.multipaz.crypto.Algorithm
import org.multipaz.provisioning.Display
import org.multipaz.rpc.backend.BackendEnvironment
import org.multipaz.util.Logger
import org.multipaz.util.fromBase64

private const val TAG = "JsonParsing"

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

    /**
     * Extracts the claims descriptions from the `claims` array of [element], as defined in Appendix B.2 of
     * OpenID4VCI 1.0.
     *
     * Only the path and the display properties are kept. Claims descriptions which are malformed are skipped,
     * since they only affect how the credential is presented to the user.
     *
     * @param element the `credential_metadata` object of a credential configuration, or `null`.
     * @return the claims descriptions, or `null` if [element] has no `claims` array.
     */
    fun extractClaims(element: JsonObject?): List<ClaimDescription>? {
        val claims = element?.get("claims") as? JsonArray ?: return null
        return claims.mapNotNull { claim ->
            val description = (claim as? JsonObject)?.let { parseClaimDescription(it) }
            if (description == null) {
                Logger.w(TAG, "$source: ignoring malformed claims description: $claim")
            }
            description
        }
    }

    private fun parseClaimDescription(claim: JsonObject): ClaimDescription? {
        val path = claim["path"] as? JsonArray ?: return null
        val isValidPath = path.isNotEmpty() && path.all { element ->
            element is JsonNull || (element is JsonPrimitive &&
                    (element.isString || (element.longOrNull?.let { it >= 0 } ?: false)))
        }
        if (!isValidPath) {
            return null
        }
        val display = (claim["display"] ?: JsonArray(emptyList())) as? JsonArray ?: return null
        return ClaimDescription(
            path = path,
            display = display.map { item ->
                val obj = item as? JsonObject ?: return null
                // Both are optional, but must be strings when present.
                val name = obj["name"]
                val locale = obj["locale"]
                if (!isOptionalString(name) || !isOptionalString(locale)) {
                    return null
                }
                ClaimDisplay(
                    name = (name as? JsonPrimitive)?.contentOrNull,
                    locale = (locale as? JsonPrimitive)?.contentOrNull
                )
            }
        )
    }

    private fun isOptionalString(value: JsonElement?): Boolean =
        value == null || value is JsonNull || (value is JsonPrimitive && value.isString)

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