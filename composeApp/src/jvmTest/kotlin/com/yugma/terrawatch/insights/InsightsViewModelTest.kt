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
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
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
import kotlin.time.Duration.Companion.seconds

private const val DAY = 86_400_000L

class InsightsViewModelTest {
    // Same leaked-coroutine/flake precedent as HistoryViewModelTest/HomeViewModelTest's own
    // createVm/tearDown (see those files' kdoc) - InsightsViewModel's two init{} collectors are not
    // children of any one test's runTest{} coroutine.
    //
    // Flake-hardening pass (2026-08-16, CI run 31938226287 -- AssertionError on `period flip to 30
    // days recomputes against the wider window`, always green locally): the gap this pass closes is
    // NOT this teardown -- it was already correct -- but [repository]/[createVm] below never pinned
    // QuakeRepository's `ioDispatcher`, unlike [InsightsDensityBackfillTest] just below in this same
    // file, which already ports exactly this fix (see that class's own kdoc for the original
    // reasoning: "a real Dispatchers.Default hop races this test's own assertions on an uncontrolled
    // thread"). Left unpinned, every `computeContent()` call (on VM construction AND on every
    // [InsightsViewModel.setPeriod]/`retry()`) sends `quakesPerDay`/`bandDistribution`/`strongest`
    // each through their own real, independent `Dispatchers.Default` hop -- three uncoordinated
    // cross-thread round trips per recompute, on a dispatcher this test cannot schedule. On a
    // starved CI runner that is enough real-thread nondeterminism to let `period flip...`'s
    // `assertIs<Loading>(awaitItem())` observe something other than the expected shape. Every test
    // below now builds its OWN `UnconfinedTestDispatcher` and threads it through as `ioDispatcher`
    // (same instance backing `Dispatchers.Main`), collapsing those hops back onto the one scheduler
    // every assertion here already depends on -- a structural fix (removes the actual cross-thread
    // race), not a timing one. `test(timeout = 30.seconds)` is added on top as a second, independent
    // line of defense: [InsightsViewModel]'s SECOND init{} collector
    // (`repository.recentQuakes().drop(1).conflate()...`) is backed by `QuakeDao.recent()`, which
    // hard-codes `.mapToList(Dispatchers.Default)` regardless of `ioDispatcher` -- un-pinnable by
    // construction, same trap HomeViewModelTest's own poll-loop test documents (commit 5e9e922).
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
    //
    // Flake hardening: [ioDispatcher] is a NEW param, defaulted to the real Dispatchers.Default this
    // class always used implicitly before this fix (so it still compiles for any future caller that
    // forgets to pin it) -- every test in this class now passes its own UnconfinedTestDispatcher
    // explicitly instead. See this class's own kdoc above for the full "why".
    private fun repository(
        dao: QuakeDao,
        engine: MockEngine = MockEngine { respond("", HttpStatusCode.NotFound) },
        ioDispatcher: CoroutineDispatcher = Dispatchers.Default,
    ): QuakeRepository =
        QuakeRepository(UsgsApi(HttpClient(engine)), EmscLiveSource(HttpClient(engine)), dao, clock = { 1L }, ioDispatcher = ioDispatcher)

    private fun createVm(
        dao: QuakeDao = freshDao(),
        nowMillis: Long = 100 * DAY,
        engine: MockEngine = MockEngine { respond("", HttpStatusCode.NotFound) },
        ioDispatcher: CoroutineDispatcher = Dispatchers.Default,
    ): InsightsViewModel =
        InsightsViewModel(repository(dao, engine, ioDispatcher), clock = { nowMillis }).also { createdViewModels += it }

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
        val testDispatcher = UnconfinedTestDispatcher()
        Dispatchers.setMain(testDispatcher)
        val dao = freshDao()
        val now = 100 * DAY
        dao.upsert(quake("a", timeMillis = now, mag = 5.0))
        val vm = createVm(dao, nowMillis = now, ioDispatcher = testDispatcher)
        vm.state.test(timeout = 30.seconds) {
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
        val testDispatcher = UnconfinedTestDispatcher()
        Dispatchers.setMain(testDispatcher)
        val vm = createVm(ioDispatcher = testDispatcher)
        vm.state.test(timeout = 30.seconds) {
            assertIs<InsightsUiState.Empty>(awaitPastLoading())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test fun `period flip to 30 days recomputes against the wider window`() = runTest {
        val testDispatcher = UnconfinedTestDispatcher()
        Dispatchers.setMain(testDispatcher)
        val dao = freshDao()
        val now = 100 * DAY
        dao.upsert(quake("recent", timeMillis = now, mag = 5.0))
        dao.upsert(quake("old", timeMillis = now - 20 * DAY, mag = 6.0)) // outside 7d window, inside 30d
        val vm = createVm(dao, nowMillis = now, ioDispatcher = testDispatcher)
        vm.state.test(timeout = 30.seconds) {
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
        val testDispatcher = UnconfinedTestDispatcher()
        Dispatchers.setMain(testDispatcher)
        val dao = freshDao()
        dao.upsert(quake("a", timeMillis = 100 * DAY, mag = 2.0)) // LOW only
        val vm = createVm(dao, nowMillis = 100 * DAY, ioDispatcher = testDispatcher)
        vm.state.test(timeout = 30.seconds) {
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

    // Flake hardening: unlike the other tests in this class, this one still crosses a genuinely
    // un-pinnable pool even with ioDispatcher fixed above -- the `dao.upsert` below feeds
    // InsightsViewModel's SECOND init{} collector, which is backed by QuakeDao.recent()'s
    // hard-coded Dispatchers.Default (see this class's own kdoc). timeout = 30.seconds is the
    // operative fix for this specific test.
    @Test fun `a new quake arriving recomputes Content directly, with no interstitial Loading flash`() = runTest {
        val testDispatcher = UnconfinedTestDispatcher()
        Dispatchers.setMain(testDispatcher)
        val dao = freshDao()
        val now = 100 * DAY
        val vm = createVm(dao, nowMillis = now, ioDispatcher = testDispatcher)
        vm.state.test(timeout = 30.seconds) {
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
        val testDispatcher = UnconfinedTestDispatcher()
        Dispatchers.setMain(testDispatcher)
        val dao = freshDao()
        val now = 100 * DAY
        dao.upsert(quake("a", timeMillis = now, mag = 5.0))
        val vm = createVm(dao, nowMillis = now, ioDispatcher = testDispatcher)
        vm.state.test(timeout = 30.seconds) {
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
 *
 * Fix round (Plan 4 Task 5 review, Critical): every thin-30d-cache scenario below now expects TWO
 * state emissions, not one - [InsightsUiState.Content] publishes with `worldwideCount = null` first
 * (the three core cards, instantly), then a SEPARATE, later emission patches in the real count once
 * `InsightsViewModel.scheduleWorldwideBackfill`'s own coroutine resolves. This mirrors the
 * ViewModel's own publish-then-patch fix - see its class-level kdoc for the bug this closes (the
 * backfill used to block the whole screen, including a period flip mid-fetch).
 *
 * [repository]'s `ioDispatcher` param is pinned to the SAME [UnconfinedTestDispatcher] instance
 * backing `Dispatchers.Main` in every test below (not just the two new ones that strictly require
 * it, for consistency) - same "a real Dispatchers.Default hop races this test's own assertions on
 * an uncontrolled thread" reasoning `HomeViewModelTest`'s own poll-loop/prune tests already
 * document for this identical pin - so the backfill's own DAO/network hops resolve
 * deterministically, inline, on the one scheduler every assertion here already depends on. This
 * matters most for `expectNoEvents()` (a non-suspending, checks-right-now assertion - see Turbine's
 * own kdoc on it): without the pin, a stale 30d fetch resolving on a real background thread could
 * still be in flight the instant that check runs, making the assertion pass for the wrong reason.
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

    private fun repository(dao: QuakeDao, engine: MockEngine, ioDispatcher: CoroutineDispatcher): QuakeRepository =
        QuakeRepository(UsgsApi(HttpClient(engine)), EmscLiveSource(HttpClient(engine)), dao, clock = { 1L }, ioDispatcher = ioDispatcher)

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
        val testDispatcher = UnconfinedTestDispatcher()
        Dispatchers.setMain(testDispatcher)
        val dao = freshDao()
        val now = 100 * DAY
        dao.upsert(quake("a", timeMillis = now, mag = 5.0))
        var called = false
        val engine = MockEngine { called = true; respond("""{"count":1}""", HttpStatusCode.OK) }
        val vm = createVm(repository(dao, engine, testDispatcher), now)
        vm.state.test {
            val content = assertIs<InsightsUiState.Content>(awaitPastLoading())
            assertEquals(null, content.worldwideCount)
            cancelAndIgnoreRemainingEvents()
        }
        assertEquals(false, called, "the 7-day period must never trigger a density backfill call")
    }

    @Test fun `never backfills for THIRTY_DAYS once the cache already has 100+ rows`() = runTest {
        val testDispatcher = UnconfinedTestDispatcher()
        Dispatchers.setMain(testDispatcher)
        val dao = freshDao()
        val now = 100 * DAY
        repeat(100) { i -> dao.upsert(quake("q$i", timeMillis = now - i * 1000L, mag = 4.0)) }
        var called = false
        val engine = MockEngine { called = true; respond("""{"count":1}""", HttpStatusCode.OK) }
        val vm = createVm(repository(dao, engine, testDispatcher), now)
        vm.state.test {
            val thirtyDay = thirtyDayContent(vm)
            assertEquals(100L, thirtyDay.dayCounts.sum())
            assertEquals(null, thirtyDay.worldwideCount)
            cancelAndIgnoreRemainingEvents()
        }
        assertEquals(false, called, "a healthy (>=100 row) cache must never trigger a density backfill call")
    }

    // Fix round (Plan 4 Task 5 review, Critical), TDD case 1 - the whole point of publish-then-patch:
    // proves the three core cards' Content is NOT held hostage by the backfill fetch, by gating that
    // fetch on a CompletableDeferred this test controls directly. Same "a real suspension point, not
    // virtual time" gate `HomeViewModelTest`'s own "cached pins render immediately, before the
    // pending network refresh resolves" test already established for the identical class of claim.
    @Test fun `Content publishes with worldwideCount null before the backfill fetch ever resolves`() = runTest {
        val testDispatcher = UnconfinedTestDispatcher()
        Dispatchers.setMain(testDispatcher)
        val dao = freshDao()
        val now = 100 * DAY
        dao.upsert(quake("a", timeMillis = now, mag = 5.0))
        val gate = CompletableDeferred<Unit>()
        val engine = MockEngine { gate.await(); respond("""{"count":11082,"maxAllowed":20000}""", HttpStatusCode.OK) }
        val vm = createVm(repository(dao, engine, testDispatcher), now)
        vm.state.test {
            val published = thirtyDayContent(vm)
            assertEquals(null, published.worldwideCount, "the 3 core cards must publish before a gated backfill fetch ever resolves")

            gate.complete(Unit) // now let the parked fetch resolve

            val patched = assertIs<InsightsUiState.Content>(awaitItem())
            assertEquals(11_082L, patched.worldwideCount)
            cancelAndIgnoreRemainingEvents()
        }
    }

    // Fix round (Plan 4 Task 5 review, Critical), TDD case 2 - the OTHER half of the bug this fix
    // closes: the old code's single _period.collect coroutine sat suspended inside the inline
    // backfill call, so a tap back to 7d had to wait behind an in-flight 30d fetch (StateFlow
    // conflation - the collector can't see the newer period until it finishes the current
    // iteration). Proves both halves at once: the flip itself is never blocked (it settles on its
    // own Content while the gate is still parked - if it WERE still blocked, the awaitItem() call
    // right after setPeriod would hang forever, since `gate` isn't completed until later), AND the
    // eventually-resolved 30d count is never patched onto the 7d Content that superseded it
    // (computeGeneration moved on the instant the flip started).
    @Test fun `a period flip mid-backfill means the stale count is never patched onto the new period's Content`() = runTest {
        val testDispatcher = UnconfinedTestDispatcher()
        Dispatchers.setMain(testDispatcher)
        val dao = freshDao()
        val now = 100 * DAY
        dao.upsert(quake("a", timeMillis = now, mag = 5.0)) // thin on both the 7d and 30d windows
        val gate = CompletableDeferred<Unit>()
        val engine = MockEngine { gate.await(); respond("""{"count":11082,"maxAllowed":20000}""", HttpStatusCode.OK) }
        val vm = createVm(repository(dao, engine, testDispatcher), now)
        vm.state.test {
            val thirtyDay = thirtyDayContent(vm)
            assertEquals(null, thirtyDay.worldwideCount, "backfill is scheduled but parked on `gate`, mid-flight")

            vm.setPeriod(InsightsPeriod.SEVEN_DAYS) // flips away WHILE the 30d backfill is still in flight

            assertIs<InsightsUiState.Loading>(awaitItem())
            val sevenDay = assertIs<InsightsUiState.Content>(awaitPastLoading())
            assertEquals(InsightsPeriod.SEVEN_DAYS.label, sevenDay.periodLabel)
            assertEquals(null, sevenDay.worldwideCount, "7d never backfills at all")

            gate.complete(Unit) // let the now-superseded 30d fetch resolve

            // Its count must never land on the (different-period, different-generation) Content
            // above - scheduleWorldwideBackfill's own gen check drops this result silently.
            expectNoEvents()
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test fun `backfills and populates worldwideCount when THIRTY_DAYS cache is thin`() = runTest {
        val testDispatcher = UnconfinedTestDispatcher()
        Dispatchers.setMain(testDispatcher)
        val dao = freshDao()
        val now = 100 * DAY
        dao.upsert(quake("a", timeMillis = now, mag = 5.0))
        val engine = MockEngine { respond("""{"count":11082,"maxAllowed":20000}""", HttpStatusCode.OK) }
        val vm = createVm(repository(dao, engine, testDispatcher), now)
        vm.state.test {
            val published = thirtyDayContent(vm)
            assertEquals(null, published.worldwideCount, "publishes before the backfill resolves - see the dedicated ordering test above")
            val patched = assertIs<InsightsUiState.Content>(awaitItem())
            assertEquals(11_082L, patched.worldwideCount)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test fun `a failed fetch with nothing cached leaves worldwideCount null`() = runTest {
        val testDispatcher = UnconfinedTestDispatcher()
        Dispatchers.setMain(testDispatcher)
        val dao = freshDao()
        val now = 100 * DAY
        dao.upsert(quake("a", timeMillis = now, mag = 5.0))
        // Channel-based rendezvous (same pattern HomeViewModelTest's own "retryNow ignores a re-tap"
        // test uses) - deterministic proof the fetch was actually attempted (and failed), never just
        // silently skipped, before asserting nothing patches.
        val callStarted = Channel<Unit>(Channel.UNLIMITED)
        val engine = MockEngine { callStarted.trySend(Unit); respond("boom", HttpStatusCode.InternalServerError) }
        val vm = createVm(repository(dao, engine, testDispatcher), now)
        vm.state.test {
            val published = thirtyDayContent(vm)
            assertEquals(null, published.worldwideCount)
            callStarted.receive() // the (failing) fetch really was attempted...
            expectNoEvents() // ...and its failure never patches - Content stays exactly as first published
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test fun `a fresh cached count is reused without a new network call`() = runTest {
        val testDispatcher = UnconfinedTestDispatcher()
        Dispatchers.setMain(testDispatcher)
        val dao = freshDao()
        val now = 100 * DAY
        dao.upsert(quake("a", timeMillis = now, mag = 5.0))
        var callCount = 0
        val engine = MockEngine { callCount++; respond("""{"count":99999,"maxAllowed":20000}""", HttpStatusCode.OK) }
        val repo = repository(dao, engine, testDispatcher)
        repo.setWorldwideCountCache(count = 555, fetchedAtMillis = now - 60 * 60 * 1000L) // 1h old, well within 6h TTL
        val vm = createVm(repo, now)
        vm.state.test {
            val published = thirtyDayContent(vm)
            assertEquals(null, published.worldwideCount)
            val patched = assertIs<InsightsUiState.Content>(awaitItem())
            assertEquals(555L, patched.worldwideCount)
            cancelAndIgnoreRemainingEvents()
        }
        assertEquals(0, callCount, "a fresh (< 6h) cache hit must never touch the network")
    }

    @Test fun `a stale (6h+) cached count triggers a fresh fetch instead of being reused`() = runTest {
        val testDispatcher = UnconfinedTestDispatcher()
        Dispatchers.setMain(testDispatcher)
        val dao = freshDao()
        val now = 100 * DAY
        dao.upsert(quake("a", timeMillis = now, mag = 5.0))
        val engine = MockEngine { respond("""{"count":77,"maxAllowed":20000}""", HttpStatusCode.OK) }
        val repo = repository(dao, engine, testDispatcher)
        repo.setWorldwideCountCache(count = 555, fetchedAtMillis = now - 7 * 60 * 60 * 1000L) // 7h old, past the 6h TTL
        val vm = createVm(repo, now)
        vm.state.test {
            val published = thirtyDayContent(vm)
            assertEquals(null, published.worldwideCount)
            val patched = assertIs<InsightsUiState.Content>(awaitItem())
            assertEquals(77L, patched.worldwideCount, "a stale cache must be refreshed, not served as-is")
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test fun `a failed fetch falls back to a stale cached value rather than null`() = runTest {
        val testDispatcher = UnconfinedTestDispatcher()
        Dispatchers.setMain(testDispatcher)
        val dao = freshDao()
        val now = 100 * DAY
        dao.upsert(quake("a", timeMillis = now, mag = 5.0))
        val engine = MockEngine { respond("boom", HttpStatusCode.InternalServerError) }
        val repo = repository(dao, engine, testDispatcher)
        repo.setWorldwideCountCache(count = 555, fetchedAtMillis = now - 7 * 60 * 60 * 1000L) // stale, forces a re-fetch attempt
        val vm = createVm(repo, now)
        vm.state.test {
            val published = thirtyDayContent(vm)
            assertEquals(null, published.worldwideCount)
            val patched = assertIs<InsightsUiState.Content>(awaitItem())
            assertEquals(555L, patched.worldwideCount, "a failed refresh should fall back to the stale value, not drop to null")
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
