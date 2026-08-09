package com.yugma.terrawatch.model

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class GeoTest {
    @Test fun `zero distance to self`() {
        assertEquals(0.0, haversineKm(GeoPoint(12.97, 77.59), GeoPoint(12.97, 77.59)), 1e-9)
    }
    @Test fun `bengaluru to delhi is about 1740 km`() {
        val d = haversineKm(GeoPoint(12.9716, 77.5946), GeoPoint(28.6139, 77.2090))
        assertTrue(abs(d - 1740.0) < 20.0, "got $d")
    }
    @Test fun `antimeridian crossing is short not long`() {
        val d = haversineKm(GeoPoint(0.0, 179.5), GeoPoint(0.0, -179.5))
        assertTrue(d < 200.0, "got $d")
    }
    @Test fun `pole to pole is half circumference`() {
        val d = haversineKm(GeoPoint(90.0, 0.0), GeoPoint(-90.0, 0.0))
        assertTrue(abs(d - 20015.0) < 30.0, "got $d")
    }
    @Test fun `near-antipodal points do not produce NaN`() {
        val d = haversineKm(
            GeoPoint(58.48394856419739, 85.15927551648440),
            GeoPoint(-58.48394856420722, -94.84072448351225),
        )
        assertTrue(d.isFinite(), "got $d")
        assertTrue(abs(d - 20015.0) < 30.0, "got $d")
    }

    // Task 7 (Plan 3): circlePolygon — the home-radius map ring's pure geometry.

    @Test fun `circlePolygon returns points+1 vertices`() {
        val ring = circlePolygon(GeoPoint(12.9716, 77.5946), radiusKm = 250.0, points = 64)
        assertEquals(65, ring.size)
    }

    @Test fun `default points parameter is 64`() {
        val ring = circlePolygon(GeoPoint(0.0, 0.0), radiusKm = 10.0)
        assertEquals(65, ring.size)
    }

    @Test fun `circlePolygon is a closed ring - first and last vertex are identical`() {
        val ring = circlePolygon(GeoPoint(10.0, 20.0), radiusKm = 50.0, points = 64)
        assertEquals(ring.first(), ring.last())
    }

    @Test fun `every vertex sits within half a percent of the requested radius`() {
        val center = GeoPoint(12.9716, 77.5946) // Bengaluru
        val radiusKm = 250.0
        val ring = circlePolygon(center, radiusKm, points = 64)
        // dropLast(1): the closing vertex is a duplicate of the first, already covered.
        ring.dropLast(1).forEach { vertex ->
            val d = haversineKm(center, vertex)
            val relativeError = abs(d - radiusKm) / radiusKm
            assertTrue(relativeError < 0.005, "vertex $vertex is ${d}km from center, expected ~${radiusKm}km")
        }
    }

    @Test fun `a tiny radius still produces vertices within half a percent`() {
        val center = GeoPoint(-33.4489, -70.6693) // Santiago
        val radiusKm = 1.0
        val ring = circlePolygon(center, radiusKm, points = 64)
        ring.dropLast(1).forEach { vertex ->
            val d = haversineKm(center, vertex)
            assertTrue(abs(d - radiusKm) / radiusKm < 0.005, "vertex $vertex is ${d}km from center")
        }
    }

    @Test fun `circlePolygon at 4 points traces north, east, south, west in order`() {
        // Center ON the equator so due-east/due-west bearings stay exactly on the equator (the
        // equator is itself a great circle) — an unambiguous, exactly-checkable sanity geometry.
        val center = GeoPoint(0.0, 0.0)
        val ring = circlePolygon(center, radiusKm = 100.0, points = 4)
        assertEquals(5, ring.size)
        assertEquals(ring.first(), ring.last())

        val north = ring[0]
        assertTrue(north.lat > 0.0, "bearing 0 (north) should increase latitude, got $north")
        assertEquals(0.0, north.lon, 1e-9)

        val east = ring[1]
        assertEquals(0.0, east.lat, 1e-9, "due east from the equator stays on the equator")
        assertTrue(east.lon > 0.0, "bearing 90 (east) should increase longitude, got $east")

        val south = ring[2]
        assertTrue(south.lat < 0.0, "bearing 180 (south) should decrease latitude, got $south")

        val west = ring[3]
        assertEquals(0.0, west.lat, 1e-9, "due west from the equator stays on the equator")
        assertTrue(west.lon < 0.0, "bearing 270 (west) should decrease longitude, got $west")
    }

    @Test fun `circlePolygon rejects fewer than 3 points`() {
        assertFailsWith<IllegalArgumentException> {
            circlePolygon(GeoPoint(0.0, 0.0), radiusKm = 10.0, points = 2)
        }
    }
}
