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
    @Test fun `garbage shapes return null, never throw`() {
        listOf(
            """{"action":"create","data":"oops"}""",
            """{"action":"create","data":{"properties":"nope"}}""",
            """{"action":{},"data":{}}""",
            """{"action":"create","data":{"properties":{"unid":"x","lat":1.0,"lon":2.0,"time":"2026-08-07T04:09:41.0Z","auto":{}}}}""",
            """{"action":"create","data":{"geometry":{"coordinates":"bad"},"properties":{"unid":"x","lat":1.0,"lon":2.0,"time":"2026-08-07T04:09:41.0Z"}}}""",
        ).forEach { assertNull(EmscParser.parse(it), "should be null: $it") }
    }
    @Test fun `negative utc offset parses correctly`() {
        val msg = """{"action":"create","data":{"properties":{"unid":"neg1","lat":1.0,"lon":2.0,"time":"2019-12-31T19:00:00-05:00","mag":5.0,"flynn_region":"X"}}}"""
        val q = EmscParser.parse(msg)
        assertEquals(1577836800000L, assertNotNull(q).timeMillis)
    }
    @Test fun `depth falls back to geometry coordinate when props depth missing`() {
        val msg = """{"action":"create","data":{"geometry":{"coordinates":[126.54,7.12,-42.5]},"properties":{"unid":"d1","lat":7.12,"lon":126.54,"time":"2026-08-07T04:09:41.0Z"}}}"""
        assertEquals(42.5, assertNotNull(EmscParser.parse(msg)).depthKm)
    }
}
