package org.multipaz.datetime

import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime

// For now, just a simple implementation assuming en-US locale.
//
// TODO: implement using the JavaScript Date or Temporal APIs

actual fun LocalDate.formatLocalized(dateStyle: FormatStyle): String {
    return formatDate(this, dateStyle)
}

actual fun LocalDateTime.formatLocalized(
    dateStyle: FormatStyle,
    timeStyle: FormatStyle
): String {
    val dateStr = formatDate(this.date, dateStyle)
    val timeStr = formatTime(this, timeStyle)
    return "$dateStr $timeStr"
}

private fun formatDate(date: LocalDate, style: FormatStyle): String {
    return when (style) {
        // M/d/yy (e.g., 12/26/25)
        FormatStyle.SHORT -> {
            "${date.month}/${date.day}/${date.year.toString().takeLast(2)}"
        }
        // MMM d, yyyy (e.g., Dec 26, 2025)
        FormatStyle.MEDIUM -> {
            "${date.month.shortName} ${date.day}, ${date.year}"
        }
        // MMMM d, yyyy (e.g., December 26, 2025)
        FormatStyle.LONG -> {
            "${date.month.displayName} ${date.day}, ${date.year}"
        }
        // EEEE, MMMM d, yyyy (e.g., Friday, December 26, 2025)
        FormatStyle.FULL -> {
            "${date.dayOfWeek.displayName}, ${date.month.displayName} ${date.day}, ${date.year}"
        }
    }
}

private fun formatTime(dateTime: LocalDateTime, style: FormatStyle): String {
    // Note: strictly speaking, JVM 'LONG' and 'FULL' time styles require a TimeZone
    // (ZonedDateTime). Since LocalDateTime has no zone, we fallback to MEDIUM
    // logic for those styles to avoid a "Missing Zone" crash or empty strings.

    val useSeconds = style != FormatStyle.SHORT

    val hourVal = if (dateTime.hour == 0 || dateTime.hour == 12) 12 else dateTime.hour % 12
    val amPm = if (dateTime.hour < 12) "AM" else "PM"
    val minuteStr = dateTime.minute.toString().padStart(2, '0')

    return if (useSeconds) {
        val secondStr = dateTime.second.toString().padStart(2, '0')
        // h:mm:ss a
        "$hourVal:$minuteStr:$secondStr $amPm"
    } else {
        // h:mm a
        "$hourVal:$minuteStr $amPm"
    }
}

private val kotlinx.datetime.Month.displayName: String
    get() = this.name.lowercase().replaceFirstChar { it.uppercase() }

private val kotlinx.datetime.Month.shortName: String
    get() = this.displayName.take(3)

private val kotlinx.datetime.DayOfWeek.displayName: String
    get() = this.name.lowercase().replaceFirstChar { it.uppercase() }
