package org.multipaz.cbor

/**
 * Format preferred for byte strings when generating CDN text.
 */
enum class ByteStringFormat {
    /** Output byte strings as hex, e.g., `h'010203'`. */
    HEX,

    /** Output byte strings as Base64, e.g., `b64'AQID'`. */
    BASE64,

    /** Output byte strings as single-quoted ASCII strings when printable, e.g., `'hello'`. */
    ASCII_SINGLE_QUOTE,

    /** Output `<N bytes>` summary instead of literal byte contents. */
    LENGTH_ONLY
}

/**
 * Options for CDN text generation (serializing [DataItem] to CDN string).
 */
data class CdnGeneratorOptions(
    /**
     * Whether to insert line breaks and indentation for readability.
     */
    val prettyPrint: Boolean = false,

    /**
     * Number of spaces per indentation level when [prettyPrint] is true.
     */
    val indentSize: Int = 2,

    /**
     * Preferred formatting style for byte strings.
     */
    val byteStringFormat: ByteStringFormat = ByteStringFormat.HEX,

    /**
     * Whether to use `<< ... >>` shorthand notation for Tag 24 encoded CBOR byte strings.
     */
    val useEmbeddedCborShorthand: Boolean = true,

    /**
     * Whether to format tagged items using application extension literals (e.g. `dt'...'`, `ip'...'`).
     */
    val useApplicationExtensions: Boolean = true,

    /**
     * Whether map keys should be sorted.
     */
    val sortMapKeys: Boolean = false,

    /**
     * Extension registry for formatting application extensions.
     */
    val extensionRegistry: CdnExtensionRegistry = CdnExtensionRegistry.Default
) {
    companion object {
        /**
         * Default CDN generator options.
         */
        val Default = CdnGeneratorOptions()

        /**
         * Options configured for pretty printing.
         */
        val Pretty = CdnGeneratorOptions(prettyPrint = true)
    }
}

/**
 * Options for CDN text parsing (parsing CDN text to [DataItem]).
 */
data class CdnParserOptions(
    /**
     * Extension registry for parsing application extensions.
     */
    val extensionRegistry: CdnExtensionRegistry = CdnExtensionRegistry.Default,

    /**
     * Maximum allowed nesting depth for compound structures (arrays/maps/tags).
     */
    val maxDepth: Int = 100
) {
    companion object {
        /**
         * Default CDN parser options.
         */
        val Default = CdnParserOptions()
    }
}

