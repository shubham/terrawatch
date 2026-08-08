package com.yugma.terrawatch.network

import com.yugma.terrawatch.model.QuakeStatus
import com.yugma.terrawatch.model.Source
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertNotNull

private fun readFixture(name: String): String =
    Thread.currentThread().contextClassLoader!!.getResource("fixtures/$name")!!.readText()

class EmscParserTest {
    @Test fun `parses create event`() {
        val q = EmscParser.parse(readFixture("emsc_event.json"))
        assertNotNull(q)
        assertEquals("20260807_0000123", q.sources[Source.EMSC])
        assertEquals(6.1, q.mag)
        assertEquals(7.12, q.lat)
        assertEquals(126.54, q.lon)
        assertEquals(10.0, q.depthKm)                    // negative coord -> positive km
        assertEquals(QuakeStatus.AUTOMATIC, q.status)
        assertEquals("MINDANAO, PHILIPPINES", q.place)
        assertEquals(1786075781000L, q.timeMillis)       // python-verified value for 2026-08-07T04:09:41Z
    }
    @Test fun `non-event message returns null`() {
        assertNull(EmscParser.parse("""{"action":"heartbeat"}"""))
        assertNull(EmscParser.parse("""not json at all"""))
    }
}
