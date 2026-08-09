package com.yugma.terrawatch.ui.format

import com.yugma.terrawatch.model.MagRevision
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

/**
 * One-decimal rounding shared by [formatMagnitude] and [formatDepthKm]. Caller guarantees [value]
 * is finite (NaN is intercepted before this is reached) - this only has to worry about rounding.
 */
private fun oneDecimal(value: Double): String {
    val d = (value * 10).roundToInt()
    val whole = d / 10
    val frac = abs(d % 10)
    // Integer division truncates toward zero, so a negative value that rounds to a magnitude
    // under 1.0 (e.g. -0.44 -> whole=0) loses its sign unless restored explicitly here.
    val formatted = if (value < 0 && whole == 0) "-$whole.$frac" else "$whole.$frac"
    // ...but a value that rounds all the way to zero (e.g. -0.01) hits that same branch with
    // frac==0 too, which would otherwise print the meaningless "-0.0". Normalize it away.
    return if (formatted == "-0.0") "0.0" else formatted
}

/** e.g. 6.1 -> "6.1", 6.0 -> "6.0", null/NaN -> "—". Always one decimal place. */
fun formatMagnitude(mag: Double?): String = if (mag == null || mag.isNaN()) "—" else oneDecimal(mag)

/** e.g. 31.1599998 -> "31.2 km", null/NaN -> "depth unknown". Always one decimal place. */
fun formatDepthKm(depthKm: Double?): String =
    if (depthKm == null || depthKm.isNaN()) "depth unknown" else "${oneDecimal(depthKm)} km"

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

/**
 * Task 11: the detail sheet's revision-honesty badge text - null unless the magnitude has
 * genuinely changed at least once. "Genuinely" excludes re-stamps: an agency re-broadcasting the
 * exact same magnitude value at a later timestamp is not a second distinct revision, so a long run
 * of identical re-confirmations never manufactures a badge (consecutive-duplicate values are
 * collapsed away entirely, keeping only the first occurrence of each new value). When there IS a
 * real change, the badge always compares the latest two DISTINCT magnitude values - never the
 * oldest two, however many distinct values the full history contains - and times itself off when
 * the CURRENT (latest) value took effect, not when the previous one did: matches the mockup's
 * "revised from M 5.9 · 12 min ago", where 12 min is how long ago the number now shown took effect,
 * not how long the previous number lasted.
 */
fun revisionNote(revisions: List<MagRevision>, nowMillis: Long): String? {
    val distinctByValue = mutableListOf<MagRevision>()
    for (revision in revisions.sortedBy { it.atMillis }) {
        if (distinctByValue.isEmpty() || distinctByValue.last().mag != revision.mag) {
            distinctByValue.add(revision)
        }
    }
    if (distinctByValue.size < 2) return null
    val previous = distinctByValue[distinctByValue.size - 2]
    val latest = distinctByValue[distinctByValue.size - 1]
    return "revised from M ${formatMagnitude(previous.mag)} · ${formatRelativeTime(latest.atMillis, nowMillis)}"
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

/** e.g. 4102.3 -> "4,102 km", NaN -> "0 km". Rounded to the nearest whole km, thousands-grouped. */
fun formatDistanceKm(km: Double): String =
    if (km.isNaN()) "0 km" else "${groupThousands(km.roundToInt())} km"

/**
 * Two-decimal analog of [oneDecimal] - needed for [formatCoordinates]'s precision, still no
 * java.text/String.format (unavailable on wasmJs). Unlike [oneDecimal], always zero-pads the
 * fractional part to two digits (5 -> "05") since a bare single digit would silently read as
 * tenths rather than the intended hundredths (7.5 vs. 7.05).
 */
private fun twoDecimals(value: Double): String {
    val d = (value * 100).roundToInt()
    val whole = d / 100
    val frac = abs(d % 100)
    val fracStr = if (frac < 10) "0$frac" else "$frac"
    val formatted = if (value < 0 && whole == 0) "-$whole.$fracStr" else "$whole.$fracStr"
    return if (formatted == "-0.00") "0.00" else formatted
}

/**
 * e.g. (7.12, 126.54) -> "7.12°N, 126.54°E". Negative lat/lon flip to S/W (zero counts as N/E)
 * instead of printing a minus sign, matching how real-world coordinates are conventionally
 * written; callers always pass a quake's raw (always-finite) lat/lon, so no NaN guard is needed
 * here the way the nullable magnitude/depth formatters above need one.
 */
fun formatCoordinates(lat: Double, lon: Double): String {
    val latDirection = if (lat < 0) "S" else "N"
    val lonDirection = if (lon < 0) "W" else "E"
    return "${twoDecimals(abs(lat))}°$latDirection, ${twoDecimals(abs(lon))}°$lonDirection"
}
