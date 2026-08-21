package com.yugma.terrawatch.database

import app.cash.turbine.test
import com.yugma.terrawatch.model.FavoriteAlertType
import com.yugma.terrawatch.model.GeoPoint
import com.yugma.terrawatch.model.MagRevision
import com.yugma.terrawatch.model.MagnitudeBand
import com.yugma.terrawatch.model.Quake
import com.yugma.terrawatch.model.QuakeStatus
import com.yugma.terrawatch.model.Source
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

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
        // placeQuery = null spelled out explicitly — see QuakeDaoTest's identical note: an
        // overriding function can't redeclare the interface's default, so a concrete-typed `store`
        // reference needs every argument named here.
        val rows = store.pageBetween(lowerInclusive = 200, upperExclusive = 400, minMag = 4.5, placeQuery = null)
        assertEquals(listOf("c", "b"), rows.map { it.id })
    }

    @Test fun `pageBetween on an empty range returns nothing`() {
        store.replace(quake(id = "a", timeMillis = 100))
        assertEquals(emptyList(), store.pageBetween(lowerInclusive = 200, upperExclusive = 300, minMag = null, placeQuery = null))
    }

    // History search (user review items 3+4): mirrors QuakeDaoTest's own placeQuery cases exactly —
    // both QuakeStore implementations honor the same contract.
    @Test fun `pageBetween with a placeQuery matches a case-insensitive substring of place`() {
        store.replace(quake(id = "a", timeMillis = 100).copy(place = "10km SE of Jakarta, Indonesia"))
        store.replace(quake(id = "b", timeMillis = 200).copy(place = "20km N of Tokyo, Japan"))
        val rows = store.pageBetween(lowerInclusive = 0, upperExclusive = 1000, minMag = null, placeQuery = "indo")
        assertEquals(listOf("a"), rows.map { it.id })
    }

    @Test fun `pageBetween placeQuery is uppercase-insensitive too`() {
        store.replace(quake(id = "a", timeMillis = 100).copy(place = "10km SE of Jakarta, Indonesia"))
        val rows = store.pageBetween(lowerInclusive = 0, upperExclusive = 1000, minMag = null, placeQuery = "JAKARTA")
        assertEquals(listOf("a"), rows.map { it.id })
    }

    @Test fun `pageBetween composes placeQuery AND minMag, not either-or`() {
        store.replace(quake(id = "small", timeMillis = 100, mag = 2.0).copy(place = "Jakarta, Indonesia"))
        store.replace(quake(id = "big", timeMillis = 200, mag = 6.0).copy(place = "Jakarta, Indonesia"))
        store.replace(quake(id = "other-place", timeMillis = 300, mag = 6.0).copy(place = "Tokyo, Japan"))
        val rows = store.pageBetween(lowerInclusive = 0, upperExclusive = 1000, minMag = 4.5, placeQuery = "jakarta")
        assertEquals(listOf("big"), rows.map { it.id })
    }

    @Test fun `pageBetween with a null placeQuery is unaffected — existing minMag-only behavior unchanged`() {
        store.replace(quake(id = "a", timeMillis = 100, mag = 2.0))
        store.replace(quake(id = "b", timeMillis = 200, mag = 5.0))
        val rows = store.pageBetween(lowerInclusive = 0, upperExclusive = 1000, minMag = 4.5, placeQuery = null)
        assertEquals(listOf("b"), rows.map { it.id })
    }

    @Test fun `pageBetween with a placeQuery matching nothing returns an empty list, not an error`() {
        store.replace(quake(id = "a", timeMillis = 100).copy(place = "Jakarta, Indonesia"))
        assertEquals(
            emptyList(),
            store.pageBetween(lowerInclusive = 0, upperExclusive = 1000, minMag = null, placeQuery = "nonexistent-place-xyz"),
        )
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

    // --- Task 2 (Plan 4), Fix Round 1 (review finding): originOf -- backs the origin-flip-on-merge
    // protection in QuakeRepository.ingest(), see QuakeStore.originOf's own kdoc ------------------

    @Test fun `originOf returns the stored origin`() {
        store.replace(quake(id = "a"), origin = "archive")
        assertEquals("archive", store.originOf("a"))
    }

    @Test fun `originOf returns null for missing id`() {
        assertNull(store.originOf("missing"))
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

    // --- Task 3 (Plan 4): newSince -- mirrors QuakeDaoTest's own matrix exactly (same contract,
    // other QuakeStore implementation).
    //
    // Fix Round 1 (I1): cursor moved from `timeMillis` to `fetchedAtMillis` -- see QuakeStore.
    // newSince's own kdoc / QuakeDaoTest's identical newSince block for the full ruling. Every
    // case below writes through an explicitly clocked `InMemoryQuakeStore(clock = {...})`, never
    // this file's own class-level `store` field (its default `clock = { 0L }` would make every
    // row's fetchedAt identically 0, meaningless for pinning a fetchedAt-based cursor). ----------

    @Test fun `newSince returns feed and live rows strictly after the cutoff`() {
        val clockedStore = InMemoryQuakeStore(clock = { 2000L })
        clockedStore.replace(quake(id = "new-feed"), origin = "feed")
        clockedStore.replace(quake(id = "new-live"), origin = "live")
        val result = clockedStore.newSince(sinceMillis = 1000)
        assertEquals(setOf("new-feed", "new-live"), result.map { it.id }.toSet())
    }

    @Test fun `newSince excludes archive rows even when they are new`() {
        val clockedStore = InMemoryQuakeStore(clock = { 2000L })
        clockedStore.replace(quake(id = "new-archive"), origin = "archive")
        assertEquals(emptyList(), clockedStore.newSince(sinceMillis = 1000))
    }

    @Test fun `newSince excludes debug rows even when they are new`() {
        val clockedStore = InMemoryQuakeStore(clock = { 2000L })
        clockedStore.replace(quake(id = "debug-new"), origin = "debug")
        assertEquals(emptyList(), clockedStore.newSince(sinceMillis = 1000))
    }

    @Test fun `newSince cutoff comparison is strict greater-than -- a row fetched exactly AT cutoff is excluded`() {
        val clockedStore = InMemoryQuakeStore(clock = { 1000L })
        clockedStore.replace(quake(id = "at-cutoff"), origin = "feed")
        assertEquals(emptyList(), clockedStore.newSince(sinceMillis = 1000))
    }

    @Test fun `newSince excludes rows fetched at or before the cutoff`() {
        val clockedStore = InMemoryQuakeStore(clock = { 500L })
        clockedStore.replace(quake(id = "old-feed"), origin = "feed")
        assertEquals(emptyList(), clockedStore.newSince(sinceMillis = 1000))
    }

    @Test fun `newSince on an empty store returns empty`() {
        assertEquals(emptyList(), InMemoryQuakeStore().newSince(sinceMillis = 1000))
    }

    @Test fun `newSince orders newest event-time first`() {
        val clockedStore = InMemoryQuakeStore(clock = { 5000L }) // both fetched well after the cutoff
        clockedStore.replace(quake(id = "older", timeMillis = 2000), origin = "feed")
        clockedStore.replace(quake(id = "newer", timeMillis = 3000), origin = "live")
        assertEquals(listOf("newer", "older"), clockedStore.newSince(sinceMillis = 1000).map { it.id })
    }

    // Fix Round 1 (I1): the fix's own two motivating scenarios -- a timeMillis-based cursor missed
    // both of these. (The third TDD case, a canonical-id swap absorbing an already-notified event,
    // is a worker-side ring-buffer concern layered on top of this read -- see
    // AlertDigestSupportTest's own `filterFreshAlertEvents` cases.)

    @Test fun `newSince selects a publication-lag quake -- old event time, fresh fetchedAt`() {
        val clockedStore = InMemoryQuakeStore(clock = { 5000L })
        clockedStore.replace(quake(id = "lagged", timeMillis = 100), origin = "feed")
        assertEquals(listOf("lagged"), clockedStore.newSince(sinceMillis = 1000).map { it.id })
    }

    @Test fun `newSince selects a magnitude revision on an old quake -- fresh fetchedAt on re-write`() {
        var now = 100L
        val clockedStore = InMemoryQuakeStore(clock = { now })
        clockedStore.replace(quake(id = "revised", timeMillis = 100, mag = 5.4), origin = "feed")
        // Same id, later revision to M6.2 -- timeMillis (the event's own reported time) never
        // changes, but this device re-writes the row, so fetchedAtMillis does.
        now = 5000L
        clockedStore.replace(quake(id = "revised", timeMillis = 100, mag = 6.2), origin = "feed")
        val result = clockedStore.newSince(sinceMillis = 1000)
        assertEquals(listOf("revised"), result.map { it.id })
        assertEquals(6.2, result.single().mag)
    }

    // --- Commit "since-last-visit summary": newSinceCount -- mirrors QuakeDaoTest's own
    // newSinceCount section exactly (same contract, other QuakeStore implementation). -------------

    @Test fun `newSinceCount counts feed and live rows at or above minMag, strictly after the cutoff`() {
        val clockedStore = InMemoryQuakeStore(clock = { 2000L })
        clockedStore.replace(quake(id = "feed-m5", mag = 5.0), origin = "feed")
        clockedStore.replace(quake(id = "live-m4", mag = 4.0), origin = "live")
        assertEquals(2, clockedStore.newSinceCount(sinceMillis = 1000, minMag = 4.0))
    }

    @Test fun `newSinceCount excludes quakes below minMag`() {
        val clockedStore = InMemoryQuakeStore(clock = { 2000L })
        clockedStore.replace(quake(id = "below", mag = 3.9), origin = "feed")
        assertEquals(0, clockedStore.newSinceCount(sinceMillis = 1000, minMag = 4.0))
    }

    @Test fun `newSinceCount excludes quakes with a null magnitude, even with a low minMag`() {
        val clockedStore = InMemoryQuakeStore(clock = { 2000L })
        clockedStore.replace(quake(id = "unknown-mag", mag = null), origin = "feed")
        assertEquals(0, clockedStore.newSinceCount(sinceMillis = 1000, minMag = 0.0))
    }

    @Test fun `newSinceCount excludes archive rows even when they qualify on magnitude`() {
        val clockedStore = InMemoryQuakeStore(clock = { 2000L })
        clockedStore.replace(quake(id = "archive-m6", mag = 6.0), origin = "archive")
        assertEquals(0, clockedStore.newSinceCount(sinceMillis = 1000, minMag = 4.0))
    }

    @Test fun `newSinceCount excludes debug rows even when they qualify on magnitude`() {
        val clockedStore = InMemoryQuakeStore(clock = { 2000L })
        clockedStore.replace(quake(id = "debug-m6", mag = 6.0), origin = "debug")
        assertEquals(0, clockedStore.newSinceCount(sinceMillis = 1000, minMag = 4.0))
    }

    @Test fun `newSinceCount cutoff comparison is strict greater-than`() {
        val clockedStore = InMemoryQuakeStore(clock = { 1000L })
        clockedStore.replace(quake(id = "at-cutoff", mag = 5.0), origin = "feed")
        assertEquals(0, clockedStore.newSinceCount(sinceMillis = 1000, minMag = 4.0))
    }

    @Test fun `newSinceCount on an empty store returns zero`() {
        assertEquals(0, InMemoryQuakeStore().newSinceCount(sinceMillis = 1000, minMag = 4.0))
    }

    // --- Task 2 (Plan 5): favorite_place CRUD -- mirrors QuakeDaoTest's own favoritePlaces section ---

    @Test fun `favoritePlaces on an empty store emits an empty list`() = runTest {
        assertEquals(emptyList(), store.favoritePlaces().first())
    }

    @Test fun `insertFavoritePlace then favoritePlaces reads it back with an assigned id`() = runTest {
        store.insertFavoritePlace("Tokyo", GeoPoint(35.6762, 139.6503), FavoriteAlertType.MAJOR_ONLY)
        val place = store.favoritePlaces().first().single()
        assertEquals("Tokyo", place.label)
        assertEquals(GeoPoint(35.6762, 139.6503), place.point)
        assertEquals(FavoriteAlertType.MAJOR_ONLY, place.alertType)
        assertTrue(place.id > 0)
    }

    @Test fun `favoritePlaces orders by id ascending -- insertion order`() = runTest {
        store.insertFavoritePlace("First", GeoPoint(1.0, 1.0), FavoriteAlertType.ALL)
        store.insertFavoritePlace("Second", GeoPoint(2.0, 2.0), FavoriteAlertType.ALL)
        store.insertFavoritePlace("Third", GeoPoint(3.0, 3.0), FavoriteAlertType.ALL)
        assertEquals(listOf("First", "Second", "Third"), store.favoritePlaces().first().map { it.label })
    }

    @Test fun `favoritePlaces is reactive -- re-emits on insert`() = runTest {
        store.favoritePlaces().test {
            assertEquals(emptyList(), awaitItem())
            store.insertFavoritePlace("Delhi", GeoPoint(28.6139, 77.2090), FavoriteAlertType.ALL)
            assertEquals(listOf("Delhi"), awaitItem().map { it.label })
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test fun `deleteFavoritePlace removes only the targeted row`() = runTest {
        store.insertFavoritePlace("Keep", GeoPoint(1.0, 1.0), FavoriteAlertType.ALL)
        store.insertFavoritePlace("Remove", GeoPoint(2.0, 2.0), FavoriteAlertType.ALL)
        val toRemove = store.favoritePlaces().first().single { it.label == "Remove" }
        store.deleteFavoritePlace(toRemove.id)
        assertEquals(listOf("Keep"), store.favoritePlaces().first().map { it.label })
    }

    @Test fun `deleteFavoritePlace on an unknown id is a harmless no-op`() = runTest {
        store.insertFavoritePlace("Keep", GeoPoint(1.0, 1.0), FavoriteAlertType.ALL)
        store.deleteFavoritePlace(id = 999_999L)
        assertEquals(listOf("Keep"), store.favoritePlaces().first().map { it.label })
    }

    @Test fun `updateFavoritePlaceAlertType changes only that field`() = runTest {
        store.insertFavoritePlace("Mumbai", GeoPoint(19.0760, 72.8777), FavoriteAlertType.ALL)
        val id = store.favoritePlaces().first().single().id
        store.updateFavoritePlaceAlertType(id, FavoriteAlertType.OFF)
        val updated = store.favoritePlaces().first().single()
        assertEquals(FavoriteAlertType.OFF, updated.alertType)
        assertEquals("Mumbai", updated.label)
        assertEquals(GeoPoint(19.0760, 72.8777), updated.point)
    }

    @Test fun `updateFavoritePlaceAlertType on an unknown id is a harmless no-op`() = runTest {
        store.insertFavoritePlace("Mumbai", GeoPoint(19.0760, 72.8777), FavoriteAlertType.ALL)
        store.updateFavoritePlaceAlertType(id = 999_999L, alertType = FavoriteAlertType.OFF)
        assertEquals(FavoriteAlertType.ALL, store.favoritePlaces().first().single().alertType)
    }
}
