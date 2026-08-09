package com.yugma.terrawatch.home

// Same jvmTest-not-commonTest rationale as FeedViewModelTest: fakeRepositoryWithOneQuake() builds a
// real QuakeRepository over app.cash.sqldelight's JDBC in-memory driver, a JVM-only artifact.
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

// Distinguishable from QuakeRepository's own `clock` (windowing clock, kept at the existing
// suite's usual 2_000_000L) so the "lastUpdatedMillis populated" test asserts a specific value
// flows all the way from QuakeDao's write-time clock through the repository into UI state, not
// merely "happens to be non-null".
private const val FETCH_CLOCK_MILLIS = 5_000_000L

class HomeViewModelTest {
    // Same Dispatchers.Main reset/Unconfined rationale as FeedViewModelTest: HomeViewModel.init
    // unconditionally calls repository.startLive(viewModelScope), which retries forever with
    // delay() on a MockEngine that has no WebSockets plugin installed.
    @AfterTest fun tearDown() {
        Dispatchers.resetMain()
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
        val vm = HomeViewModel(fakeRepositoryWithOneQuake(), emptyHomeLocationStore(), LocationProvider())
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
        val vm = HomeViewModel(fakeRepositoryAlwaysFailing(), emptyHomeLocationStore(), LocationProvider())
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
        val vm = HomeViewModel(fakeRepositoryWithOneQuake(), emptyHomeLocationStore(), LocationProvider())
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
        val vm = HomeViewModel(fakeRepositoryWithOneQuake(), emptyHomeLocationStore(), LocationProvider())
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
        val vm = HomeViewModel(fakeRepositoryWithOneQuake(), emptyHomeLocationStore(), LocationProvider())
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
        val vm = HomeViewModel(repository, emptyHomeLocationStore(), LocationProvider())
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
        val vm = HomeViewModel(fakeRepositorySeededWithOneQuake(gate), emptyHomeLocationStore(), LocationProvider())
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

    // Task 9: the feed sheet's "N NEW" chip. fakeRepositoryAlwaysFailing() (not …WithOneQuake())
    // deliberately: its refreshFeed() never calls ingest() on anything, so the counter starts
    // from a known, deterministic 0 rather than racing whatever the network-seeded quake in the
    // "WithOneQuake" fake would otherwise add to it.
    @Test fun `newSinceExpand increments once per newly inserted quake`() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        val repository = fakeRepositoryAlwaysFailing()
        val vm = HomeViewModel(repository, emptyHomeLocationStore(), LocationProvider())
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
        val vm = HomeViewModel(repository, emptyHomeLocationStore(), LocationProvider())
        vm.newSinceExpand.test {
            assertEquals(0, awaitItem())
            repository.ingest(freshQuake("new1"))
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
        val vm = HomeViewModel(fakeRepositoryAlwaysFailing(), store, LocationProvider())
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
        val vm = HomeViewModel(fakeRepositoryWithOneQuake(), emptyHomeLocationStore(), LocationProvider())
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
        val vm = HomeViewModel(fakeRepositoryWithOneQuake(), emptyHomeLocationStore(), LocationProvider())
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
        val vm = HomeViewModel(fakeRepositoryWithOneQuake(), emptyHomeLocationStore(), LocationProvider())
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

private fun fakeRepositoryAlwaysFailing(): QuakeRepository {
    val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
    TerraWatchDb.Schema.create(driver)
    val dao = QuakeDao(TerraWatchDb(driver))
    val engine = MockEngine { respond("", HttpStatusCode.InternalServerError) }
    return QuakeRepository(
        UsgsApi(HttpClient(engine)),
        EmscLiveSource(HttpClient(engine)),
        dao,
        clock = { 2_000_000L },
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

private fun freshQuake(id: String) = Quake(
    id = id, timeMillis = 1_950_000, lat = 1.0, lon = 2.0, depthKm = 5.0,
    mag = 4.0, magType = "mw", place = "Fresh", tsunami = false, felt = null,
    status = QuakeStatus.AUTOMATIC, sources = mapOf(Source.USGS to id),
    revisions = listOf(MagRevision(4.0, "mw", 1_950_000, Source.USGS)),
    updatedAtMillis = 1_950_000,
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
