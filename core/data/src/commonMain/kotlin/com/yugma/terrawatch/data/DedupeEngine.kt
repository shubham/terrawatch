package com.yugma.terrawatch.data

import com.yugma.terrawatch.model.GeoPoint
import com.yugma.terrawatch.model.Quake
import com.yugma.terrawatch.model.QuakeStatus
import com.yugma.terrawatch.model.Source
import com.yugma.terrawatch.model.haversineKm
import kotlin.math.abs

data class ReconcileResult(val canonical: Quake, val replacesId: String?)

class DedupeEngine(
    private val timeWindowMs: Long = 90_000,
    private val distanceKm: Double = 100.0,
) {
    fun reconcile(candidates: List<Quake>, incoming: Quake): ReconcileResult {
        val match = candidates
            .filter { abs(it.timeMillis - incoming.timeMillis) <= timeWindowMs }
            .map { it to haversineKm(GeoPoint(it.lat, it.lon), GeoPoint(incoming.lat, incoming.lon)) }
            .filter { (_, d) -> d <= distanceKm }
            .minByOrNull { (_, d) -> d }?.first
            ?: return ReconcileResult(incoming, null)

        val merged = merge(match, incoming)
        val replaces = if (merged.id != match.id) match.id else null
        return ReconcileResult(merged, replaces)
    }

    private fun merge(existing: Quake, incoming: Quake): Quake {
        val id = when {
            existing.sources.containsKey(Source.USGS) -> existing.id
            incoming.sources.containsKey(Source.USGS) ->
                incoming.sources.getValue(Source.USGS)
            else -> existing.id
        }
        val magHolder = pickMagnitudeHolder(existing, incoming)
        val placeHolder = if (existing.sources.containsKey(Source.USGS)) existing
            else if (incoming.sources.containsKey(Source.USGS)) incoming else existing
        val revisions = (existing.revisions + incoming.revisions)
            .distinctBy { Triple(it.mag, it.atMillis, it.source) }
            .sortedBy { it.atMillis }
        return Quake(
            id = id,
            timeMillis = magHolder.timeMillis,
            lat = magHolder.lat, lon = magHolder.lon,
            depthKm = magHolder.depthKm ?: existing.depthKm ?: incoming.depthKm,
            mag = magHolder.mag, magType = magHolder.magType,
            place = placeHolder.place,
            tsunami = existing.tsunami || incoming.tsunami,
            felt = listOfNotNull(existing.felt, incoming.felt).maxOrNull(),
            status = if (existing.status == QuakeStatus.REVIEWED || incoming.status == QuakeStatus.REVIEWED)
                QuakeStatus.REVIEWED else QuakeStatus.AUTOMATIC,
            sources = existing.sources + incoming.sources,
            revisions = revisions,
            updatedAtMillis = maxOf(existing.updatedAtMillis, incoming.updatedAtMillis),
        )
    }

    private fun pickMagnitudeHolder(a: Quake, b: Quake): Quake = when {
        (a.status == QuakeStatus.REVIEWED) != (b.status == QuakeStatus.REVIEWED) ->
            if (a.status == QuakeStatus.REVIEWED) a else b
        a.sources.containsKey(Source.USGS) != b.sources.containsKey(Source.USGS) ->
            if (a.sources.containsKey(Source.USGS)) a else b
        else -> if (a.updatedAtMillis >= b.updatedAtMillis) a else b
    }
}
