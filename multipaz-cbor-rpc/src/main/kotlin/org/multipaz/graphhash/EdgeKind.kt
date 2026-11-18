package org.multipaz.graphhash

import kotlinx.io.bytestring.ByteString

// ErrorProne doesn't understand that all values here are immutable. Suppress its warning.
@Suppress("ImmutableEnum")
enum class EdgeKind(
    val mark: ByteString
) {
    REQUIRED(ByteString((0xF0).toByte())),
    OPTIONAL(ByteString((0xF1).toByte())),
    ALTERNATIVE(ByteString((0xF2).toByte())),
    DEFAULT(ByteString((0xF3).toByte())),
}