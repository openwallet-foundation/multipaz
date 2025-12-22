package org.multipaz.datetime

import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.toJavaLocalDate
import kotlinx.datetime.toJavaLocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import org.multipaz.datetime.FormatStyle as MultipazFormatStyle

actual fun LocalDate.formatLocalized(
    dateStyle: MultipazFormatStyle
): String {
    val formatter = DateTimeFormatter.ofLocalizedDate(dateStyle.toJavaFormatStyle())
    return this.toJavaLocalDate().format(formatter)
}

actual fun LocalDateTime.formatLocalized(
    dateStyle: MultipazFormatStyle,
    timeStyle: MultipazFormatStyle
): String {
    val formatter = DateTimeFormatter.ofLocalizedDateTime(
        dateStyle.toJavaFormatStyle(),
        timeStyle.toJavaFormatStyle()
    )
    return this.toJavaLocalDateTime()
        .atZone(ZoneId.systemDefault())
        .format(formatter)
}

private fun MultipazFormatStyle.toJavaFormatStyle(): FormatStyle {
    return when (this) {
        MultipazFormatStyle.SHORT -> FormatStyle.SHORT
        MultipazFormatStyle.MEDIUM -> FormatStyle.MEDIUM
        MultipazFormatStyle.LONG -> FormatStyle.LONG
        MultipazFormatStyle.FULL -> FormatStyle.FULL
    }
}