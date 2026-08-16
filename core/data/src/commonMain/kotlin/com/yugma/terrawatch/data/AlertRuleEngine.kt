package com.yugma.terrawatch.data

import com.yugma.terrawatch.model.GeoPoint
import com.yugma.terrawatch.model.Quake
import com.yugma.terrawatch.model.haversineKm

data class AlertRule(
    val id: String,
    val minMag: Double,
    val radiusKm: Double?,
    val center: GeoPoint?,
    val enabled: Boolean = true,
)

val DEFAULT_RULES = listOf(
    AlertRule(id = "near", minMag = 4.5, radiusKm = 500.0, center = null),
    AlertRule(id = "world", minMag = 6.0, radiusKm = null, center = null),
)

data class AlertEvent(val quake: Quake, val matchedRuleId: String)

/**
 * **M4 ruling (Task 2, Plan 4; plan-3-exit-conditions.md carried item) — mirrors [PillStatus.
 * pillStatus]'s own copy of this same note, recorded on both sites per that item's request:** this
 * engine's rule set is intentionally BROADER than the status pill's own vocabulary.
 * [DEFAULT_RULES] carries two independent rules — "near" (home-relative, radius-bounded) AND
 * "world" (M6.0+, unbounded radius, anywhere on Earth) — and [evaluate] matches either one. The
 * pill ([pillStatus]) only ever reflects the "near" half; it has no "world" state of its own, so a
 * "world" [AlertEvent] can fire here (already reaching [QuakeRepository.alertEvents] today) with
 * zero visible change to the pill. That split is deliberate, not a bug: **notifications (Plan 4
 * Task 3) will consume this engine's full rule set, world rule included — the pill stays
 * home-relative only.** See [pillStatus]'s own kdoc for the fuller ruling and the
 * "calm pill + real world-rule alert already fired" scenario this implies.
 */
class AlertRuleEngine {
    /**
     * USER REQUIREMENT (2026-08-16, binding): "only notify for magnitude 4 and above" — enforced
     * here as a hard floor UNDER every rule's own [AlertRule.minMag], not layered on top of any one
     * caller. (Note for anyone cross-referencing this file's own pre-existing "M4 ruling" kdoc
     * above, on [DEFAULT_RULES]: that's Plan 4's unrelated ruling about the pill vs. notifications
     * consuming different rule subsets — a coincidence of shorthand, not the same decision as this
     * one, which is purely about a MAGNITUDE floor.)
     *
     * This `for` loop is the SOLE place any [AlertRule.minMag] is ever compared against a quake's
     * magnitude — home's own "near"/"world" rules ([com.yugma.terrawatch.data.QuakeRepository.
     * currentRules]) AND every rule [buildDigestRules] assembles for a favorite place (including a
     * [com.yugma.terrawatch.model.FavoriteAlertType.ALL] favorite, whose own `minMag` is literally
     * whatever [AlertRuleStore.minMag] currently holds) all funnel through this exact method. Gating
     * HERE, and only here, is what makes "nothing below M4.0 can ever notify" true for every one of
     * those paths at once, including any future caller nobody has written yet — no per-caller
     * discipline required.
     *
     * `effectiveMinMag = max(rule.minMag, MIN_NOTIFIABLE_MAGNITUDE)`, not a flat replacement of
     * [AlertRule.minMag]: a rule already at or above 4.0 ("world", or a [com.yugma.terrawatch.model.
     * FavoriteAlertType.MAJOR_ONLY] favorite, both fixed at 6.0) is completely unaffected — only a
     * rule that WOULD have allowed something below 4.0 gets clamped up. [AlertRuleStore]'s own
     * Settings-slider range moved to 4.0-6.0 in lockstep (read-clamped there too, mirroring the
     * pre-existing M2 radius precedent — see [AlertRuleStore.minMag]'s own kdoc) precisely so the UI
     * can no longer even OFFER a value this floor would silently override — but this floor is the
     * actual backstop regardless of what the store, or any future non-slider caller, holds.
     */
    fun evaluate(previous: Quake?, current: Quake, rules: List<AlertRule>, home: GeoPoint?): AlertEvent? {
        val mag = current.mag ?: return null
        for (rule in rules) {
            if (!rule.enabled) continue
            val effectiveMinMag = maxOf(rule.minMag, MIN_NOTIFIABLE_MAGNITUDE)
            if (mag < effectiveMinMag) continue
            val prevMag = previous?.mag
            if (prevMag != null && prevMag >= effectiveMinMag) continue
            if (rule.radiusKm != null) {
                val center = rule.center ?: home ?: continue
                if (haversineKm(center, GeoPoint(current.lat, current.lon)) > rule.radiusKm) continue
            }
            return AlertEvent(current, rule.id)
        }
        return null
    }

    companion object {
        /** See [evaluate]'s own kdoc for the full ruling this hard floor enforces. */
        const val MIN_NOTIFIABLE_MAGNITUDE = 4.0
    }
}
