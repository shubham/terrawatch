package com.yugma.terrawatch.data

import com.yugma.terrawatch.model.GeoPoint
import com.yugma.terrawatch.model.Quake
import com.yugma.terrawatch.model.QuakeStatus
import com.yugma.terrawatch.model.Source
import com.yugma.terrawatch.model.haversineKm
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

private const val NOW = 2_000_000L
private val HOME = GeoPoint(12.97, 77.59) // Bengaluru, same reference point AlertRuleEngineTest uses

private fun q(
    id: String = "q1",
    mag: Double?,
    lat: Double = HOME.lat,
    lon: Double = HOME.lon,
    timeMillis: Long = NOW,
) = Quake(
    id = id, timeMillis = timeMillis, lat = lat, lon = lon, depthKm = 10.0,
    mag = mag, magType = "mb", place = "P", tsunami = false, felt = null,
    status = QuakeStatus.AUTOMATIC, sources = mapOf(Source.USGS to id),
    revisions = emptyList(), updatedAtMillis = timeMillis,
)

class PillStatusTest {
    @Test fun `missing home location asks for one regardless of quakes`() {
        val quake = q(mag = 9.0, lat = 0.0, lon = 0.0)
        val status = pillStatus(listOf(quake), home = null, nowMillis = NOW)
        assertEquals(PillStatus.Kind.ASK_LOCATION, status.kind)
        assertNull(status.quake)
    }

    @Test fun `no qualifying quakes yields calm`() {
        // Too weak (below the default 4.5 minMag) even though it's right at home and brand new.
        val tooWeak = q(id = "weak", mag = 3.0)
        val status = pillStatus(listOf(tooWeak), HOME, NOW)
        assertEquals(PillStatus.Kind.CALM, status.kind)
        assertNull(status.quake)
    }

    @Test fun `alert picks the nearest qualifying quake, not the largest`() {
        val near = q(id = "near", mag = 4.6, lat = HOME.lat + 0.1, lon = HOME.lon)
        val far = q(id = "far", mag = 7.9, lat = HOME.lat + 2.0, lon = HOME.lon)
        // Passed far-first so a correct implementation can't accidentally "win" by picking
        // whichever quake happens to come first in the list.
        val status = pillStatus(listOf(far, near), HOME, NOW)
        assertEquals(PillStatus.Kind.ALERT, status.kind)
        assertEquals("near", status.quake?.id)
    }

    @Test fun `quake exactly at the radius boundary counts as within range`() {
        val quakePoint = GeoPoint(HOME.lat + 3.0, HOME.lon + 2.0)
        // Derive the boundary radius from the SAME haversineKm the implementation itself must
        // call, rather than hand-computing a "should be ~500km" coordinate — that would only
        // prove the boundary is *near* the cutoff, not verify the comparison is <= (inclusive)
        // rather than < (exclusive) at the exact value in question.
        val exactDistance = haversineKm(HOME, quakePoint)
        val quake = q(mag = 5.0, lat = quakePoint.lat, lon = quakePoint.lon)
        val status = pillStatus(listOf(quake), HOME, NOW, radiusKm = exactDistance)
        assertEquals(PillStatus.Kind.ALERT, status.kind)
    }

    @Test fun `quake exactly at the magnitude threshold counts`() {
        val quake = q(mag = 4.5)
        val status = pillStatus(listOf(quake), HOME, NOW, minMag = 4.5)
        assertEquals(PillStatus.Kind.ALERT, status.kind)
    }

    @Test fun `quake outside the time window is excluded even if otherwise qualifying`() {
        val windowMs = 86_400_000L
        val stale = q(mag = 6.0, timeMillis = NOW - windowMs - 1)
        val status = pillStatus(listOf(stale), HOME, NOW, windowMs = windowMs)
        assertEquals(PillStatus.Kind.CALM, status.kind)
        assertNull(status.quake)
    }
}
