package com.yugma.terrawatch.insights

// Same "real QuakeRepository over an in-memory JDBC driver, MockEngine stands in for the network"
// pattern as HistoryViewModelTest/HomeViewModelTest (QuakeRepository is concrete, no interface to
// fake). Insights never actually touches the network (recentQuakes/quakesPerDay/bandDistribution/
// strongest are all pure DAO reads) so the MockEngine below is never even invoked.
import androidx.lifecycle.viewModelScope
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import app.cash.turbine.test
import com.yugma.terrawatch.data.QuakeRepository
import com.yugma.terrawatch.database.BandCount
import com.yugma.terrawatch.database.DayCount
import com.yugma.terrawatch.database.QuakeDao
import com.yugma.terrawatch.database.TerraWatchDb
import com.yugma.terrawatch.model.MagRevision
import com.yugma.terrawatch.model.MagnitudeBand
import com.yugma.terrawatch.model.Quake
import com.yugma.terrawatch.model.QuakeStatus
import com.yugma.terrawatch.model.Source
import com.yugma.terrawatch.network.EmscLiveSource
import com.yugma.terrawatch.network.UsgsApi
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
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
import kotlin.test.assertIs

private const val DAY = 86_400_000L

class InsightsViewModelTest {
    // Same leaked-coroutine/flake precedent as HistoryViewModelTest/HomeViewModelTest's own
    // createVm/tearDown (see those files' kdoc) - InsightsViewModel's two init{} collectors are not
    // children of any one test's runTest{} coroutine.
    private val createdViewModels = mutableListOf<InsightsViewModel>()

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

    // Plan 4 Task 5: engine is now an optional param (defaulted to the original fixed 404
    // responder) so the density-backfill tests below can supply a real FDSN /count responder
    // without disturbing any pre-existing call site.
    private fun repository(dao: QuakeDao, engine: MockEngine = MockEngine { respond("", HttpStatusCode.NotFound) }): QuakeRepository =
        QuakeRepository(UsgsApi(HttpClient(engine)), EmscLiveSource(HttpClient(engine)), dao, clock = { 1L })

    private fun createVm(
        dao: QuakeDao = freshDao(),
        nowMillis: Long = 100 * DAY,
        engine: MockEngine = MockEngine { respond("", HttpStatusCode.NotFound) },
    ): InsightsViewModel =
        InsightsViewModel(repository(dao, engine), clock = { nowMillis }).also { createdViewModels += it }

    private fun quake(id: String, timeMillis: Long, mag: Double?) = Quake(
        id = id, timeMillis = timeMillis, lat = 1.0, lon = 2.0, depthKm = 5.0, mag = mag,
        magType = if (mag != null) "mw" else null, place = "Test $id", tsunami = false, felt = null,
        status = QuakeStatus.AUTOMATIC, sources = mapOf(Source.USGS to id),
        revisions = if (mag != null) listOf(MagRevision(mag, "mw", timeMillis, Source.USGS)) else emptyList(),
        updatedAtMillis = timeMillis,
    )

    private suspend fun app.cash.turbine.ReceiveTurbine<InsightsUiState>.awaitPastLoading(): InsightsUiState {
        var s = awaitItem()
        while (s is InsightsUiState.Loading) s = awaitItem()
        return s
    }

    @Test fun `starts Loading then settles on Content for the default 7-day period`() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        val dao = freshDao()
        val now = 100 * DAY
        dao.upsert(quake("a", timeMillis = now, mag = 5.0))
        val vm = createVm(dao, nowMillis = now)
        vm.state.test {
            val content = assertIs<InsightsUiState.Content>(awaitPastLoading())
            assertEquals(7, content.dayCounts.size)
            assertEquals(1L, content.dayCounts.last(), "the last bucket is always 'today', by construction")
            assertEquals("a", content.strongest?.id)
            assertEquals(InsightsPeriod.SEVEN_DAYS.label, content.periodLabel)
            // Fix round (review I1): pins that Content actually carries the frozen "now" bucket it
            // was built with - InsightsScreen's dayCountLabels() depends on this being correct
            // (see that function's own kdoc for the drift bug this field exists to close).
            assertEquals(now / DAY_MILLIS, content.nowBucketAtCompute)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test fun `an empty database shows Empty, not Content`() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        val vm = createVm()
        vm.state.test {
            assertIs<InsightsUiState.Empty>(awaitPastLoading())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test fun `period flip to 30 days recomputes against the wider window`() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        val dao = freshDao()
        val now = 100 * DAY
        dao.upsert(quake("recent", timeMillis = now, mag = 5.0))
        dao.upsert(quake("old", timeMillis = now - 20 * DAY, mag = 6.0)) // outside 7d window, inside 30d
        val vm = createVm(dao, nowMillis = now)
        vm.state.test {
            val sevenDay = assertIs<InsightsUiState.Content>(awaitPastLoading())
            assertEquals(1L, sevenDay.dayCounts.sum())
            assertEquals("recent", sevenDay.strongest?.id)

            vm.setPeriod(InsightsPeriod.THIRTY_DAYS)

            // The period flip is a user action - it must show Loading on the way (same
            // "flip -> LoadingFirst -> new Content" contract HistoryViewModel.setFilter already
            // established for this codebase's other filter-like control).
            assertIs<InsightsUiState.Loading>(awaitItem())
            val thirtyDay = assertIs<InsightsUiState.Content>(awaitPastLoading())
            assertEquals(2L, thirtyDay.dayCounts.sum())
            assertEquals(30, thirtyDay.dayCounts.size)
            assertEquals(InsightsPeriod.THIRTY_DAYS.label, thirtyDay.periodLabel)
            assertEquals("old", thirtyDay.strongest?.id, "the M6.0 outranks the M5.0 once both are in-window")
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test fun `bands always include the four real bands even when some are zero`() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        val dao = freshDao()
        dao.upsert(quake("a", timeMillis = 100 * DAY, mag = 2.0)) // LOW only
        val vm = createVm(dao, nowMillis = 100 * DAY)
        vm.state.test {
            val content = assertIs<InsightsUiState.Content>(awaitPastLoading())
            assertEquals(
                listOf(MagnitudeBand.LOW, MagnitudeBand.MODERATE, MagnitudeBand.STRONG, MagnitudeBand.MAJOR),
                content.bands.map { it.first },
            )
            assertEquals(1L, content.bands.first { it.first == MagnitudeBand.LOW }.second)
            assertEquals(0L, content.bands.first { it.first == MagnitudeBand.MODERATE }.second)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test fun `a new quake arriving recomputes Content directly, with no interstitial Loading flash`() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        val dao = freshDao()
        val now = 100 * DAY
        val vm = createVm(dao, nowMillis = now)
        vm.state.test {
            assertIs<InsightsUiState.Empty>(awaitPastLoading())

            dao.upsert(quake("late", timeMillis = now, mag = 4.0))

            // Must land directly on the new Content - never pass back through Loading, unlike the
            // user-driven period flip above (mirrors HomeViewModel's own poll-tick behavior: new
            // data updates Content's numbers in place, it never re-shows a skeleton after the
            // first successful load).
            val content = assertIs<InsightsUiState.Content>(awaitItem())
            assertEquals(1L, content.dayCounts.sum())
            cancelAndIgnoreRemainingEvents()
        }
    }

    // Deliberately NOT a "retry recovers from a genuine Error" test: QuakeRepository's aggregate
    // reads are real, local SQLite calls with no fault-injection seam (unlike HistoryPager's own
    // retry tests, which can make MockEngine return an HTTP error on demand) - matching the
    // brief's own "Error unlikely [pure DB] but keep shape" framing, this only pins retry()'s
    // Loading-flash + recompute behavior from an ordinary Content state; InsightsUiState.Error
    // itself exists so the plan's global four-states rule is satisfied structurally (see
    // InsightsUiState.Error's own kdoc), not because this suite can cheaply force it to occur.
    @Test fun `retry re-flashes Loading and recomputes the current period`() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        val dao = freshDao()
        val now = 100 * DAY
        dao.upsert(quake("a", timeMillis = now, mag = 5.0))
        val vm = createVm(dao, nowMillis = now)
        vm.state.test {
            assertIs<InsightsUiState.Content>(awaitPastLoading())

            vm.retry()

            assertIs<InsightsUiState.Loading>(awaitItem())
            val content = assertIs<InsightsUiState.Content>(awaitPastLoading())
            assertEquals(1L, content.dayCounts.sum())
            cancelAndIgnoreRemainingEvents()
        }
    }
}

/**
 * Plan 4 Task 5: [InsightsViewModel]'s density-disclosure backfill (`worldwideCountIfThin`) — see
 * that function's own kdoc for the exact gate (THIRTY_DAYS period AND cachedCount < 100) and the
 * 6h meta-cache. A separate class (not folded into [InsightsViewModelTest] above) purely to keep
 * this feature's own test setup (custom MockEngine responders, cache pre-seeding) from cluttering
 * the existing suite's already-established helper shapes.
 */
class InsightsDensityBackfillTest {
    private val createdViewModels = mutableListOf<InsightsViewModel>()

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

    private fun repository(dao: QuakeDao, engine: MockEngine): QuakeRepository =
        QuakeRepository(UsgsApi(HttpClient(engine)), EmscLiveSource(HttpClient(engine)), dao, clock = { 1L })

    private fun createVm(repository: QuakeRepository, nowMillis: Long): InsightsViewModel =
        InsightsViewModel(repository, clock = { nowMillis }).also { createdViewModels += it }

    private fun quake(id: String, timeMillis: Long, mag: Double) = Quake(
        id = id, timeMillis = timeMillis, lat = 1.0, lon = 2.0, depthKm = 5.0, mag = mag,
        magType = "mw", place = "Test $id", tsunami = false, felt = null,
        status = QuakeStatus.AUTOMATIC, sources = mapOf(Source.USGS to id),
        revisions = listOf(MagRevision(mag, "mw", timeMillis, Source.USGS)), updatedAtMillis = timeMillis,
    )

    // Same shape as InsightsViewModelTest's own private awaitPastLoading() (not shared across
    // classes - member extension functions are class-private in this file's own convention).
    private suspend fun app.cash.turbine.ReceiveTurbine<InsightsUiState>.awaitPastLoading(): InsightsUiState {
        var s = awaitItem()
        while (s is InsightsUiState.Loading) s = awaitItem()
        return s
    }

    // Flips to THIRTY_DAYS from within the turbine block (never before subscribing) - the same
    // ordering InsightsViewModelTest's own "period flip to 30 days" test already uses, so the
    // Loading->Content transition this triggers can never race ahead of the test's own subscription.
    private suspend fun app.cash.turbine.ReceiveTurbine<InsightsUiState>.thirtyDayContent(vm: InsightsViewModel): InsightsUiState.Content {
        assertIs<InsightsUiState.Content>(awaitPastLoading()) // the default 7-day load
        vm.setPeriod(InsightsPeriod.THIRTY_DAYS)
        assertIs<InsightsUiState.Loading>(awaitItem())
        return assertIs<InsightsUiState.Content>(awaitPastLoading())
    }

    @Test fun `never backfills for the 7-day period, even with a thin cache`() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        val dao = freshDao()
        val now = 100 * DAY
        dao.upsert(quake("a", timeMillis = now, mag = 5.0))
        var called = false
        val vm = createVm(repository(dao, MockEngine { called = true; respond("""{"count":1}""", HttpStatusCode.OK) }), now)
        vm.state.test {
            val content = assertIs<InsightsUiState.Content>(awaitPastLoading())
            assertEquals(null, content.worldwideCount)
            cancelAndIgnoreRemainingEvents()
        }
        assertEquals(false, called, "the 7-day period must never trigger a density backfill call")
    }

    @Test fun `never backfills for THIRTY_DAYS once the cache already has 100+ rows`() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        val dao = freshDao()
        val now = 100 * DAY
        repeat(100) { i -> dao.upsert(quake("q$i", timeMillis = now - i * 1000L, mag = 4.0)) }
        var called = false
        val vm = createVm(repository(dao, MockEngine { called = true; respond("""{"count":1}""", HttpStatusCode.OK) }), now)
        vm.state.test {
            val thirtyDay = thirtyDayContent(vm)
            assertEquals(100L, thirtyDay.dayCounts.sum())
            assertEquals(null, thirtyDay.worldwideCount)
            cancelAndIgnoreRemainingEvents()
        }
        assertEquals(false, called, "a healthy (>=100 row) cache must never trigger a density backfill call")
    }

    @Test fun `backfills and populates worldwideCount when THIRTY_DAYS cache is thin`() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        val dao = freshDao()
        val now = 100 * DAY
        dao.upsert(quake("a", timeMillis = now, mag = 5.0))
        val vm = createVm(repository(dao, MockEngine { respond("""{"count":11082,"maxAllowed":20000}""", HttpStatusCode.OK) }), now)
        vm.state.test {
            val thirtyDay = thirtyDayContent(vm)
            assertEquals(11_082L, thirtyDay.worldwideCount)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test fun `a failed fetch with nothing cached leaves worldwideCount null`() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        val dao = freshDao()
        val now = 100 * DAY
        dao.upsert(quake("a", timeMillis = now, mag = 5.0))
        val vm = createVm(repository(dao, MockEngine { respond("boom", HttpStatusCode.InternalServerError) }), now)
        vm.state.test {
            val thirtyDay = thirtyDayContent(vm)
            assertEquals(null, thirtyDay.worldwideCount)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test fun `a fresh cached count is reused without a new network call`() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        val dao = freshDao()
        val now = 100 * DAY
        dao.upsert(quake("a", timeMillis = now, mag = 5.0))
        var callCount = 0
        val engine = MockEngine { callCount++; respond("""{"count":99999,"maxAllowed":20000}""", HttpStatusCode.OK) }
        val repo = repository(dao, engine)
        repo.setWorldwideCountCache(count = 555, fetchedAtMillis = now - 60 * 60 * 1000L) // 1h old, well within 6h TTL
        val vm = createVm(repo, now)
        vm.state.test {
            val thirtyDay = thirtyDayContent(vm)
            assertEquals(555L, thirtyDay.worldwideCount)
            cancelAndIgnoreRemainingEvents()
        }
        assertEquals(0, callCount, "a fresh (< 6h) cache hit must never touch the network")
    }

    @Test fun `a stale (6h+) cached count triggers a fresh fetch instead of being reused`() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        val dao = freshDao()
        val now = 100 * DAY
        dao.upsert(quake("a", timeMillis = now, mag = 5.0))
        val engine = MockEngine { respond("""{"count":77,"maxAllowed":20000}""", HttpStatusCode.OK) }
        val repo = repository(dao, engine)
        repo.setWorldwideCountCache(count = 555, fetchedAtMillis = now - 7 * 60 * 60 * 1000L) // 7h old, past the 6h TTL
        val vm = createVm(repo, now)
        vm.state.test {
            val thirtyDay = thirtyDayContent(vm)
            assertEquals(77L, thirtyDay.worldwideCount, "a stale cache must be refreshed, not served as-is")
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test fun `a failed fetch falls back to a stale cached value rather than null`() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        val dao = freshDao()
        val now = 100 * DAY
        dao.upsert(quake("a", timeMillis = now, mag = 5.0))
        val engine = MockEngine { respond("boom", HttpStatusCode.InternalServerError) }
        val repo = repository(dao, engine)
        repo.setWorldwideCountCache(count = 555, fetchedAtMillis = now - 7 * 60 * 60 * 1000L) // stale, forces a re-fetch attempt
        val vm = createVm(repo, now)
        vm.state.test {
            val thirtyDay = thirtyDayContent(vm)
            assertEquals(555L, thirtyDay.worldwideCount, "a failed refresh should fall back to the stale value, not drop to null")
            cancelAndIgnoreRemainingEvents()
        }
    }
}

class FillDayGapsTest {
    @Test fun `fills every bucket in range with zero when nothing was loaded`() {
        assertEquals(listOf(0L, 0L, 0L), fillDayGaps(emptyList(), sinceBucket = 5, nowBucket = 7))
    }

    @Test fun `keeps a loaded count at its own bucket and zero-fills the rest`() {
        assertEquals(
            listOf(0L, 4L, 0L),
            fillDayGaps(listOf(DayCount(dayBucket = 6, n = 4)), sinceBucket = 5, nowBucket = 7),
        )
    }

    @Test fun `a single-day range returns exactly one bucket`() {
        assertEquals(listOf(2L), fillDayGaps(listOf(DayCount(5, 2)), sinceBucket = 5, nowBucket = 5))
    }

    @Test fun `buckets outside the requested range are ignored`() {
        assertEquals(
            listOf(3L),
            fillDayGaps(listOf(DayCount(4, 99), DayCount(5, 3), DayCount(6, 99)), sinceBucket = 5, nowBucket = 5),
        )
    }
}

class FillBandGapsTest {
    @Test fun `zero-fills all four real bands when the db has none of them`() {
        assertEquals(
            listOf(
                MagnitudeBand.LOW to 0L, MagnitudeBand.MODERATE to 0L,
                MagnitudeBand.STRONG to 0L, MagnitudeBand.MAJOR to 0L,
            ),
            fillBandGaps(emptyList()),
        )
    }

    @Test fun `keeps a loaded band's real count instead of zeroing it`() {
        val result = fillBandGaps(listOf(BandCount(MagnitudeBand.MAJOR, 3)))
        assertEquals(3L, result.first { it.first == MagnitudeBand.MAJOR }.second)
    }

    @Test fun `UNKNOWN is appended only when its own count is nonzero`() {
        assertEquals(4, fillBandGaps(emptyList()).size, "no UNKNOWN row when it would be zero")
        val withUnknown = fillBandGaps(listOf(BandCount(MagnitudeBand.UNKNOWN, 3)))
        assertEquals(5, withUnknown.size)
        assertEquals(MagnitudeBand.UNKNOWN to 3L, withUnknown.last())
    }
}
