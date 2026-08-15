package com.yugma.terrawatch.data

import com.yugma.terrawatch.model.GeoPoint
import com.yugma.terrawatch.model.Quake
import com.yugma.terrawatch.model.haversineKm

/**
 * The status pill's brain (Task 9) — a pure verdict on "is there a significant quake close
 * enough to home to warrant an alert, or is everything calm?" [kind] drives which of
 * [com.yugma.terrawatch.ui.components.StatusShield]'s three faces renders; [quake] is only
 * non-null for [Kind.ALERT] (the nearest qualifying quake).
 */
data class PillStatus(val kind: Kind, val quake: Quake?) {
    enum class Kind { CALM, ALERT, ASK_LOCATION }
}

/**
 * [home] being unknown wins over everything else — there's no "near"/"far" without a reference
 * point, so HomeScreen falls back to this before [com.yugma.terrawatch.location.LocationProvider]
 * (via [HomeLocationStore]) has resolved a fix. Otherwise: the nearest quake within [radiusKm]
 * (inclusive), that occurred within [windowMs] of [nowMillis] (inclusive), at or above [minMag]
 * (inclusive) becomes an [PillStatus.Kind.ALERT]; if none qualify, [PillStatus.Kind.CALM].
 *
 * **M4 ruling (Task 2, Plan 4; plan-3-exit-conditions.md carried item), recorded here per that
 * item's own request — do not re-litigate without a new decision:** this function is, and stays,
 * **nearby-only**. It has no notion of [AlertRuleEngine.DEFAULT_RULES]'s independent "world" rule
 * (M6.0+, unbounded radius, matches a major quake anywhere on Earth) — that rule already populates
 * [QuakeRepository.alertEvents] today via [AlertRuleEngine.evaluate] with zero pill-visible effect,
 * and this ruling makes that split deliberate rather than an oversight. The reconciliation Plan 3
 * left open ("a third pill state for world-rule matches, or notifications scoped to near-rule
 * only") resolves as: **notifications (Plan 4 Task 3) will consume [AlertRuleEngine]'s full rule
 * set, world rule included — the pill stays exactly this narrow.** A user can therefore see a
 * "calm" pill (nothing qualifies within [radiusKm]/[windowMs]/[minMag] of home) at the same moment
 * a distant M6.5 has already fired a "world" [com.yugma.terrawatch.data.AlertEvent] and will, once
 * Task 3 ships, raise a real notification — this is the intended vocabulary split, not a bug: the
 * pill answers "should I be worried about MY area right now," notifications answer "did something
 * globally significant just happen." See [AlertRuleEngine]'s own kdoc for the mirror of this note.
 */
fun pillStatus(
    quakes: List<Quake>,
    home: GeoPoint?,
    nowMillis: Long,
    // Fix Round 1 (entangled minor): was an independently-hardcoded 500.0, stale the moment Task 7
    // (Plan 3) shipped 100.0 as this app's real default (AlertRuleStore.DEFAULT_RADIUS_KM). Every
    // real call site (HomeScreen, both PhoneLayout/TwoPaneLayout) already threads the store's live
    // value through explicitly - see HomeScreen.kt's own comment at its pillStatus() calls - so this
    // default is only ever reached by a caller (chiefly tests) that omits the argument entirely.
    // Pointing straight at the same constant AlertRuleStore itself defines removes the drift risk
    // outright instead of re-hardcoding a second, independent "100.0" that could silently diverge
    // from it again later - exactly the class of bug StatusShield's own Task 7 device-caught fix
    // (see that file's kdoc) already burned this task once.
    radiusKm: Double = AlertRuleStore.DEFAULT_RADIUS_KM,
    windowMs: Long = 86_400_000,
    minMag: Double = 4.5,
): PillStatus {
    if (home == null) return PillStatus(PillStatus.Kind.ASK_LOCATION, null)
    val nearest = nearestSignificant(quakes, home, nowMillis, radiusKm, windowMs, minMag)
    return if (nearest == null) {
        PillStatus(PillStatus.Kind.CALM, null)
    } else {
        PillStatus(PillStatus.Kind.ALERT, nearest)
    }
}

/**
 * The nearest quake to [home] that's both big enough ([minMag]) and recent enough ([windowMs]) —
 * everything else (too small, too old, or simply too far away) is invisible to the pill. A tie
 * for distance keeps whichever [Quake] [minByOrNull] happens to return first; the pill only ever
 * surfaces one quake at a time, and an exact distance tie between two real event coordinates is
 * astronomically unlikely.
 */
private fun nearestSignificant(
    quakes: List<Quake>,
    home: GeoPoint,
    nowMillis: Long,
    radiusKm: Double,
    windowMs: Long,
    minMag: Double,
): Quake? = quakes
    .filter { val mag = it.mag; mag != null && mag >= minMag }
    .filter { nowMillis - it.timeMillis <= windowMs }
    .map { it to haversineKm(home, GeoPoint(it.lat, it.lon)) }
    .filter { (_, distanceKm) -> distanceKm <= radiusKm }
    .minByOrNull { (_, distanceKm) -> distanceKm }
    ?.first
