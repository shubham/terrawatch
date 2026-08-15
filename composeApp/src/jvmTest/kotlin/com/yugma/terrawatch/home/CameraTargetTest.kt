package com.yugma.terrawatch.home

import com.yugma.terrawatch.model.GeoPoint
import com.yugma.terrawatch.model.haversineKm
import kotlin.math.PI
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

// Same sphere haversineKm itself uses (core:model's Geo.kt, EARTH_RADIUS_KM — private there, so
// this constant is duplicated rather than imported): for two points sharing the same longitude,
// haversineKm collapses to exactly EARTH_RADIUS_KM * |dLatRadians| (the sin(dLon/2)=0 term drops
// out, and asin(sin(x)) == x for any |x| <= PI/2 — true of every latitude delta this file
// constructs). That closed form lets [pointDueNorth] place a point at a KNOWN, precise distance
// from another, so the near/far/boundary tests below assert against a derived value rather than a
// guessed one — mirroring PillStatusTest's own "derive the boundary from the same haversineKm the
// implementation itself must call" discipline (core/data/src/commonTest/.../PillStatusTest.kt),
// adapted for a fixed internal threshold (COLD_START_RECENTER_THRESHOLD_KM) rather than a caller
// -supplied parameter.
private const val EARTH_RADIUS_KM = 6371.0088

private fun pointDueNorth(from: GeoPoint, distanceKm: Double): GeoPoint {
    val deltaLatDegrees = (distanceKm / EARTH_RADIUS_KM) * (180.0 / PI)
    return GeoPoint(from.lat + deltaLatDegrees, from.lon)
}

private val SAVED = GeoPoint(12.9716, 77.5946) // Bengaluru

class CameraTargetTest {
    @Test
    fun `no permission means leave the camera alone, even with a real fix`() {
        val fix = pointDueNorth(SAVED, 500.0) // unambiguously "far", if it mattered
        assertNull(startupCameraTarget(savedTarget = SAVED, fix = fix, permissionGranted = false))
    }

    @Test
    fun `no fix means leave the camera alone, even with permission granted`() {
        assertNull(startupCameraTarget(savedTarget = SAVED, fix = null, permissionGranted = true))
    }

    @Test
    fun `no saved target and a real fix centers on the fix - first-ever resolution`() {
        val fix = GeoPoint(40.7128, -74.0060) // New York - arbitrary, nothing to compare it against
        assertEquals(fix, startupCameraTarget(savedTarget = null, fix = fix, permissionGranted = true))
    }

    @Test
    fun `no saved, no fix, no permission still yields null - permission checked first`() {
        assertNull(startupCameraTarget(savedTarget = null, fix = null, permissionGranted = false))
    }

    @Test
    fun `fix well within 50km of saved does not recenter - avoid fighting deliberate pans`() {
        val fix = pointDueNorth(SAVED, 10.0)
        assertNull(startupCameraTarget(savedTarget = SAVED, fix = fix, permissionGranted = true))
    }

    @Test
    fun `fix well beyond 50km of saved recenters to the fix`() {
        val fix = pointDueNorth(SAVED, 200.0)
        assertEquals(fix, startupCameraTarget(savedTarget = SAVED, fix = fix, permissionGranted = true))
    }

    // Boundary pair: derived via the exact same haversineKm the implementation itself calls (see
    // pointDueNorth's own kdoc above), not hand-guessed lat/lon offsets — this is what lets these
    // two cases pin the ">" (strictly greater than) comparison itself, not just "somewhere near the
    // threshold, roughly the right side".

    @Test
    fun `fix just under the 50km threshold does not recenter`() {
        val fix = pointDueNorth(SAVED, 49.999)
        // Sanity-check this test's own derived point before trusting it as "just under 50km" —
        // guards this test against a formula slip in pointDueNorth, same self-verifying style
        // GeoTest.kt already uses for circlePolygon's traced vertices.
        val actualDistance = haversineKm(SAVED, fix)
        assertEquals(49.999, actualDistance, 1e-6)
        assertNull(startupCameraTarget(savedTarget = SAVED, fix = fix, permissionGranted = true))
    }

    @Test
    fun `fix just over the 50km threshold recenters`() {
        val fix = pointDueNorth(SAVED, 50.001)
        val actualDistance = haversineKm(SAVED, fix)
        assertEquals(50.001, actualDistance, 1e-6)
        assertEquals(fix, startupCameraTarget(savedTarget = SAVED, fix = fix, permissionGranted = true))
    }

    @Test
    fun `fix exactly at the 50km threshold does not recenter - boundary is exclusive`() {
        val fix = pointDueNorth(SAVED, 50.0)
        val actualDistance = haversineKm(SAVED, fix)
        assertEquals(50.0, actualDistance, 1e-6)
        assertNull(startupCameraTarget(savedTarget = SAVED, fix = fix, permissionGranted = true))
    }

    @Test
    fun `fix identical to saved does not recenter`() {
        assertNull(startupCameraTarget(savedTarget = SAVED, fix = SAVED, permissionGranted = true))
    }
}
