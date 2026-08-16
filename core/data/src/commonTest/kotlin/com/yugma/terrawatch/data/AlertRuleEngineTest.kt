package com.yugma.terrawatch.data

import com.yugma.terrawatch.model.GeoPoint
import com.yugma.terrawatch.model.Quake
import com.yugma.terrawatch.model.QuakeStatus
import com.yugma.terrawatch.model.Source
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertNotNull

private fun q(mag: Double?, lat: Double = 13.0, lon: Double = 77.6, updated: Long = 1000) =
    Quake("q1", 900, lat, lon, 10.0, mag, "mb", "P", false, null,
        QuakeStatus.AUTOMATIC, mapOf(Source.USGS to "q1"), emptyList(), updated)

class AlertRuleEngineTest {
    private val engine = AlertRuleEngine()
    private val home = GeoPoint(12.97, 77.59)  // Bengaluru

    @Test fun `new nearby quake above threshold fires near rule`() {
        val e = assertNotNull(engine.evaluate(null, q(5.0), DEFAULT_RULES, home))
        assertEquals("near", e.matchedRuleId)
    }

    @Test fun `new far quake below world threshold does not fire`() {
        assertNull(engine.evaluate(null, q(5.5, lat = 35.0, lon = 140.0), DEFAULT_RULES, home))
    }

    @Test fun `new far major quake fires world rule`() {
        val e = assertNotNull(engine.evaluate(null, q(6.2, lat = 35.0, lon = 140.0), DEFAULT_RULES, home))
        assertEquals("world", e.matchedRuleId)
    }

    @Test fun `revision crossing threshold fires`() {
        val e = engine.evaluate(q(5.8, lat = 35.0, lon = 140.0), q(6.1, lat = 35.0, lon = 140.0), DEFAULT_RULES, home)
        assertEquals("world", assertNotNull(e).matchedRuleId)
    }

    @Test fun `update without crossing does not refire`() {
        assertNull(engine.evaluate(q(6.1, lat = 35.0, lon = 140.0), q(6.3, lat = 35.0, lon = 140.0), DEFAULT_RULES, home))
    }

    @Test fun `radius rule without home never fires`() {
        assertNull(engine.evaluate(null, q(5.0), DEFAULT_RULES, home = null))
    }

    @Test fun `world rule works without home`() {
        val e = assertNotNull(engine.evaluate(null, q(6.5), DEFAULT_RULES, home = null))
        assertEquals("world", e.matchedRuleId)
    }

    @Test fun `disabled rule is skipped`() {
        val rules = DEFAULT_RULES.map { it.copy(enabled = false) }
        assertNull(engine.evaluate(null, q(7.0), rules, home))
    }

    @Test fun `null magnitude never fires`() {
        assertNull(engine.evaluate(null, q(null), DEFAULT_RULES, home))
    }

    @Test fun `previous without magnitude does not suppress firing`() {
        // first report had no mag; revision adds M6.2 — must fire (null prev-mag = never crossed)
        val e = engine.evaluate(q(null, lat = 35.0, lon = 140.0), q(6.2, lat = 35.0, lon = 140.0), DEFAULT_RULES, home)
        assertEquals("world", assertNotNull(e).matchedRuleId)
    }

    // --- USER REQUIREMENT (2026-08-16, binding), M4.0 magnitude-floor ruling: a hard floor beneath
    // EVERY rule's own minMag, enforced in evaluate() itself (see that method's own kdoc). Uses a
    // permissive custom rule (minMag well below 4.0, radiusKm=null so home/location never enters
    // into it) to isolate the floor from every other one of evaluate's own checks. -------------------

    @Test fun `a custom rule with minMag below the M4 floor does not fire for a sub-4 quake`() {
        val permissive = AlertRule(id = "custom", minMag = 3.0, radiusKm = null, center = null)
        assertNull(engine.evaluate(null, q(3.9), listOf(permissive), home = null))
    }

    @Test fun `a custom rule with minMag below the M4 floor fires at exactly M4point0 -- inclusive`() {
        val permissive = AlertRule(id = "custom", minMag = 3.0, radiusKm = null, center = null)
        val e = assertNotNull(engine.evaluate(null, q(4.0), listOf(permissive), home = null))
        assertEquals("custom", e.matchedRuleId)
    }

    @Test fun `a rule already stricter than the M4 floor is unaffected -- its own higher minMag still governs`() {
        val strict = AlertRule(id = "custom", minMag = 5.0, radiusKm = null, center = null)
        assertNull(engine.evaluate(null, q(4.5), listOf(strict), home = null)) // above the floor, below the rule's own 5.0
        val e = assertNotNull(engine.evaluate(null, q(5.0), listOf(strict), home = null))
        assertEquals("custom", e.matchedRuleId)
    }

    @Test fun `world (fixed at M6) is unaffected by the M4 floor -- it never lowers an already-stricter rule`() {
        val e = assertNotNull(engine.evaluate(null, q(6.2, lat = 35.0, lon = 140.0), DEFAULT_RULES, home))
        assertEquals("world", e.matchedRuleId)
        assertNull(engine.evaluate(null, q(5.9, lat = 35.0, lon = 140.0), DEFAULT_RULES, home))
    }

    @Test fun `a revision crossing the M4 floor fires even though the rule's own permissive minMag was already cleared`() {
        val permissive = AlertRule(id = "custom", minMag = 2.0, radiusKm = null, center = null)
        // previous 3.5 (below the floor, so never counted as "already fired"), current 4.0 (clears it).
        val e = engine.evaluate(q(3.5), q(4.0), listOf(permissive), home = null)
        assertEquals("custom", assertNotNull(e).matchedRuleId)
    }

    @Test fun `an update that stays below the M4 floor never fires, regardless of the rule's own permissive minMag`() {
        val permissive = AlertRule(id = "custom", minMag = 2.0, radiusKm = null, center = null)
        assertNull(engine.evaluate(q(3.0), q(3.8), listOf(permissive), home = null))
    }

    @Test fun `once a quake has cleared the M4 floor, a later revision that stays above it does not refire`() {
        val permissive = AlertRule(id = "custom", minMag = 2.0, radiusKm = null, center = null)
        assertNull(engine.evaluate(q(4.2), q(4.6), listOf(permissive), home = null))
    }
}
