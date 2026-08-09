package com.yugma.terrawatch.database

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import app.cash.turbine.test
import com.yugma.terrawatch.model.MagRevision
import com.yugma.terrawatch.model.MagnitudeBand
import com.yugma.terrawatch.model.Quake
import com.yugma.terrawatch.model.QuakeStatus
import com.yugma.terrawatch.model.Source
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.BeforeTest

class QuakeDaoTest {
    private lateinit var dao: QuakeDao
    private lateinit var db: TerraWatchDb

    @BeforeTest fun setup() {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        TerraWatchDb.Schema.create(driver)
        db = TerraWatchDb(driver)
        dao = QuakeDao(db)
    }

    private fun quake(
        id: String = "us1", updated: Long = 1000, mag: Double? = 5.0,
        sources: Map<Source, String> = mapOf(Source.USGS to id),
        revisions: List<MagRevision> = listOf(MagRevision(5.0, "mb", 1000, Source.USGS)),
    ) = Quake(id, 900, 7.1, 126.5, 10.0, mag, "mb", "Somewhere", false, null,
        QuakeStatus.AUTOMATIC, sources, revisions, updated)

    @Test fun `insert then read back`() {
        dao.upsert(quake())
        val q = assertNotNull(dao.byId("us1"))
        assertEquals(5.0, q.mag)
        assertEquals(1, dao.countAll())
    }

    @Test fun `stale update is ignored`() {
        dao.upsert(quake(updated = 2000, mag = 6.1))
        dao.upsert(quake(updated = 1000, mag = 5.0))
        assertEquals(6.1, assertNotNull(dao.byId("us1")).mag)
    }

    @Test fun `newer update merges sources and appends distinct revisions`() {
        dao.upsert(quake(updated = 1000))
        dao.upsert(quake(
            updated = 2000, mag = 6.1,
            sources = mapOf(Source.EMSC to "e1"),
            revisions = listOf(MagRevision(6.1, "mw", 2000, Source.EMSC)),
        ))
        val q = assertNotNull(dao.byId("us1"))
        assertEquals(6.1, q.mag)
        assertEquals(setOf(Source.USGS, Source.EMSC), q.sources.keys)
        assertEquals(2, q.revisions.size)
    }

    @Test fun `duplicate revision entries are not appended twice`() {
        dao.upsert(quake(updated = 1000))
        dao.upsert(quake(updated = 2000, revisions = listOf(MagRevision(5.0, "mb", 1000, Source.USGS))))
        assertEquals(1, assertNotNull(dao.byId("us1")).revisions.size)
    }

    @Test fun `pageBefore filters by magnitude and pages by time`() {
        dao.upsertAll(listOf(
            quake(id = "a", updated = 1).copy(timeMillis = 100, mag = 2.0),
            quake(id = "b", updated = 1).copy(timeMillis = 200, mag = 5.0),
            quake(id = "c", updated = 1).copy(timeMillis = 300, mag = 6.5),
        ))
        val page = dao.pageBefore(timeMillis = 400, limit = 10, minMag = 4.5)
        assertEquals(listOf("c", "b"), page.map { it.id })
    }

    @Test fun `delete removes the row`() {
        dao.upsert(quake())
        dao.delete("us1")
        assertEquals(null, dao.byId("us1"))
        assertEquals(0, dao.countAll())
    }

    // Fix Round 1 (I2): the debug long-press hook's injected fake quakes must be purgeable without
    // touching real data — every debug-injected id is prefixed "debug-" (HomeViewModel), so a
    // prefix-delete is the whole mechanism. TDD: written before `deleteByIdPrefix` exists.
    @Test fun `deleteByIdPrefix removes only ids with that prefix`() {
        dao.upsert(quake(id = "debug-1"))
        dao.upsert(quake(id = "debug-2"))
        dao.upsert(quake(id = "us123"))
        dao.deleteByIdPrefix("debug-")
        assertEquals(1, dao.countAll())
        assertNotNull(dao.byId("us123"))
    }

    @Test fun `metaGet returns null for an unknown key`() {
        assertEquals(null, dao.metaGet("feed_etag"))
    }

    @Test fun `metaPut then metaGet roundtrips and overwrites`() {
        dao.metaPut("feed_etag", "\"e1\"")
        assertEquals("\"e1\"", dao.metaGet("feed_etag"))
        dao.metaPut("feed_etag", "\"e2\"")
        assertEquals("\"e2\"", dao.metaGet("feed_etag"))
    }

    @Test fun `replace bypasses the upsert recency gate that silently drops equal-timestamp merges`() {
        // Identical starting condition on two rows: stored updatedAt=2000, sources={USGS}.
        dao.upsert(quake(id = "us1", updated = 2000))
        dao.upsert(quake(id = "us2", updated = 2000))

        // replace() with the SAME updatedAt (2000) but a different sources map must land —
        // this is the DedupeEngine-reconciled-canonical write path.
        dao.replace(quake(id = "us1", updated = 2000, sources = mapOf(Source.EMSC to "e1")))
        assertEquals(setOf(Source.EMSC), assertNotNull(dao.byId("us1")).sources.keys)

        // upsert() with the identical input is silently dropped by the recency gate
        // (incoming.updatedAtMillis <= existing.updatedAtMillis) — the exact bug replace()
        // exists to bypass for reconciled rows (Task 7 review: a late-arriving agency twin
        // with a lagging/equal timestamp must still contribute its merged fields).
        dao.upsert(quake(id = "us2", updated = 2000, sources = mapOf(Source.EMSC to "e1")))
        assertEquals(setOf(Source.USGS), assertNotNull(dao.byId("us2")).sources.keys)
    }

    // Task 9 review, Important 2 (bug-pinning half): calling delete() and replace() as two
    // separate statements is two separate SQLDelight transactions, so a live recent() collector
    // observes the transient state in between — here, an empty list — before the final state
    // lands. This test documents why ingest() must never do this two-step dance itself; it is
    // expected to keep passing (it pins the primitives' standalone behavior, not a bug in them).
    @Test fun `separate delete then replace calls observably flicker to an empty list`() = runTest {
        dao.upsert(quake(id = "old", updated = 1000).copy(timeMillis = 500))
        dao.recent(0).test {
            assertEquals(listOf("old"), awaitItem().map { it.id })
            dao.delete("old")
            assertEquals(emptyList(), awaitItem().map { it.id })   // the flicker
            dao.replace(quake(id = "new", updated = 2000).copy(timeMillis = 600))
            assertEquals(listOf("new"), awaitItem().map { it.id })
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test fun `replaceAndDelete is atomic — recent() sees exactly one transition, never an empty list`() = runTest {
        dao.upsert(quake(id = "old", updated = 1000).copy(timeMillis = 500))
        dao.recent(0).test {
            assertEquals(listOf("old"), awaitItem().map { it.id })
            dao.replaceAndDelete(quake(id = "new", updated = 2000).copy(timeMillis = 600), deleteIds = listOf("old"))
            assertEquals(listOf("new"), awaitItem().map { it.id })   // straight to final state
            expectNoEvents()
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test fun `fetchedAtMillis comes from injected clock`() {
        val clockedDao = QuakeDao(db, clock = { 42_000L })
        clockedDao.upsert(quake())
        assertEquals(42_000L, clockedDao.lastFetchedAtMillis())
    }

    @Test fun `lastFetchedAt tracks latest write clock, not quake timestamps`() {
        var now = 42_000L
        val clockedDao = QuakeDao(db, clock = { now })
        clockedDao.upsert(quake(id = "a", updated = 999_999))
        now = 99_000L
        clockedDao.upsert(quake(id = "b", updated = 1))
        assertEquals(99_000L, clockedDao.lastFetchedAtMillis())
    }

    @Test fun `lastFetchedAt null on empty table`() {
        val clockedDao = QuakeDao(db, clock = { 42_000L })
        assertEquals(null, clockedDao.lastFetchedAtMillis())
    }

    // Task 6 (Plan 3): Insights' three read-only aggregates -----------------------------------

    @Test fun `quakesPerDay groups by day bucket and counts, ordered ascending`() {
        val day0 = 0L
        val day1 = 86_400_000L
        dao.upsertAll(listOf(
            quake(id = "a", updated = 1).copy(timeMillis = day0 + 1_000),
            quake(id = "b", updated = 1).copy(timeMillis = day0 + 2_000),
            quake(id = "c", updated = 1).copy(timeMillis = day1 + 500),
        ))
        assertEquals(listOf(DayCount(0L, 2L), DayCount(1L, 1L)), dao.quakesPerDay(sinceMillis = 0L))
    }

    @Test fun `quakesPerDay excludes rows before sinceMillis`() {
        dao.upsertAll(listOf(
            quake(id = "old", updated = 1).copy(timeMillis = 500),
            quake(id = "new", updated = 1).copy(timeMillis = 100_000_000),
        ))
        assertEquals(listOf(DayCount(100_000_000L / 86_400_000L, 1L)), dao.quakesPerDay(sinceMillis = 1_000_000))
    }

    @Test fun `quakesPerDay on an empty db returns no buckets at all, not zeros`() {
        assertEquals(emptyList(), dao.quakesPerDay(sinceMillis = 0L))
    }

    @Test fun `day bucket boundary — one ms before the next day still buckets to the earlier day`() {
        dao.upsertAll(listOf(
            quake(id = "just-before", updated = 1).copy(timeMillis = 86_400_000L - 1),
            quake(id = "exactly-at", updated = 1).copy(timeMillis = 86_400_000L),
        ))
        val byBucket = dao.quakesPerDay(sinceMillis = 0L).associate { it.dayBucket to it.n }
        assertEquals(1L, byBucket[0L])
        assertEquals(1L, byBucket[1L])
    }

    @Test fun `bandDistribution buckets by the magnitudeBand edges — AT 4point5 is STRONG, AT 6point0 is MAJOR`() {
        // Edges independently cross-checked against model/MagnitudeBand.kt's own magnitudeBand()
        // before writing this test (EVIDENCE INTEGRITY) — mag < 3.0 LOW, < 4.5 MODERATE, < 6.0
        // STRONG, else MAJOR; null -> UNKNOWN. The two boundary values (4.5, 6.0) are asserted
        // landing in the HIGHER band, matching that function's own `<` (not `<=`) comparisons.
        dao.upsertAll(listOf(
            quake(id = "low", updated = 1, mag = 2.9),
            quake(id = "mod", updated = 1, mag = 4.4),
            quake(id = "edge-4-5", updated = 1, mag = 4.5),
            quake(id = "strong", updated = 1, mag = 5.9),
            quake(id = "edge-6-0", updated = 1, mag = 6.0),
            quake(id = "major", updated = 1, mag = 9.0),
            quake(id = "unknown", updated = 1, mag = null),
        ).map { it.copy(timeMillis = 1_000) })
        val bands = dao.bandDistribution(sinceMillis = 0L).associate { it.band to it.n }
        assertEquals(1L, bands[MagnitudeBand.LOW])
        assertEquals(1L, bands[MagnitudeBand.MODERATE])
        assertEquals(2L, bands[MagnitudeBand.STRONG], "4.5 (edge) + 5.9 (strong)")
        assertEquals(2L, bands[MagnitudeBand.MAJOR], "6.0 (edge) + 9.0 (major)")
        assertEquals(1L, bands[MagnitudeBand.UNKNOWN])
    }

    @Test fun `bandDistribution excludes rows before sinceMillis`() {
        dao.upsertAll(listOf(
            quake(id = "old", updated = 1, mag = 9.0).copy(timeMillis = 500),
            quake(id = "new", updated = 1, mag = 2.0).copy(timeMillis = 100_000_000),
        ))
        val bands = dao.bandDistribution(sinceMillis = 1_000_000)
        assertEquals(listOf(BandCount(MagnitudeBand.LOW, 1L)), bands)
    }

    @Test fun `bandDistribution on an empty db returns no bands at all, not zeros`() {
        assertEquals(emptyList(), dao.bandDistribution(sinceMillis = 0L))
    }

    @Test fun `strongest returns the highest-magnitude quake in window, ignoring null-mag rows`() {
        dao.upsertAll(listOf(
            quake(id = "a", updated = 1, mag = 5.0).copy(timeMillis = 1_000),
            quake(id = "b", updated = 1, mag = 7.2).copy(timeMillis = 2_000),
            quake(id = "null-mag", updated = 1, mag = null).copy(timeMillis = 3_000),
        ))
        assertEquals("b", dao.strongest(sinceMillis = 0L)?.id)
    }

    @Test fun `strongest excludes quakes before sinceMillis`() {
        dao.upsertAll(listOf(
            quake(id = "old-big", updated = 1, mag = 9.0).copy(timeMillis = 500),
            quake(id = "new-small", updated = 1, mag = 4.0).copy(timeMillis = 100_000_000),
        ))
        assertEquals("new-small", dao.strongest(sinceMillis = 1_000_000)?.id)
    }

    @Test fun `strongest on an empty db returns null`() {
        assertEquals(null, dao.strongest(sinceMillis = 0L))
    }
}
