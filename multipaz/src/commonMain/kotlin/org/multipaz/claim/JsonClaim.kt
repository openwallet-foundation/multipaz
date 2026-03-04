package org.multipaz.claim

import org.multipaz.datetime.formatLocalized
import org.multipaz.documenttype.DocumentAttribute
import org.multipaz.documenttype.DocumentAttributeType
import org.multipaz.util.fromBase64Url
import kotlin.time.Instant
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonPrimitive

/**
 * A claim in a JSON-based credential.
 *
 * @property vct the Verifiable Credential Type.
 * @property claimPath the claim name.
 * @property value the value of the claim
 */
data class JsonClaim(
    override val displayName: String,
    override val attribute: DocumentAttribute?,
    val vct: String,
    val claimPath: JsonArray,
    val value: JsonElement
) : Claim(displayName, attribute) {

    override fun render(timeZone: TimeZone): String {
        val str = try {
            if (attribute == null) {
                if (value is JsonPrimitive) {
                    value.jsonPrimitive.content
                } else {
                    value.toString()
                }
            } else {
                when (attribute.type) {
                    // TODO: translations
                    DocumentAttributeType.Boolean -> {
                        if (value.jsonPrimitive.boolean) {
                            "True"
                        } else {
                            "False"
                        }
                    }

                    DocumentAttributeType.Blob -> {
                        "Blob of ${value.jsonPrimitive.content.length} bytes"
                    }

                    DocumentAttributeType.ComplexType -> {
                        value.toString()
                    }

                    DocumentAttributeType.Date -> {
                        val d = LocalDate.parse(value.jsonPrimitive.content)
                        d.formatLocalized()
                    }

                    DocumentAttributeType.DateTime -> {
                        val pointInTime = Instant.parse(value.jsonPrimitive.content)
                        val dt = pointInTime.toLocalDateTime(timeZone)
                        dt.formatLocalized()
                    }

                    is DocumentAttributeType.IntegerOptions -> {
                        val type = attribute.type
                        val option = type.options.find { it.value == value.jsonPrimitive.int }
                        option?.displayName ?: value.jsonPrimitive.content
                    }
                    DocumentAttributeType.Number -> {
                        value.jsonPrimitive.content
                    }
                    DocumentAttributeType.Picture -> {
                        "Image (${value.jsonPrimitive.content.fromBase64Url().size} bytes)"
                    }
                    DocumentAttributeType.String -> {
                        value.jsonPrimitive.content
                    }
                    is DocumentAttributeType.StringOptions -> {
                        val type = attribute.type
                        val option = type.options.find { it.value == value.jsonPrimitive.content }
                        option?.displayName ?: value.jsonPrimitive.content
                    }
                }

            }
        } catch (e: Throwable) {
            val fallback = value.toString()
            "$fallback (fallback, error occurred during rendering: ${e.message})"
        }
        return str
    }

    companion object
}