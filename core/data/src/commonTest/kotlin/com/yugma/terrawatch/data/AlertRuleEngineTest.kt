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
}
