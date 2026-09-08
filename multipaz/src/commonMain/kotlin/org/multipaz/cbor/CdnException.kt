package org.multipaz.cbor

/**
 * Exception thrown when parsing Concise Diagnostic Notation (CDN) fails.
 *
 * @param message Description of the syntax error.
 * @param line Line number in the CDN text where the error occurred (1-indexed).
 * @param column Column number in the CDN text where the error occurred (1-indexed).
 */
class CdnException(
    message: String,
    val line: Int = 1,
    val column: Int = 1
) : IllegalArgumentException("CDN parse error at $line:$column: $message")
