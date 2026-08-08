package com.yugma.terrawatch.home

// Same jvmTest-not-commonTest rationale as FeedViewModelTest: fakeRepositoryWithOneQuake() builds a
// real QuakeRepository over app.cash.sqldelight's JDBC in-memory driver, a JVM-only artifact.
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import app.cash.turbine.test
import com.yugma.terrawatch.data.QuakeRepository
import com.yugma.terrawatch.database.QuakeDao
import com.yugma.terrawatch.database.TerraWatchDb
import com.yugma.terrawatch.model.MagnitudeBand
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
import kotlin.test.assertEquals
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

    @Test fun `loads feed into content state`() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        val vm = HomeViewModel(fakeRepositoryWithOneQuake())
        vm.state.test {
            assertIs<HomeUiState.Loading>(awaitItem())
            val content = awaitItem()
            assertIs<HomeUiState.Content>(content)
            assertTrue(content.quakes.isNotEmpty())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test fun `refreshFailed is true when the initial refresh fails`() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        val vm = HomeViewModel(fakeRepositoryAlwaysFailing())
        vm.state.test {
            var s = awaitItem()
            while (s is HomeUiState.Loading) s = awaitItem()
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
        val vm = HomeViewModel(fakeRepositoryWithOneQuake())
        vm.state.test {
            var s = awaitItem()
            while (s is HomeUiState.Loading) s = awaitItem()
            val content = assertIs<HomeUiState.Content>(s)
            val pin = content.pins.single()
            assertEquals("us1234", pin.id)
            assertEquals(5.5, pin.mag)
            assertEquals(MagnitudeBand.STRONG, pin.band) // 4.5 <= 5.5 < 6.0
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test fun `lastUpdatedMillis is populated from the repository's fetch clock`() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        val vm = HomeViewModel(fakeRepositoryWithOneQuake())
        vm.state.test {
            var s = awaitItem()
            while (s is HomeUiState.Loading) s = awaitItem()
            val content = assertIs<HomeUiState.Content>(s)
            assertEquals(FETCH_CLOCK_MILLIS, content.lastUpdatedMillis)
            cancelAndIgnoreRemainingEvents()
        }
    }
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
