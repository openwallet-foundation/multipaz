package org.multipaz.compose.datetime

import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import org.multipaz.multipaz_compose.generated.resources.Res
import org.multipaz.multipaz_compose.generated.resources.duration_day
import org.multipaz.multipaz_compose.generated.resources.duration_days_and_hours
import org.multipaz.multipaz_compose.generated.resources.duration_hour
import org.multipaz.multipaz_compose.generated.resources.duration_hours_and_minutes
import org.multipaz.multipaz_compose.generated.resources.duration_in_a_few_moments
import org.multipaz.multipaz_compose.generated.resources.duration_just_now
import org.multipaz.multipaz_compose.generated.resources.duration_less_than_a_minute
import org.multipaz.multipaz_compose.generated.resources.duration_minute
import org.multipaz.multipaz_compose.generated.resources.duration_month
import org.multipaz.multipaz_compose.generated.resources.duration_months_and_days
import org.multipaz.multipaz_compose.generated.resources.duration_time_ago
import org.multipaz.multipaz_compose.generated.resources.duration_time_from_now
import org.multipaz.multipaz_compose.generated.resources.duration_week
import org.multipaz.multipaz_compose.generated.resources.duration_weeks_and_days
import org.multipaz.multipaz_compose.generated.resources.duration_year
import org.multipaz.multipaz_compose.generated.resources.duration_years_and_months
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.time.Clock
import kotlinx.datetime.DateTimePeriod
import kotlin.time.Instant
import kotlinx.datetime.TimeZone
import kotlinx.datetime.monthsUntil
import kotlinx.datetime.plus
import kotlinx.datetime.yearsUntil
import org.multipaz.multipaz_compose.generated.resources.duration_never
import org.jetbrains.compose.resources.PluralStringResource
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

internal var clockForTesting: Clock? = null

/**
 * Format time interval to the time instant in human-readable way, such as
 * "3 days and 5 hours ago" or "4 months and 3 days from now".
 *
 * @param instant time instant in the past or future.
 * @param now time now, only exposed for testing.
 */
@Composable
fun durationFromNowText(
    instant: Instant,
    now: Instant = (clockForTesting ?: Clock.System).now()
): String {
    val coroutineScope = rememberCoroutineScope()

    val tick = remember { mutableIntStateOf(0) }
    val unused = tick.intValue  // reading value ensures update when tick changes
    val (text, updateAt) = durationFromNowTextCore(instant, now)
    val delayDuration = maxOf(updateAt - now, 16.milliseconds)
    SideEffect {
        coroutineScope.launch {
            delay(delayDuration)
            tick.intValue++
        }
    }
    return text
}

/**
 * Formats text for duration from time instant in the past or future and computes the time
 * instant when this text has to be updated.
 *
 * @param instant time instant in the past or future.
 * @param now time now, only exposed for testing.
 */
@Composable
internal fun durationFromNowTextCore(instant: Instant, now: Instant): Pair<String, Instant> {
    return if (instant > now) {
        futureRawTextAndUpdateTime(instant, now)
    } else {
        pastRawTextAndUpdateTime(instant, now)
    }
}

/**
 * Formats text for duration from time instant in the past and computes the time instant when
 * this text has to be updated.
 */
@Composable
private fun pastRawTextAndUpdateTime(past: Instant, now: Instant): Pair<String, Instant> {
    if (past == Instant.DISTANT_PAST) {
        return Pair(stringResource(Res.string.duration_never), Instant.DISTANT_FUTURE)
    }
    val (majorUnit, majorCount) = selectMajorUnit(past, now)
    if (majorUnit == null) {
        // Less than a minute: special case
        val duration = now - past
        if (duration < 10.seconds) {
            return Pair(stringResource(Res.string.duration_just_now), past + 10.seconds)
        }
        val smallestUnit = units[units.size - 1]
        val durationText = stringResource(Res.string.duration_less_than_a_minute)
        return Pair(stringResource(Res.string.duration_time_ago, durationText),
            smallestUnit.addTo(past, 1))
    }
    val majorStr = pluralStringResource(
        majorUnit.pluralResourceId, majorCount.toInt(), majorCount
    )
    val minorUnit = majorUnit.minorUnit()
        ?: return Pair(stringResource(Res.string.duration_time_ago, majorStr),
            majorUnit.addTo(past, majorCount + 1))
    val minorUnitInstance = majorUnit.addTo(now, -majorCount)
    val minorCount = minorUnit.wholeOf(past, minorUnitInstance)
    val update = minorUnit.addTo(majorUnit.addTo(past, majorCount), minorCount + 1)
    if (minorCount != 0L) {
        val minorStr = pluralStringResource(
            minorUnit.pluralResourceId, minorCount.toInt(), minorCount
        )
        return Pair(stringResource(Res.string.duration_time_ago,
            stringResource(majorUnit.combinerResourceId!!, majorStr, minorStr)), update)
    }
    return Pair(stringResource(Res.string.duration_time_ago, majorStr), update)
}

/**
 * Formats text for duration to time instant the future and computes the time instant when this
 * text has to be updated.
 */
@Composable
private fun futureRawTextAndUpdateTime(future: Instant, now: Instant): Pair<String, Instant> {
    val (majorUnit, majorCount) = selectMajorUnit(now, future)
    if (majorUnit == null) {
        val duration = future - now
        if (duration < 10.seconds) {
            return Pair(stringResource(Res.string.duration_in_a_few_moments), future)
        }
        val durationText = stringResource(Res.string.duration_less_than_a_minute)
        return Pair(
            stringResource(Res.string.duration_time_from_now, durationText), future - 10.seconds)
    }
    val majorStr = pluralStringResource(
        majorUnit.pluralResourceId, majorCount.toInt(), majorCount
    )
    val minorUnit = majorUnit.minorUnit()
        ?: return Pair(stringResource(Res.string.duration_time_from_now, majorStr),
            majorUnit.addTo(future, -majorCount))
    val minorUnitInstance = majorUnit.addTo(now, majorCount)
    val minorCount = minorUnit.wholeOf(minorUnitInstance, future)
    val update = minorUnit.addTo(majorUnit.addTo(future, -majorCount), -minorCount)
    if (minorCount != 0L) {
        val minorStr = pluralStringResource(
            minorUnit.pluralResourceId, minorCount.toInt(), minorCount
        )
        return Pair(stringResource(Res.string.duration_time_from_now,
            stringResource(majorUnit.combinerResourceId!!, majorStr, minorStr)), update)
    }
    return Pair(stringResource(Res.string.duration_time_from_now, majorStr), update)
}

internal sealed class DurationUnit(
    val pluralResourceId: PluralStringResource,
    val combinerResourceId: StringResource?
) {

    internal abstract fun minorUnit(): DurationUnit?

    internal abstract fun addTo(instant: Instant, num: Long): Instant
    internal abstract fun wholeOf(past: Instant, future: Instant): Long
}

internal object Minute : DurationUnit(Res.plurals.duration_minute, null) {
    override fun minorUnit(): DurationUnit? = null

    override fun addTo(instant: Instant, num: Long): Instant = instant + num.minutes

    override fun wholeOf(past: Instant, future: Instant): Long = (future - past).inWholeMinutes
}

internal object Hour : DurationUnit(Res.plurals.duration_hour, Res.string.duration_hours_and_minutes) {
    override fun minorUnit(): DurationUnit = Minute

    override fun addTo(instant: Instant, num: Long): Instant = instant + num.hours

    override fun wholeOf(past: Instant, future: Instant): Long = (future - past).inWholeHours
}

internal object Day : DurationUnit(Res.plurals.duration_day, Res.string.duration_days_and_hours) {
    override fun minorUnit(): DurationUnit = Hour

    override fun addTo(instant: Instant, num: Long): Instant = instant + num.days

    override fun wholeOf(past: Instant, future: Instant): Long = (future - past).inWholeDays
}

internal object Week : DurationUnit(Res.plurals.duration_week, Res.string.duration_weeks_and_days) {
    override fun minorUnit(): DurationUnit = Day

    override fun addTo(instant: Instant, num: Long): Instant = instant + (7*num).days

    override fun wholeOf(past: Instant, future: Instant): Long = (future - past).inWholeDays / 7
}

internal object Month : DurationUnit(Res.plurals.duration_month, Res.string.duration_months_and_days) {
    override fun minorUnit(): DurationUnit = Day

    override fun addTo(instant: Instant, num: Long): Instant =
        instant.plus(DateTimePeriod(months = num.toInt()), TimeZone.currentSystemDefault())

    override fun wholeOf(past: Instant, future: Instant): Long =
        past.monthsUntil(future, TimeZone.currentSystemDefault()).toLong()
}

internal object Year : DurationUnit(Res.plurals.duration_year, Res.string.duration_years_and_months) {
    override fun minorUnit(): DurationUnit = Month

    override fun addTo(instant: Instant, num: Long): Instant =
        instant.plus(DateTimePeriod(years = num.toInt()), TimeZone.currentSystemDefault())

    override fun wholeOf(past: Instant, future: Instant): Long =
        past.yearsUntil(future, TimeZone.currentSystemDefault()).toLong()
}

private val units = arrayOf(Year, Month, Week, Day, Hour, Minute)

private fun selectMajorUnit(past: Instant, future: Instant): Pair<DurationUnit?, Long> {
    for (majorUnit in units) {
        val majorCount = majorUnit.wholeOf(past, future)
        if (majorCount != 0L) {
            return Pair(majorUnit, majorCount)
        }
    }
    return Pair(null, 0)
}