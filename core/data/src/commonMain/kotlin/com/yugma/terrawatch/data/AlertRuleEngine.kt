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
