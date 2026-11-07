package org.multipaz.mpzpass

import org.multipaz.cbor.Cbor
import org.multipaz.cbor.DataItem
import org.multipaz.cbor.annotation.CborSerializationImplemented
import org.multipaz.cbor.buildCborMap
import org.multipaz.cbor.putCborMap
import org.multipaz.documenttype.DocumentAttributeSensitivity
import org.multipaz.documenttype.DocumentAttributeType
import org.multipaz.documenttype.Icon
import org.multipaz.util.Logger
import org.multipaz.util.currentLocale
import org.multipaz.util.selectByLanguage

/**
 * Represents display and sensitivity metadata for a single claim within an [MpzPass] container.
 *
 * @property displayName Localized display names keyed by BCP 47 language tag (e.g., `"en-US"`, `"ja"`),
 *   or `""` for a language-neutral default. Must contain at least one entry.
 * @property description Optional localized descriptions keyed by BCP 47 language tag. If non-null,
 *   must contain at least one entry.
 * @property type Claim data type; defaults to [DocumentAttributeType.String]. Must be one of the 8 base
 *   types ([DocumentAttributeType.Blob], [DocumentAttributeType.String], [DocumentAttributeType.Number],
 *   [DocumentAttributeType.Date], [DocumentAttributeType.DateTime], [DocumentAttributeType.Picture],
 *   [DocumentAttributeType.Boolean], or [DocumentAttributeType.ComplexType]).
 * @property icon Optional [Icon] for the claim.
 * @property sensitivity Sensitivity classification of the claim; defaults to [DocumentAttributeSensitivity.PII].
 * @property groupId Optional identifier of the group in [MpzPassMetadata.claimGroupsMetadata] that this claim
 *   belongs to.
 * @throws IllegalArgumentException if [displayName] is empty, if [description] is non-null and empty, or if
 *   [type] is [DocumentAttributeType.StringOptions] or [DocumentAttributeType.IntegerOptions].
 */
@CborSerializationImplemented(schemaId = "")
data class MpzPassClaimMetadata(
    val displayName: Map<String, String>,
    val description: Map<String, String>? = null,
    val type: DocumentAttributeType = DocumentAttributeType.String,
    val icon: Icon? = null,
    val sensitivity: DocumentAttributeSensitivity = DocumentAttributeSensitivity.PII,
    val groupId: String? = null,
) {
    init {
        require(displayName.isNotEmpty()) { "displayName cannot be empty" }
        require(description == null || description.isNotEmpty()) {
            "description must be null or non-empty"
        }
        require(type !is DocumentAttributeType.StringOptions && type !is DocumentAttributeType.IntegerOptions) {
            "MpzPassClaimMetadata does not support StringOptions or IntegerOptions"
        }
    }

    /**
     * Gets the display name of the claim in the language which best matches [locales].
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
     * Gets the description of the claim in the language which best matches [locales], or `null`
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

    /**
     * Serializes this [MpzPassClaimMetadata] into a CBOR map [DataItem].
     *
     * @return A CBOR map representing this claim metadata.
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
        if (type != DocumentAttributeType.String) {
            put("type", typeToCborName(type))
        }
        icon?.let {
            putCborMap("icon") {
                put("m", it.iconName)
            }
        }
        if (sensitivity != DocumentAttributeSensitivity.PII) {
            put("sensitivity", sensitivity.name)
        }
        groupId?.let { put("groupId", it) }
    }

    /**
     * Serializes this [MpzPassClaimMetadata] to CBOR bytes.
     *
     * @return The CBOR-encoded bytes.
     */
    fun toCbor(): ByteArray = Cbor.encode(toDataItem())

    companion object {
        private const val TAG = "MpzPassClaimMetadata"

        internal fun typeToCborName(type: DocumentAttributeType): String = when (type) {
            DocumentAttributeType.Blob -> "BLOB"
            DocumentAttributeType.String -> "STRING"
            DocumentAttributeType.Number -> "NUMBER"
            DocumentAttributeType.Date -> "DATE"
            DocumentAttributeType.DateTime -> "DATETIME"
            DocumentAttributeType.Picture -> "PICTURE"
            DocumentAttributeType.Boolean -> "BOOLEAN"
            DocumentAttributeType.ComplexType -> "COMPLEX_TYPE"
            is DocumentAttributeType.StringOptions,
            is DocumentAttributeType.IntegerOptions ->
                throw IllegalArgumentException("Unsupported claim type: $type")
        }

        private fun typeFromCborName(name: String): DocumentAttributeType? = when (name.uppercase()) {
            "BLOB" -> DocumentAttributeType.Blob
            "STRING" -> DocumentAttributeType.String
            "NUMBER" -> DocumentAttributeType.Number
            "DATE" -> DocumentAttributeType.Date
            "DATETIME" -> DocumentAttributeType.DateTime
            "PICTURE" -> DocumentAttributeType.Picture
            "BOOLEAN" -> DocumentAttributeType.Boolean
            "COMPLEX_TYPE" -> DocumentAttributeType.ComplexType
            else -> null
        }

        /**
         * Parses a CBOR map [DataItem] into an [MpzPassClaimMetadata] instance.
         *
         * Unrecognized icon names are logged and treated as `null`. Unrecognized or absent
         * claim types default to [DocumentAttributeType.String]. Unrecognized or absent
         * sensitivity values default to [DocumentAttributeSensitivity.PII].
         *
         * @param dataItem The CBOR map containing the claim metadata.
         * @return The parsed [MpzPassClaimMetadata].
         * @throws IllegalArgumentException if required fields are missing or malformed.
         */
        @Throws(IllegalArgumentException::class)
        fun fromDataItem(dataItem: DataItem): MpzPassClaimMetadata {
            val displayNameItem = dataItem.getOrNull("displayName")
                ?: throw IllegalArgumentException("Missing displayName in ClaimMetadata")
            val displayName = displayNameItem.asMap.entries.associate { (k, v) ->
                k.asTstr to v.asTstr
            }
            val description = dataItem.getOrNull("description")?.asMap?.entries?.associate { (k, v) ->
                k.asTstr to v.asTstr
            }
            val type = dataItem.getOrNull("type")?.asTstr?.let { typeStr ->
                typeFromCborName(typeStr).also {
                    if (it == null) {
                        Logger.w(TAG, "Unknown claim type '$typeStr', defaulting to STRING")
                    }
                }
            } ?: DocumentAttributeType.String
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
            val groupId = dataItem.getOrNull("groupId")?.asTstr

            return MpzPassClaimMetadata(
                displayName = displayName,
                description = description,
                type = type,
                icon = icon,
                sensitivity = sensitivity,
                groupId = groupId
            )
        }

        /**
         * Parses CBOR bytes into an [MpzPassClaimMetadata] instance.
         *
         * @param data The CBOR-encoded bytes.
         * @return The parsed [MpzPassClaimMetadata].
         * @throws IllegalArgumentException if CBOR decoding fails or required fields are missing or malformed.
         */
        @Throws(IllegalArgumentException::class)
        fun fromCbor(data: ByteArray): MpzPassClaimMetadata = fromDataItem(Cbor.decode(data))
    }
}
