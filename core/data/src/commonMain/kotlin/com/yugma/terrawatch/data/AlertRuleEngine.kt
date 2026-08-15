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
    fun evaluate(previous: Quake?, current: Quake, rules: List<AlertRule>, home: GeoPoint?): AlertEvent? {
        val mag = current.mag ?: return null
        for (rule in rules) {
            if (!rule.enabled) continue
            if (mag < rule.minMag) continue
            val prevMag = previous?.mag
            if (prevMag != null && prevMag >= rule.minMag) continue
            if (rule.radiusKm != null) {
                val center = rule.center ?: home ?: continue
                if (haversineKm(center, GeoPoint(current.lat, current.lon)) > rule.radiusKm) continue
            }
            return AlertEvent(current, rule.id)
        }
        return null
    }
}
