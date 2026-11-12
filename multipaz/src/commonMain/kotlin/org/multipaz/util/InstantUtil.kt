package org.multipaz.util

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull
import kotlin.math.floor
import kotlin.math.roundToLong
import kotlin.time.Instant

/**
 * Returns a new [Instant] with the fractional part of seconds removed.
 */
fun Instant.truncateToWholeSeconds() = Instant.fromEpochSeconds(this.epochSeconds)

/**
 * Parses a JSON numeric date according to RFC 7519 Section 2 (`NumericDate`), supporting both
 * integer and floating-point / scientific-notation JSON numbers.
 *
 * @throws IllegalStateException if the element is not a valid JSON number (e.g. a string or object).
 */
internal fun JsonElement.toNumericDateInstant(claimName: String): Instant {
    if (this is JsonNull) {
        throw IllegalStateException("Invalid '$claimName' claim: expected JSON number, got null")
    }
    val primitive = this as? JsonPrimitive
        ?: throw IllegalStateException("Invalid '$claimName' claim: expected JSON number")
    if (primitive.isString) {
        throw IllegalStateException("Invalid '$claimName' claim: expected JSON number, got string")
    }
    primitive.longOrNull?.let { return Instant.fromEpochSeconds(it, 0) }
    val doubleVal = primitive.doubleOrNull
        ?: throw IllegalStateException("Invalid '$claimName' claim: not a valid number")
    if (!doubleVal.isFinite() || doubleVal < Long.MIN_VALUE.toDouble() || doubleVal > Long.MAX_VALUE.toDouble()) {
        throw IllegalStateException("Invalid '$claimName' claim: out of range")
    }
    val wholeSeconds = floor(doubleVal).toLong()
    val nanos = ((doubleVal - wholeSeconds) * 1_000_000_000.0).roundToLong()
    return Instant.fromEpochSeconds(wholeSeconds, nanos)
}

/**
 * Parses a JSON numeric date according to RFC 7519 Section 2 (`NumericDate`), returning `null`
 * if the primitive is a string or not a valid finite number.
 */
internal fun JsonPrimitive.numericDateInstantOrNull(): Instant? {
    if (isString) return null
    longOrNull?.let { return Instant.fromEpochSeconds(it, 0) }
    val doubleVal = doubleOrNull ?: return null
    if (!doubleVal.isFinite() || doubleVal < Long.MIN_VALUE.toDouble() || doubleVal > Long.MAX_VALUE.toDouble()) {
        return null
    }
    val wholeSeconds = floor(doubleVal).toLong()
    val nanos = ((doubleVal - wholeSeconds) * 1_000_000_000.0).roundToLong()
    return Instant.fromEpochSeconds(wholeSeconds, nanos)
}