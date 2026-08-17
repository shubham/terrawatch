package com.yugma.terrawatch.home

// Same jvmTest-not-commonTest rationale as FeedViewModelTest: fakeRepositoryWithOneQuake() builds a
// real QuakeRepository over app.cash.sqldelight's JDBC in-memory driver, a JVM-only artifact.
import androidx.lifecycle.viewModelScope
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import app.cash.turbine.test
import com.yugma.terrawatch.data.AlertRuleStore
import com.yugma.terrawatch.data.FavoritePlaceStore
import com.yugma.terrawatch.data.FeedFilterStore
import com.yugma.terrawatch.data.HomeLocationStore
import com.yugma.terrawatch.data.QuakeRepository
import com.yugma.terrawatch.data.VisitStore
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
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.time.Duration.Companion.seconds
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
    //
    // Flake-hardening pass (2026-08-16, CI run 31936189058 — TurbineAssertionError, green on every
    // local run): [createVm]/tearDown above already close the leaked-coroutine race; the newer gap
    // this pass closes is Turbine's 3s wall-clock DEFAULT expiring on a starved CI runner even
    // though virtual time is fully advanced — the same class of trap commit 5e9e922 first
    // documented for the poll-loop test further down this file. Every `newSinceExpand`/
    // `startupCameraTarget`/`locationUnavailableEvents`/`favorites`/`homeLocation` await this pass
    // touched crosses a REAL, uncontrolled thread pool by construction, not by test mis-setup:
    // `FavoritePlaceStore.favorites` is `QuakeDao.favoritePlaces()`, which hard-codes
    // `.mapToList(Dispatchers.Default)` (same as `QuakeDao.recent()`); `startupCameraTarget`/
    // `homeLocation`'s first value and `recenterToCurrentLocation()` are written from `init`'s own
    // hard-coded `viewModelScope.launch(Dispatchers.Default) { ... }` blocks (see those fields' own
    // kdoc) — neither is routed through any `ioDispatcher` a test could pin the way the poll-loop
    // test pins `QuakeRepository`'s. `test(timeout = 30.seconds)` is therefore the correct lever
    // (not a dispatcher pin — there is no seam to pin), applied only to the tests this branch
    // actually added (grepped this branch's own diff against `main` first — EVIDENCE INTEGRITY —
    // rather than touching every pre-existing, already-stable test that happens to share the same
    // exposure); it only widens the failure window for a genuine hang (e.g. the leaked-coroutine
    // 100%-CPU spin CI run 31939018579 shows), never slows a passing run.
    // Flake-hardening pass (2026-08-16, this session): closes the "pre-existing finding" from the
    // PRIOR flake-hardening pass documented just above -- the ~10-15% `kotlinx.coroutines.test.
    // internal.TestMainDispatcher`/`IllegalStateException` race (always attributed to THIS class's
    // own `tearDown()`'s `Dispatchers.resetMain()` call, see task-flake-hardening-report.md's own
    // stack-trace analysis) was rooted in real, uncontrolled `Dispatchers.Default` thread-pool
    // crossings that no test here could pin, not in anything about this suite's own test logic.
    // Took THREE rounds to fully close, each verified empirically (not by inspection) via an
    // isolated 30x `--rerun-tasks` loop before moving to the next:
    //  - Round 1: gave [HomeViewModel] itself a pinnable `ioDispatcher` ctor param (see that
    //    class's own kdoc), replacing its two hard-coded `viewModelScope.launch(Dispatchers.
    //    Default)` blocks (init's location/camera-target resolution, [recenterToCurrentLocation])
    //    plus the state collector's `.flowOn(Dispatchers.Default)`. INSUFFICIENT ALONE: a follow-up
    //    30x run still showed 3/26 failures (~11.5%, statistically indistinguishable from the
    //    original ~10-15% baseline) -- the exact same exception signature, meaning this round's fix,
    //    while a real and necessary seam, was not where most of the actual race came from.
    //  - Round 2: [QuakeRepository] ALREADY had its own, pre-existing `ioDispatcher` ctor param
    //    (used by `refreshFeed`/`purgeDebugQuakes`/`pruneOldRows`/`byId`/etc., all called from
    //    HomeViewModel's own plain Main-dispatched `viewModelScope.launch { ... }` blocks -- the
    //    identical "Main-dispatched parent, Default-dispatched child" shape Task-13's own kdoc
    //    already documents for the `flowOn` case, just via the REPOSITORY's internal hop instead of
    //    a HomeViewModel-level one) -- but every `fakeRepository*()` helper below left it at its
    //    real-`Dispatchers.Default` default. Threaded [testDispatcher] through all four
    //    `fakeRepository*()` helpers and every direct `QuakeRepository(...)` construction in this
    //    file. Dropped the rate to 1/30 (~3.3%) -- a real, large improvement, but still not zero,
    //    and still the identical exception signature.
    //  - Round 3: `QuakeDao.recent()`'s own hard-coded `.mapToList(Dispatchers.Default)` -- a
    //    crossing neither HomeViewModel's nor QuakeRepository's own `ioDispatcher` could ever reach,
    //    since this dao is constructed independently and handed to the repository as a finished
    //    object. Gave [QuakeDao] itself a third, defaulted `dispatcher` ctor param (see that class's
    //    own kdoc) and threaded it through every `QuakeDao(...)` construction that feeds a test's
    //    repository (the four `fakeRepository*()` helpers plus the direct-construction tests) to the
    //    same pinned test dispatcher. Result: 30/30 across two consecutive isolated 30x runs (this
    //    round's own proof, plus a fresh confirming run) -- the flake is gone, not just quieter.
    //  - Round 4 (2026-08-16, post-merge verification): the residual deliberately left unpinned by
    //    round 3 -- `QuakeDao.favoritePlaces()`'s identical `.mapToList(Dispatchers.Default)` hop,
    //    reached via the separately-constructed `emptyFavoritePlaceStore()` -- DID reproduce in an
    //    extended 80x verification loop (79/80; the one failure's stack traced to exactly this
    //    crossing: HomeViewModel.kt collects favoritePlaceStore.favorites on Main in every test,
    //    docs/qa/flake-verification-2026-08-16.md has the full trace). All three empty-store
    //    helpers below (`emptyHomeLocationStore`/`emptyAlertRuleStore`/`emptyFavoritePlaceStore`)
    //    now pin their QuakeDao's `dispatcher` to an UnconfinedTestDispatcher, closing the last
    //    unpinned Main<->Default crossing this suite can reach. (InsightsViewModelTest's own
    //    `recentQuakes()` DAO-level crossing remains that file's documented, separate residual.)
    // Root-cause takeaway: this was never about test hygiene (the leaked-coroutine teardown fix
    // above already closed that class of bug) -- it was a genuine architectural gap, a real
    // background dispatcher with no seam, at THREE different layers (VM, repository, DAO) that all
    // had to be closed together before the race actually went away, not just got rarer.
    private val createdViewModels = mutableListOf<HomeViewModel>()

    private fun createVm(
        repository: QuakeRepository,
        homeLocationStore: HomeLocationStore = emptyHomeLocationStore(),
        locationProvider: LocationProvider = LocationProvider(),
        alertRuleStore: AlertRuleStore = emptyAlertRuleStore(),
        // Task 2 (Plan 4): HomeViewModel.init now unconditionally calls repository.pruneOldRows(
        // clock() - 30d) alongside purgeDebugQuakes() -- every quake this whole suite seeds uses a
        // tiny epoch-relative timeMillis (900, 1_950_000, 2_000_000, ...), not a real wall-clock
        // timestamp, so HomeViewModel's own real-clock default would judge every one of them as
        // decades-old and prune it out from under whichever test seeded it. clock() = 0L makes
        // pruneOldRows' cutoff deeply NEGATIVE (0 - 2_592_000_000), so `timeMillis < cutoff` never
        // holds for any non-negative test timeMillis -- a safe, guaranteed no-op default for every
        // test below that isn't specifically exercising retention (that one test passes its own
        // clock explicitly instead of relying on this default).
        clock: () -> Long = { 0L },
        // Task 2 (Plan 5): defaulted so every pre-existing test above (none of which care about
        // favorites) keeps compiling and passing unchanged -- same "add a new store, default it"
        // shape alertRuleStore's own default already established for this helper.
        favoritePlaceStore: FavoritePlaceStore = emptyFavoritePlaceStore(),
        // Flake-hardening pass (2026-08-16, this session): HomeViewModel gained a pinnable
        // `ioDispatcher` ctor param (see its own kdoc) closing the pre-existing ~10-15%
        // TestMainDispatcher/IllegalStateException race documented in this class's own kdoc above
        // and in task-flake-hardening-report.md. Defaulted to the real Dispatchers.Default
        // (compile-safe for any future call site that forgets to pin it) -- every test below now
        // passes its own UnconfinedTestDispatcher explicitly, the SAME instance backing
        // Dispatchers.Main, matching InsightsViewModelTest's own `ioDispatcher` pin style exactly.
        ioDispatcher: CoroutineDispatcher = Dispatchers.Default,
        // feat/feed-visit-ux: same "add a new store, default it" shape favoritePlaceStore's own
        // comment above already establishes for this helper.
        visitStore: VisitStore = emptyVisitStore(),
        // User review items 3+4: same "add a new store, default it" shape every prior store param
        // above already establishes for this helper — none of the pre-existing tests care about the
        // feed filter, so they all keep compiling/behaving unchanged via this default.
        feedFilterStore: FeedFilterStore = emptyFeedFilterStore(),
    ): HomeViewModel =
        HomeViewModel(
            repository, homeLocationStore, locationProvider, alertRuleStore, clock,
            favoritePlaceStore = favoritePlaceStore, ioDispatcher = ioDispatcher, visitStore = visitStore,
            feedFilterStore = feedFilterStore,
        ).also { createdViewModels += it }

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
        val testDispatcher = UnconfinedTestDispatcher()
        Dispatchers.setMain(testDispatcher)
        val vm = createVm(fakeRepositoryWithOneQuake(ioDispatcher = testDispatcher), ioDispatcher = testDispatcher)
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
        val testDispatcher = UnconfinedTestDispatcher()
        Dispatchers.setMain(testDispatcher)
        val vm = createVm(fakeRepositoryAlwaysFailing(ioDispatcher = testDispatcher), ioDispatcher = testDispatcher)
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
        val testDispatcher = UnconfinedTestDispatcher()
        Dispatchers.setMain(testDispatcher)
        val vm = createVm(fakeRepositoryWithOneQuake(ioDispatcher = testDispatcher), ioDispatcher = testDispatcher)
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
        val testDispatcher = UnconfinedTestDispatcher()
        Dispatchers.setMain(testDispatcher)
        val vm = createVm(fakeRepositoryWithOneQuake(ioDispatcher = testDispatcher), ioDispatcher = testDispatcher)
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
        val testDispatcher = UnconfinedTestDispatcher()
        Dispatchers.setMain(testDispatcher)
        val vm = createVm(fakeRepositoryWithOneQuake(ioDispatcher = testDispatcher), ioDispatcher = testDispatcher)
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
        val testDispatcher = UnconfinedTestDispatcher()
        Dispatchers.setMain(testDispatcher)
        val repository = fakeRepositoryAlwaysFailing(ioDispatcher = testDispatcher)
        val vm = createVm(repository, ioDispatcher = testDispatcher)
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

    // Task 2 (Plan 3) carry-in — the Task 1 refreshFailed fencing debt: refreshOnce() captures its
    // own `refreshGeneration` at call time and only writes refreshFailed if that generation is
    // still current when its (possibly slow) result lands. Without this, a poll that's been sitting
    // in flight for a while can resolve FAILED *after* a live arrival already proved the feed
    // healthy again (repository.insertedQuakeIds bumping the generation and clearing refreshFailed
    // itself, same as the "refreshFailed clears once a new quake proves data is flowing again" test
    // above) — and unconditionally re-raise a banner the user just watched clear. Red (pre-fix):
    // the slow attempt's `refreshFailed.value = true` always lands unfenced, so the drain loop below
    // observes a second Content with refreshFailed flipped back to true.
    //
    // fakeRepositorySlowThenFailing gates ONLY the very first refreshFeed() call (init{}'s own) on
    // [gate] — a real suspension point, not virtual time, same reasoning
    // "cached pins render immediately..." documents for its own CompletableDeferred gate.
    @Test fun `a slow-failed poll landing after a live-clear does not re-raise the banner`() = runTest {
        val testDispatcher = UnconfinedTestDispatcher()
        Dispatchers.setMain(testDispatcher)
        val gate = CompletableDeferred<Unit>()
        val repository = fakeRepositorySlowThenFailing(gate, ioDispatcher = testDispatcher)
        val vm = createVm(repository, ioDispatcher = testDispatcher)

        vm.state.test {
            // The initial refreshOnce() is parked on `gate` inside the network layer -- state so
            // far only reflects the (empty, never-failed) cache-driven collector.
            var s = awaitItem()
            while (s is HomeUiState.Loading) s = awaitItem()
            assertFalse(assertIs<HomeUiState.Content>(s).refreshFailed)

            // A live-style arrival WHILE the slow poll is still in flight -- bumps refreshGeneration
            // (HomeViewModel.refreshGeneration's own kdoc) via the insertedQuakeIds collector, the
            // exact same path "refreshFailed clears once a new quake proves data is flowing again"
            // exercises.
            repository.ingest(freshQuake("live1"))
            var s2 = awaitItem()
            while (s2 is HomeUiState.Content && s2.quakes.none { it.id == "live1" }) s2 = awaitItem()
            assertFalse(assertIs<HomeUiState.Content>(s2).refreshFailed)

            // Now let the slow poll's own FAILED result land. Pre-fix, refreshOnce()'s
            // `refreshFailed.value = true` re-raises the banner right here even though live1
            // already proved the feed healthy; post-fix, this write is fenced out -- its captured
            // generation no longer matches after the ingest above bumped it.
            gate.complete(Unit)

            // The fenced-out attempt still bumps pollTick unconditionally (a harmless re-subscribe
            // with a fresh cutoff -- see refreshOnce()'s own kdoc). _state is a StateFlow, which
            // conflates a re-combined value that's data-equal to the current one -- so the fenced,
            // correct outcome is that NOTHING further ever arrives here at all (same quakes, same
            // refreshFailed=false as s2 above). The unfenced, buggy outcome is exactly one more
            // Content with refreshFailed flipped back to true (a genuine change from s2, so it is
            // NOT conflated away).
            //
            // NOT `withTimeoutOrNull(...) { awaitItem() }`: Turbine's own awaitItem() internally
            // catches ANY TimeoutCancellationException reaching it -- including one thrown by an
            // ENCLOSING withTimeoutOrNull -- and rethrows it as a (package-internal, so caught here
            // via its public AssertionError supertype rather than by its own unnamable type)
            // TurbineAssertionError, which withTimeoutOrNull no longer recognizes as its own
            // cancellation and lets propagate as a real failure (confirmed by actually running
            // this: the first draft's withTimeoutOrNull wrapper never suppressed anything, it just
            // relabeled the exception). Catching the timeout-triggered AssertionError directly is
            // the correct way to say "nothing arrived within Turbine's own wait budget" here.
            val afterGate = try {
                awaitItem()
            } catch (expectedWhenFenceHolds: AssertionError) {
                null
            }
            val sawFailedAgain = afterGate is HomeUiState.Content && afterGate.refreshFailed
            assertFalse(sawFailedAgain, "a slow-failed poll must not re-raise the banner after a live-clear")
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
        val testDispatcher = UnconfinedTestDispatcher()
        Dispatchers.setMain(testDispatcher)
        val gate = CompletableDeferred<Unit>()
        val vm = createVm(fakeRepositorySeededWithOneQuake(gate, ioDispatcher = testDispatcher), ioDispatcher = testDispatcher)
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
        val dao = QuakeDao(TerraWatchDb(driver), dispatcher = testDispatcher)
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
        val vm = createVm(repository, ioDispatcher = testDispatcher)

        // timeout = 30s (Turbine's default is 3s WALL-CLOCK): the emission chain that satisfies
        // awaitItem() below crosses two thread pools this test does NOT control — QuakeDao.recent()'s
        // hard-coded mapToList(Dispatchers.Default) hop and MockEngine's own worker — so on a starved
        // CI runner the post-advance emission can take >3s of real time despite virtual time being
        // fully advanced. Flaked exactly this way on GitHub Actions run 31894021662 (passed on rerun
        // and locally). The margin only widens the failure window for a real hang; it never slows a
        // passing run.
        vm.state.test(timeout = 30.seconds) {
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
        val testDispatcher = UnconfinedTestDispatcher()
        Dispatchers.setMain(testDispatcher)
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
            dispatcher = testDispatcher,
        )
        val engine = MockEngine {
            respond(
                ONE_FEATURE_GEOJSON, HttpStatusCode.OK,
                headersOf(HttpHeaders.ContentType to listOf("application/json")),
            )
        }
        val repository = QuakeRepository(
            UsgsApi(HttpClient(engine)), EmscLiveSource(HttpClient(engine)), dao,
            clock = { 2_000_000L }, ioDispatcher = testDispatcher,
        )
        val vm = createVm(repository, ioDispatcher = testDispatcher)

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
        val testDispatcher = UnconfinedTestDispatcher()
        Dispatchers.setMain(testDispatcher)
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
        val dao = QuakeDao(TerraWatchDb(driver), dispatcher = testDispatcher)
        val repository = QuakeRepository(
            UsgsApi(HttpClient(engine)), EmscLiveSource(HttpClient(engine)), dao,
            clock = { 2_000_000L }, ioDispatcher = testDispatcher,
        )
        val vm = createVm(repository, ioDispatcher = testDispatcher)

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
        val testDispatcher = UnconfinedTestDispatcher()
        Dispatchers.setMain(testDispatcher)
        var callCount = 0
        val engine = MockEngine {
            callCount++
            if (callCount == 1) respond("", HttpStatusCode.InternalServerError)
            else respond("", HttpStatusCode.NotModified)
        }
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        TerraWatchDb.Schema.create(driver)
        val dao = QuakeDao(TerraWatchDb(driver), dispatcher = testDispatcher)
        val repository = QuakeRepository(
            UsgsApi(HttpClient(engine)), EmscLiveSource(HttpClient(engine)), dao,
            clock = { 2_000_000L }, ioDispatcher = testDispatcher,
        )
        val vm = createVm(repository, ioDispatcher = testDispatcher)

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
        val testDispatcher = UnconfinedTestDispatcher()
        Dispatchers.setMain(testDispatcher)
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
        val dao = QuakeDao(TerraWatchDb(driver), dispatcher = testDispatcher)
        val repository = QuakeRepository(
            UsgsApi(HttpClient(engine)), EmscLiveSource(HttpClient(engine)), dao,
            clock = { 2_000_000L }, ioDispatcher = testDispatcher,
        )
        val vm = createVm(repository, ioDispatcher = testDispatcher)
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
        val testDispatcher = UnconfinedTestDispatcher()
        Dispatchers.setMain(testDispatcher)
        var now = 2_000_000L
        val repository = fakeRepositoryAlwaysFailing(clock = { now }, ioDispatcher = testDispatcher)
        repository.ingest(freshQuake("old1", timeMillis = now))
        val vm = createVm(repository, ioDispatcher = testDispatcher)

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
        val testDispatcher = UnconfinedTestDispatcher()
        Dispatchers.setMain(testDispatcher)
        val repository = fakeRepositoryAlwaysFailing(ioDispatcher = testDispatcher)
        val vm = createVm(repository, ioDispatcher = testDispatcher)
        vm.newSinceExpand.test {
            assertEquals(0, awaitItem())
            repository.ingest(freshQuake("new1"))
            assertEquals(1, awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test fun `markSheetExpanded resets newSinceExpand to zero`() = runTest {
        val testDispatcher = UnconfinedTestDispatcher()
        Dispatchers.setMain(testDispatcher)
        val repository = fakeRepositoryAlwaysFailing(ioDispatcher = testDispatcher)
        val vm = createVm(repository, ioDispatcher = testDispatcher)
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
        val testDispatcher = UnconfinedTestDispatcher()
        Dispatchers.setMain(testDispatcher)
        val repository = fakeRepositoryAlwaysFailing(ioDispatcher = testDispatcher)
        val vm = createVm(repository, ioDispatcher = testDispatcher)
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

    // ---- User review items 3+4: feedFilterMinMag + newSinceExpand coherence -----------------------
    // The dashboard feed sheet's own persisted magnitude filter, and the "N NEW" counter's own
    // gating against it — an M<filter arrival must not bump newSinceExpand (no reveal chip, no
    // auto-scroll, no "N NEW" badge increment), even though it DOES still reach the DB/quakes list
    // (map pins/pillStatus are unaffected — see HomeScreen.kt's own kdoc note for where the actual
    // list DISPLAY filtering happens; this class only owns the filter's own persisted value and the
    // counter's gating against it).

    @Test fun `feedFilterMinMag defaults to FeedFilterStore's own 4-0 default`() = runTest {
        val testDispatcher = UnconfinedTestDispatcher()
        Dispatchers.setMain(testDispatcher)
        val vm = createVm(fakeRepositoryAlwaysFailing(ioDispatcher = testDispatcher), ioDispatcher = testDispatcher)
        vm.feedFilterMinMag.test {
            // ONE awaitItem(), not two: [_feedFilterMinMag] is already seeded at 4.0 before init{}'s
            // own feedFilterStore.minMag collector re-emits that SAME 4.0 — a StateFlow conflates an
            // equal consecutive value, so there is only ever one real emission here to observe (same
            // "seeded default, live collector re-confirms it, no second event" shape
            // `nearbyRadiusKm defaults to AlertRuleStore's own 100km default` above already pins).
            assertEquals(FeedFilterStore.DEFAULT_MIN_MAG, awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test fun `feedFilterMinMag reacts to a store update landing mid-session, no restart needed`() = runTest {
        val testDispatcher = UnconfinedTestDispatcher()
        Dispatchers.setMain(testDispatcher)
        val feedFilterStore = emptyFeedFilterStore()
        val vm = createVm(fakeRepositoryAlwaysFailing(ioDispatcher = testDispatcher), ioDispatcher = testDispatcher, feedFilterStore = feedFilterStore)
        vm.feedFilterMinMag.test {
            assertEquals(4.0, awaitItem())
            feedFilterStore.setMinMag(6.0)
            assertEquals(6.0, awaitItem())
            feedFilterStore.setMinMag(null) // "All"
            assertEquals(null, awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test fun `setFeedFilterMinMag writes through to the store and round-trips`() = runTest {
        val testDispatcher = UnconfinedTestDispatcher()
        Dispatchers.setMain(testDispatcher)
        val feedFilterStore = emptyFeedFilterStore()
        val vm = createVm(fakeRepositoryAlwaysFailing(ioDispatcher = testDispatcher), ioDispatcher = testDispatcher, feedFilterStore = feedFilterStore)
        vm.feedFilterMinMag.test {
            assertEquals(4.0, awaitItem())
            vm.setFeedFilterMinMag(5.0)
            assertEquals(5.0, awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    // The coherence proof: an arrival BELOW the active filter must not move newSinceExpand at all.
    @Test fun `newSinceExpand does not increment for an arrival below the active feed filter`() = runTest {
        val testDispatcher = UnconfinedTestDispatcher()
        Dispatchers.setMain(testDispatcher)
        val repository = fakeRepositoryAlwaysFailing(ioDispatcher = testDispatcher)
        // Default filter is 4.0+ (FeedFilterStore.DEFAULT_MIN_MAG) — an M2.2 arrival must be gated out.
        val vm = createVm(repository, ioDispatcher = testDispatcher)
        vm.newSinceExpand.test {
            assertEquals(0, awaitItem())
            repository.ingest(freshQuake("sub-threshold", mag = 2.2))
            // Nothing further should ever arrive on this StateFlow for this ingest. NOT
            // `withTimeoutOrNull(...) { awaitItem() }` — this file's own "a slow-failed poll landing
            // after a live-clear..." test already documents why that specific combination breaks:
            // Turbine's `awaitItem()` internally catches ANY TimeoutCancellationException reaching
            // it, INCLUDING one thrown by an enclosing withTimeoutOrNull, and rethrows it as its own
            // (package-internal) TurbineAssertionError — which the enclosing withTimeoutOrNull no
            // longer recognizes as its own cancellation, so it propagates as a real test failure
            // instead of yielding `null`. Catching the AssertionError directly (Turbine's own 3s
            // wall-clock default timeout) is this file's own established way to say "prove nothing
            // arrived" — see that other test's identical `try { awaitItem() } catch (...: AssertionError)`.
            val late = try { awaitItem() } catch (expectedWhenGateHolds: AssertionError) { null }
            assertEquals(null, late, "an arrival below the active feed filter must never increment newSinceExpand")
            cancelAndIgnoreRemainingEvents()
        }
    }

    // The complementary proof: an arrival AT/ABOVE the active filter still increments normally —
    // the gate excludes sub-threshold arrivals specifically, not arrivals in general.
    @Test fun `newSinceExpand still increments for an arrival at or above the active feed filter`() = runTest {
        val testDispatcher = UnconfinedTestDispatcher()
        Dispatchers.setMain(testDispatcher)
        val repository = fakeRepositoryAlwaysFailing(ioDispatcher = testDispatcher)
        val vm = createVm(repository, ioDispatcher = testDispatcher)
        vm.newSinceExpand.test {
            assertEquals(0, awaitItem())
            repository.ingest(freshQuake("at-threshold", mag = 4.0))
            assertEquals(1, awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    // Setting the filter to "All" (null) must let a normally-gated sub-threshold arrival through —
    // proves the gate reads the LIVE filter value, not a value frozen at HomeViewModel construction.
    @Test fun `newSinceExpand increments for a sub-threshold arrival once the filter is widened to All`() = runTest {
        val testDispatcher = UnconfinedTestDispatcher()
        Dispatchers.setMain(testDispatcher)
        val feedFilterStore = emptyFeedFilterStore()
        val repository = fakeRepositoryAlwaysFailing(ioDispatcher = testDispatcher)
        val vm = createVm(repository, ioDispatcher = testDispatcher, feedFilterStore = feedFilterStore)
        vm.setFeedFilterMinMag(null)
        vm.newSinceExpand.test {
            assertEquals(0, awaitItem())
            repository.ingest(freshQuake("sub-threshold", mag = 2.2))
            assertEquals(1, awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    // The user's own explicit device-verification scenario, pinned at the ViewModel level: setting
    // the filter to 6.0+ must gate out an M4-M5.9 arrival that the DEFAULT 4.0+ filter would have let
    // through — not just prove the default filter's own floor works.
    @Test fun `newSinceExpand does not increment for an arrival below a user-raised 6-0+ filter`() = runTest {
        val testDispatcher = UnconfinedTestDispatcher()
        Dispatchers.setMain(testDispatcher)
        val feedFilterStore = emptyFeedFilterStore()
        val repository = fakeRepositoryAlwaysFailing(ioDispatcher = testDispatcher)
        val vm = createVm(repository, ioDispatcher = testDispatcher, feedFilterStore = feedFilterStore)
        vm.setFeedFilterMinMag(6.0)
        vm.newSinceExpand.test {
            assertEquals(0, awaitItem())
            repository.ingest(freshQuake("m5", mag = 5.0))
            // See the previous test's own kdoc for why this is a plain try/catch around awaitItem()
            // (Turbine's own 3s default timeout), not a nested withTimeoutOrNull.
            val late = try { awaitItem() } catch (expectedWhenGateHolds: AssertionError) { null }
            assertEquals(null, late, "an M5.0 arrival under a 6.0+ filter must never increment newSinceExpand")
            repository.ingest(freshQuake("m6-5", mag = 6.5, timeMillis = 1_950_000 + 3_600_000))
            assertEquals(1, awaitItem(), "an M6.5 arrival under the SAME 6.0+ filter must still increment")
            cancelAndIgnoreRemainingEvents()
        }
    }

    // A gated (filtered-out) arrival must not silently break the OTHER, unrelated effects the same
    // insertedQuakeIds collector drives — refreshFailed clearing still fires regardless of whether
    // the arrival happened to qualify for the feed filter (the gate is scoped to newSinceExpand
    // alone, per this task's own spec — refreshFailed is a "is data flowing at all" signal, not a
    // magnitude-scoped one).
    @Test fun `a sub-threshold arrival still clears refreshFailed even though it does not bump newSinceExpand`() = runTest {
        val testDispatcher = UnconfinedTestDispatcher()
        Dispatchers.setMain(testDispatcher)
        val repository = fakeRepositoryAlwaysFailing(ioDispatcher = testDispatcher)
        val vm = createVm(repository, ioDispatcher = testDispatcher)
        vm.state.test {
            var s = awaitItem()
            while (s is HomeUiState.Loading || (s is HomeUiState.Content && !s.refreshFailed)) s = awaitItem()
            assertTrue(assertIs<HomeUiState.Content>(s).refreshFailed)

            repository.ingest(freshQuake("sub-threshold", mag = 2.2))
            var s2 = awaitItem()
            while (s2 is HomeUiState.Content && s2.refreshFailed) s2 = awaitItem()
            assertFalse(assertIs<HomeUiState.Content>(s2).refreshFailed)
            cancelAndIgnoreRemainingEvents()
        }
    }

    // Task 3b: pins the exact ViewModel-level fact that makes FeedSheet.kt's own reveal wiring need
    // its OWN baseline tracking (a remembered "previous top id", null until the first real
    // emission — see FeedSheet's kdoc) rather than trusting newCount == 0 to mean "nothing to
    // reveal yet." fakeRepositoryWithOneQuake() (not …AlwaysFailing(), which every OTHER test above
    // deliberately uses instead) is the point here: its refreshFeed() genuinely ingests "us1234" as
    // a brand-new row on the very first call, and insertedQuakeIds' own contract (no first-load
    // special case — see HomeViewModel.init's kdoc) means that cold-start insert counts exactly
    // like a live arrival would. Red (pre-existing behavior, not something this task changes):
    // asserting `0` here instead would time out, since the counter never actually settles on 0 once
    // the feed has anything to ingest at all.
    //
    // Flake hardening (CI runs 31936189058, red on a slow GitHub runner; always green locally):
    // fakeRepositoryWithOneQuake() doesn't pin QuakeRepository's ioDispatcher, so ingest()'s
    // _insertedQuakeIds.tryEmit(...) — the write newSinceExpand derives from — runs inside
    // withContext(Dispatchers.Default), a real, uncontrolled thread pool this test does not
    // schedule. Same "the emission chain crosses a pool this test doesn't control" trap the
    // poll-loop test below documents (commit 5e9e922) — Turbine's 3s wall-clock default can expire
    // here too on a starved runner despite virtual time never being the bottleneck. timeout = 30s
    // only widens the failure window for a genuine hang; it never slows a passing run. This
    // particular crossing (QuakeRepository's OWN ioDispatcher, un-pinned by fakeRepositoryWithOneQuake())
    // is deliberately NOT threaded to [testDispatcher] below — out of scope for the flake-hardening
    // pass (2026-08-16) that gave HomeViewModel its own [ioDispatcher] seam (see that class's kdoc);
    // the timeout margin here is an independent, already-sufficient fix for this specific crossing.
    @Test fun `newSinceExpand already reflects quakes ingested by the very first refresh, not just later arrivals`() = runTest {
        val testDispatcher = UnconfinedTestDispatcher()
        Dispatchers.setMain(testDispatcher)
        val vm = createVm(fakeRepositoryWithOneQuake(ioDispatcher = testDispatcher), ioDispatcher = testDispatcher)
        vm.newSinceExpand.test(timeout = 30.seconds) {
            var v = awaitItem()
            while (v == 0) v = awaitItem()
            assertEquals(1, v, "the cold-start ingest of us1234 must count, exactly like a live arrival would")
            cancelAndIgnoreRemainingEvents()
        }
    }

    // feat/feed-visit-ux: visitSummary — HomeViewModel.init{}'s own read of VisitStore.get() +
    // QuakeRepository.newSinceCount(), reduced through the pure visitSummary() fn (FeedSheetTest.kt
    // pins that pure fn's own truth table directly; these two tests instead prove the ViewModel
    // ACTUALLY wires a real store + a real repository query into it end to end).

    @Test fun `visitSummary reports qualifying M4-0+ quakes fetched after a recorded prior visit`() = runTest {
        val testDispatcher = UnconfinedTestDispatcher()
        Dispatchers.setMain(testDispatcher)
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        TerraWatchDb.Schema.create(driver)
        val db = TerraWatchDb(driver)
        var writeClock = 500L
        val dao = QuakeDao(db, clock = { writeClock }, dispatcher = testDispatcher)
        // The recorded visit itself is a plain meta write — independent of the dao's own
        // fetchedAtMillis clock (VisitStore.set never touches a quake row).
        val visitStore = VisitStore(dao).apply { set(1_000L) }
        writeClock = 2_000L // both rows below are fetched strictly AFTER the recorded visit
        dao.replace(freshQuake(id = "qualifies", mag = 5.0), origin = "feed")
        dao.replace(freshQuake(id = "too-small", mag = 2.0), origin = "feed") // below the M4.0 floor
        val engine = MockEngine { respond("", HttpStatusCode.InternalServerError) }
        val repository = QuakeRepository(
            UsgsApi(HttpClient(engine)), EmscLiveSource(HttpClient(engine)), dao,
            clock = { 3_000_000L }, ioDispatcher = testDispatcher,
        )
        val vm = createVm(repository, ioDispatcher = testDispatcher, visitStore = visitStore)
        vm.visitSummary.test(timeout = 30.seconds) {
            var v = awaitItem()
            while (v == null) v = awaitItem()
            assertEquals("1 quake M4.0+ since your last visit", v)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test fun `visitSummary is null when a recorded prior visit has nothing qualifying since`() = runTest {
        val testDispatcher = UnconfinedTestDispatcher()
        Dispatchers.setMain(testDispatcher)
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        TerraWatchDb.Schema.create(driver)
        val db = TerraWatchDb(driver)
        val dao = QuakeDao(db, clock = { 500L }, dispatcher = testDispatcher) // fetched BEFORE the visit
        val visitStore = VisitStore(dao).apply { set(1_000L) }
        dao.replace(freshQuake(id = "too-old", mag = 6.0), origin = "feed")
        val engine = MockEngine { respond("", HttpStatusCode.InternalServerError) }
        val repository = QuakeRepository(
            UsgsApi(HttpClient(engine)), EmscLiveSource(HttpClient(engine)), dao,
            clock = { 3_000_000L }, ioDispatcher = testDispatcher,
        )
        val vm = createVm(repository, ioDispatcher = testDispatcher, visitStore = visitStore)
        // Unlike the positive test above, null is both the initial default AND the correctly-
        // settled value here, so there is no transient-to-skip the way a Turbine await-loop would
        // need — this instead independently re-proves the query itself returned zero (the same
        // fact QuakeDaoTest's own "newSinceCount cutoff comparison is strict greater-than" case
        // pins at the DAO layer) before trusting the ViewModel-level value, so a passing assertion
        // here can't be mistaken for "the computation simply never ran".
        assertEquals(0L, dao.newSinceCount(sinceMillis = 1_000L, minMag = 4.0))
        assertEquals(null, vm.visitSummary.value)
    }

    // Task 9: homeLocation. Seeds the store via HomeLocationStore.set() (the same dao-backed path
    // HomeViewModel itself reads through), then asserts the ViewModel's own flow eventually
    // reflects it — proving the init{} load actually reads from the injected store rather than,
    // say, silently defaulting to null or only consulting LocationProvider.
    @Test fun `homeLocation loads the previously stored point`() = runTest {
        val testDispatcher = UnconfinedTestDispatcher()
        Dispatchers.setMain(testDispatcher)
        val store = emptyHomeLocationStore().apply { set(GeoPoint(12.34, 56.78)) }
        val vm = createVm(fakeRepositoryAlwaysFailing(ioDispatcher = testDispatcher), store, ioDispatcher = testDispatcher)
        vm.homeLocation.test {
            var v = awaitItem()
            while (v == null) v = awaitItem()
            assertEquals(GeoPoint(12.34, 56.78), v)
            cancelAndIgnoreRemainingEvents()
        }
    }

    // Task 2 (Plan 3), "close the location loop": homeLocation used to be resolved ONCE at
    // startup (a plain `val stored = store.get() ?: ...` assignment, never revisited) — a grant or
    // city-pick landing mid-session (HomeLocationStore.set(), called from MainActivity's permission
    // callback or LocationAskDialog's CityPickerDialog) would sit in the DB forever, invisible to
    // this already-running ViewModel, until the next process restart re-ran init{}. This is the
    // device-observable bug: the ASK pill staying frozen even after the user just granted location.
    //
    // The store starts genuinely empty (no seeded point, and createVm()'s default LocationProvider()
    // jvm actual always resolves null too — see LocationProvider.jvm.kt) so the FIRST emission is
    // deterministically null, not a race between "not yet resolved" and "resolved to something" the
    // way the seeded test above has to loop over — only [store.set] below should ever produce a
    // non-null value here.
    @Test fun `homeLocation reacts to a store update landing mid-session, no restart needed`() = runTest {
        val testDispatcher = UnconfinedTestDispatcher()
        Dispatchers.setMain(testDispatcher)
        val store = emptyHomeLocationStore()
        val vm = createVm(fakeRepositoryAlwaysFailing(ioDispatcher = testDispatcher), store, ioDispatcher = testDispatcher)
        vm.homeLocation.test {
            assertEquals(null, awaitItem())
            store.set(GeoPoint(9.9, 8.8))
            assertEquals(GeoPoint(9.9, 8.8), awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    // Task 1 (Plan 5), USER REQUIREMENT: startupCameraTarget/recenterTarget/
    // locationUnavailableEvents. KNOWN COVERAGE LIMIT (documented rather than silently accepted —
    // same "flag the gap, don't hide it" discipline this codebase already applies elsewhere, e.g.
    // core/model's GeoTest "KNOWN LIMITATION" test): LocationProvider/LocationRequester are plain
    // `expect`/`actual` CLASSES (not interfaces), neither `open`, so no jvmTest fake can make either
    // one report anything other than what LocationProvider.jvm.kt/LocationRequester.jvm.kt
    // hardcode — always a null fix, always NOT_APPLICABLE (-> GRANTED). That makes the
    // NON-null-fix half of startupCameraTarget's own wiring (createVm()'s default
    // `LocationProvider()`) untestable at this level by construction: it can never be proven here
    // that a real "fix differs >50km from stored home" scenario actually reaches the camera — only
    // on the real device (this task's own device-verification step) does that path run for real.
    // What jvmTest CAN prove — and what's pinned below — is everything on THIS side of that
    // platform boundary: the null-fix degrade-to-"do nothing" path, and recenterToCurrentLocation's
    // own null-fix -> snackbar-event branch (which — precisely because the jvm actual always
    // returns null — is fully exercised for real here, not merely a default-value smoke check).
    // [startupCameraTarget]'s own full 5-case decision table is pinned exhaustively, independent of
    // any of this, by CameraTargetTest.kt.

    // Flake hardening (same class as the newSinceExpand fix above, superseded 2026-08-16): this
    // value used to be written from init's own hard-coded `viewModelScope.launch(Dispatchers.Default)
    // { ... }` block with no pinnable seam -- now routed through HomeViewModel's own [ioDispatcher]
    // (see its kdoc), pinned to [testDispatcher] below, the SAME instance backing Dispatchers.Main.
    // timeout = 30.seconds kept as a harmless belt (matches the task's own "remove/keep as-is"
    // guidance), not because this still crosses an un-pinnable pool.
    @Test fun `startupCameraTarget stays null when the platform has no location fix to offer`() = runTest {
        val testDispatcher = UnconfinedTestDispatcher()
        Dispatchers.setMain(testDispatcher)
        val vm = createVm(fakeRepositoryAlwaysFailing(ioDispatcher = testDispatcher), ioDispatcher = testDispatcher)
        vm.startupCameraTarget.test(timeout = 30.seconds) {
            assertEquals(null, awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    // Flake hardening (superseded 2026-08-16): recenterToCurrentLocation() used to launch on a
    // hard-coded Dispatchers.Default with no seam -- now routed through [ioDispatcher] (see its
    // kdoc), pinned below to the same instance backing Main. Timeout kept as a harmless belt.
    @Test fun `recenterToCurrentLocation emits a locationUnavailableEvent when no fix is available`() = runTest {
        val testDispatcher = UnconfinedTestDispatcher()
        Dispatchers.setMain(testDispatcher)
        val vm = createVm(fakeRepositoryAlwaysFailing(ioDispatcher = testDispatcher), ioDispatcher = testDispatcher)
        vm.locationUnavailableEvents.test(timeout = 30.seconds) {
            vm.recenterToCurrentLocation()
            awaitItem() // Unit - the event itself firing is the whole assertion
            cancelAndIgnoreRemainingEvents()
        }
        // The complementary outcome: a null fix must NOT also populate recenterTarget - the two
        // are meant to be mutually exclusive (see recenterToCurrentLocation's own kdoc).
        assertEquals(null, vm.recenterTarget.value)
    }

    @Test fun `consumeStartupCameraTarget and consumeRecenterTarget are safe no-ops with nothing pending`() = runTest {
        val testDispatcher = UnconfinedTestDispatcher()
        Dispatchers.setMain(testDispatcher)
        val vm = createVm(fakeRepositoryAlwaysFailing(ioDispatcher = testDispatcher), ioDispatcher = testDispatcher)
        vm.consumeStartupCameraTarget()
        vm.consumeRecenterTarget()
        assertEquals(null, vm.startupCameraTarget.value)
        assertEquals(null, vm.recenterTarget.value)
    }

    // Task 2 (Plan 5): favorites -- mirrors homeLocation's own "loads the stored value, then reacts
    // live to a store update" shape (see that field's two tests above), applied to
    // FavoritePlaceStore.favorites instead of HomeLocationStore.

    // Flake hardening: FavoritePlaceStore.favorites is QuakeDao.favoritePlaces(), which hard-codes
    // `.asFlow().mapToList(Dispatchers.Default)` (QuakeDao.kt) — a real thread-pool hop no
    // ioDispatcher pin on the repository OR on HomeViewModel's own new seam can reach (a separate
    // module's DAO-level crossing — see HomeViewModel.ioDispatcher's own kdoc for why this is
    // deliberately not plumbed further). Same starved-runner exposure as newSinceExpand's own fix
    // above; timeout = 30.seconds on all three favorites tests below (kept as belt-and-braces; each
    // test also now pins HomeViewModel's OWN ioDispatcher via [testDispatcher], closing that
    // separate TestMainDispatcher race this file's class kdoc documents).
    @Test fun `favorites starts empty when the store has none`() = runTest {
        val testDispatcher = UnconfinedTestDispatcher()
        Dispatchers.setMain(testDispatcher)
        val vm = createVm(fakeRepositoryAlwaysFailing(ioDispatcher = testDispatcher), ioDispatcher = testDispatcher)
        vm.favorites.test(timeout = 30.seconds) {
            assertEquals(emptyList(), awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test fun `favorites loads previously-added places`() = runTest {
        val testDispatcher = UnconfinedTestDispatcher()
        Dispatchers.setMain(testDispatcher)
        val favoritePlaceStore = emptyFavoritePlaceStore().apply { add("Tokyo", GeoPoint(35.6762, 139.6503)) }
        val vm = createVm(fakeRepositoryAlwaysFailing(ioDispatcher = testDispatcher), favoritePlaceStore = favoritePlaceStore, ioDispatcher = testDispatcher)
        vm.favorites.test(timeout = 30.seconds) {
            var v = awaitItem()
            while (v.isEmpty()) v = awaitItem()
            assertEquals("Tokyo", v.single().label)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test fun `favorites reacts to a store update landing mid-session`() = runTest {
        val testDispatcher = UnconfinedTestDispatcher()
        Dispatchers.setMain(testDispatcher)
        val favoritePlaceStore = emptyFavoritePlaceStore()
        val vm = createVm(fakeRepositoryAlwaysFailing(ioDispatcher = testDispatcher), favoritePlaceStore = favoritePlaceStore, ioDispatcher = testDispatcher)
        vm.favorites.test(timeout = 30.seconds) {
            assertEquals(emptyList(), awaitItem())
            favoritePlaceStore.add("Delhi", GeoPoint(28.6139, 77.2090))
            assertEquals(listOf("Delhi"), awaitItem().map { it.label })
            cancelAndIgnoreRemainingEvents()
        }
    }

    // Task 2 (Plan 5): the Home quick-switch chips' own session-only pill-target swap --
    // focusTarget starts null (home is the pill's reference point); focusFavorite/focusHome both
    // reuse the Task 1 recenterTarget flow for the camera fly, per this task's own dispatch
    // ("reuse Task 1 recenterTarget flow if suitable").

    @Test fun `focusTarget starts null -- home is the pill's default reference point`() = runTest {
        val testDispatcher = UnconfinedTestDispatcher()
        Dispatchers.setMain(testDispatcher)
        val vm = createVm(fakeRepositoryAlwaysFailing(ioDispatcher = testDispatcher), ioDispatcher = testDispatcher)
        assertEquals(null, vm.focusTarget.value)
    }

    @Test fun `focusFavorite sets both focusTarget and recenterTarget to the favorite's point`() = runTest {
        val testDispatcher = UnconfinedTestDispatcher()
        Dispatchers.setMain(testDispatcher)
        val vm = createVm(fakeRepositoryAlwaysFailing(ioDispatcher = testDispatcher), ioDispatcher = testDispatcher)
        val tokyo = GeoPoint(35.6762, 139.6503)
        vm.focusFavorite(tokyo)
        assertEquals(tokyo, vm.focusTarget.value)
        assertEquals(tokyo, vm.recenterTarget.value)
    }

    @Test fun `focusHome resets focusTarget to null and flies the camera back to the current home`() = runTest {
        val testDispatcher = UnconfinedTestDispatcher()
        Dispatchers.setMain(testDispatcher)
        val homeLocationStore = emptyHomeLocationStore().apply { set(GeoPoint(12.34, 56.78)) }
        val vm = createVm(fakeRepositoryAlwaysFailing(ioDispatcher = testDispatcher), homeLocationStore = homeLocationStore, ioDispatcher = testDispatcher)
        // Let the init{} block's one-shot homeLocation load resolve before focusing a favorite.
        // Flake-hardening pass (2026-08-16, superseded prior note): that one-shot load now runs on
        // [testDispatcher] via HomeViewModel's own ioDispatcher seam (see its kdoc) — timeout kept
        // as a harmless belt, not because this still crosses an un-pinnable pool.
        vm.homeLocation.test(timeout = 30.seconds) {
            var v = awaitItem()
            while (v == null) v = awaitItem()
            cancelAndIgnoreRemainingEvents()
        }
        vm.focusFavorite(GeoPoint(35.6762, 139.6503))
        vm.focusHome()
        assertEquals(null, vm.focusTarget.value)
        assertEquals(GeoPoint(12.34, 56.78), vm.recenterTarget.value)
    }

    @Test fun `focusHome with no resolved home yet does not crash and still clears focusTarget`() = runTest {
        val testDispatcher = UnconfinedTestDispatcher()
        Dispatchers.setMain(testDispatcher)
        val vm = createVm(fakeRepositoryAlwaysFailing(ioDispatcher = testDispatcher), ioDispatcher = testDispatcher)
        vm.focusFavorite(GeoPoint(1.0, 2.0))
        vm.focusHome()
        assertEquals(null, vm.focusTarget.value)
    }

    // Task 11's selection wiring tests (`select`/`dismissSelection`/`selectedQuake`) MIGRATED to
    // QuakeSelectionViewModelTest.kt as of Task 3 (Plan 3) — see QuakeSelectionViewModel's own
    // kdoc for why that state no longer lives on this class at all.

    // Task 7 (Plan 3), USER REQUIREMENT: nearbyRadiusKm/minMag — same "loads the stored value, then
    // reacts live to a store update" shape the homeLocation tests above already pin for
    // HomeLocationStore, applied to AlertRuleStore's own Flows instead of a get()+updates split.

    @Test fun `nearbyRadiusKm defaults to AlertRuleStore's own 100km default`() = runTest {
        val testDispatcher = UnconfinedTestDispatcher()
        Dispatchers.setMain(testDispatcher)
        val vm = createVm(fakeRepositoryAlwaysFailing(ioDispatcher = testDispatcher), ioDispatcher = testDispatcher)
        vm.nearbyRadiusKm.test {
            assertEquals(100.0, awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test fun `nearbyRadiusKm reacts to a store update landing mid-session, no restart needed`() = runTest {
        val testDispatcher = UnconfinedTestDispatcher()
        Dispatchers.setMain(testDispatcher)
        val alertRuleStore = emptyAlertRuleStore()
        val vm = createVm(fakeRepositoryAlwaysFailing(ioDispatcher = testDispatcher), alertRuleStore = alertRuleStore, ioDispatcher = testDispatcher)
        vm.nearbyRadiusKm.test {
            assertEquals(100.0, awaitItem())
            alertRuleStore.setNearbyRadius(500.0)
            assertEquals(500.0, awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test fun `minMag reacts to a store update landing mid-session`() = runTest {
        val testDispatcher = UnconfinedTestDispatcher()
        Dispatchers.setMain(testDispatcher)
        val alertRuleStore = emptyAlertRuleStore()
        val vm = createVm(fakeRepositoryAlwaysFailing(ioDispatcher = testDispatcher), alertRuleStore = alertRuleStore, ioDispatcher = testDispatcher)
        vm.minMag.test {
            assertEquals(4.5, awaitItem())
            alertRuleStore.setMinMag(6.0)
            assertEquals(6.0, awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    // Task 2 (Plan 4), F1 retention ruling: init's new pruneOldRows(clock() - 30d) call, wired
    // end-to-end through the real ViewModel/repository/DAO stack (not just at QuakeDaoTest/
    // QuakeRepositoryTest's lower levels, which pin the mechanism itself). ioDispatcher is pinned to
    // the SAME UnconfinedTestDispatcher instance backing Dispatchers.Main, so the purge+prune
    // launch{} block (no genuine suspension point anywhere in either call — a synchronous SQLite
    // read/write through a `withContext` hop onto a dispatcher that's already current is a no-op
    // re-dispatch) runs eagerly to completion inline, before createVm() even returns.
    // `runCurrent()` right after is a defensive flush, not a load-bearing wait — NOT
    // `advanceUntilIdle()`: this ViewModel's OWN refresh-loop coroutine (`while (isActive) {
    // delay(POLL_INTERVAL_MILLIS); refreshOnce() }`) never terminates by construction, so
    // `advanceUntilIdle()` fast-forwards through that delay, runs another tick, schedules the next
    // delay, and repeats forever — confirmed by actually running it that way first and watching the
    // whole test JVM OOM (EVIDENCE INTEGRITY). `runCurrent()` only drains work already due at the
    // CURRENT virtual instant, never fast-forwarding past a future delay, so it can't hit that trap.
    @Test fun `init prunes old feed rows but protects archive rows, via the injected clock`() = runTest {
        val testDispatcher = UnconfinedTestDispatcher()
        Dispatchers.setMain(testDispatcher)
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        TerraWatchDb.Schema.create(driver)
        val dao = QuakeDao(TerraWatchDb(driver), dispatcher = testDispatcher)
        val now = 100_000_000_000L
        val old = now - 40L * 24 * 60 * 60 * 1000 // 40 days before `now` -- past the 30-day cutoff
        dao.replace(freshQuake("old-feed", timeMillis = old))                     // origin defaults "feed"
        dao.replace(freshQuake("old-archive", timeMillis = old), origin = "archive")
        val engine = MockEngine { respond("", HttpStatusCode.InternalServerError) }
        val repository = QuakeRepository(
            UsgsApi(HttpClient(engine)), EmscLiveSource(HttpClient(engine)), dao,
            clock = { now }, ioDispatcher = testDispatcher,
        )
        createVm(repository, clock = { now }, ioDispatcher = testDispatcher)
        testDispatcher.scheduler.runCurrent()

        assertEquals(null, dao.byId("old-feed"), "40-day-old feed row must be pruned")
        assertEquals("old-archive", dao.byId("old-archive")?.id, "archive rows are exempt regardless of age")
    }
}

// A fresh, empty in-memory-backed HomeLocationStore — used by every test above that needs
// *a* HomeLocationStore to satisfy HomeViewModel's constructor but doesn't care what (if
// anything) it resolves to.
private fun emptyHomeLocationStore(): HomeLocationStore {
    val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
    TerraWatchDb.Schema.create(driver)
    return HomeLocationStore(QuakeDao(TerraWatchDb(driver), dispatcher = UnconfinedTestDispatcher()))
}

// Task 7 (Plan 3): same "fresh, empty, don't-care-what-it-resolves-to" role as
// emptyHomeLocationStore() above, for HomeViewModel's new AlertRuleStore constructor param — every
// pre-existing test that doesn't care about radius/minMag wiring gets this via createVm()'s default.
private fun emptyAlertRuleStore(): AlertRuleStore {
    val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
    TerraWatchDb.Schema.create(driver)
    return AlertRuleStore(QuakeDao(TerraWatchDb(driver), dispatcher = UnconfinedTestDispatcher()))
}

// Task 2 (Plan 5): same "fresh, empty, don't-care-what-it-resolves-to" role as
// emptyHomeLocationStore()/emptyAlertRuleStore() above, for HomeViewModel's new FavoritePlaceStore
// constructor param.
private fun emptyFavoritePlaceStore(): FavoritePlaceStore {
    val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
    TerraWatchDb.Schema.create(driver)
    return FavoritePlaceStore(QuakeDao(TerraWatchDb(driver), dispatcher = UnconfinedTestDispatcher()))
}

// Builds a real QuakeRepository over an in-memory JVM SQLDelight driver with a MockEngine that
// returns one-feature GeoJSON for the feed request — reuses FeedViewModelTest's construction
// pattern, but injects an explicit QuakeDao clock so lastFetchedAtMillis() is a known value rather
// than the QuakeDao default (0L).
//
// Flake-hardening pass (2026-08-16, round 2 -- the FIRST round's HomeViewModel.ioDispatcher seam
// alone did NOT close the pre-existing TestMainDispatcher race; 3/26 in a follow-up 30x isolated
// run, same exception signature, same tearDown()-resetMain() attribution as before): this
// repository's OWN `ioDispatcher` (QuakeRepository's pre-existing ctor param -- a DIFFERENT
// dispatcher from HomeViewModel's own) was still defaulting to real Dispatchers.Default here, and
// HomeViewModel.init unconditionally calls `repository.purgeDebugQuakes()`/`repository.
// pruneOldRows()` (and the poll loop calls `repository.refreshFeed()`) from a plain Main-dispatched
// `viewModelScope.launch { ... }` -- i.e. exactly Task-13's own already-documented "Main-dispatched
// parent, Default-dispatched child" shape, just via the REPOSITORY's internal hop instead of a
// HomeViewModel-level one. InsightsViewModelTest's own `repository()`/`createVm()` helpers already
// pin this identical parameter for every one of their tests (see that file's kdoc) -- this was the
// missing piece to actually match that precedent, not merely resemble it. Defaulted to the real
// Dispatchers.Default (compile-safe), every call site below now passes its own testDispatcher.
private fun fakeRepositoryWithOneQuake(ioDispatcher: CoroutineDispatcher = Dispatchers.Default): QuakeRepository {
    val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
    TerraWatchDb.Schema.create(driver)
    val dao = QuakeDao(TerraWatchDb(driver), clock = { FETCH_CLOCK_MILLIS }, dispatcher = ioDispatcher)
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
        ioDispatcher = ioDispatcher,
    )
}

// Task 1 (Plan 3): [clock] gained a parameter (default unchanged from before this task) so the
// sliding-window test below can mutate "now" out from under an already-constructed repository —
// same "add a defaulted param for one new test" precedent as [freshQuake]'s own [timeMillis]
// parameter further down this file. [ioDispatcher]: see fakeRepositoryWithOneQuake's own kdoc above
// for the flake-hardening round-2 fix this closes.
private fun fakeRepositoryAlwaysFailing(
    clock: () -> Long = { 2_000_000L },
    ioDispatcher: CoroutineDispatcher = Dispatchers.Default,
): QuakeRepository {
    val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
    TerraWatchDb.Schema.create(driver)
    val dao = QuakeDao(TerraWatchDb(driver), dispatcher = ioDispatcher)
    val engine = MockEngine { respond("", HttpStatusCode.InternalServerError) }
    return QuakeRepository(
        UsgsApi(HttpClient(engine)),
        EmscLiveSource(HttpClient(engine)),
        dao,
        clock = clock,
        ioDispatcher = ioDispatcher,
    )
}

// Task 2 (Plan 3) carry-in: gates every feed response on [gate] before resolving FAILED (500) --
// used to hold the very first refreshOnce() call in flight while the test ingests a live-style
// quake out from under it, reproducing "a slow-failed poll landing after a live-clear" in a
// controlled, deterministic order rather than hoping for a real race. [ioDispatcher]: see
// fakeRepositoryWithOneQuake's own kdoc for the flake-hardening round-2 fix this closes.
private fun fakeRepositorySlowThenFailing(
    gate: CompletableDeferred<Unit>,
    ioDispatcher: CoroutineDispatcher = Dispatchers.Default,
): QuakeRepository {
    val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
    TerraWatchDb.Schema.create(driver)
    val dao = QuakeDao(TerraWatchDb(driver), dispatcher = ioDispatcher)
    val engine = MockEngine {
        gate.await()
        respond("", HttpStatusCode.InternalServerError)
    }
    return QuakeRepository(
        UsgsApi(HttpClient(engine)),
        EmscLiveSource(HttpClient(engine)),
        dao,
        clock = { 2_000_000L },
        ioDispatcher = ioDispatcher,
    )
}

// Pre-seeds the DAO directly (bypassing any network call) with one quake, then gates the feed
// MockEngine's response on [gate] so refreshFeed() stays suspended until the test completes it.
// [ioDispatcher]: see fakeRepositoryWithOneQuake's own kdoc for the flake-hardening round-2 fix
// this closes.
private fun fakeRepositorySeededWithOneQuake(
    gate: CompletableDeferred<Unit>,
    ioDispatcher: CoroutineDispatcher = Dispatchers.Default,
): QuakeRepository {
    val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
    TerraWatchDb.Schema.create(driver)
    val dao = QuakeDao(TerraWatchDb(driver), dispatcher = ioDispatcher)
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
        ioDispatcher = ioDispatcher,
    )
}

// Task 12 review Fix 1's test (below) needs a SECOND quake DedupeEngine won't merge into the
// first: its window is 90s / 100km (DedupeEngine.kt), so [timeMillis] is now a parameter — every
// pre-existing call site keeps the original hardcoded 1_950_000 via the default, only the new test
// passes a value far outside that window.
// feat/feed-visit-ux: [mag] is now a THIRD, defaulted parameter (default unchanged at 4.0, so
// every pre-existing call site above keeps compiling and behaving identically) — same "add a
// defaulted param for one new test" precedent [timeMillis] itself already established, needed by
// the visitSummary tests below to construct quakes both above and below the M4.0+ banner floor.
private fun freshQuake(id: String, timeMillis: Long = 1_950_000, mag: Double? = 4.0) = Quake(
    id = id, timeMillis = timeMillis, lat = 1.0, lon = 2.0, depthKm = 5.0,
    mag = mag, magType = "mw", place = "Fresh", tsunami = false, felt = null,
    status = QuakeStatus.AUTOMATIC, sources = mapOf(Source.USGS to id),
    revisions = listOf(MagRevision(mag ?: 0.0, "mw", timeMillis, Source.USGS)),
    updatedAtMillis = timeMillis,
)

// feat/feed-visit-ux: same "fresh, empty, don't-care-what-it-resolves-to" role as
// emptyHomeLocationStore()/emptyAlertRuleStore()/emptyFavoritePlaceStore() above, for
// HomeViewModel's new VisitStore constructor param.
private fun emptyVisitStore(): VisitStore {
    val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
    TerraWatchDb.Schema.create(driver)
    return VisitStore(QuakeDao(TerraWatchDb(driver), dispatcher = UnconfinedTestDispatcher()))
}

// User review items 3+4: same "fresh, empty, don't-care-what-it-resolves-to" role as
// emptyVisitStore()/emptyAlertRuleStore() above, for HomeViewModel's new FeedFilterStore
// constructor param — "empty" here still resolves to FeedFilterStore's own real 4.0 default
// (nothing written to the underlying dao yet), matching the user's own "first-run default 4.0+"
// instruction for every test that doesn't explicitly override it.
private fun emptyFeedFilterStore(): FeedFilterStore {
    val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
    TerraWatchDb.Schema.create(driver)
    return FeedFilterStore(QuakeDao(TerraWatchDb(driver), dispatcher = UnconfinedTestDispatcher()))
}

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
