package com.yugma.terrawatch.data

import com.yugma.terrawatch.model.MagRevision
import com.yugma.terrawatch.model.Quake
import com.yugma.terrawatch.model.QuakeStatus
import com.yugma.terrawatch.model.Source
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

private fun q(
    id: String, source: Source, t: Long = 1_000_000, lat: Double = 7.10, lon: Double = 126.50,
    mag: Double = 6.0, status: QuakeStatus = QuakeStatus.AUTOMATIC, updated: Long = t,
) = Quake(id, t, lat, lon, 10.0, mag, "mw", "PLACE", false, null, status,
    mapOf(source to id), listOf(MagRevision(mag, "mw", updated, source)), updated)

class DedupeEngineTest {
    private val engine = DedupeEngine()

    @Test fun `no candidates passes through`() {
        val r = engine.reconcile(emptyList(), q("e1", Source.EMSC))
        assertEquals("e1", r.canonical.id)
        assertNull(r.replacesId)
    }

    @Test fun `same event from both agencies merges under usgs id`() {
        val emscFirst = q("e1", Source.EMSC, t = 1_000_000)
        val usgsLater = q("us1", Source.USGS, t = 1_000_030_000 - 1_000_000 + 1_000_000)
        // keep it simple: 30s apart, 5km apart
        val usgs = usgsLater.copy(timeMillis = 1_030_000, lat = 7.14, updatedAtMillis = 1_030_000)
        val r = engine.reconcile(listOf(emscFirst), usgs)
        assertEquals("us1", r.canonical.id)
        assertEquals("e1", r.replacesId)
        assertEquals(setOf(Source.USGS, Source.EMSC), r.canonical.sources.keys)
    }

    @Test fun `usgs stored first keeps its id when emsc twin arrives`() {
        val usgsFirst = q("us1", Source.USGS, t = 1_000_000)
        val emsc = q("e1", Source.EMSC, t = 1_020_000)
        val r = engine.reconcile(listOf(usgsFirst), emsc)
        assertEquals("us1", r.canonical.id)
        assertNull(r.replacesId)   // canonical row already stored under us1
        assertEquals("e1", r.canonical.sources[Source.EMSC])
    }

    @Test fun `outside time window does not match`() {
        val a = q("us1", Source.USGS, t = 1_000_000)
        val b = q("e1", Source.EMSC, t = 1_000_000 + 91_000)
        assertNull(engine.reconcile(listOf(a), b).replacesId)
        assertEquals("e1", engine.reconcile(listOf(a), b).canonical.id)
    }

    @Test fun `outside distance does not match`() {
        val a = q("us1", Source.USGS, lat = 7.0, lon = 126.0)
        val b = q("e1", Source.EMSC, lat = 8.5, lon = 127.5) // ~190 km away
        assertNull(engine.reconcile(listOf(a), b).replacesId)
    }

    @Test fun `reviewed magnitude beats automatic`() {
        val auto = q("us1", Source.USGS, mag = 5.9, status = QuakeStatus.AUTOMATIC, updated = 2_000_000)
        val reviewed = q("e1", Source.EMSC, mag = 6.1, status = QuakeStatus.REVIEWED, updated = 1_500_000)
        val r = engine.reconcile(listOf(auto), reviewed)
        assertEquals(6.1, r.canonical.mag)
    }

    @Test fun `revisions union is deduped and sorted`() {
        val a = q("us1", Source.USGS, updated = 1_000)
        val b = q("e1", Source.EMSC, updated = 2_000).copy(
            revisions = listOf(
                MagRevision(6.0, "mw", 500, Source.EMSC),
                MagRevision(6.1, "mw", 2_000, Source.EMSC),
            ))
        val r = engine.reconcile(listOf(a), b)
        assertEquals(listOf(500L, 1_000L, 2_000L), r.canonical.revisions.map { it.atMillis })
    }

    @Test fun `closest of multiple candidates wins`() {
        val near = q("us_near", Source.USGS, lat = 7.11)
        val far = q("us_far", Source.USGS, lat = 7.60)
        val incoming = q("e1", Source.EMSC, lat = 7.10)
        val r = engine.reconcile(listOf(far, near), incoming)
        assertEquals("us_near", r.canonical.id)
    }

    @Test fun `tsunami flag ors and felt takes max`() {
        val a = q("us1", Source.USGS).copy(tsunami = true, felt = 120)
        val b = q("e1", Source.EMSC).copy(felt = 300)
        val r = engine.reconcile(listOf(a), b)
        assertTrue(r.canonical.tsunami)
        assertEquals(300, r.canonical.felt)
    }
}
