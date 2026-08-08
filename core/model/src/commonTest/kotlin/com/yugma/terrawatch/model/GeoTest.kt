package com.yugma.terrawatch.model

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
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
}
