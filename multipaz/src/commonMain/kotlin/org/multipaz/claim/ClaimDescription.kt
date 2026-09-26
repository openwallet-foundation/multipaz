package org.multipaz.claim

import kotlinx.io.bytestring.ByteString
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.longOrNull
import org.multipaz.cbor.Cbor
import org.multipaz.cbor.DataItem
import org.multipaz.cbor.Simple
import org.multipaz.cbor.Tstr
import org.multipaz.cbor.Uint
import org.multipaz.cbor.annotation.CborSerializationImplemented
import org.multipaz.cbor.buildCborArray
import org.multipaz.cbor.buildCborMap
import org.multipaz.util.selectByLanguage

/**
 * Describes a claim in a credential, as provided by the issuer of the credential.
 *
 * This corresponds to a claims description object in the `claims` array of a credential
 * configuration in OpenID4VCI issuer metadata, see Appendix B.2 of
 * [OpenID for Verifiable Credential Issuance 1.0](https://openid.net/specs/openid-4-verifiable-credential-issuance-1_0.html).
 *
 * @property path the claims path pointer identifying the claim. For ISO mdoc credentials this is the
 *   namespace followed by the data element name, for SD-JWT VC credentials it is the path to the claim.
 *   Each element is a string, a non-negative integer or `null`.
 * @property display the display properties of the claim, typically one per language.
 */
@CborSerializationImplemented(schemaId = "")
data class ClaimDescription(
    val path: JsonArray,
    val display: List<ClaimDisplay>
) {
    /**
     * Gets the display name of the claim in the language which best matches [locales].
     *
     * Display objects without a name are not considered. See [ClaimDisplay.locale] for how languages
     * are matched.
     *
     * @param locales BCP 47 language tags, most preferred first, e.g. `["ja-JP", "en-US"]`.
     * @return the display name, or `null` if the issuer did not provide any.
     */
    fun getDisplayName(locales: List<String>): String? =
        selectByLanguage(
            candidates = display.filter { it.name != null },
            locales = locales,
            languageTagOf = { it.locale }
        )?.name

    internal fun toDataItem(): DataItem = buildCborMap {
        put("path", buildCborArray {
            for (element in path) {
                add(element.toPathDataItem())
            }
        })
        put("display", buildCborArray {
            for (item in display) {
                add(item.toDataItem())
            }
        })
    }

    companion object {
        internal fun fromDataItem(dataItem: DataItem): ClaimDescription =
            ClaimDescription(
                path = buildJsonArray {
                    for (element in dataItem["path"].asArray) {
                        when (element) {
                            is Tstr -> add(element.asTstr)
                            Simple.NULL -> add(JsonNull)
                            else -> add(element.asNumber)
                        }
                    }
                },
                display = dataItem["display"].asArray.map { ClaimDisplay.fromDataItem(it) }
            )
    }
}

/**
 * Display properties of a claim for a certain language.
 *
 * @property name the display name of the claim, or `null` if not provided.
 * @property locale the language of this object as a BCP 47 language tag, e.g. `ja-JP`, or `null` if not
 *   provided. When picking a display name for a list of preferred languages, a tag also matches a more or
 *   less specific tag for the same language, so `ja` and `ja-JP` match each other, and English is used when
 *   none of the preferred languages are available.
 */
data class ClaimDisplay(
    val name: String?,
    val locale: String?
) {
    internal fun toDataItem(): DataItem = buildCborMap {
        name?.let { put("name", it) }
        locale?.let { put("locale", it) }
    }

    companion object {
        internal fun fromDataItem(dataItem: DataItem): ClaimDisplay =
            ClaimDisplay(
                name = dataItem.getOrNull("name")?.asTstr,
                locale = dataItem.getOrNull("locale")?.asTstr
            )
    }
}

/**
 * Finds the display name for the claim at [path] in the language which best matches [locales].
 */
internal fun List<ClaimDescription>.findDisplayName(path: JsonArray, locales: List<String>): String? =
    firstOrNull { it.path == path }?.getDisplayName(locales)

internal fun List<ClaimDescription>.encodeToCbor(): ByteString =
    ByteString(Cbor.encode(buildCborArray { this@encodeToCbor.forEach { add(it.toDataItem()) } }))

internal fun decodeClaimDescriptions(encoded: ByteString): List<ClaimDescription> =
    Cbor.decode(encoded.toByteArray()).asArray.map { ClaimDescription.fromDataItem(it) }

private fun JsonElement.toPathDataItem(): DataItem {
    if (this is JsonNull) {
        return Simple.NULL
    }
    require(this is JsonPrimitive) { "Invalid claims path element: $this" }
    if (isString) {
        return Tstr(content)
    }
    val index = longOrNull
    require(index != null && index >= 0) { "Invalid claims path element: $this" }
    return Uint(index.toULong())
}
