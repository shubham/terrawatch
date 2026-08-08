package com.yugma.terrawatch.network

import com.yugma.terrawatch.model.QuakeStatus
import com.yugma.terrawatch.model.Source
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.assertNotNull

private fun readFixture(name: String): String =
    Thread.currentThread().contextClassLoader!!.getResource("fixtures/$name")!!.readText()

class UsgsFeedParserTest {
    private val quakes = UsgsFeedParser.parse(readFixture("usgs_all_hour.json"))

    @Test fun `parses every feature in the fixture`() {
        assertTrue(quakes.isNotEmpty())
    }
    @Test fun `every quake has usgs source id`() {
        quakes.forEach { q -> assertNotNull(q.sources[Source.USGS], "missing USGS id on ${q.id}") }
    }
    @Test fun `coordinates are sane`() {
        quakes.forEach { q ->
            assertTrue(q.lat in -90.0..90.0, "lat ${q.lat}")
            assertTrue(q.lon in -180.0..180.0, "lon ${q.lon}")
        }
    }
    @Test fun `times are epoch millis after 2020`() {
        quakes.forEach { q -> assertTrue(q.timeMillis > 1_577_836_800_000, "time ${q.timeMillis}") }
    }
    @Test fun `status maps reviewed or automatic`() {
        quakes.forEach { q -> assertTrue(q.status == QuakeStatus.REVIEWED || q.status == QuakeStatus.AUTOMATIC) }
    }
    @Test fun `malformed json throws`() {
        kotlin.test.assertFailsWith<Exception> { UsgsFeedParser.parse("{not json") }
    }
}
