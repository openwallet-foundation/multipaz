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
     * Whether to format any byte string using `<< ... >>` embedded CBOR notation when it contains valid encoded CBOR.
     */
    val useEmbeddedCborOpportunistically: Boolean = true,

    /**
     * Whether to format byte strings containing valid X.509 certificates using `cert'''...'''` PEM notation.
     */
    val useEmbeddedCertsOpportunistically: Boolean = true,

    /**
     * Whether to format tagged items using application extension literals (e.g. `dt'...'`, `ip'...'`).
     */
    val useApplicationExtensions: Boolean = true,

    /**
     * Whether map keys should be sorted.
     */
    val sortMapKeys: Boolean = false,

    /**
     * Whether to heuristically detect COSE data structures (e.g., COSE_Sign1, COSE_Mac0, COSE_Key)
     * and annotate their elements, header parameters, key parameters, and algorithms with descriptive comments.
     *
     * Following the draft-ietf-cbor-edn-literals convention:
     * - Structure labels and key names are placed in inline comments (`/ label /`).
     * - Parameter values and algorithms are annotated with line comments (`# value`).
     *
     * ### Supported Structures:
     * - **COSE_Sign1** (RFC 9052 Section 4.2): 4-element array `[/ protected /, / unprotected /, / payload /, / signature /]`.
     * - **COSE_Mac0** (RFC 9052 Section 6.2): 4-element array `[/ protected /, / unprotected /, / payload /, / tag /]`.
     * - **COSE_Key** (RFC 9052 Section 7): Map containing `/ kty / 1` and key parameters (`/ alg /`, `/ crv /`, `/ x /`, `/ y /`, `/ d /`, `/ k /`).
     *
     * ### Heuristic Detection:
     * - **COSE_Sign1**: Tag 18 OR 4-element array `[bstr, map, bstr/null, bstr]` containing recognized COSE header labels.
     * - **COSE_Mac0**: Tag 17 OR 4-element array `[bstr, map, bstr/null, bstr]` containing recognized MAC algorithm/header labels.
     * - **COSE_Key**: Map containing integer key `1` (`kty`) matching valid key types (`1` OKP, `2` EC2, `3` RSA, `4` Symmetric).
     */
    val annotateCoseOpportunistically: Boolean = true,

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

