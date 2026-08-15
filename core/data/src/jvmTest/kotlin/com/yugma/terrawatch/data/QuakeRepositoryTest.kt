package com.yugma.terrawatch.data

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import app.cash.turbine.test
import com.yugma.terrawatch.database.QuakeDao
import com.yugma.terrawatch.database.QuakeStore
import com.yugma.terrawatch.database.TerraWatchDb
import com.yugma.terrawatch.model.GeoPoint
import com.yugma.terrawatch.model.MagRevision
import com.yugma.terrawatch.model.Quake
import com.yugma.terrawatch.model.QuakeStatus
import com.yugma.terrawatch.model.Source
import com.yugma.terrawatch.model.haversineKm
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import com.yugma.terrawatch.network.EmscLiveSource
import com.yugma.terrawatch.network.UsgsApi
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.BeforeTest

class QuakeRepositoryTest {
    private lateinit var dao: QuakeDao

    @BeforeTest fun setup() {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        TerraWatchDb.Schema.create(driver)
        dao = QuakeDao(TerraWatchDb(driver))
    }

    private fun quake(id: String, source: Source, mag: Double, t: Long, updated: Long = t) =
        Quake(id, t, 7.1, 126.5, 10.0, mag, "mw", "P", false, null, QuakeStatus.AUTOMATIC,
            mapOf(source to id), listOf(MagRevision(mag, "mw", updated, source)), updated)

    @Test fun `ingest stores new quake and emits on recent flow`() = runTest {
        val r = QuakeRepository(
            UsgsApi(HttpClient(MockEngine { respond("", HttpStatusCode.NotFound) })),
            EmscLiveSource(HttpClient(MockEngine { respond("", HttpStatusCode.NotFound) })),
            dao, clock = { 2_000_000 })
        r.ingest(quake("us1", Source.USGS, 5.5, t = 1_950_000))
        r.recentQuakes(windowMs = 100_000).test {
            assertEquals(listOf("us1"), awaitItem().map { it.id })
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test fun `emsc twin merges into stored usgs row`() = runTest {
        val r = QuakeRepository(
            UsgsApi(HttpClient(MockEngine { respond("", HttpStatusCode.NotFound) })),
            EmscLiveSource(HttpClient(MockEngine { respond("", HttpStatusCode.NotFound) })),
            dao, clock = { 2_000_000 })
        r.ingest(quake("us1", Source.USGS, 5.5, t = 1_950_000, updated = 1_950_000))
        r.ingest(quake("e1", Source.EMSC, 5.7, t = 1_960_000, updated = 1_960_000))
        assertEquals(1, dao.countAll())
        val stored = dao.byId("us1")!!
        assertEquals("e1", stored.sources[Source.EMSC])
    }

    @Test fun `alert fires once when threshold crossed by revision`() = runTest {
        val r = QuakeRepository(
            UsgsApi(HttpClient(MockEngine { respond("", HttpStatusCode.NotFound) })),
            EmscLiveSource(HttpClient(MockEngine { respond("", HttpStatusCode.NotFound) })),
            dao, clock = { 2_000_000 })
        r.alertEvents.test {
            r.ingest(quake("us1", Source.USGS, 5.8, t = 1_900_000, updated = 1_900_000), home = null)
            r.ingest(quake("us1", Source.USGS, 6.1, t = 1_900_000, updated = 1_910_000), home = null)
            assertEquals("world", awaitItem().matchedRuleId)
            expectNoEvents()
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test fun `refreshFeed persists etag and second call sends it`() = runTest {
        var sawIfNoneMatch: String? = null
        val engine = MockEngine { req ->
            sawIfNoneMatch = req.headers[HttpHeaders.IfNoneMatch]
            if (sawIfNoneMatch == null)
                respond(
                    """{"features":[]}""", HttpStatusCode.OK,
                    headersOf(HttpHeaders.ETag to listOf("\"e1\""), HttpHeaders.ContentType to listOf("application/json")),
                )
            else respond("", HttpStatusCode.NotModified)
        }
        val r = QuakeRepository(UsgsApi(HttpClient(engine)),
            EmscLiveSource(HttpClient(engine)), dao, clock = { 2_000_000 })
        assertEquals(RefreshStatus.UPDATED, r.refreshFeed())
        assertEquals(RefreshStatus.NOT_MODIFIED, r.refreshFeed())
        assertEquals("\"e1\"", sawIfNoneMatch)
    }

    // Task 7 review carry-over (system seam, empirically proven): ingest() MUST write the
    // reconciled canonical via replace(), never upsert() — the surviving updatedAtMillis here
    // (max(2_000_000, 1_500_000) = 2_000_000) equals the already-stored row's updatedAtMillis,
    // which is exactly the input shape that makes the DAO's upsert() recency gate silently
    // drop the write. If ingest() ever regresses to dao.upsert(), this test fails.
    @Test fun `lagging-timestamp emsc twin still contributes tsunami and sources`() = runTest {
        val r = QuakeRepository(
            UsgsApi(HttpClient(MockEngine { respond("", HttpStatusCode.NotFound) })),
            EmscLiveSource(HttpClient(MockEngine { respond("", HttpStatusCode.NotFound) })),
            dao, clock = { 2_000_000 })
        r.ingest(quake("us1", Source.USGS, 5.5, t = 1_950_000, updated = 2_000_000))
        r.ingest(quake("e1", Source.EMSC, 5.5, t = 1_960_000, updated = 1_500_000).copy(tsunami = true))
        val stored = dao.byId("us1")!!
        assertEquals(true, stored.tsunami)                 // OR survived the write
        assertEquals("e1", stored.sources[Source.EMSC])    // union survived the write
        assertEquals(1, dao.countAll())
    }

    private fun repoNoop(clockValue: Long) = QuakeRepository(
        UsgsApi(HttpClient(MockEngine { respond("", HttpStatusCode.NotFound) })),
        EmscLiveSource(HttpClient(MockEngine { respond("", HttpStatusCode.NotFound) })),
        dao, clock = { clockValue })

    // Task 9 review, Critical 1: DedupeEngine.merge() picks canonical.id from either the
    // matched row's id or incoming's own id. The pre-existing code only ever deleted the
    // matched row's id (via replacesId, when canonical adopted incoming's id) — it never
    // deleted the row already stored at incoming.id when canonical instead adopted the
    // MATCH's id. That row becomes a permanent orphan: dao.byId(incoming.id) keeps resolving
    // to it as "previous" on every future update for the same id, freezing the alert-crossing
    // baseline and re-firing on every subsequent update.
    @Test fun `dedupe match adopting the match id deletes the stale row at incoming id (no orphan, no refire)`() = runTest {
        val r = repoNoop(10_000_000)

        // Seed two unrelated, far-apart rows — DedupeEngine must not merge them (>100km apart).
        r.ingest(quake("e1", Source.EMSC, 5.5, t = 1_000_000, updated = 1_000_000).copy(lat = 0.0, lon = 0.0), home = null)
        r.ingest(quake("us1", Source.USGS, 6.1, t = 1_050_000, updated = 1_050_000), home = null)
        assertEquals(2, dao.countAll())

        r.alertEvents.test {
            // e1 revision: epicenter moves to us1's location (within 100km) with a newer
            // timestamp. merge() picks canonical.id = "us1" (match has USGS) — the stale row
            // still sitting at incoming.id="e1" must be deleted or it orphans permanently.
            r.ingest(quake("e1", Source.EMSC, 5.5, t = 1_060_000, updated = 1_060_000), home = null)
            assertEquals("world", awaitItem().matchedRuleId)   // baseline was the pre-merge mag-5.5 e1 row

            assertEquals(1, dao.countAll())
            assertEquals(null, dao.byId("e1"))
            assertEquals("e1", dao.byId("us1")!!.sources[Source.EMSC])

            // Regression probe: pre-fix, "previous" kept resolving to the frozen orphan
            // (mag 5.5 < 6.0 world threshold), so this identical-shape update re-fired "world"
            // every single time. Post-fix, previous correctly resolves to the live merged row
            // (mag 6.1, already at/above threshold), so no re-fire.
            r.ingest(quake("e1", Source.EMSC, 5.5, t = 1_060_000, updated = 1_070_000), home = null)
            expectNoEvents()

            cancelAndIgnoreRemainingEvents()
        }
    }

    // Task 9 review, Important 3: the replacesId/delete branch (EMSC arrives first, USGS
    // arrives second and takes over the row's id) had zero direct coverage in this suite —
    // every existing test merged USGS-first, where replacesId is always null. This pins that
    // branch: deleting the delete call breaks this test.
    @Test fun `usgs twin arriving second replaces emsc row under usgs id`() = runTest {
        val r = repoNoop(2_000_000)
        r.alertEvents.test {
            r.ingest(quake("e1", Source.EMSC, 5.5, t = 1_950_000, updated = 1_950_000), home = null)
            r.ingest(quake("us1", Source.USGS, 6.1, t = 1_960_000, updated = 1_960_000), home = null)

            assertEquals(1, dao.countAll())
            assertEquals(null, dao.byId("e1"))
            val stored = dao.byId("us1")!!
            assertEquals("e1", stored.sources[Source.EMSC])

            assertEquals("world", awaitItem().matchedRuleId)   // previous resolved via replacesId to the pre-merge e1 row
            expectNoEvents()
            cancelAndIgnoreRemainingEvents()
        }
    }

    // Task 9 review, Important 2 (end-to-end half): the merge-write must be one atomic
    // transaction, or a live recentQuakes() collector observes a transient empty-list frame
    // between the delete of the superseded row and the write of the merged canonical.
    @Test fun `ingest of a merging update emits exactly one transition on recentQuakes (no empty-list flicker)`() = runTest {
        val r = repoNoop(2_000_000)
        r.ingest(quake("e1", Source.EMSC, 5.5, t = 1_950_000, updated = 1_950_000))
        r.recentQuakes(windowMs = 200_000).test {
            assertEquals(listOf("e1"), awaitItem().map { it.id })
            // Merges under "us1"; replacesId="e1" triggers the delete of the old row.
            r.ingest(quake("us1", Source.USGS, 6.1, t = 1_960_000, updated = 1_960_000))
            assertEquals(listOf("us1"), awaitItem().map { it.id })   // straight to final state
            expectNoEvents()
            cancelAndIgnoreRemainingEvents()
        }
    }

    // Task 8: insertedQuakeIds must emit exactly when ingest() decides this is a brand-new quake
    // (previous == null) — never on an update/revision to an already-stored row. HomeViewModel
    // (Task 8) re-exposes this as-is to drive the map's pin-drop animation (Task 10); a false
    // emission on every revision would replay the drop animation for quakes that aren't new.
    @Test fun `ingest of a brand-new quake emits its canonical id on insertedQuakeIds`() = runTest {
        val r = repoNoop(2_000_000)
        r.insertedQuakeIds.test {
            r.ingest(quake("us1", Source.USGS, 5.5, t = 1_950_000))
            assertEquals("us1", awaitItem())
            expectNoEvents()
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test fun `ingest of a revision to an existing quake does not emit on insertedQuakeIds`() = runTest {
        val r = repoNoop(2_000_000)
        r.ingest(quake("us1", Source.USGS, 5.5, t = 1_950_000, updated = 1_950_000))
        r.insertedQuakeIds.test {
            // Same id, newer `updated` — a revision of the row seeded above, not a new event.
            r.ingest(quake("us1", Source.USGS, 5.8, t = 1_950_000, updated = 1_960_000))
            expectNoEvents()
            cancelAndIgnoreRemainingEvents()
        }
    }

    // Task 9 review round 3 (re-review): disproves the "at most one of replacesId / incoming.id
    // needs deleting" invariant the round-2 fix rested on. UsgsFeedParser sets Quake.id from
    // properties.ids's first alias but sources[USGS] from the top-level feature "id" — nothing
    // guarantees they're equal. When incoming.id ("X") diverges from incoming.sources[USGS]
    // ("Y") and dedupe matches a DIFFERENT existing row (one lacking USGS, e.g. an EMSC row),
    // DedupeEngine.merge() sets canonical.id = incoming.sources.getValue(USGS) = "Y" — a THIRD
    // id, distinct from both match.id and incoming.id. Both the matched row (via replacesId)
    // AND the stale row already stored at incoming.id ("X") need deleting simultaneously.
    @Test fun `divergent usgs id and sources id cannot orphan the incoming row`() = runTest {
        val r = repoNoop(10_000_000)

        // 1) A USGS quake whose own id ("X") differs from its sources[USGS] value ("Y") — this
        //    is exactly what UsgsFeedParser produces when the feature's top-level "id" isn't the
        //    first alias in properties.ids. Far from where e1 will be.
        val divergent = Quake(
            "X", 1_000_000, 0.0, 0.0, 10.0, 5.5, "mw", "P", false, null, QuakeStatus.AUTOMATIC,
            mapOf(Source.USGS to "Y"), listOf(MagRevision(5.5, "mw", 1_000_000, Source.USGS)), 1_000_000,
        )
        r.ingest(divergent, home = null)

        // 2) An unrelated EMSC row, far from "X" — must not merge with it.
        r.ingest(quake("e1", Source.EMSC, 5.5, t = 1_000_000, updated = 1_000_000).copy(lat = 50.0, lon = 50.0), home = null)
        assertEquals(2, dao.countAll())

        // 3) A revision of the SAME event (id="X", sources={USGS:"Y"} again), epicenter moved to
        //    e1's location with a newer updatedAt. dedupe matches e1 (existing lacks USGS,
        //    incoming has USGS) -> canonical.id = incoming.sources[USGS] = "Y", a THIRD id
        //    distinct from match.id ("e1") AND incoming.id ("X"). Both must be deleted.
        val revision = divergent.copy(lat = 50.0, lon = 50.0, timeMillis = 1_010_000, updatedAtMillis = 1_010_000)
        r.ingest(revision, home = null)

        assertEquals(1, dao.countAll())
        assertEquals(null, dao.byId("X"))
        assertEquals(null, dao.byId("e1"))
        val stored = dao.byId("Y")
        assertEquals("e1", stored?.sources?.get(Source.EMSC))
        assertEquals("Y", stored?.sources?.get(Source.USGS))
    }

    // Fix Round 1 (I2, review finding): the debug long-press hook used to write its fake quakes
    // through ingest() itself, so a fake landing within DedupeEngine's match window/radius of a
    // REAL quake could merge INTO it under the real quake's id, corrupting stored data for an
    // event that actually happened. ingestDebugBypassingDedupe must never merge — it writes
    // unconditionally under its own id (QuakeDao.replace), leaving any nearby real row untouched.
    // Added alongside the fix (not strict red/green TDD, unlike QuakeDaoTest's deleteByIdPrefix
    // test, which the brief specifically called out as TDD-first) — see task-10-report.md's Fix
    // Round 1 for that distinction.
    @Test fun `ingestDebugBypassingDedupe never merges into a nearby real quake`() = runTest {
        val r = repoNoop(2_000_000)
        r.ingest(quake("us1", Source.USGS, 5.5, t = 1_950_000))   // a real quake already stored
        // Same place, same timestamp — squarely inside DedupeEngine's 90s/100km match window;
        // ingest() would merge this into "us1". ingestDebugBypassingDedupe must not.
        r.ingestDebugBypassingDedupe(
            quake("debug-1", Source.USGS, 6.0, t = 1_950_000).copy(place = "[DEBUG] Injected M6.0"),
        )
        assertEquals(2, dao.countAll(), "the fake must not merge into the real row")
        assertEquals(5.5, dao.byId("us1")?.mag, "the real quake's stored data must be untouched")
        assertEquals(6.0, dao.byId("debug-1")?.mag)
    }

    // Fix Round 1 (I2, review finding): ingest() runs AlertRuleEngine against DEFAULT_RULES — a
    // fake M6.0 debug quake would trip the "world" rule (minMag 6.0, no radius, fires on ANY new
    // quake at or above it) and raise a real, user-visible AlertEvent purely from a debug tap.
    // ingestDebugBypassingDedupe must never evaluate alerts, while still emitting on
    // insertedQuakeIds — the same signal the pin-drop animation and the feed sheet's "N NEW" chip
    // key off of, so the debug hook still exercises that half of the pipeline honestly.
    @Test fun `ingestDebugBypassingDedupe emits insertedQuakeIds but never evaluates alerts`() = runTest {
        val r = repoNoop(2_000_000)
        r.alertEvents.test {
            r.insertedQuakeIds.test {
                r.ingestDebugBypassingDedupe(quake("debug-1", Source.USGS, 6.0, t = 1_950_000))
                assertEquals("debug-1", awaitItem())
                cancelAndIgnoreRemainingEvents()
            }
            expectNoEvents() // the "world" rule (minMag 6.0) would otherwise fire here
            cancelAndIgnoreRemainingEvents()
        }
    }

    // Fix Round 1 (I2): purgeDebugQuakes is the repository-level wrapper HomeViewModel's init
    // calls unconditionally — this pins that its hardcoded prefix constant actually matches
    // HomeViewModel.injectDebugQuake's "debug-" id prefix (the two aren't derived from one shared
    // constant, so a typo in either would otherwise only surface as silent non-cleanup on device).
    @Test fun `purgeDebugQuakes removes only debug-prefixed rows, real quakes untouched`() = runTest {
        val r = repoNoop(2_000_000)
        r.ingestDebugBypassingDedupe(quake("debug-1", Source.USGS, 6.0, t = 1_950_000))
        r.ingest(quake("us1", Source.USGS, 5.5, t = 1_000_000))
        r.purgeDebugQuakes()
        assertEquals(1, dao.countAll())
        assertNotNull(dao.byId("us1"))
    }

    // Task 7 (Plan 3), USER REQUIREMENT: currentRules()/refreshFeed() must read the "near" rule's
    // radius/minMag from a wired AlertRuleStore instead of DEFAULT_RULES' compile-time 500.0/4.5.

    @Test fun `currentRules falls back to DEFAULT_RULES when no store is wired`() = runTest {
        val r = repoNoop(2_000_000)
        assertEquals(DEFAULT_RULES, r.currentRules())
    }

    @Test fun `currentRules builds the near rule from the wired AlertRuleStore, world rule unchanged`() = runTest {
        val alertRuleStore = AlertRuleStore(dao).apply {
            setNearbyRadius(50.0)
            setMinMag(5.0)
        }
        val r = QuakeRepository(
            UsgsApi(HttpClient(MockEngine { respond("", HttpStatusCode.NotFound) })),
            EmscLiveSource(HttpClient(MockEngine { respond("", HttpStatusCode.NotFound) })),
            dao, clock = { 2_000_000 }, alertRuleStore = alertRuleStore,
        )
        val rules = r.currentRules()
        val near = rules.single { it.id == "near" }
        assertEquals(50.0, near.radiusKm)
        assertEquals(5.0, near.minMag)
        val world = rules.single { it.id == "world" }
        assertEquals(6.0, world.minMag)
        assertNull(world.radiusKm)
    }

    // The end-to-end proof: a quake safely inside DEFAULT_RULES' 500km "near" radius must NOT fire
    // once the store configures a SMALLER radius that excludes it — if refreshFeed() were still
    // silently using DEFAULT_RULES underneath, this quake would fire "near" and this test would go
    // red. Distance is computed via the same haversineKm the implementation itself calls (matches
    // PillStatusTest's own "derive the boundary from the real formula" convention), not guessed.
    @Test fun `refreshFeed does not fire the near rule beyond the store's configured radius, even within DEFAULT_RULES' 500km`() = runTest {
        val home = GeoPoint(12.9716, 77.5946) // Bengaluru
        val quakePoint = GeoPoint(15.5, 77.5946) // due north
        val distanceKm = haversineKm(home, quakePoint)
        assertTrue(distanceKm < 500.0, "test setup: must be within DEFAULT_RULES' 500km to prove anything ($distanceKm km)")

        val homeLocationStore = HomeLocationStore(dao).apply { set(home) }
        val alertRuleStore = AlertRuleStore(dao).apply {
            setNearbyRadius(distanceKm - 20.0) // smaller than the actual distance -> excludes it
            setMinMag(4.0)
        }
        val engine = MockEngine {
            respond(
                oneFeatureGeoJson(id = "q1", lat = quakePoint.lat, lon = quakePoint.lon, mag = 5.0),
                HttpStatusCode.OK,
                headersOf(HttpHeaders.ContentType to listOf("application/json")),
            )
        }
        val r = QuakeRepository(
            UsgsApi(HttpClient(engine)), EmscLiveSource(HttpClient(engine)), dao,
            clock = { 2_000_000 }, alertRuleStore = alertRuleStore, homeLocationStore = homeLocationStore,
        )
        r.alertEvents.test {
            r.refreshFeed()
            expectNoEvents()
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test fun `refreshFeed fires the near rule for a quake within the store's configured radius`() = runTest {
        val home = GeoPoint(12.9716, 77.5946) // Bengaluru
        val quakePoint = GeoPoint(15.5, 77.5946) // due north
        val distanceKm = haversineKm(home, quakePoint)

        val homeLocationStore = HomeLocationStore(dao).apply { set(home) }
        val alertRuleStore = AlertRuleStore(dao).apply {
            setNearbyRadius(distanceKm + 20.0) // larger than the actual distance -> includes it
            setMinMag(4.0)
        }
        val engine = MockEngine {
            respond(
                oneFeatureGeoJson(id = "q1", lat = quakePoint.lat, lon = quakePoint.lon, mag = 5.0),
                HttpStatusCode.OK,
                headersOf(HttpHeaders.ContentType to listOf("application/json")),
            )
        }
        val r = QuakeRepository(
            UsgsApi(HttpClient(engine)), EmscLiveSource(HttpClient(engine)), dao,
            clock = { 2_000_000 }, alertRuleStore = alertRuleStore, homeLocationStore = homeLocationStore,
        )
        r.alertEvents.test {
            r.refreshFeed()
            assertEquals("near", awaitItem().matchedRuleId)
            cancelAndIgnoreRemainingEvents()
        }
    }

    // Task 2 (Plan 4), F5 guard (plan-3-exit-conditions.md carried item) — SUPERSEDES the old
    // "loadArchivePage does not honor the store - it always uses DEFAULT_RULES" test that used to
    // live here: that test asserted loadArchivePage's M6.5 fixture quake FIRED the "world" alert —
    // exactly the hazard F5 flags ("the instant Plan 4 wires real notifications off alertEvents, a
    // user deep-scrolling History past old M6+ quakes will notification-storm on events years
    // old"). Now proves the opposite: archive ingestion emits NO alertEvents at all, for a quake
    // that would otherwise trip both the "near" rule (store configured to a tiny 10km radius that
    // still contains 0,0 relative to itself is irrelevant here) AND DEFAULT_RULES' "world" rule
    // (minMag 6.0, unbounded) — proving `rules = emptyList()` really does suppress evaluation
    // entirely, not just "unwired from the store" the way the superseded test only showed.
    @Test fun `loadArchivePage never alerts, even for a world-rule-qualifying quake`() = runTest {
        val home = GeoPoint(12.9716, 77.5946)
        val quakePoint = GeoPoint(0.0, 0.0) // far outside any "near" radius, well within old "world" territory
        val homeLocationStore = HomeLocationStore(dao).apply { set(home) }
        val alertRuleStore = AlertRuleStore(dao).apply { setNearbyRadius(10.0) }
        val engine = MockEngine {
            respond(
                oneFeatureGeoJson(id = "q1", lat = quakePoint.lat, lon = quakePoint.lon, mag = 6.5),
                HttpStatusCode.OK,
                headersOf(HttpHeaders.ContentType to listOf("application/json")),
            )
        }
        val r = QuakeRepository(
            UsgsApi(HttpClient(engine)), EmscLiveSource(HttpClient(engine)), dao,
            clock = { 2_000_000 }, alertRuleStore = alertRuleStore, homeLocationStore = homeLocationStore,
        )
        r.alertEvents.test {
            r.loadArchivePage(beforeMillis = 2_000_000)
            expectNoEvents()
            cancelAndIgnoreRemainingEvents()
        }
    }

    // Task 2 (Plan 4), origin tagging: loadArchivePage's rows must be tagged 'archive' — proven
    // end-to-end via pruneOldRows' own exemption rather than a direct read (origin is deliberately
    // DB-layer-only; QuakeStore.originOf, added Fix Round 1, is a narrow exception for internal
    // merge-protection bookkeeping only, not a general-purpose read — see QuakeStore's own kdoc),
    // which is also exactly the real-world property that matters: an archive-backfilled row must
    // survive retention even when it's decades old, while a feed-ingested row of the identical age
    // must not.
    @Test fun `pruneOldRows protects an archive-origin row but deletes a feed-origin row of the same age`() = runTest {
        val oldTime = 1_000_000L // ancient relative to the cutoff below
        val feedRepo = repoNoop(clockValue = 50_000_000_000L)
        feedRepo.ingest(quake("feed1", Source.USGS, 5.0, t = oldTime))   // origin defaults "feed"

        val archiveEngine = MockEngine {
            respond(
                oneFeatureGeoJson(id = "arch1", lat = 10.0, lon = 20.0, mag = 5.0, timeMillis = oldTime),
                HttpStatusCode.OK,
                headersOf(HttpHeaders.ContentType to listOf("application/json")),
            )
        }
        // A second QuakeRepository sharing the SAME dao -- loadArchivePage's own network call needs
        // a real 200 response (unlike feedRepo's always-404 MockEngine), but both writes must land
        // in the one shared table this test asserts against.
        val archiveRepo = QuakeRepository(
            UsgsApi(HttpClient(archiveEngine)), EmscLiveSource(HttpClient(archiveEngine)), dao,
            clock = { 50_000_000_000L },
        )
        archiveRepo.loadArchivePage(beforeMillis = oldTime + 1)
        assertEquals(2, dao.countAll())

        feedRepo.pruneOldRows(cutoffMillis = oldTime + 1)

        assertEquals(1, dao.countAll())
        assertNull(dao.byId("feed1"))
        assertNotNull(dao.byId("arch1"))
    }

    // Task 2 (Plan 4), Fix Round 1 (review finding): origin-flip-on-merge protection ---------------
    // Reproduction this closes: History archives a quake (origin='archive') -> the very next
    // all_day feed poll re-ingests the SAME quake (same USGS id -- stable across archive vs.
    // realtime USGS queries for one real-world event) -> pre-fix, ingest()'s merge-write path
    // unconditionally stamped the CALLER's origin ('feed'), silently downgrading the row -> a
    // pruneOldRows pass 30 days later deletes a row the user had actually viewed via History,
    // violating that screen's own "cached pages browse offline" contract. See
    // QuakeStore.pruneOldRows's own kdoc for the full mechanism this now mitigates.

    @Test fun `origin protection - a same-id feed re-ingest does not downgrade an archived row`() = runTest {
        val r = repoNoop(2_000_000)
        r.ingest(quake("us1", Source.USGS, 5.0, t = 1_000_000), origin = QuakeStore.ORIGIN_ARCHIVE)
        assertEquals(QuakeStore.ORIGIN_ARCHIVE, dao.originOf("us1"))

        // Same real-world event re-ingested via the feed poll under the identical id -- previousById
        // (dao.byId(incoming.id)) resolves non-null, the "same-id update" lookup shape.
        r.ingest(quake("us1", Source.USGS, 5.1, t = 1_000_000, updated = 1_010_000), origin = QuakeStore.ORIGIN_FEED)

        assertEquals(1, dao.countAll())
        assertEquals(QuakeStore.ORIGIN_ARCHIVE, dao.originOf("us1"))
    }

    @Test fun `origin protection - an archive-path ingest may still upgrade an existing feed row`() = runTest {
        val r = repoNoop(2_000_000)
        r.ingest(quake("us1", Source.USGS, 5.0, t = 1_000_000), origin = QuakeStore.ORIGIN_FEED)
        assertEquals(QuakeStore.ORIGIN_FEED, dao.originOf("us1"))

        r.ingest(quake("us1", Source.USGS, 5.1, t = 1_000_000, updated = 1_010_000), origin = QuakeStore.ORIGIN_ARCHIVE)

        assertEquals(1, dao.countAll())
        assertEquals(
            QuakeStore.ORIGIN_ARCHIVE, dao.originOf("us1"),
            "feed carries no protection of its own -- archive may freely overwrite it",
        )
    }

    @Test fun `origin protection - feed and live carry no protection between each other, caller's origin always wins`() = runTest {
        val r = repoNoop(2_000_000)
        r.ingest(quake("us1", Source.USGS, 5.0, t = 1_000_000), origin = QuakeStore.ORIGIN_FEED)
        r.ingest(quake("us1", Source.USGS, 5.1, t = 1_000_000, updated = 1_010_000), origin = QuakeStore.ORIGIN_LIVE)
        assertEquals(QuakeStore.ORIGIN_LIVE, dao.originOf("us1"))

        // Reverse direction too -- neither origin is ever "protected" against the other.
        r.ingest(quake("us2", Source.USGS, 5.0, t = 1_100_000), origin = QuakeStore.ORIGIN_LIVE)
        r.ingest(quake("us2", Source.USGS, 5.1, t = 1_100_000, updated = 1_110_000), origin = QuakeStore.ORIGIN_FEED)
        assertEquals(QuakeStore.ORIGIN_FEED, dao.originOf("us2"))
    }

    // Fix Round 1 (review finding), "check the replaced row too": mirrors "divergent usgs id and
    // sources id cannot orphan the incoming row" above -- same dual-stale-row shape, where a SINGLE
    // ingest call supersedes BOTH the row already at incoming.id AND a separately dedupe-matched row
    // under a DIFFERENT id at once -- but stacks origins on the two rows so they disagree. `previous`
    // itself resolves to the row at incoming.id here (the elvis chain's first hit, previousById) --
    // protection logic that only ever consulted `previous`'s own origin would see 'feed'
    // (unprotected) and let the caller's origin win, silently discarding the OTHER superseded row's
    // 'archive' protection. Checking `result.replacesId`'s own origin independently, in addition to
    // `previous`'s, is what catches it.
    @Test fun `origin protection checks the replaced row too, not just whichever row previous resolved to`() = runTest {
        val r = repoNoop(10_000_000)

        // "X"'s own id differs from its sources[USGS] value ("Y") -- the same divergent-id shape as
        // the pre-existing dual-delete test above, needed here so the eventual merge's canonical.id
        // ("Y") ends up a THIRD id, distinct from both "X" (incoming.id) and "e1" (the dedupe match).
        val divergent = Quake(
            "X", 1_000_000, 0.0, 0.0, 10.0, 5.5, "mw", "P", false, null, QuakeStatus.AUTOMATIC,
            mapOf(Source.USGS to "Y"), listOf(MagRevision(5.5, "mw", 1_000_000, Source.USGS)), 1_000_000,
        )
        r.ingest(divergent, home = null, origin = QuakeStore.ORIGIN_FEED)
        r.ingest(
            quake("e1", Source.EMSC, 5.5, t = 1_000_000, updated = 1_000_000).copy(lat = 50.0, lon = 50.0),
            home = null, origin = QuakeStore.ORIGIN_ARCHIVE,
        )
        assertEquals(QuakeStore.ORIGIN_FEED, dao.originOf("X"))
        assertEquals(QuakeStore.ORIGIN_ARCHIVE, dao.originOf("e1"))

        // Revision of "X": epicenter moves onto e1's location, newer timestamp. dedupe matches "e1"
        // (lacks USGS) against this incoming (has USGS) -> canonical.id = incoming.sources[USGS] =
        // "Y" -- replacesId = "e1", AND the stale row at incoming.id = "X" both get deleted in the
        // same call. previousById = dao.byId("X") is non-null, so `previous` resolves to "X" (origin
        // 'feed') WITHOUT ever looking at "e1" -- only a separate, explicit replacesId origin check
        // finds "e1"'s 'archive' tag.
        val revision = divergent.copy(lat = 50.0, lon = 50.0, timeMillis = 1_010_000, updatedAtMillis = 1_010_000)
        r.ingest(revision, home = null, origin = QuakeStore.ORIGIN_FEED)

        assertEquals(1, dao.countAll())
        assertNull(dao.byId("X"))
        assertNull(dao.byId("e1"))
        assertEquals(
            QuakeStore.ORIGIN_ARCHIVE, dao.originOf("Y"),
            "e1's archive protection must survive even though it was found via replacesId, not via previous",
        )
    }

    // Plan 4 Task 5 (Insights density backfill): worldwideCount/worldwideCountCache/
    // setWorldwideCountCache — see QuakeRepository's own kdoc for each.
    @Test fun `worldwideCount parses a successful FDSN count response`() = runTest {
        val engine = MockEngine { respond("""{"count":11082,"maxAllowed":20000}""", HttpStatusCode.OK) }
        val r = QuakeRepository(
            UsgsApi(HttpClient(engine)),
            EmscLiveSource(HttpClient(MockEngine { respond("", HttpStatusCode.NotFound) })),
            dao, clock = { 2_000_000 })
        assertEquals(11_082L, r.worldwideCount(startTimeMillis = 0, endTimeMillis = 1_000_000))
    }

    @Test fun `worldwideCount returns null on a failed request rather than throwing`() = runTest {
        val engine = MockEngine { respond("boom", HttpStatusCode.InternalServerError) }
        val r = QuakeRepository(
            UsgsApi(HttpClient(engine)),
            EmscLiveSource(HttpClient(MockEngine { respond("", HttpStatusCode.NotFound) })),
            dao, clock = { 2_000_000 })
        assertEquals(null, r.worldwideCount(startTimeMillis = 0, endTimeMillis = 1_000_000))
    }

    @Test fun `worldwideCountCache is null before anything is ever cached`() = runTest {
        val r = QuakeRepository(
            UsgsApi(HttpClient(MockEngine { respond("", HttpStatusCode.NotFound) })),
            EmscLiveSource(HttpClient(MockEngine { respond("", HttpStatusCode.NotFound) })),
            dao, clock = { 2_000_000 })
        assertNull(r.worldwideCountCache())
    }

    @Test fun `setWorldwideCountCache then worldwideCountCache round-trips both fields together`() = runTest {
        val r = QuakeRepository(
            UsgsApi(HttpClient(MockEngine { respond("", HttpStatusCode.NotFound) })),
            EmscLiveSource(HttpClient(MockEngine { respond("", HttpStatusCode.NotFound) })),
            dao, clock = { 2_000_000 })
        r.setWorldwideCountCache(count = 42, fetchedAtMillis = 1_800_000)
        val cached = r.worldwideCountCache()
        assertNotNull(cached)
        assertEquals(42L, cached.count)
        assertEquals(1_800_000L, cached.fetchedAtMillis)
    }

    private fun oneFeatureGeoJson(id: String, lat: Double, lon: Double, mag: Double, timeMillis: Long = 1_950_000L) = """
        {
          "type": "FeatureCollection",
          "features": [
            {
              "type": "Feature",
              "id": "$id",
              "properties": {
                "mag": $mag,
                "place": "Test quake",
                "time": $timeMillis,
                "updated": $timeMillis,
                "magType": "mw",
                "status": "automatic",
                "tsunami": 0
              },
              "geometry": { "type": "Point", "coordinates": [$lon, $lat, 10.0] }
            }
          ]
        }
    """.trimIndent()
}
