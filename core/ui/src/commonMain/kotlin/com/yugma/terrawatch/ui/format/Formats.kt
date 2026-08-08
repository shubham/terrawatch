package com.yugma.terrawatch.ui.format

import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.time.ExperimentalTime
import kotlin.time.Instant
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime

/**
 * Pure, allocation-cheap display formatting for quake numbers. No java.text/String.format
 * anywhere in this file - both are unavailable on the wasmJs target, so every rule below is
 * hand-rolled from integer math instead.
 */

private val MONTH_NAMES = arrayOf(
    "Jan", "Feb", "Mar", "Apr", "May", "Jun",
    "Jul", "Aug", "Sep", "Oct", "Nov", "Dec",
)

private const val MINUTE_MILLIS = 60_000L
private const val HOUR_MILLIS = 3_600_000L
private const val DAY_MILLIS = 86_400_000L
private const val WEEK_MILLIS = 604_800_000L

/** One-decimal rounding shared by [formatMagnitude] and [formatDepthKm]. */
private fun oneDecimal(value: Double): String {
    val d = (value * 10).roundToInt()
    val whole = d / 10
    val frac = abs(d % 10)
    // Integer division truncates toward zero, so a negative value that rounds to a magnitude
    // under 1.0 (e.g. -0.44 -> whole=0) loses its sign unless restored explicitly here.
    return if (value < 0 && whole == 0) "-$whole.$frac" else "$whole.$frac"
}

/** e.g. 6.1 -> "6.1", 6.0 -> "6.0", null -> "—". Always one decimal place. */
fun formatMagnitude(mag: Double?): String = if (mag == null) "—" else oneDecimal(mag)

/** e.g. 31.1599998 -> "31.2 km", null -> "depth unknown". Always one decimal place. */
fun formatDepthKm(depthKm: Double?): String =
    if (depthKm == null) "depth unknown" else "${oneDecimal(depthKm)} km"

/**
 * Human-relative age of an event, bucketed at minute/hour/day granularity and falling back to a
 * plain "MMM d" date past a week. Boundaries are exact and half-open: exactly 60s counts as
 * "1 min ago", not "just now"; exactly 7 days counts as a date, not "7 d ago".
 */
@OptIn(ExperimentalTime::class)
fun formatRelativeTime(thenMillis: Long, nowMillis: Long): String {
    val diff = nowMillis - thenMillis
    return when {
        diff < MINUTE_MILLIS -> "just now"
        diff < HOUR_MILLIS -> "${diff / MINUTE_MILLIS} min ago"
        diff < DAY_MILLIS -> "${diff / HOUR_MILLIS} h ago"
        diff < WEEK_MILLIS -> "${diff / DAY_MILLIS} d ago"
        else -> {
            val date = Instant.fromEpochMilliseconds(thenMillis).toLocalDateTime(TimeZone.UTC)
            "${MONTH_NAMES[date.month.ordinal]} ${date.day}"
        }
    }
}

/** Groups the digits of [n]'s absolute value in threes from the right; sign is applied after. */
private fun groupThousands(n: Int): String {
    val digits = abs(n).toString()
    val grouped = buildString {
        for (i in digits.length - 1 downTo 0) {
            append(digits[i])
            val digitsSoFar = digits.length - i
            if (digitsSoFar % 3 == 0 && i != 0) append(',')
        }
    }.reversed()
    return if (n < 0) "-$grouped" else grouped
}

/** e.g. 4102.3 -> "4,102 km". Rounded to the nearest whole km, thousands-grouped. */
fun formatDistanceKm(km: Double): String = "${groupThousands(km.roundToInt())} km"
