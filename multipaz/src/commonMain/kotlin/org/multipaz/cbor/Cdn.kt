package org.multipaz.cbor

/**
 * Main entry point for Concise Diagnostic Notation (CDN) parsing and generation.
 *
 * CDN is specified by IETF `draft-ietf-cbor-edn-literals` and updates RFC 8949 / RFC 8610 Appendix G.
 */
object Cdn {
    /**
     * Parses a CDN text string into a single [DataItem].
     *
     * @param cdnText The Concise Diagnostic Notation text to parse.
     * @param options Options for parsing CDN.
     * @return The parsed [DataItem].
     * @throws CdnException If a syntax or token error occurs during parsing.
     */
    fun parse(
        cdnText: String,
        options: CdnParserOptions = CdnParserOptions.Default
    ): DataItem {
        val parser = CdnParser(cdnText, options)
        return parser.parseSingle()
    }

    /**
     * Parses a CDN text string containing zero or more data items (a CBOR sequence) into a [List] of [DataItem]s.
     *
     * @param cdnText The Concise Diagnostic Notation sequence text to parse.
     * @param options Options for parsing CDN.
     * @return A list of parsed [DataItem]s.
     * @throws CdnException If a syntax or token error occurs during parsing.
     */
    fun parseSequence(
        cdnText: String,
        options: CdnParserOptions = CdnParserOptions.Default
    ): List<DataItem> {
        val parser = CdnParser(cdnText, options)
        return parser.parseSequence()
    }

    /**
     * Serializes a [DataItem] into its Concise Diagnostic Notation text representation.
     *
     * @param item The [DataItem] to encode.
     * @param options Options controlling formatting (e.g. pretty printing, byte string format).
     * @return The formatted CDN string.
     */
    fun encode(
        item: DataItem,
        options: CdnGeneratorOptions = CdnGeneratorOptions.Default
    ): String {
        val generator = CdnGenerator(options)
        return generator.generate(item)
    }

    /**
     * Decodes encoded CBOR bytes and serializes them into Concise Diagnostic Notation text.
     *
     * @param encodedCbor The raw CBOR bytes.
     * @param options Options controlling formatting.
     * @return The formatted CDN string.
     */
    fun encode(
        encodedCbor: ByteArray,
        options: CdnGeneratorOptions = CdnGeneratorOptions.Default
    ): String {
        val decoded = Cbor.decode(encodedCbor)
        return encode(decoded, options)
    }

    /**
     * Registers a custom [CdnExtension] with the default extension registry.
     */
    fun registerExtension(extension: CdnExtension) {
        CdnExtensionRegistry.Default.register(extension)
    }

    /**
     * Parses a CDN text string into a single [DataItem] using a custom extension registry.
     */
    fun parse(
        cdnText: String,
        extensionRegistry: CdnExtensionRegistry
    ): DataItem = parse(cdnText, CdnParserOptions(extensionRegistry = extensionRegistry))

    /**
     * Parses a CDN text string containing zero or more data items into a [List] of [DataItem]s using a custom extension registry.
     */
    fun parseSequence(
        cdnText: String,
        extensionRegistry: CdnExtensionRegistry
    ): List<DataItem> = parseSequence(cdnText, CdnParserOptions(extensionRegistry = extensionRegistry))

    /**
     * Serializes a [DataItem] into its CDN text representation using a custom extension registry.
     */
    fun encode(
        item: DataItem,
        extensionRegistry: CdnExtensionRegistry
    ): String = encode(item, CdnGeneratorOptions(extensionRegistry = extensionRegistry))

    /**
     * Decodes CBOR bytes and serializes them into CDN text representation using a custom extension registry.
     */
    fun encode(
        encodedCbor: ByteArray,
        extensionRegistry: CdnExtensionRegistry
    ): String = encode(encodedCbor, CdnGeneratorOptions(extensionRegistry = extensionRegistry))
}

/**
 * Extension function to convert a [DataItem] to a CDN string representation.
 */
fun DataItem.toCdn(options: CdnGeneratorOptions = CdnGeneratorOptions.Default): String =
    Cdn.encode(this, options)

/**
 * Extension function to parse a CDN string representation into a [DataItem].
 */
fun String.toDataItemFromCdn(options: CdnParserOptions = CdnParserOptions.Default): DataItem =
    Cdn.parse(this, options)
