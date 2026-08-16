package com.yugma.terrawatch.history

// Real QuakeRepository + a real HistoryPager over app.cash.sqldelight's JDBC in-memory driver
// (JVM-only), same jvmTest-not-commonTest rationale as HomeViewModelTest/QuakeRepositoryTest, and
// the same "MockEngine stands in for the network" pattern this task's brief calls for (QuakeRepository
// is a concrete class with no interface to fake against).
import androidx.lifecycle.viewModelScope
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import app.cash.turbine.test
import com.yugma.terrawatch.data.HistoryFilter
import com.yugma.terrawatch.data.HistoryPager
import com.yugma.terrawatch.data.QuakeRepository
import com.yugma.terrawatch.database.QuakeDao
import com.yugma.terrawatch.database.TerraWatchDb
import com.yugma.terrawatch.model.MagRevision
import com.yugma.terrawatch.model.Quake
import com.yugma.terrawatch.model.QuakeStatus
import com.yugma.terrawatch.model.Source
import com.yugma.terrawatch.network.EmscLiveSource
import com.yugma.terrawatch.network.UsgsApi
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.job
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

class HistoryViewModelTest {
    private lateinit var dao: QuakeDao

    // Same leaked-coroutine/flake precedent as HomeViewModelTest's own createVm/tearDown (see that
    // file's extensive kdoc on why): HistoryViewModel's loadUntilDecided() loop is not a child of
    // any one test's runTest{} coroutine, so it must be explicitly cancelled+joined, in this exact
    // order relative to Dispatchers.resetMain(), or a still-unwinding previous test's coroutine can
    // race the next test's Dispatchers.setMain() call.
    //
    // Flake-hardening pass (2026-08-16, sweeping the terrawatch flaky-test playbook -- see
    // HomeViewModelTest's own kdoc for the original Task-13/commit-5e9e922 precedent this ports):
    // `repository(engine, dao)` below never pins QuakeRepository's `ioDispatcher`, so every
    // `loadUntilDecided()` step (`HistoryPager.loadNext` -> `repository.loadArchivePage`/
    // `pageBetween`) hops through a real, uncontrolled `Dispatchers.Default` thread. Every
    // `vm.state.test { ... }` (and the two-VM `vm1`/`vm2` variant) in this class now carries
    // `timeout = 30.seconds` for the same starved-CI-runner margin commit 5e9e922 first
    // established -- `GroupByMonthTest` below needs none of this: it's a pure-function suite with
    // no coroutines at all.
    private val createdViewModels = mutableListOf<HistoryViewModel>()

    @AfterTest fun tearDown() {
        Dispatchers.resetMain()
        runBlocking { createdViewModels.forEach { it.viewModelScope.coroutineContext.job.cancelAndJoin() } }
        createdViewModels.clear()
    }

    private fun freshDao(): QuakeDao {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        TerraWatchDb.Schema.create(driver)
        return QuakeDao(TerraWatchDb(driver))
    }

    private fun repository(engine: MockEngine, dao: QuakeDao) = QuakeRepository(
        UsgsApi(HttpClient(engine)),
        EmscLiveSource(HttpClient(engine)),
        dao,
        clock = { 2_000_000L },
    )

    private fun createVm(engine: MockEngine, dao: QuakeDao = freshDao(), pagerClock: () -> Long = { 10_000_000L }): HistoryViewModel {
        val repo = repository(engine, dao)
        val pager = HistoryPager(repo, clock = pagerClock)
        return HistoryViewModel(repo, pager).also { createdViewModels += it }
    }

    private fun featureJson(id: String, timeMillis: Long, mag: Double = 5.0) = """
        {
          "type": "Feature",
          "id": "$id",
          "properties": {"mag": $mag, "place": "Test $id", "time": $timeMillis, "updated": $timeMillis, "magType": "mw", "status": "automatic", "tsunami": 0},
          "geometry": {"type": "Point", "coordinates": [10.0, 20.0, 10.0]}
        }
    """.trimIndent()

    private fun featureCollection(vararg features: String) =
        """{"type":"FeatureCollection","features":[${features.joinToString(",")}]}"""

    private fun MockRequestHandleScope.geojson(body: String) =
        respond(body, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType to listOf("application/json")))

    private fun HistoryUiState.Content.totalQuakes(): Int = sections.sumOf { it.quakes.size }

    @Test fun `starts LoadingFirst then settles on Content once the first page resolves`() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        val engine = MockEngine { geojson(featureCollection(featureJson("a", 9_000_000), featureJson("b", 8_000_000))) }
        val vm = createVm(engine)
        vm.state.test(timeout = 30.seconds) {
            var s = awaitItem()
            while (s is HistoryUiState.LoadingFirst) s = awaitItem()
            val content = assertIs<HistoryUiState.Content>(s)
            assertEquals(2, content.totalQuakes())
            assertFalse(content.loadingMore)
            assertFalse(content.endReached)
            assertFalse(content.loadMoreFailed)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test fun `loadMore appends another page and keeps endReached false while more data exists`() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        var callCount = 0
        val engine = MockEngine {
            callCount++
            if (callCount == 1) {
                geojson(featureCollection(featureJson("a", 9_000_000), featureJson("b", 8_000_000)))
            } else {
                geojson(featureCollection(featureJson("c", 7_000_000)))
            }
        }
        val vm = createVm(engine)
        vm.state.test(timeout = 30.seconds) {
            var s = awaitItem()
            while (s is HistoryUiState.LoadingFirst) s = awaitItem()
            assertEquals(2, assertIs<HistoryUiState.Content>(s).totalQuakes())

            vm.loadMore()

            var s2 = awaitItem()
            while (s2 is HistoryUiState.Content && s2.totalQuakes() < 3) s2 = awaitItem()
            val content = assertIs<HistoryUiState.Content>(s2)
            assertEquals(3, content.totalQuakes())
            assertFalse(content.endReached)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test fun `reaching an empty page sets endReached true without losing already-loaded rows`() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        var callCount = 0
        val engine = MockEngine {
            callCount++
            if (callCount == 1) geojson(featureCollection(featureJson("a", 9_000_000)))
            else geojson(featureCollection())
        }
        val vm = createVm(engine)
        vm.state.test(timeout = 30.seconds) {
            var s = awaitItem()
            while (s is HistoryUiState.LoadingFirst) s = awaitItem()
            assertFalse(assertIs<HistoryUiState.Content>(s).endReached)

            vm.loadMore()

            var s2 = awaitItem()
            while (s2 is HistoryUiState.Content && !s2.endReached) s2 = awaitItem()
            val content = assertIs<HistoryUiState.Content>(s2)
            assertTrue(content.endReached)
            assertEquals(1, content.totalQuakes(), "the row loaded before End must still be showing")
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test fun `a first-page failure with nothing cached shows Error`() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        val engine = MockEngine { respond("boom", HttpStatusCode.InternalServerError) }
        val vm = createVm(engine)
        vm.state.test(timeout = 30.seconds) {
            var s = awaitItem()
            while (s is HistoryUiState.LoadingFirst) s = awaitItem()
            assertIs<HistoryUiState.Error>(s)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test fun `retry from Error re-attempts the first load and can recover`() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        var callCount = 0
        val engine = MockEngine {
            callCount++
            if (callCount == 1) respond("boom", HttpStatusCode.InternalServerError)
            else geojson(featureCollection(featureJson("a", 9_000_000)))
        }
        val vm = createVm(engine)
        vm.state.test(timeout = 30.seconds) {
            var s = awaitItem()
            while (s is HistoryUiState.LoadingFirst) s = awaitItem()
            assertIs<HistoryUiState.Error>(s)

            vm.retry()

            var s2 = awaitItem()
            while (s2 !is HistoryUiState.Content) s2 = awaitItem()
            assertEquals(1, (s2 as HistoryUiState.Content).totalQuakes())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test fun `a page failure after items are already loaded shows Content with loadMoreFailed, not Error`() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        var callCount = 0
        val engine = MockEngine {
            callCount++
            if (callCount == 1) geojson(featureCollection(featureJson("a", 9_000_000)))
            else respond("boom", HttpStatusCode.InternalServerError)
        }
        val vm = createVm(engine)
        vm.state.test(timeout = 30.seconds) {
            var s = awaitItem()
            while (s is HistoryUiState.LoadingFirst) s = awaitItem()
            assertFalse(assertIs<HistoryUiState.Content>(s).loadMoreFailed)

            vm.loadMore()

            var s2 = awaitItem()
            while (s2 is HistoryUiState.Content && !s2.loadMoreFailed) s2 = awaitItem()
            val content = assertIs<HistoryUiState.Content>(s2)
            assertTrue(content.loadMoreFailed)
            assertEquals(1, content.totalQuakes(), "the already-loaded row must still be showing, not wiped by the failure")
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test fun `retry from a loadMoreFailed Content re-attempts loadMore and can recover`() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        var callCount = 0
        val engine = MockEngine {
            callCount++
            when (callCount) {
                1 -> geojson(featureCollection(featureJson("a", 9_000_000)))
                2 -> respond("boom", HttpStatusCode.InternalServerError)
                else -> geojson(featureCollection(featureJson("b", 8_000_000)))
            }
        }
        val vm = createVm(engine)
        vm.state.test(timeout = 30.seconds) {
            var s = awaitItem()
            while (s is HistoryUiState.LoadingFirst) s = awaitItem()

            vm.loadMore() // fails -> loadMoreFailed = true
            var s2 = awaitItem()
            while (s2 is HistoryUiState.Content && !s2.loadMoreFailed) s2 = awaitItem()
            assertTrue(assertIs<HistoryUiState.Content>(s2).loadMoreFailed)

            vm.retry() // re-attempts loadMore -> succeeds this time

            var s3 = awaitItem()
            while (s3 is HistoryUiState.Content && s3.loadMoreFailed) s3 = awaitItem()
            val content = assertIs<HistoryUiState.Content>(s3)
            assertFalse(content.loadMoreFailed)
            assertEquals(2, content.totalQuakes())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test fun `setFilter starts a fresh, isolated walk for the new filter`() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        val engine = MockEngine { req ->
            if (req.url.parameters["minmagnitude"] == "6.0") {
                geojson(featureCollection(featureJson("strong", 7_000_000, mag = 6.5)))
            } else {
                geojson(featureCollection(featureJson("a", 9_000_000), featureJson("b", 8_000_000)))
            }
        }
        val vm = createVm(engine)
        vm.state.test(timeout = 30.seconds) {
            var s = awaitItem()
            while (s is HistoryUiState.LoadingFirst) s = awaitItem()
            assertEquals(2, assertIs<HistoryUiState.Content>(s).totalQuakes())

            vm.setFilter(HistoryFilter(minMag = 6.0))

            var s2 = awaitItem()
            while (s2 is HistoryUiState.LoadingFirst) s2 = awaitItem()
            val content = assertIs<HistoryUiState.Content>(s2)
            assertEquals(1, content.totalQuakes(), "the new filter's walk must not carry over the old filter's loadedCount")
            assertEquals("strong", content.sections.single().quakes.single().id)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test fun `a filter with zero matching data ever shows Empty`() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        val engine = MockEngine { geojson(featureCollection()) }
        val vm = createVm(engine)
        vm.state.test(timeout = 30.seconds) {
            var s = awaitItem()
            while (s is HistoryUiState.LoadingFirst) s = awaitItem()
            assertIs<HistoryUiState.Empty>(s)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test fun `sections group loaded quakes by UTC month`() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        // 2026-08-09T00:00:00Z and 2026-07-01T00:00:00Z — independently verified two ways (BSD
        // `date -j -f "%Y-%m-%d %H:%M:%S" ... +%s` AND python's datetime.timestamp()), not derived
        // from this file's own use of kotlinx-datetime.
        val augustMillis = 1_786_233_600_000L
        val julyMillis = 1_782_864_000_000L
        val engine = MockEngine { geojson(featureCollection(featureJson("aug", augustMillis), featureJson("jul", julyMillis))) }
        val vm = createVm(engine)
        vm.state.test(timeout = 30.seconds) {
            var s = awaitItem()
            while (s is HistoryUiState.LoadingFirst) s = awaitItem()
            val content = assertIs<HistoryUiState.Content>(s)
            assertEquals(listOf("AUGUST 2026", "JULY 2026"), content.sections.map { it.label })
            cancelAndIgnoreRemainingEvents()
        }
    }

    // ---- Fix round 1 (review Critical): loadedCount was a session-local fetch tally used as the
    // display query's LIMIT — never re-derived from actual cache/cursor state. Two reproducible
    // breaks pinned here (a restart-while-offline combination of the reviewer's (b)+(c), and (a)
    // directly): both confirmed RED against the pre-fix code before the fix landed (see
    // task-5-report.md's Fix round 1 section for the actual red output), GREEN after.

    @Test fun `a second ViewModel instance over the SAME dao shows cached rows even when its own fetch fails`() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        val dao = freshDao()

        // VM #1: a normal session that pages two batches deep (3 quakes total) and persists its
        // cursor to `dao`'s meta table as it goes (HistoryPager.setCursor, unchanged behavior).
        var callCount1 = 0
        val engine1 = MockEngine {
            callCount1++
            if (callCount1 == 1) geojson(featureCollection(featureJson("a", 9_000_000), featureJson("b", 8_000_000)))
            else geojson(featureCollection(featureJson("c", 7_000_000)))
        }
        val vm1 = createVm(engine1, dao = dao)
        vm1.state.test(timeout = 30.seconds) {
            var s = awaitItem()
            while (s is HistoryUiState.LoadingFirst) s = awaitItem()
            vm1.loadMore()
            var s2 = awaitItem()
            while (s2 is HistoryUiState.Content && s2.totalQuakes() < 3) s2 = awaitItem()
            assertEquals(3, (s2 as HistoryUiState.Content).totalQuakes())
            cancelAndIgnoreRemainingEvents()
        }

        // VM #2: a FRESH HistoryViewModel + FRESH HistoryPager (own empty in-memory cursor map,
        // exactly like a real process restart) over the SAME `dao` -- i.e. the same on-disk cache
        // VM #1 just populated. Its own network is fully down (every request fails), simulating
        // "restarted the app while offline" -- the reviewer's case (c), the most severe of the three.
        val failingEngine = MockEngine { respond("boom", HttpStatusCode.InternalServerError) }
        val vm2 = createVm(failingEngine, dao = dao)
        vm2.state.test(timeout = 30.seconds) {
            var s = awaitItem()
            while (s is HistoryUiState.LoadingFirst) s = awaitItem()
            // Pre-fix: loadedCount starts at 0 on this brand-new instance and is never seeded from
            // the persisted cursor/cache, so visibleItems() queries `LIMIT 0` -> always empty ->
            // this lands on Error (or Empty), hiding a fully-cached 3-row archive. Post-fix: the
            // display window is derived from the pager's own (persisted, correctly-resumed) cursor,
            // independent of whether THIS instance's own fetch attempt succeeded.
            val content = assertIs<HistoryUiState.Content>(s, "expected cached rows to still show; got $s")
            assertEquals(3, content.totalQuakes())
            assertTrue(content.loadMoreFailed)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test fun `revisiting a filter within the same session shows its previously-cached depth, not a reset`() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        // Filter A ("All") pages 2 batches deep (3 quakes). Filter B ("M6+") pages 1 batch (1 quake,
        // deliberately dated BEFORE filter A's own cursor so it can never accidentally satisfy A's
        // own range once we flip back). Flipping back to A must show A's own full depth again, not
        // whatever B happened to leave behind, and not a reset to a single fresh page.
        var callCount = 0
        val engine = MockEngine { req ->
            callCount++
            when {
                req.url.parameters["minmagnitude"] == "6.0" ->
                    geojson(featureCollection(featureJson("strong", 6_000_000, mag = 6.5)))
                callCount == 1 -> geojson(featureCollection(featureJson("a", 9_000_000), featureJson("b", 8_000_000)))
                callCount == 2 -> geojson(featureCollection(featureJson("c", 7_000_000)))
                // 3rd call for filter A (the revisit): nothing further back -- End, cursor unchanged.
                else -> geojson(featureCollection())
            }
        }
        val vm = createVm(engine)
        vm.state.test(timeout = 30.seconds) {
            var s = awaitItem()
            while (s is HistoryUiState.LoadingFirst) s = awaitItem()
            vm.loadMore()
            var s2 = awaitItem()
            while (s2 is HistoryUiState.Content && s2.totalQuakes() < 3) s2 = awaitItem()
            assertEquals(3, (s2 as HistoryUiState.Content).totalQuakes(), "filter A should be 3 deep")

            vm.setFilter(HistoryFilter(minMag = 6.0))
            var s3 = awaitItem()
            while (s3 is HistoryUiState.LoadingFirst) s3 = awaitItem()
            assertEquals(1, (s3 as HistoryUiState.Content).totalQuakes(), "filter B should be 1 deep")

            vm.setFilter(HistoryFilter())
            var s4 = awaitItem()
            while (s4 is HistoryUiState.LoadingFirst) s4 = awaitItem()
            // Pre-fix: setFilter() unconditionally zeroes loadedCount, so revisiting A re-derives
            // its display window from whatever THIS call's own (possibly empty/End) fetch returns,
            // not from A's actual 3-row cache -- this lands on Empty. Post-fix: A's own cursor
            // (tracked independently per filter inside HistoryPager, never reset by setFilter) is
            // exactly where A's walk left off, so the display window still covers all 3 rows.
            val content = assertIs<HistoryUiState.Content>(s4, "expected filter A's cached depth to still show; got $s4")
            assertTrue(content.totalQuakes() >= 3, "revisiting filter A must not shrink below its previous depth (3), was ${content.totalQuakes()}")
            cancelAndIgnoreRemainingEvents()
        }
    }
}

class GroupByMonthTest {
    private fun quake(id: String, timeMillis: Long) = Quake(
        id = id, timeMillis = timeMillis, lat = 1.0, lon = 2.0, depthKm = 5.0,
        mag = 4.0, magType = "mw", place = "Test", tsunami = false, felt = null,
        status = QuakeStatus.AUTOMATIC, sources = mapOf(Source.USGS to id),
        revisions = listOf(MagRevision(4.0, "mw", timeMillis, Source.USGS)),
        updatedAtMillis = timeMillis,
    )

    @Test fun `empty input produces empty sections`() {
        assertEquals(emptyList(), groupByMonth(emptyList(), nowMillis = 0L))
    }

    @Test fun `a single quake produces one section labeled with its own UTC month and year`() {
        // 2026-08-09T12:00:00Z, independently verified the same two ways as HistoryViewModelTest's own.
        val sections = groupByMonth(listOf(quake("a", 1_786_276_800_000L)), nowMillis = 0L)
        assertEquals(1, sections.size)
        assertEquals("AUGUST 2026", sections.single().label)
        assertEquals(listOf("a"), sections.single().quakes.map { it.id })
    }

    @Test fun `consecutive same-month quakes land in one section, not one per quake`() {
        val sections = groupByMonth(
            listOf(quake("a", 1_786_276_800_000L), quake("b", 1_786_233_600_000L)),
            nowMillis = 0L,
        )
        assertEquals(1, sections.size)
        assertEquals(listOf("a", "b"), sections.single().quakes.map { it.id })
    }

    @Test fun `sections come out in first-seen order, not re-sorted`() {
        val augustMillis = 1_786_233_600_000L
        val julyMillis = 1_782_864_000_000L
        val juneMillis = 1_780_272_000_000L // 2026-06-01T00:00:00Z, independently verified
        val sections = groupByMonth(
            listOf(quake("aug", augustMillis), quake("jun", juneMillis), quake("jul", julyMillis)),
            nowMillis = 0L,
        )
        assertEquals(listOf("AUGUST 2026", "JUNE 2026", "JULY 2026"), sections.map { it.label })
    }
}
