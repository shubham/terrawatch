package com.yugma.terrawatch.feed

// This suite lives in jvmTest, not commonTest: fakeRepositoryWithOneQuake() builds a real
// QuakeRepository over app.cash.sqldelight's JDBC in-memory driver, which is a JVM-only artifact
// (core:database only puts it on the jvmMain/jvmTest classpath) — commonTest compiles against
// androidTarget and wasmJs too, neither of which has that class. Sanctioned by the task brief.
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import app.cash.turbine.test
import com.yugma.terrawatch.data.QuakeRepository
import com.yugma.terrawatch.database.QuakeDao
import com.yugma.terrawatch.database.TerraWatchDb
import com.yugma.terrawatch.network.EmscLiveSource
import com.yugma.terrawatch.network.UsgsApi
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertIs
import kotlin.test.assertTrue

class FeedViewModelTest {
    // Dispatchers.setMain(...) installs a classloader-global override — without resetting it,
    // the next test in this class (or file) would silently inherit whichever TestDispatcher the
    // previous test left behind instead of getting its own.
    @AfterTest fun tearDown() {
        Dispatchers.resetMain()
    }

    // FeedViewModel.init unconditionally calls repository.startLive(viewModelScope), which
    // subscribes to EmscLiveSource.events() — a flow that retries forever with exponential
    // backoff delay() on any failure. The fake HttpClient below has no WebSockets plugin
    // installed, so every attempt fails immediately, and that retry+delay() loop runs on
    // whatever Dispatchers.Main resolves to for viewModelScope.
    // Wiring Main to StandardTestDispatcher(testScheduler) — sharing this test's own scheduler,
    // as kotlinx-coroutines-test samples typically show — was tried first and hangs empirically:
    // runTest's advanceUntilIdle-based draining pumps that shared scheduler until it is idle,
    // which never happens with an infinite delay()-rescheduling loop feeding it (confirmed via
    // jstack: the test worker spun at 100% CPU inside TestCoroutineScheduler.advanceUntilIdleOr,
    // stuck at EmscLiveSource.kt's delay() call, for 5+ real minutes with no built-in timeout).
    // UnconfinedTestDispatcher() below gives viewModelScope its own, separate scheduler that
    // nothing in this test ever drives: the retry loop's delay() parks on it forever (harmless —
    // it's simply never resumed), while dispatch that doesn't need virtual time to pass (every
    // other line the ViewModel runs) still executes eagerly. This test's own coroutine keeps
    // using the runTest-provided scheduler untouched, so awaitItem() below is unaffected.
    @Test fun `loads feed into content state`() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        val vm = FeedViewModel(repository = fakeRepositoryWithOneQuake())
        vm.state.test {
            assertIs<FeedUiState.Loading>(awaitItem())
            val content = awaitItem()
            assertIs<FeedUiState.Content>(content)
            assertTrue(content.quakes.isNotEmpty())
            cancelAndIgnoreRemainingEvents()
        }
    }

    // Review finding (Critical): on a fresh install with zero local rows, recentQuakes() emits
    // its current (empty) snapshot immediately on subscribe — that emission ran unconditionally
    // and clobbered the Error state set moments earlier, so a total refresh failure permanently
    // settled on a blank Content(emptyList) screen instead of surfacing the error. Deterministic,
    // not a race: the DB is empty here regardless of timing, since nothing was ever ingested.
    @Test fun `total refresh failure on empty db settles on Error, not blank Content`() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        val vm = FeedViewModel(repository = fakeRepositoryAlwaysFailing())
        vm.state.test {
            var s = awaitItem()
            while (s is FeedUiState.Loading) s = awaitItem()
            assertIs<FeedUiState.Error>(s)
            expectNoEvents()
            cancelAndIgnoreRemainingEvents()
        }
    }
}

// Builds a real QuakeRepository over an in-memory JVM SQLDelight driver with a MockEngine that
// returns one-feature GeoJSON for the feed request — reuses QuakeRepositoryTest's construction
// pattern (core/data/src/jvmTest/kotlin/com/yugma/terrawatch/data/QuakeRepositoryTest.kt).
private fun fakeRepositoryWithOneQuake(): QuakeRepository {
    val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
    TerraWatchDb.Schema.create(driver)
    val dao = QuakeDao(TerraWatchDb(driver))
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

// Builds a real QuakeRepository whose HttpClient returns HTTP 500 for every request: fetchFeed()
// maps that to FeedResult.Failure -> RefreshStatus.FAILED, and the (unrelated) EMSC websocket
// attempt fails immediately too — same MockEngine, no WebSockets plugin installed — which is
// harmless here since nothing in this test ever drives that retry loop's scheduler forward (see
// the UnconfinedTestDispatcher note above).
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
