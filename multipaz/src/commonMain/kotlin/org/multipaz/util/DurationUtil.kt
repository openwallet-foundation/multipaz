package org.multipaz.util

import kotlin.time.Duration
import kotlin.time.DurationUnit
import kotlin.time.toDuration

/**
 * Creates a [Duration] from number of nanoseconds.
 *
 * @param nanoseconds the number of nanoseconds.
 * @return a [Duration].
 */
fun Duration.Companion.fromNanoseconds(nanoseconds: Long): Duration =
    nanoseconds.toDuration(DurationUnit.NANOSECONDS)
