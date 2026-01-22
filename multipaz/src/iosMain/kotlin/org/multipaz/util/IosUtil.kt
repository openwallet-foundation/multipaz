package org.multipaz.util

import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.allocArrayOf
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.usePinned
import platform.Foundation.NSData
import platform.Foundation.NSDate
import platform.Foundation.NSError
import platform.Foundation.create
import platform.Foundation.timeIntervalSince1970
import platform.posix.memcpy
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.DurationUnit
import kotlin.time.Instant
import kotlin.time.toDuration

// Various iOS related utilities.
//

@OptIn(ExperimentalForeignApi::class)
fun NSData.toByteArray(): ByteArray {
    return if (length == 0UL) {
        byteArrayOf()
    } else {
        ByteArray(length.toInt()).apply {
            usePinned {
                memcpy(it.addressOf(0), bytes, length)
            }
        }
    }
}

@OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)
fun ByteArray.toNSData(): NSData = memScoped {
    NSData.create(bytes = allocArrayOf(this@toNSData), length = this@toNSData.size.toULong())
}

fun NSError.toKotlinError(): Error {
    return Error("NSError domain=${this.domain} code=${this.code}: ${this.localizedDescription}")
}

/**
 * Converts a [NSDate] to [Instant].
 *
 * @return an [Instant] representing the same time.
 */
fun NSDate.toKotlinInstant(): Instant {
    val epochSeconds = this.timeIntervalSince1970.toLong()
    val nanosecondAdjustment = ((this.timeIntervalSince1970 - epochSeconds) * 1_000_000_000).toInt()
    return Instant.fromEpochSeconds(epochSeconds, nanosecondAdjustment)
}

fun Clock.Companion.getSystem(): Clock = Clock.System

// Helper to create duration from simple seconds (Double)
fun Duration.Companion.fromSeconds(seconds: Double): Duration = seconds.toDuration(DurationUnit.SECONDS)
