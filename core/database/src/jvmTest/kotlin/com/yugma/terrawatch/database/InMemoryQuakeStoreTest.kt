package com.yugma.terrawatch.database

import app.cash.turbine.test
import com.yugma.terrawatch.model.MagRevision
import com.yugma.terrawatch.model.MagnitudeBand
import com.yugma.terrawatch.model.Quake
import com.yugma.terrawatch.model.QuakeStatus
import com.yugma.terrawatch.model.Source
import kotlinx.coroutines.test.runTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * Task 9 (Plan 3): [InMemoryQuakeStore]'s contract tests — the SAME core scenarios
 * [QuakeDaoTest] exercises against the real SQLite-backed [QuakeDao] (insert/replace, recent-flow
 * reactivity, pageBetween, meta, plus the aggregates QuakeDaoTest itself covers), re-run here
 * against [QuakeStore]'s OTHER implementation so both honor one contract, not two independently
 * verified ones.
 *
 * Not literally shared test *code* with [QuakeDaoTest] — this codebase's plain `kotlin.test` setup
 * (no parameterized-test runner in use anywhere else) has no existing pattern for running one test
 * body against two backing instances, and introducing one for a single pair of implementations
 * wasn't worth it under this task's time budget. Deviation, documented per this plan's own
 * convention: "upsert" is one of QuakeDaoTest's named categories, but `upsert()`/`upsertAll()`
 * aren't on [QuakeStore] at all (grepped — no production caller ever uses them, see that
 * interface's own kdoc), so the equivalent case here is "insert via [QuakeStore.replace] then read
 * back", the interface's own real write path.
 */
class InMemoryQuakeStoreTest {
    private lateinit var store: InMemoryQuakeStore

    @BeforeTest fun setup() {
        store = InMemoryQuakeStore()
    }

    private fun quake(
        id: String = "us1", timeMillis: Long = 900, mag: Double? = 5.0,
        sources: Map<Source, String> = mapOf(Source.USGS to id),
        revisions: List<MagRevision> = listOf(MagRevision(5.0, "mb", 1000, Source.USGS)),
        updated: Long = 1000,
    ) = Quake(id, timeMillis, 7.1, 126.5, 10.0, mag, "mb", "Somewhere", false, null,
        QuakeStatus.AUTOMATIC, sources, revisions, updated)

    // --- insert (via replace, QuakeStore's real write path — see class kdoc) / read back --------

    @Test fun `replace inserts a new row, readable back by id`() {
        store.replace(quake())
        val q = assertNotNull(store.byId("us1"))
        assertEquals(5.0, q.mag)
    }

    @Test fun `replace is unconditional — an older-looking write still overwrites completely`() {
        // Unlike QuakeDao's separate upsert() recency gate, QuakeStore.replace's own documented
        // contract (see QuakeDao.replace's kdoc, unchanged by Task 9) is "no gate at all" — the
        // reconciler already resolved recency before calling it. A second replace() with a LOWER
        // updatedAtMillis than the first still fully lands here, source set and all.
        store.replace(quake(updated = 2000, sources = mapOf(Source.USGS to "us1")))
        store.replace(quake(updated = 500, sources = mapOf(Source.EMSC to "e1"), mag = 6.1))
        val q = assertNotNull(store.byId("us1"))
        assertEquals(6.1, q.mag)
        assertEquals(setOf(Source.EMSC), q.sources.keys)
    }

    @Test fun `byId returns null for an id never written`() {
        assertNull(store.byId("missing"))
    }

    // --- recent(): reactive flow ------------------------------------------------------------

    @Test fun `recent emits the current window on subscribe, then again on every write`() = runTest {
        store.replace(quake(id = "old", timeMillis = 500))
        store.recent(0).test {
            assertEquals(listOf("old"), awaitItem().map { it.id })
            store.replace(quake(id = "new", timeMillis = 600))
            assertEquals(setOf("old", "new"), awaitItem().map { it.id }.toSet())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test fun `recent excludes rows before sinceMillis and orders newest first`() = runTest {
        store.replace(quake(id = "a", timeMillis = 100))
        store.replace(quake(id = "b", timeMillis = 300))
        store.replace(quake(id = "c", timeMillis = 200))
        store.recent(150).test {
            assertEquals(listOf("b", "c"), awaitItem().map { it.id })
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test fun `replaceAndDelete is atomic — recent sees exactly one transition, never an empty list`() = runTest {
        store.replace(quake(id = "old", timeMillis = 500))
        store.recent(0).test {
            assertEquals(listOf("old"), awaitItem().map { it.id })
            store.replaceAndDelete(quake(id = "new", timeMillis = 600), deleteIds = listOf("old"))
            assertEquals(listOf("new"), awaitItem().map { it.id })   // straight to final state
            expectNoEvents()
            cancelAndIgnoreRemainingEvents()
        }
    }

    // --- pageBefore ----------------------------------------------------------------------------

    @Test fun `pageBefore filters by magnitude and pages by time, descending`() {
        store.replace(quake(id = "a", timeMillis = 100, mag = 2.0))
        store.replace(quake(id = "b", timeMillis = 200, mag = 5.0))
        store.replace(quake(id = "c", timeMillis = 300, mag = 6.5))
        val page = store.pageBefore(timeMillis = 400, limit = 10, minMag = 4.5)
        assertEquals(listOf("c", "b"), page.map { it.id })
    }

    @Test fun `pageBefore respects limit`() {
        store.replace(quake(id = "a", timeMillis = 100))
        store.replace(quake(id = "b", timeMillis = 200))
        store.replace(quake(id = "c", timeMillis = 300))
        val page = store.pageBefore(timeMillis = 400, limit = 2, minMag = null)
        assertEquals(listOf("c", "b"), page.map { it.id })
    }

    // --- pageBetween (History's display query — see QuakeRepository.pageBetween's kdoc) --------

    @Test fun `pageBetween returns rows in the range, magnitude-filtered, descending, no limit`() {
        store.replace(quake(id = "a", timeMillis = 100, mag = 2.0))
        store.replace(quake(id = "b", timeMillis = 200, mag = 5.0))
        store.replace(quake(id = "c", timeMillis = 300, mag = 6.5))
        store.replace(quake(id = "d", timeMillis = 400, mag = 7.0))
        // [200, 400) with minMag=4.5 -- "a" excluded (below range), "d" excluded (at/above ceiling).
        val rows = store.pageBetween(lowerInclusive = 200, upperExclusive = 400, minMag = 4.5)
        assertEquals(listOf("c", "b"), rows.map { it.id })
    }

    @Test fun `pageBetween on an empty range returns nothing`() {
        store.replace(quake(id = "a", timeMillis = 100))
        assertEquals(emptyList(), store.pageBetween(lowerInclusive = 200, upperExclusive = 300, minMag = null))
    }

    // --- meta ------------------------------------------------------------------------------------

    @Test fun `metaGet returns null for an unknown key`() {
        assertNull(store.metaGet("feed_etag"))
    }

    @Test fun `metaPut then metaGet roundtrips and overwrites`() {
        store.metaPut("feed_etag", "\"e1\"")
        assertEquals("\"e1\"", store.metaGet("feed_etag"))
        store.metaPut("feed_etag", "\"e2\"")
        assertEquals("\"e2\"", store.metaGet("feed_etag"))
    }

    // --- deleteByIdPrefix (debug-quake purge — see QuakeRepository.purgeDebugQuakes) -----------

    @Test fun `deleteByIdPrefix removes only ids with that prefix`() {
        store.replace(quake(id = "debug-1"))
        store.replace(quake(id = "debug-2"))
        store.replace(quake(id = "us123"))
        store.deleteByIdPrefix("debug-")
        assertNull(store.byId("debug-1"))
        assertNull(store.byId("debug-2"))
        assertNotNull(store.byId("us123"))
    }

    // --- lastFetchedAtMillis -----------------------------------------------------------------

    @Test fun `lastFetchedAtMillis comes from injected clock`() {
        val clocked = InMemoryQuakeStore(clock = { 42_000L })
        clocked.replace(quake())
        assertEquals(42_000L, clocked.lastFetchedAtMillis())
    }

    @Test fun `lastFetchedAtMillis tracks latest write clock, not quake timestamps`() {
        var now = 42_000L
        val clocked = InMemoryQuakeStore(clock = { now })
        clocked.replace(quake(id = "a", updated = 999_999))
        now = 99_000L
        clocked.replace(quake(id = "b", updated = 1))
        assertEquals(99_000L, clocked.lastFetchedAtMillis())
    }

    @Test fun `lastFetchedAtMillis null on empty store`() {
        val clocked = InMemoryQuakeStore(clock = { 42_000L })
        assertNull(clocked.lastFetchedAtMillis())
    }

    // --- Insights aggregates (Task 6, Plan 3 — Insights runs against whichever QuakeStore Koin
    // hands it too, wasmJs included, so these must hold here exactly like QuakeDaoTest's own copies) --

    @Test fun `quakesPerDay groups by day bucket and counts, ordered ascending, excludes rows before sinceMillis`() {
        val day0 = 0L
        val day1 = 86_400_000L
        store.replace(quake(id = "a", timeMillis = day0 + 1_000))
        store.replace(quake(id = "b", timeMillis = day0 + 2_000))
        store.replace(quake(id = "c", timeMillis = day1 + 500))
        assertEquals(listOf(DayCount(0L, 2L), DayCount(1L, 1L)), store.quakesPerDay(sinceMillis = 0L))
        assertEquals(emptyList(), InMemoryQuakeStore().quakesPerDay(sinceMillis = 0L))
    }

    @Test fun `bandDistribution buckets by the magnitudeBand edges and excludes rows before sinceMillis`() {
        store.replace(quake(id = "low", mag = 2.9, timeMillis = 1_000))
        store.replace(quake(id = "edge-4-5", mag = 4.5, timeMillis = 1_000))
        store.replace(quake(id = "edge-6-0", mag = 6.0, timeMillis = 1_000))
        store.replace(quake(id = "unknown", mag = null, timeMillis = 1_000))
        val bands = store.bandDistribution(sinceMillis = 0L).associate { it.band to it.n }
        assertEquals(1L, bands[MagnitudeBand.LOW])
        assertEquals(1L, bands[MagnitudeBand.STRONG], "4.5 lands in STRONG, matching model.magnitudeBand()")
        assertEquals(1L, bands[MagnitudeBand.MAJOR], "6.0 lands in MAJOR, matching model.magnitudeBand()")
        assertEquals(1L, bands[MagnitudeBand.UNKNOWN])
        assertEquals(emptyList(), InMemoryQuakeStore().bandDistribution(sinceMillis = 0L))
    }

    @Test fun `strongest returns the highest-magnitude quake in window, ignoring null-mag rows, tie-broken by recency`() {
        store.replace(quake(id = "a", mag = 5.0, timeMillis = 1_000))
        store.replace(quake(id = "b", mag = 7.2, timeMillis = 2_000))
        store.replace(quake(id = "null-mag", mag = null, timeMillis = 3_000))
        assertEquals("b", store.strongest(sinceMillis = 0L)?.id)

        val tieStore = InMemoryQuakeStore()
        tieStore.replace(quake(id = "older", mag = 7.0, timeMillis = 1_000))
        tieStore.replace(quake(id = "newer", mag = 7.0, timeMillis = 2_000))
        assertEquals("newer", tieStore.strongest(sinceMillis = 0L)?.id)

        assertNull(InMemoryQuakeStore().strongest(sinceMillis = 0L))
    }

    // --- Task 2 (Plan 4), M1 torn-write fix: metaPutAll -----------------------------------------

    @Test fun `metaPutAll writes multiple pairs`() {
        store.metaPutAll("k1" to "v1", "k2" to "v2")
        assertEquals("v1", store.metaGet("k1"))
        assertEquals("v2", store.metaGet("k2"))
    }

    @Test fun `metaPutAll overwrites existing keys`() {
        store.metaPut("k1", "old")
        store.metaPutAll("k1" to "new", "k2" to "v2")
        assertEquals("new", store.metaGet("k1"))
        assertEquals("v2", store.metaGet("k2"))
    }

    // --- Task 2 (Plan 4), F1 retention ruling: pruneOldRows -- mirrors QuakeDaoTest's own matrix
    // exactly (same contract, other QuakeStore implementation) ------------------------------------

    @Test fun `pruneOldRows deletes old feed and live rows`() {
        store.replace(quake(id = "old-feed", timeMillis = 100), origin = "feed")
        store.replace(quake(id = "old-live", timeMillis = 100), origin = "live")
        store.pruneOldRows(cutoffMillis = 1000)
        assertNull(store.byId("old-feed"))
        assertNull(store.byId("old-live"))
    }

    @Test fun `pruneOldRows protects old archive rows`() {
        store.replace(quake(id = "old-archive", timeMillis = 100), origin = "archive")
        store.pruneOldRows(cutoffMillis = 1000)
        assertNotNull(store.byId("old-archive"))
    }

    @Test fun `pruneOldRows protects old debug rows too`() {
        store.replace(quake(id = "debug-old", timeMillis = 100), origin = "debug")
        store.pruneOldRows(cutoffMillis = 1000)
        assertNotNull(store.byId("debug-old"))
    }

    @Test fun `pruneOldRows protects young feed and live rows`() {
        store.replace(quake(id = "young-feed", timeMillis = 2000), origin = "feed")
        store.replace(quake(id = "young-live", timeMillis = 2000), origin = "live")
        store.pruneOldRows(cutoffMillis = 1000)
        assertNotNull(store.byId("young-feed"))
        assertNotNull(store.byId("young-live"))
    }

    @Test fun `pruneOldRows cutoff comparison is strict less-than — a row exactly AT cutoff survives`() {
        store.replace(quake(id = "at-cutoff", timeMillis = 1000), origin = "feed")
        store.pruneOldRows(cutoffMillis = 1000)
        assertNotNull(store.byId("at-cutoff"))
    }

    @Test fun `pruneOldRows on an empty store is a no-op`() {
        InMemoryQuakeStore().pruneOldRows(cutoffMillis = 1000) // must not throw
    }

    @Test fun `pruneOldRows leaves non-expired rows of mixed origin untouched in one pass`() {
        store.replace(quake(id = "old-feed", timeMillis = 100), origin = "feed")
        store.replace(quake(id = "old-archive", timeMillis = 100), origin = "archive")
        store.replace(quake(id = "young-live", timeMillis = 5000), origin = "live")
        store.pruneOldRows(cutoffMillis = 1000)
        assertNull(store.byId("old-feed"))
        assertNotNull(store.byId("old-archive"))
        assertNotNull(store.byId("young-live"))
    }
}
