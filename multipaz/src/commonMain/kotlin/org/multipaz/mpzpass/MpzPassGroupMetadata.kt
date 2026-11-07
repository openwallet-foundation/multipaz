package org.multipaz.mpzpass

import org.multipaz.cbor.Cbor
import org.multipaz.cbor.DataItem
import org.multipaz.cbor.annotation.CborSerializationImplemented
import org.multipaz.cbor.buildCborMap
import org.multipaz.cbor.putCborMap
import org.multipaz.documenttype.DocumentAttributeGroup
import org.multipaz.documenttype.DocumentAttributeSensitivity
import org.multipaz.documenttype.Icon
import org.multipaz.util.Logger
import org.multipaz.util.currentLocale
import org.multipaz.util.selectByLanguage

/**
 * Represents display, sensitivity, and grouping threshold metadata for a logical group of claims
 * within an [MpzPass] container.
 *
 * @property displayName Localized display names keyed by BCP 47 language tag (e.g., `"en-US"`, `"ja"`),
 *   or `""` for a language-neutral default. Must contain at least one entry.
 * @property description Optional localized descriptions keyed by BCP 47 language tag. If non-null,
 *   must contain at least one entry.
 * @property icon Optional [Icon] for the grouped pseudo-claim.
 * @property sensitivity Sensitivity classification of the grouped pseudo-claim; defaults to
 *   [DocumentAttributeSensitivity.PII].
 * @property minClaimsToGroup Minimum number of requested claims in this group required before
 *   collapsing into the pseudo-claim; defaults to `2`. Must be at least `1`.
 * @throws IllegalArgumentException if [displayName] is empty, if [description] is non-null and empty,
 *   or if [minClaimsToGroup] is less than `1`.
 */
@CborSerializationImplemented(schemaId = "")
data class MpzPassGroupMetadata(
    val displayName: Map<String, String>,
    val description: Map<String, String>? = null,
    val icon: Icon? = null,
    val sensitivity: DocumentAttributeSensitivity = DocumentAttributeSensitivity.PII,
    val minClaimsToGroup: Int = 2,
) {
    init {
        require(displayName.isNotEmpty()) { "displayName cannot be empty" }
        require(description == null || description.isNotEmpty()) {
            "description must be null or non-empty"
        }
        require(minClaimsToGroup >= 1) { "minClaimsToGroup must be >= 1, was $minClaimsToGroup" }
    }

    /**
     * Gets the display name of the group in the language which best matches [locales].
     *
     * @param locales BCP 47 language tags, most preferred first (e.g., `["ja-JP", "en-US"]`).
     *   If empty, the current platform locale is used.
     * @return The localized display name.
     */
    fun getDisplayName(locales: List<String> = emptyList()): String =
        selectByLanguage(
            candidates = displayName.entries.toList(),
            locales = locales.ifEmpty { listOf(currentLocale) },
            languageTagOf = { it.key.ifEmpty { null } }
        )!!.value

    /**
     * Gets the description of the group in the language which best matches [locales], or `null`
     * if [description] is not set.
     *
     * @param locales BCP 47 language tags, most preferred first (e.g., `["ja-JP", "en-US"]`).
     *   If empty, the current platform locale is used.
     * @return The localized description, or `null` if no description is provided.
     */
    fun getDescription(locales: List<String> = emptyList()): String? =
        description?.let { descMap ->
            selectByLanguage(
                candidates = descMap.entries.toList(),
                locales = locales.ifEmpty { listOf(currentLocale) },
                languageTagOf = { it.key.ifEmpty { null } }
            )?.value
        }

    internal fun toDocumentAttributeGroup(
        identifier: String,
        locales: List<String> = emptyList()
    ): DocumentAttributeGroup = DocumentAttributeGroup(
        identifier = identifier,
        displayName = getDisplayName(locales),
        description = getDescription(locales) ?: "",
        icon = icon,
        sensitivity = sensitivity,
        minClaimsToGroup = minClaimsToGroup
    )

    /**
     * Serializes this [MpzPassGroupMetadata] into a CBOR map [DataItem].
     *
     * @return A CBOR map representing this group metadata.
     */
    fun toDataItem(): DataItem = buildCborMap {
        putCborMap("displayName") {
            displayName.forEach { (locale, text) ->
                put(locale, text)
            }
        }
        description?.let { descMap ->
            putCborMap("description") {
                descMap.forEach { (locale, text) ->
                    put(locale, text)
                }
            }
        }
        icon?.let {
            putCborMap("icon") {
                put("m", it.iconName)
            }
        }
        if (sensitivity != DocumentAttributeSensitivity.PII) {
            put("sensitivity", sensitivity.name)
        }
        if (minClaimsToGroup != 2) {
            put("minClaimsToGroup", minClaimsToGroup)
        }
    }

    /**
     * Serializes this [MpzPassGroupMetadata] to CBOR bytes.
     *
     * @return The CBOR-encoded bytes.
     */
    fun toCbor(): ByteArray = Cbor.encode(toDataItem())

    companion object {
        private const val TAG = "MpzPassGroupMetadata"

        /**
         * Parses a CBOR map [DataItem] into an [MpzPassGroupMetadata] instance.
         *
         * Unrecognized icon names are logged and treated as `null`. Unrecognized or absent
         * sensitivity values default to [DocumentAttributeSensitivity.PII].
         *
         * @param dataItem The CBOR map containing the group metadata.
         * @return The parsed [MpzPassGroupMetadata].
         * @throws IllegalArgumentException if required fields are missing or malformed.
         */
        @Throws(IllegalArgumentException::class)
        fun fromDataItem(dataItem: DataItem): MpzPassGroupMetadata {
            val displayNameItem = dataItem.getOrNull("displayName")
                ?: throw IllegalArgumentException("Missing displayName in ClaimGroupMetadata")
            val displayName = displayNameItem.asMap.entries.associate { (k, v) ->
                k.asTstr to v.asTstr
            }
            val description = dataItem.getOrNull("description")?.asMap?.entries?.associate { (k, v) ->
                k.asTstr to v.asTstr
            }
            val icon = dataItem.getOrNull("icon")?.let { iconItem ->
                require(iconItem.asMap.isNotEmpty()) { "icon map cannot be empty" }
                iconItem.getOrNull("m")?.asTstr?.let { iconStr ->
                    Icon.entries.find { it.iconName == iconStr || it.name == iconStr }.also {
                        if (it == null) {
                            Logger.w(TAG, "Unknown icon '$iconStr', ignoring")
                        }
                    }
                }
            }
            val sensitivity = dataItem.getOrNull("sensitivity")?.asTstr?.let { sensStr ->
                DocumentAttributeSensitivity.entries.find { it.name.equals(sensStr, ignoreCase = true) }.also {
                    if (it == null) {
                        Logger.w(TAG, "Unknown sensitivity '$sensStr', defaulting to PII")
                    }
                }
            } ?: DocumentAttributeSensitivity.PII
            val minClaimsToGroup = dataItem.getOrNull("minClaimsToGroup")?.asNumber?.toInt() ?: 2

            return MpzPassGroupMetadata(
                displayName = displayName,
                description = description,
                icon = icon,
                sensitivity = sensitivity,
                minClaimsToGroup = minClaimsToGroup
            )
        }

        /**
         * Parses CBOR bytes into an [MpzPassGroupMetadata] instance.
         *
         * @param data The CBOR-encoded bytes.
         * @return The parsed [MpzPassGroupMetadata].
         * @throws IllegalArgumentException if CBOR decoding fails or required fields are missing or malformed.
         */
        @Throws(IllegalArgumentException::class)
        fun fromCbor(data: ByteArray): MpzPassGroupMetadata = fromDataItem(Cbor.decode(data))
    }
}
