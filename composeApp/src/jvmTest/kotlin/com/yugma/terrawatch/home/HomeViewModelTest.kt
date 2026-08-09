package com.yugma.terrawatch.home

// Same jvmTest-not-commonTest rationale as FeedViewModelTest: fakeRepositoryWithOneQuake() builds a
// real QuakeRepository over app.cash.sqldelight's JDBC in-memory driver, a JVM-only artifact.
import androidx.lifecycle.viewModelScope
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import app.cash.turbine.test
import com.yugma.terrawatch.data.HomeLocationStore
import com.yugma.terrawatch.data.QuakeRepository
import com.yugma.terrawatch.database.QuakeDao
import com.yugma.terrawatch.database.TerraWatchDb
import com.yugma.terrawatch.location.LocationProvider
import com.yugma.terrawatch.model.GeoPoint
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
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.job
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

// Distinguishable from QuakeRepository's own `clock` (windowing clock, kept at the existing
// suite's usual 2_000_000L) so the "lastUpdatedMillis populated" test asserts a specific value
// flows all the way from QuakeDao's write-time clock through the repository into UI state, not
// merely "happens to be non-null".
private const val FETCH_CLOCK_MILLIS = 5_000_000L

class HomeViewModelTest {
    // Task 13 flake fix (carried in from Task 12 review: "~8% jvmTest flake — HomeViewModelTest
    // leaks viewModelScope collectors, Dispatchers.resetMain race"). Root cause: every HomeViewModel
    // built below launches several viewModelScope coroutines in init{} (the refresh loop, the
    // cache-driven collector, repository.startLive()'s forever-retrying delay() loop, ...) — none of
    // that is a child of this test's runTest{} coroutine, so cancelAndIgnoreRemainingEvents()/runTest
    // completing never cancels it. The old tearDown only called Dispatchers.resetMain(), which
    // swaps out the global Main dispatcher out from under those still-running coroutines without
    // stopping them; a leaked coroutine that then tries to hop back onto Dispatchers.Main (e.g. the
    // next delay() in startLive()'s retry loop) can hit a torn-down/mismatched Main dispatcher from
    // a DIFFERENT test's setMain() call, an inherently racy cross-test interaction — that race is the
    // ~8% flake.
    //
    // Fix: every HomeViewModel this suite builds now goes through [createVm], which tracks the
    // instance so tearDown can cancel each one's viewModelScope — stopping every coroutine it ever
    // launched, including the forever-retrying ones, instead of leaking it into later tests.
    //
    // ORDER MATTERS, and got it wrong on the first attempt (caught by actually running this 10x, not
    // by inspection — see task-13-report.md): cancelling BEFORE Dispatchers.resetMain() intermittently
    // threw `CompletionHandlerException` / `UnsupportedOperationException: Function
    // UnconfinedTestCoroutineDispatcher.dispatch can only be used by the yield function`, and in one
    // observed run left a Gradle test worker spinning at 100% CPU. Cause: HomeViewModel's state
    // collector does `combine(repository.recentQuakes().map{...}.flowOn(Dispatchers.Default), ...)`
    // inside a `viewModelScope.launch { ... }` — i.e. a Main.immediate-scoped parent with a
    // Default-dispatched child. Cancelling while `Dispatchers.Main` is still THIS test's
    // `UnconfinedTestDispatcher` forces that child's cancellation-completion to hop back onto Main
    // from a Default-pool thread; `UnconfinedTestDispatcher.dispatch()` deliberately throws unless
    // called through its own `yield()`-driven path, which a genuine cross-thread dispatch is not.
    // Calling `Dispatchers.resetMain()` FIRST sidesteps this: by the time `cancel()`'s cascading
    // completions try to dispatch onto `Dispatchers.Main`, Main has already reverted to the real
    // dispatcher `kotlinx-coroutines-swing` registers (composeApp's jvmMain depends on
    // `libs.coroutines.swing` for the desktop target; jvmTest inherits it) — an ordinary
    // `SwingDispatcher` that accepts posts from any thread, no reentrancy assertion to trip.
    //
    // Fix Round 1 (review finding, "close teardown race structurally"): the reorder above fixed the
    // crash/hang symptom, but plain `.cancel()` only REQUESTS cancellation — it returns immediately
    // without waiting for the cascading cancellation to actually finish draining (finally blocks,
    // the forever-retrying startLive() delay() loop unwinding, the flowOn(Dispatchers.Default)
    // child's completion hopping back onto Main, ...). tearDown() could therefore return, and the
    // next test's @Test method call Dispatchers.setMain(UnconfinedTestDispatcher()) again, while the
    // PREVIOUS test's viewModelScope was still mid-unwind — the same class of cross-test race that
    // caused the original ~8% flake, just narrowed rather than closed. `cancelAndJoin()` (needs a
    // suspend context, hence the `runBlocking` wrapper — JUnit's `@AfterTest` is not itself
    // suspending) blocks tearDown() until each tracked VM's job, and everything structurally under
    // it, has fully completed cancelling before the next test can start. Structural fix, not a
    // timing one: correctness no longer depends on cancellation happening to finish fast enough
    // between tests.
    private val createdViewModels = mutableListOf<HomeViewModel>()

    private fun createVm(
        repository: QuakeRepository,
        homeLocationStore: HomeLocationStore = emptyHomeLocationStore(),
        locationProvider: LocationProvider = LocationProvider(),
    ): HomeViewModel = HomeViewModel(repository, homeLocationStore, locationProvider).also { createdViewModels += it }

    @AfterTest fun tearDown() {
        Dispatchers.resetMain()
        runBlocking { createdViewModels.forEach { it.viewModelScope.coroutineContext.job.cancelAndJoin() } }
        createdViewModels.clear()
    }

    // Fix Round 2 (review finding): HomeViewModel's cache-driven collection now starts before
    // refreshFeed() resolves (see the "cached pins render..." test below) — on the fresh,
    // never-seeded DB these fakes use, that means a legitimate transient Content(quakes=empty) /
    // Content(lastUpdatedMillis=null) / Content(pins=empty) can land BEFORE the
    // network-sourced/failure-flagged one this test (and the three below it) actually cares about.
    // Every while-loop in this file that used to only skip Loading now also skips that one
    // specific transient shape, so these tests assert on the settled state, not an interim one.
    @Test fun `loads feed into content state`() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        val vm = createVm(fakeRepositoryWithOneQuake())
        vm.state.test {
            var s = awaitItem()
            while (s is HomeUiState.Loading || (s is HomeUiState.Content && s.quakes.isEmpty())) {
                s = awaitItem()
            }
            val content = assertIs<HomeUiState.Content>(s)
            assertTrue(content.quakes.isNotEmpty())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test fun `refreshFailed is true when the initial refresh fails`() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        val vm = createVm(fakeRepositoryAlwaysFailing())
        vm.state.test {
            var s = awaitItem()
            while (s is HomeUiState.Loading || (s is HomeUiState.Content && !s.refreshFailed)) {
                s = awaitItem()
            }
            val content = assertIs<HomeUiState.Content>(s)
            assertTrue(content.refreshFailed)
            // No Error terminal state on Home (unlike Feed) — a failed refresh on an empty cache
            // still settles on Content, just with the banner flag set and an empty pin list.
            assertTrue(content.pins.isEmpty())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test fun `pins carry the id, magnitude and band of their quake`() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        val vm = createVm(fakeRepositoryWithOneQuake())
        vm.state.test {
            var s = awaitItem()
            while (s is HomeUiState.Loading || (s is HomeUiState.Content && s.pins.isEmpty())) {
                s = awaitItem()
            }
            val content = assertIs<HomeUiState.Content>(s)
            val pin = content.pins.single()
            assertEquals("us1234", pin.id)
            assertEquals(5.5, pin.mag)
            assertEquals(MagnitudeBand.STRONG, pin.band) // 4.5 <= 5.5 < 6.0
            cancelAndIgnoreRemainingEvents()
        }
    }

    // Task 10: isLive now binds to the repository's real WS connection state
    // (QuakeRepository.liveConnected -> EmscLiveSource.connected) instead of the old hardcoded
    // `isLive = true` placeholder. NOTE on this task's brief: it asked to "update the existing
    // HomeViewModelTest assertions that expect isLive==true" — grepped this file and the rest of
    // composeApp/src before writing this test; no such assertion existed anywhere (the placeholder
    // was never actually asserted on by name in this suite), so there is nothing to flip — this is
    // a purely additive test. fakeRepositoryWithOneQuake() builds its EmscLiveSource over a
    // MockEngine with no WebSockets plugin installed, so http.webSocket(...) can never actually
    // open a session; liveConnected — and therefore isLive — must read false here. This is the
    // honest, corrected behavior: a repository with no real WebSocket has no business claiming to
    // be live.
    @Test fun `isLive is false when the repository's WebSocket never actually connects`() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        val vm = createVm(fakeRepositoryWithOneQuake())
        vm.state.test {
            var s = awaitItem()
            while (s is HomeUiState.Loading || (s is HomeUiState.Content && s.quakes.isEmpty())) {
                s = awaitItem()
            }
            val content = assertIs<HomeUiState.Content>(s)
            assertFalse(content.isLive)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test fun `lastUpdatedMillis is populated from the repository's fetch clock`() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        val vm = createVm(fakeRepositoryWithOneQuake())
        vm.state.test {
            var s = awaitItem()
            while (s is HomeUiState.Loading || (s is HomeUiState.Content && s.lastUpdatedMillis == null)) {
                s = awaitItem()
            }
            val content = assertIs<HomeUiState.Content>(s)
            assertEquals(FETCH_CLOCK_MILLIS, content.lastUpdatedMillis)
            cancelAndIgnoreRemainingEvents()
        }
    }

    // Fix Round 2, blocking fix 1 (review finding): refreshFailed used to be a `val status`
    // captured once from the initial refreshFeed() call and baked into every future Content
    // forever -- once true, always true, even after a later update proved data was flowing again.
    // Red (pre-fix): times out waiting for a Content with refreshFailed == false, because the old
    // code has no path that ever produces one once the initial refresh has failed.
    @Test fun `refreshFailed clears once a new quake proves data is flowing again`() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        val repository = fakeRepositoryAlwaysFailing()
        val vm = createVm(repository)
        vm.state.test {
            var s = awaitItem()
            while (s is HomeUiState.Loading || (s is HomeUiState.Content && !s.refreshFailed)) {
                s = awaitItem()
            }
            val failed = assertIs<HomeUiState.Content>(s)
            assertTrue(failed.refreshFailed)

            // Ingested directly on the repository -- bypasses refreshFeed() entirely, exactly like
            // a live-WebSocket-sourced quake would. previous == null for this brand-new id, so this
            // is exactly the insertedQuakeIds-emitting path HomeViewModel's clearing logic keys off.
            repository.ingest(freshQuake("live1"))

            var s2 = awaitItem()
            while (s2 is HomeUiState.Content && s2.refreshFailed) s2 = awaitItem()
            val recovered = assertIs<HomeUiState.Content>(s2)
            assertFalse(recovered.refreshFailed)
            cancelAndIgnoreRemainingEvents()
        }
    }

    // Fix Round 2, blocking fix 2 (review finding): the cache-driven recentQuakes() collection
    // used to run in the same coroutine as, and strictly after, the refreshFeed() network call --
    // so a pre-seeded local cache wouldn't paint until the network round-trip resolved, one way or
    // the other. Red (pre-fix): times out -- state never leaves Loading, because the old code's one
    // and only launch is permanently parked on `gate.await()` inside refreshFeed() and never even
    // reaches `repository.recentQuakes().collect { ... }`.
    //
    // The gate is a real suspension point (CompletableDeferred), not a virtual-time delay(): this
    // sidesteps needing to coordinate two different TestDispatcher schedulers (Main is
    // UnconfinedTestDispatcher() with its own scheduler; ioDispatcher defaults to a real
    // Dispatchers.Default) just to keep refreshFeed() from resolving -- the test never calls
    // advanceUntilIdle()/advanceTimeBy() at all, so there's nothing to accidentally advance past.
    @Test fun `cached pins render immediately, before the pending network refresh resolves`() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        val gate = CompletableDeferred<Unit>()
        val vm = createVm(fakeRepositorySeededWithOneQuake(gate))
        vm.state.test {
            var s = awaitItem()
            while (s is HomeUiState.Loading) s = awaitItem()
            val content = assertIs<HomeUiState.Content>(s)
            assertEquals(1, content.pins.size)
            assertEquals("seed1", content.pins.single().id)
            cancelAndIgnoreRemainingEvents()
        }
        gate.complete(Unit) // let the pending refresh finish so it doesn't leak into later tests
    }

    // Task 1 (Plan 3): the one-shot refresh becomes a poll loop with honest failure recovery —
    // this closes the Plan 2 entry-conditions debt ("refreshFeed() can throw (DB errors) despite
    // returning a status enum — wrap when the poll loop lands") and the F1 gap (no retry, no
    // sliding window). See HomeViewModel.refreshOnce's own kdoc for the throw-vs-FAILED unification
    // these tests pin.

    // Proves the RAW loop mechanic in isolation from any failure/recovery semantics (those are
    // covered by the tests below, via retryNow() instead of virtual time -- see that choice's own
    // rationale in "a throw during refresh..." below): advancing past delay(60_000) (mirroring
    // HomeViewModel's private POLL_INTERVAL_MILLIS) must actually cause a SECOND refreshFeed()
    // call, not just leave the loop parked in its first delay() forever.
    @Test fun `poll loop re-invokes refreshFeed after the interval elapses`() = runTest {
        val testDispatcher = UnconfinedTestDispatcher()
        Dispatchers.setMain(testDispatcher)
        var callCount = 0
        val engine = MockEngine {
            callCount++
            respond(
                if (callCount == 1) ONE_FEATURE_GEOJSON else SECOND_FEATURE_GEOJSON,
                HttpStatusCode.OK,
                headersOf(HttpHeaders.ContentType to listOf("application/json")),
            )
        }
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        TerraWatchDb.Schema.create(driver)
        val dao = QuakeDao(TerraWatchDb(driver))
        // ioDispatcher pinned to the SAME UnconfinedTestDispatcher instance as Dispatchers.Main:
        // this test's whole point is that a virtual-time advance resumes the loop's delay(), so
        // refreshFeed()'s own suspensions must run on a scheduler this test controls too -- left
        // as the default Dispatchers.Default, the second refreshFeed() call would race this test's
        // own advanceTimeBy()/assertions on an uncontrolled real thread instead of resolving
        // deterministically before them.
        val repository = QuakeRepository(
            UsgsApi(HttpClient(engine)), EmscLiveSource(HttpClient(engine)), dao,
            clock = { 2_000_000L }, ioDispatcher = testDispatcher,
        )
        val vm = createVm(repository)

        vm.state.test {
            var s = awaitItem()
            while (s is HomeUiState.Loading || (s is HomeUiState.Content && s.quakes.none { it.id == "us1234" })) {
                s = awaitItem()
            }

            testDispatcher.scheduler.advanceTimeBy(60_000)
            testDispatcher.scheduler.runCurrent()

            var s2 = awaitItem()
            while (s2 is HomeUiState.Content && s2.quakes.none { it.id == "us5678" }) s2 = awaitItem()
            assertTrue(assertIs<HomeUiState.Content>(s2).quakes.any { it.id == "us5678" })
            cancelAndIgnoreRemainingEvents()
        }
    }

    // Red (pre-fix): times out -- the old code's one-shot `val status = repository.refreshFeed()`
    // propagates a throw straight out of its enclosing coroutine, which never reaches (and permanently
    // skips) `repository.startLive(viewModelScope)`, let alone any second attempt.
    //
    // dbShouldThrow throws from INSIDE QuakeDao's injectable clock lambda -- an existing test seam,
    // not a new one -- which toRow() calls while building the row for ingest()'s dao.replaceAndDelete()
    // write. That happens inside a db.transaction{} block, so the throw rolls the write back cleanly
    // (nothing partially persists) rather than corrupting the DB for this test's recovery half, and it
    // fires from a genuinely successful (200, valid-body) network fetch -- exercising the DB-error path
    // the Plan 2 note calls out, distinct from (and not already covered by) UsgsApi's own
    // network/HTTP-failure-to-FAILED mapping.
    @Test fun `a throw during refresh marks failed and survives, a later retry clears it`() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        var dbShouldThrow = true
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        TerraWatchDb.Schema.create(driver)
        val dao = QuakeDao(
            TerraWatchDb(driver),
            clock = {
                if (dbShouldThrow) {
                    dbShouldThrow = false
                    error("simulated DB error")
                }
                FETCH_CLOCK_MILLIS
            },
        )
        val engine = MockEngine {
            respond(
                ONE_FEATURE_GEOJSON, HttpStatusCode.OK,
                headersOf(HttpHeaders.ContentType to listOf("application/json")),
            )
        }
        val repository = QuakeRepository(
            UsgsApi(HttpClient(engine)), EmscLiveSource(HttpClient(engine)), dao,
            clock = { 2_000_000L },
        )
        val vm = createVm(repository)

        vm.state.test {
            var s = awaitItem()
            while (s is HomeUiState.Loading || (s is HomeUiState.Content && !s.refreshFailed)) s = awaitItem()
            assertTrue(assertIs<HomeUiState.Content>(s).refreshFailed)

            // The "next tick": retryNow() shares refreshOnce() with the periodic loop (see its own
            // kdoc), so this exercises the identical success-clears-failure code path a real
            // delay(POLL_INTERVAL_MILLIS)-gated tick would -- without this test needing its own
            // virtual-clock plumbing (that mechanic is pinned separately, see the test above).
            vm.retryNow()

            var s2 = awaitItem()
            while (s2 is HomeUiState.Content && s2.refreshFailed) s2 = awaitItem()
            assertFalse(assertIs<HomeUiState.Content>(s2).refreshFailed)
            cancelAndIgnoreRemainingEvents()
        }
    }

    // Isolates refreshOnce()'s OWN status-based clearing (this task's new behavior) from the
    // pre-existing insertedQuakeIds-collector clearing path (already pinned by "refreshFailed clears
    // once a new quake proves data is flowing again" above): an empty features list is UPDATED with
    // zero ingests, so this test would still pass even if that other collector were deleted outright.
    @Test fun `refreshFailed clears after an UPDATED refresh even with no new quakes`() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        var callCount = 0
        val engine = MockEngine {
            callCount++
            if (callCount == 1) respond("", HttpStatusCode.InternalServerError)
            else respond(
                """{"features":[]}""", HttpStatusCode.OK,
                headersOf(HttpHeaders.ContentType to listOf("application/json")),
            )
        }
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        TerraWatchDb.Schema.create(driver)
        val dao = QuakeDao(TerraWatchDb(driver))
        val repository = QuakeRepository(
            UsgsApi(HttpClient(engine)), EmscLiveSource(HttpClient(engine)), dao,
            clock = { 2_000_000L },
        )
        val vm = createVm(repository)

        vm.state.test {
            var s = awaitItem()
            while (s is HomeUiState.Loading || (s is HomeUiState.Content && !s.refreshFailed)) s = awaitItem()
            assertTrue(assertIs<HomeUiState.Content>(s).refreshFailed)

            vm.retryNow()

            var s2 = awaitItem()
            while (s2 is HomeUiState.Content && s2.refreshFailed) s2 = awaitItem()
            val recovered = assertIs<HomeUiState.Content>(s2)
            assertFalse(recovered.refreshFailed)
            assertTrue(recovered.quakes.isEmpty(), "this test's whole point is clearing WITHOUT any new quake")
            cancelAndIgnoreRemainingEvents()
        }
    }

    // The other successful-status half: a 304 must clear refreshFailed exactly like a 200 with new
    // data does (HomeViewModel.refreshOnce's fold sets refreshFailed = (status == FAILED), which is
    // false for NOT_MODIFIED too) -- the pre-Task-1 code had NO path that ever cleared a
    // previously-failed flag from a no-op-but-healthy poll at all.
    @Test fun `refreshFailed clears after a NOT_MODIFIED refresh`() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        var callCount = 0
        val engine = MockEngine {
            callCount++
            if (callCount == 1) respond("", HttpStatusCode.InternalServerError)
            else respond("", HttpStatusCode.NotModified)
        }
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        TerraWatchDb.Schema.create(driver)
        val dao = QuakeDao(TerraWatchDb(driver))
        val repository = QuakeRepository(
            UsgsApi(HttpClient(engine)), EmscLiveSource(HttpClient(engine)), dao,
            clock = { 2_000_000L },
        )
        val vm = createVm(repository)

        vm.state.test {
            var s = awaitItem()
            while (s is HomeUiState.Loading || (s is HomeUiState.Content && !s.refreshFailed)) s = awaitItem()
            assertTrue(assertIs<HomeUiState.Content>(s).refreshFailed)

            vm.retryNow()

            var s2 = awaitItem()
            while (s2 is HomeUiState.Content && s2.refreshFailed) s2 = awaitItem()
            assertFalse(assertIs<HomeUiState.Content>(s2).refreshFailed)
            cancelAndIgnoreRemainingEvents()
        }
    }

    // retryNow()'s coalescing guard (HomeViewModel.retryJob): a re-tap while the first tap's own
    // refreshFeed() call is still suspended must NOT start a second, overlapping fetch.
    //
    // Fix (found by actually running this): a first draft asserted a raw `fetchCount` var
    // immediately after the (non-suspending) retryNow() call returned, assuming ktor's MockEngine
    // dispatches its handler inline/synchronously. It does not -- there's a genuine async gap
    // inside ktor's own HttpClient/engine machinery that neither Dispatchers.setMain(...) nor a
    // shared ioDispatcher reaches, so that assertion raced it and failed (1 != 2). Worse, the
    // thrown AssertionError skipped `gate.complete(Unit)`, permanently orphaning a coroutine
    // parked on `gate.await()` inside this VM's viewModelScope -- which is what actually caused
    // this whole suite to hang for 8+ minutes in tearDown()'s cancelAndJoin() the first time this
    // was run (confirmed via jstack: every thread idle, "Test worker" parked forever in
    // BlockingCoroutine.joinBlocking). Rewritten to (a) only ever confirm things via a Channel --
    // genuinely suspending, thread-safe regardless of which real dispatcher ktor happens to use --
    // and (b) release the gate in a `finally`, so a future assertion failure here degrades to an
    // honest test failure instead of a repeat of that hang.
    @Test fun `retryNow ignores a re-tap while its own refresh is still in flight`() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        val gate = CompletableDeferred<Unit>()
        // Unlimited + trySend (not a rendezvous send) so the MockEngine callback -- which may run
        // on a real ktor-internal thread this test does not otherwise control -- never itself
        // blocks trying to hand off a signal nobody has asked for yet.
        val callStarted = Channel<Int>(Channel.UNLIMITED)
        var fetchCount = 0
        val engine = MockEngine {
            val n = ++fetchCount
            callStarted.trySend(n)
            if (n > 1) gate.await() // only the 2nd+ call (retryNow()'s own) ever gates
            respond("", HttpStatusCode.InternalServerError)
        }
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        TerraWatchDb.Schema.create(driver)
        val dao = QuakeDao(TerraWatchDb(driver))
        val repository = QuakeRepository(
            UsgsApi(HttpClient(engine)), EmscLiveSource(HttpClient(engine)), dao,
            clock = { 2_000_000L },
        )
        val vm = createVm(repository)
        try {
            assertEquals(1, callStarted.receive()) // init{}'s own first refresh has genuinely started

            vm.retryNow()
            // Genuinely waits (suspends) until retryNow()'s OWN refreshFeed() call has reached the
            // network layer -- at which point its body is proven still in flight (parked on the
            // gate below), so retryJob.isActive is necessarily still true for the next call.
            assertEquals(2, callStarted.receive())

            vm.retryNow() // re-tap while call #2 is confirmed in flight -> must be a no-op

            // If the guard were broken, a rogue 3rd call would ALSO already have incremented
            // fetchCount and signalled callStarted BEFORE ever reaching gate.await() -- i.e.
            // strictly before this point, not after. A short, bounded wait here is only checking
            // whether that signal is already sitting in the channel, not racing its arrival.
            // withContext(Dispatchers.Default) makes this a REAL bounded wait rather than
            // runTest's own virtualized (and therefore instantly-skippable) delay/timeout time.
            val thirdCall = withContext(Dispatchers.Default) { withTimeoutOrNull(500) { callStarted.receive() } }
            assertEquals(null, thirdCall, "a re-tap while a retry is already in flight must not start a second fetch")
        } finally {
            gate.complete(Unit) // always release the in-flight call, even if an assertion above
            // failed -- an un-released gate leaves a coroutine permanently parked inside this VM's
            // viewModelScope, which is exactly what hung this whole suite the first time (see kdoc).
        }
    }

    // Sliding window: recentQuakes()'s own cutoff is frozen per-subscription (see its kdoc), but
    // HomeViewModel's pollTick.flatMapLatest re-subscribes on every refreshOnce() attempt -- so a
    // quake that ages out of the (default 24h) window between polls must actually disappear from
    // `state.quakes` on the very next tick, not just the next time this test happens to reconstruct
    // the ViewModel from scratch.
    @Test fun `sliding window drops a quake that ages past the cutoff on the next poll tick`() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        var now = 2_000_000L
        val repository = fakeRepositoryAlwaysFailing(clock = { now })
        repository.ingest(freshQuake("old1", timeMillis = now))
        val vm = createVm(repository)

        vm.state.test {
            var s = awaitItem()
            while (s is HomeUiState.Loading || (s is HomeUiState.Content && s.quakes.none { it.id == "old1" })) {
                s = awaitItem()
            }
            assertTrue(assertIs<HomeUiState.Content>(s).quakes.any { it.id == "old1" })

            // Push the clock past recentQuakes()'s default 24h window relative to old1's own
            // timeMillis -- +1 past the exact boundary so this isn't an off-by-one coin flip
            // (QuakeDao.recent()'s SQL is an inclusive `timeMillis >= cutoff`).
            now += 86_400_000L + 1
            vm.retryNow() // bumps pollTick -> flatMapLatest re-subscribes recentQuakes() with the fresh cutoff

            var s2 = awaitItem()
            while (s2 is HomeUiState.Content && s2.quakes.any { it.id == "old1" }) s2 = awaitItem()
            assertTrue(assertIs<HomeUiState.Content>(s2).quakes.none { it.id == "old1" })
            cancelAndIgnoreRemainingEvents()
        }
    }

    // Task 9: the feed sheet's "N NEW" chip. fakeRepositoryAlwaysFailing() (not …WithOneQuake())
    // deliberately: its refreshFeed() never calls ingest() on anything, so the counter starts
    // from a known, deterministic 0 rather than racing whatever the network-seeded quake in the
    // "WithOneQuake" fake would otherwise add to it.
    @Test fun `newSinceExpand increments once per newly inserted quake`() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        val repository = fakeRepositoryAlwaysFailing()
        val vm = createVm(repository)
        vm.newSinceExpand.test {
            assertEquals(0, awaitItem())
            repository.ingest(freshQuake("new1"))
            assertEquals(1, awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test fun `markSheetExpanded resets newSinceExpand to zero`() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        val repository = fakeRepositoryAlwaysFailing()
        val vm = createVm(repository)
        vm.newSinceExpand.test {
            assertEquals(0, awaitItem())
            repository.ingest(freshQuake("new1"))
            assertEquals(1, awaitItem())
            vm.markSheetExpanded()
            assertEquals(0, awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    // Task 12 review, Fix 1: HomeScreen.kt's TwoPaneLayout now calls markSheetExpanded() from a
    // `LaunchedEffect(state)` that re-fires on every single content update for as long as the
    // two-pane panel is composed — not a one-shot call. That composition-level fix is only safe if
    // markSheetExpanded() reliably zeroes the counter every time it's invoked, no matter how many
    // prior increment/reset cycles came before it; this proves a SECOND arrival+reset cycle behaves
    // identically to the first; the existing test above only ever proved one.
    //
    // "new2" uses a timeMillis an hour after "new1"'s (well outside DedupeEngine's 90s/100km
    // window — same lat/lon as "new1" otherwise) so it lands as a genuinely distinct insert rather
    // than being merged into "new1" as a revision, which would never fire insertedQuakeIds at all
    // and hang this test's second awaitItem() forever (caught exactly that way on the first attempt
    // at this test, via a real TurbineTimeoutCancellationException — not a hypothetical concern).
    @Test fun `markSheetExpanded keeps the counter at zero across repeated arrivals`() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        val repository = fakeRepositoryAlwaysFailing()
        val vm = createVm(repository)
        vm.newSinceExpand.test {
            assertEquals(0, awaitItem())
            repository.ingest(freshQuake("new1"))
            assertEquals(1, awaitItem())
            vm.markSheetExpanded()
            assertEquals(0, awaitItem())
            repository.ingest(freshQuake("new2", timeMillis = 1_950_000 + 3_600_000))
            assertEquals(1, awaitItem())
            vm.markSheetExpanded()
            assertEquals(0, awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    // Task 9: homeLocation. Seeds the store via HomeLocationStore.set() (the same dao-backed path
    // HomeViewModel itself reads through), then asserts the ViewModel's own flow eventually
    // reflects it — proving the init{} load actually reads from the injected store rather than,
    // say, silently defaulting to null or only consulting LocationProvider.
    @Test fun `homeLocation loads the previously stored point`() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        val store = emptyHomeLocationStore().apply { set(GeoPoint(12.34, 56.78)) }
        val vm = createVm(fakeRepositoryAlwaysFailing(), store)
        vm.homeLocation.test {
            var v = awaitItem()
            while (v == null) v = awaitItem()
            assertEquals(GeoPoint(12.34, 56.78), v)
            cancelAndIgnoreRemainingEvents()
        }
    }

    // Task 11: selection wiring for the detail sheet. fakeRepositoryWithOneQuake()'s seeded feed
    // (see ONE_FEATURE_GEOJSON below) always lands as id "us1234" once refreshFeed() resolves —
    // select() reads through the repository's real (DAO-backed) byId(), not some in-memory copy of
    // the emitted list, so this only becomes non-null once that quake has actually been persisted.
    @Test fun `select populates selectedQuake from the repository when the quake exists`() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        val vm = createVm(fakeRepositoryWithOneQuake())
        // Wait for the seeded quake to actually land before selecting it — otherwise select("us1234")
        // could race the still-in-flight refreshFeed() ingest and legitimately find nothing yet.
        vm.state.test {
            var s = awaitItem()
            while (s is HomeUiState.Loading || (s is HomeUiState.Content && s.quakes.isEmpty())) {
                s = awaitItem()
            }
            cancelAndIgnoreRemainingEvents()
        }
        vm.selectedQuake.test {
            assertEquals(null, awaitItem())
            vm.select("us1234")
            val selected = awaitItem()
            assertEquals("us1234", selected?.id)
            cancelAndIgnoreRemainingEvents()
        }
    }

    // Not-found proven as an active transition (a prior selection reverting to null on a second,
    // unknown-id select()) rather than "was already null and I did nothing" — the latter would
    // pass even if select() never actually re-assigned selectedQuake at all, since StateFlow never
    // re-emits an already-equal value; this way the null is provably select()'s own doing.
    @Test fun `select sets selectedQuake to null when the id is not found`() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        val vm = createVm(fakeRepositoryWithOneQuake())
        vm.state.test {
            var s = awaitItem()
            while (s is HomeUiState.Loading || (s is HomeUiState.Content && s.quakes.isEmpty())) {
                s = awaitItem()
            }
            cancelAndIgnoreRemainingEvents()
        }
        vm.selectedQuake.test {
            assertEquals(null, awaitItem())
            vm.select("us1234")
            assertEquals("us1234", awaitItem()?.id)
            vm.select("does-not-exist")
            assertEquals(null, awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test fun `dismissSelection clears selectedQuake back to null`() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        val vm = createVm(fakeRepositoryWithOneQuake())
        vm.state.test {
            var s = awaitItem()
            while (s is HomeUiState.Loading || (s is HomeUiState.Content && s.quakes.isEmpty())) {
                s = awaitItem()
            }
            cancelAndIgnoreRemainingEvents()
        }
        vm.selectedQuake.test {
            assertEquals(null, awaitItem())
            vm.select("us1234")
            assertEquals("us1234", awaitItem()?.id)
            vm.dismissSelection()
            assertEquals(null, awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }
}

// A fresh, empty in-memory-backed HomeLocationStore — used by every test above that needs
// *a* HomeLocationStore to satisfy HomeViewModel's constructor but doesn't care what (if
// anything) it resolves to.
private fun emptyHomeLocationStore(): HomeLocationStore {
    val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
    TerraWatchDb.Schema.create(driver)
    return HomeLocationStore(QuakeDao(TerraWatchDb(driver)))
}

// Builds a real QuakeRepository over an in-memory JVM SQLDelight driver with a MockEngine that
// returns one-feature GeoJSON for the feed request — reuses FeedViewModelTest's construction
// pattern, but injects an explicit QuakeDao clock so lastFetchedAtMillis() is a known value rather
// than the QuakeDao default (0L).
private fun fakeRepositoryWithOneQuake(): QuakeRepository {
    val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
    TerraWatchDb.Schema.create(driver)
    val dao = QuakeDao(TerraWatchDb(driver), clock = { FETCH_CLOCK_MILLIS })
    val engine = MockEngine {
        respond(
            ONE_FEATURE_GEOJSON,
            HttpStatusCode.OK,
            headersOf(HttpHeaders.ContentType to listOf("application/json")),
        )
    }
    return QuakeRepository(
        UsgsApi(HttpClient(engine)),
        EmscLiveSource(HttpClient(engine)),
        dao,
        clock = { 2_000_000L },
    )
}

// Task 1 (Plan 3): [clock] gained a parameter (default unchanged from before this task) so the
// sliding-window test below can mutate "now" out from under an already-constructed repository —
// same "add a defaulted param for one new test" precedent as [freshQuake]'s own [timeMillis]
// parameter further down this file.
private fun fakeRepositoryAlwaysFailing(clock: () -> Long = { 2_000_000L }): QuakeRepository {
    val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
    TerraWatchDb.Schema.create(driver)
    val dao = QuakeDao(TerraWatchDb(driver))
    val engine = MockEngine { respond("", HttpStatusCode.InternalServerError) }
    return QuakeRepository(
        UsgsApi(HttpClient(engine)),
        EmscLiveSource(HttpClient(engine)),
        dao,
        clock = clock,
    )
}

// Pre-seeds the DAO directly (bypassing any network call) with one quake, then gates the feed
// MockEngine's response on [gate] so refreshFeed() stays suspended until the test completes it.
private fun fakeRepositorySeededWithOneQuake(gate: CompletableDeferred<Unit>): QuakeRepository {
    val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
    TerraWatchDb.Schema.create(driver)
    val dao = QuakeDao(TerraWatchDb(driver))
    dao.upsert(
        Quake(
            id = "seed1", timeMillis = 1_900_000, lat = 10.0, lon = 20.0, depthKm = 5.0,
            mag = 3.0, magType = "mw", place = "Seeded", tsunami = false, felt = null,
            status = QuakeStatus.AUTOMATIC, sources = mapOf(Source.USGS to "seed1"),
            revisions = listOf(MagRevision(3.0, "mw", 1_900_000, Source.USGS)),
            updatedAtMillis = 1_900_000,
        ),
    )
    val engine = MockEngine {
        gate.await() // refreshFeed()'s api.fetchFeed() call suspends here until the test says go.
        respond("", HttpStatusCode.InternalServerError)
    }
    return QuakeRepository(
        UsgsApi(HttpClient(engine)),
        EmscLiveSource(HttpClient(engine)),
        dao,
        clock = { 2_000_000L },
    )
}

// Task 12 review Fix 1's test (below) needs a SECOND quake DedupeEngine won't merge into the
// first: its window is 90s / 100km (DedupeEngine.kt), so [timeMillis] is now a parameter — every
// pre-existing call site keeps the original hardcoded 1_950_000 via the default, only the new test
// passes a value far outside that window.
private fun freshQuake(id: String, timeMillis: Long = 1_950_000) = Quake(
    id = id, timeMillis = timeMillis, lat = 1.0, lon = 2.0, depthKm = 5.0,
    mag = 4.0, magType = "mw", place = "Fresh", tsunami = false, felt = null,
    status = QuakeStatus.AUTOMATIC, sources = mapOf(Source.USGS to id),
    revisions = listOf(MagRevision(4.0, "mw", timeMillis, Source.USGS)),
    updatedAtMillis = timeMillis,
)

private val ONE_FEATURE_GEOJSON = """
    {
      "type": "FeatureCollection",
      "features": [
        {
          "type": "Feature",
          "id": "us1234",
          "properties": {
            "mag": 5.5,
            "place": "10km SE of Testville",
            "time": 1950000,
            "updated": 1950000,
            "magType": "mw",
            "status": "automatic",
            "tsunami": 0
          },
          "geometry": { "type": "Point", "coordinates": [126.5, 7.1, 10.0] }
        }
      ]
    }
""".trimIndent()

// Task 1 (Plan 3): a second, distinct feature for the poll-loop test below — different id, place,
// time (100 min vs. ONE_FEATURE_GEOJSON's 32.5 min -- well outside DedupeEngine's 90s window) and
// coordinates (Chile vs. the Philippines -- well outside its 100km radius too, redundant safety
// margin on top of the time gap alone), so the two never dedupe-merge into one row.
private val SECOND_FEATURE_GEOJSON = """
    {
      "type": "FeatureCollection",
      "features": [
        {
          "type": "Feature",
          "id": "us5678",
          "properties": {
            "mag": 4.2,
            "place": "20km NW of Otherville",
            "time": 6000000,
            "updated": 6000000,
            "magType": "mw",
            "status": "automatic",
            "tsunami": 0
          },
          "geometry": { "type": "Point", "coordinates": [-70.0, -33.0, 20.0] }
        }
      ]
    }
""".trimIndent()
