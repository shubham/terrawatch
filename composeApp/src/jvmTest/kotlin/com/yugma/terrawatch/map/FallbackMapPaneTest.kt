package com.yugma.terrawatch.map

import androidx.compose.ui.geometry.Offset
import com.yugma.terrawatch.model.MagnitudeBand
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Task 12: [FallbackMapPane]'s pure geometry/hit-test helpers, tested independent of any Compose
 * runtime (`Offset` is a plain value class, no composition needed). The projection and hit-test
 * are exactly the kind of "off by a sign, inverted axis, wrong comparison operator" logic that
 * looks obviously right on the screen until it silently drops taps near an edge or a pole — worth
 * pinning even though this task's own TDD mandate names only `layoutMode`.
 */
class FallbackMapPaneTest {

    @Test
    fun `projectLon maps the antimeridian to the canvas edges and the prime meridian to center`() {
        assertEquals(0f, projectLon(-180.0, widthPx = 1000f))
        assertEquals(1000f, projectLon(180.0, widthPx = 1000f))
        assertEquals(500f, projectLon(0.0, widthPx = 1000f))
    }

    @Test
    fun `projectLat maps the north pole to the top and south pole to the bottom`() {
        assertEquals(0f, projectLat(90.0, heightPx = 500f))
        assertEquals(500f, projectLat(-90.0, heightPx = 500f))
        assertEquals(250f, projectLat(0.0, heightPx = 500f))
    }

    @Test
    fun `fallbackPinRadiusDp is exactly half of QuakeMap-android's band radii`() {
        // Mirrors QuakeMap.android.kt's pinRadiusDp table (LOW=4, MODERATE=6, STRONG=8, MAJOR=10,
        // UNKNOWN=3), halved — see FallbackMapPane.kt's own kdoc for why.
        assertEquals(2f, fallbackPinRadiusDp(MagnitudeBand.LOW))
        assertEquals(3f, fallbackPinRadiusDp(MagnitudeBand.MODERATE))
        assertEquals(4f, fallbackPinRadiusDp(MagnitudeBand.STRONG))
        assertEquals(5f, fallbackPinRadiusDp(MagnitudeBand.MAJOR))
        assertEquals(1.5f, fallbackPinRadiusDp(MagnitudeBand.UNKNOWN))
    }

    @Test
    fun `nearestPinWithin picks the closer of two in-range pins`() {
        val nearer = pin("nearer", lat = 0.0, lon = 0.5)
        val farther = pin("farther", lat = 0.0, lon = 3.0)
        val id = nearestPinWithin(
            pins = listOf(farther, nearer),
            tap = Offset(500f, 250f),
            widthPx = 1000f,
            heightPx = 500f,
            radiusPx = 24f,
        )
        assertEquals("nearer", id)
    }

    @Test
    fun `nearestPinWithin ignores a pin outside the hit radius`() {
        val outOfRange = pin("far", lat = 0.0, lon = -90.0)
        val id = nearestPinWithin(
            pins = listOf(outOfRange),
            tap = Offset(500f, 250f),
            widthPx = 1000f,
            heightPx = 500f,
            radiusPx = 24f,
        )
        assertNull(id)
    }

    @Test
    fun `nearestPinWithin returns null for an empty pin list`() {
        assertNull(
            nearestPinWithin(
                pins = emptyList(),
                tap = Offset(500f, 250f),
                widthPx = 1000f,
                heightPx = 500f,
                radiusPx = 24f,
            ),
        )
    }

    private fun pin(id: String, lat: Double, lon: Double) =
        QuakePin(id = id, lat = lat, lon = lon, mag = 5.0, band = MagnitudeBand.MODERATE, isNew = false)
}
