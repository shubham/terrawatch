package com.yugma.terrawatch.alerts

import com.yugma.terrawatch.data.AlertEvent
import com.yugma.terrawatch.ui.format.formatMagnitude
import com.yugma.terrawatch.ui.format.formatRelativeTime

// Task 2 (Plan 4), M4 ruling (recorded at AlertRuleEngine/PillStatus): [DEFAULT_RULES] carries
// exactly "near" (home-relative, radius-bounded) and "world" (unbounded, M6+ anywhere) — this
// file's own honesty split below only needs to tell those two apart, not enumerate every possible
// future rule id.
private const val NEAR_RULE_ID = "near"

/**
 * Task 3 (Plan 4), digest honesty (spec §6.5): `AlertDigestWorker`'s (androidMain) notification
 * title — magnitude, an honest location phrase, and how long ago the quake happened.
 *
 * The location phrase is rule-aware, not a blanket "near you": [AlertEvent.matchedRuleId] ==
 * [NEAR_RULE_ID] is the ONLY case where "near you" is actually true (that rule is home-relative
 * and radius-bounded — see `AlertRuleEngine`'s own kdoc). A "world" match (M6+, unbounded radius,
 * anywhere on Earth) uses the quake's own [com.yugma.terrawatch.model.Quake.place] instead — saying
 * "near you" for a quake that could be on the opposite side of the planet would be the exact
 * dishonesty spec §6.5 exists to rule out, one step further than just avoiding the phrase
 * "early warning."
 *
 * Reuses [formatRelativeTime] verbatim (its own already-TDD'd "X h ago" spacing) rather than a
 * second hand-rolled "how long ago" string — same copy convention every other "ago" label in this
 * app (DetailSheet's revision badge, QuakeCard) already uses, so a digest notification reads like
 * the rest of TerraWatch, not a one-off dialect.
 */
fun digestNotificationTitle(event: AlertEvent, nowMillis: Long): String {
    val magText = "M${formatMagnitude(event.quake.mag)}"
    val timeText = formatRelativeTime(event.quake.timeMillis, nowMillis)
    // "near": one joined clause ("M6.2 near you") then time -- "near you" reads as a description
    // OF the magnitude clause, not a separate fact. "world": three independent segments, each its
    // own `·`-separated fact (magnitude / place / time) -- there is no single natural clause to
    // join "M6.5" and an arbitrary place name into, and forcing one would blur the honesty split
    // this function exists for.
    return if (event.matchedRuleId == NEAR_RULE_ID) {
        "$magText near you · $timeText"
    } else {
        "$magText · ${event.quake.place} · $timeText"
    }
}

/**
 * The notification's supporting line. For a "near" match (whose title already says "near you"
 * rather than naming a place), this is the quake's own place — the one honest location detail the
 * title left out. For a "world" match (whose title already names the place), this instead names
 * WHICH rule matched, so a user who never configured a "worldwide M6+" rule mentally isn't left
 * wondering why a quake nowhere near them showed up at all.
 */
fun digestNotificationBody(event: AlertEvent): String =
    if (event.matchedRuleId == NEAR_RULE_ID) {
        event.quake.place
    } else {
        "${event.quake.place} — matches your worldwide M6+ alert rule"
    }

/** The summary notification's text when [DigestPlan.summaryExtraCount][com.yugma.terrawatch.data.
 * DigestPlan.summaryExtraCount] is positive — never a second full listing, just an honest count. */
fun summaryNotificationText(extraCount: Int): String {
    val noun = if (extraCount == 1) "earthquake" else "earthquakes"
    return "$extraCount more $noun matched your alerts"
}
