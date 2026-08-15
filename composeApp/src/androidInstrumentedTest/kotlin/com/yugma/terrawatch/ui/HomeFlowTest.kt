package com.yugma.terrawatch.ui

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.test.platform.app.InstrumentationRegistry
import app.cash.sqldelight.driver.android.AndroidSqliteDriver
import com.yugma.terrawatch.data.AlertRuleStore
import com.yugma.terrawatch.data.HomeLocationStore
import com.yugma.terrawatch.data.QuakeRepository
import com.yugma.terrawatch.database.QuakeDao
import com.yugma.terrawatch.database.TerraWatchDb
import com.yugma.terrawatch.home.HomeScreen
import com.yugma.terrawatch.home.HomeViewModel
import com.yugma.terrawatch.home.QuakeSelectionViewModel
import com.yugma.terrawatch.location.LocationProvider
import com.yugma.terrawatch.network.EmscLiveSource
import com.yugma.terrawatch.network.UsgsApi
import com.yugma.terrawatch.ui.theme.TerraTheme
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Rule
import org.junit.Test

/**
 * Task 13: the one DI-backed flow test the brief asks for — [HomeScreen] wired to a real
 * [HomeViewModel] over a real (throwaway) [QuakeRepository], proving Loading -> Content and the
 * offline banner actually reach the composed UI end to end, not just HomeViewModelTest's
 * state-flow-level (jvmTest) assertions.
 *
 * "DI-backed" is read here as "a real object graph, wired together" rather than "must go through
 * Koin" — deliberately NOT `startKoin {}` + `koinViewModel<HomeViewModel>()`: androidx.test's
 * instrumentation runs every test class in one Gradle invocation inside a SINGLE Instrumentation
 * process by default, so a `startKoin()` in this class's setup racing against
 * [ComponentsTest]/other future instrumented classes (or even just this class's own two `@Test`
 * methods) risks `KoinAppAlreadyStartedException` with no natural place for a matching
 * `stopKoin()` between them. `HomeViewModel`'s constructor already takes plain, directly
 * constructible arguments (`QuakeRepository`, `HomeLocationStore`, `LocationProvider`) — exactly
 * the same "fake repository, real ViewModel, no framework in the loop" shape
 * `HomeViewModelTest`'s jvmTest suite already uses — so this wires that same graph on-device
 * instead, with a real (in-memory) `AndroidSqliteDriver` standing in for the JVM suite's
 * `JdbcSqliteDriver`, and composes `HomeScreen(viewModel, selectionViewModel)` directly with no
 * Activity/Koin involved. Task 3 (Plan 3): `selectionViewModel` (`QuakeSelectionViewModel`, split
 * out of `HomeViewModel` — see that class's own kdoc) is passed explicitly for exactly the same
 * reason `viewModel` is — its default is `koinViewModel()`, which this class's whole
 * no-`startKoin{}` design can't rely on.
 *
 * This DOES compose the real `QuakeMap` (Task 8's Android `actual`, backed by maplibre-compose) as
 * part of `HomeScreen` — there is no way to test "does HomeScreen reach Content" without also
 * mounting the screen that map lives on. That's a deliberate, accepted scope for this one test: the
 * physical device's network is clean (unlike the emulator's, blocked by the corp TLS proxy hitting
 * maplibre-native's own HTTP stack — see this plan's progress ledger, Task 6/8 carry-ins), so the
 * map itself loading is not this test's concern either way — both assertions below key off text
 * that comes from local DB/repository state, not the map.
 */
class HomeFlowTest {
    @get:Rule
    val composeTestRule = createComposeRule()

    private val createdDrivers = mutableListOf<AndroidSqliteDriver>()

    // Task 3 (Plan 3): typed as the common ViewModel base (not HomeViewModel specifically) so this
    // one list/teardown loop covers both HomeViewModel and the new QuakeSelectionViewModel — both
    // expose the same `viewModelScope` extension property tearDown() below relies on.
    private val createdViewModels = mutableListOf<ViewModel>()

    @After
    fun tearDown() {
        // Same "don't leak viewModelScope coroutines past this test" discipline as
        // HomeViewModelTest's jvmTest suite (Task 13's flake fix) — Dispatchers.Main here is the
        // real Android main-looper dispatcher throughout (no kotlinx-coroutines-test
        // setMain/resetMain involved on this instrumented side), so none of that fix's
        // UnconfinedTestDispatcher reentrancy hazard applies; a plain cancel() is sufficient.
        createdViewModels.forEach { it.viewModelScope.cancel() }
        createdViewModels.clear()
        createdDrivers.forEach { it.close() }
        createdDrivers.clear()
    }

    @Test
    fun homeScreen_movesFromLoadingToContentWithTheSeededQuake() {
        val repository = fakeRepositoryWithOneQuake()
        val vm = buildViewModel(repository)
        val selectionVm = buildSelectionViewModel(repository)
        composeTestRule.setContent { TerraTheme { HomeScreen(vm, selectionVm) } }

        composeTestRule.waitUntil(timeoutMillis = 10_000) {
            composeTestRule.onAllNodesWithText("10km SE of Testville", substring = true)
                .fetchSemanticsNodes().isNotEmpty()
        }
        composeTestRule.onNodeWithText("10km SE of Testville", substring = true).assertExists()
    }

    @Test
    fun homeScreen_showsTheOfflineBannerWhenTheInitialRefreshFails() {
        val repository = fakeRepositoryAlwaysFailing()
        val vm = buildViewModel(repository)
        val selectionVm = buildSelectionViewModel(repository)
        composeTestRule.setContent { TerraTheme { HomeScreen(vm, selectionVm) } }

        // HomeScreen's StalenessBanner (HomeScreen.kt) renders "Not updated yet" whenever
        // lastUpdatedMillis is null — true here since fakeRepositoryAlwaysFailing() never
        // completes a successful write, gated on refreshFailed (also true here), which is what
        // actually puts the banner on screen at all (see PhoneLayout's `if (s.refreshFailed ||
        // isStale(...))` guard) rather than merely being consistent with it.
        composeTestRule.waitUntil(timeoutMillis = 10_000) {
            composeTestRule.onAllNodesWithText("Not updated yet").fetchSemanticsNodes().isNotEmpty()
        }
        composeTestRule.onNodeWithText("Not updated yet").assertExists()
    }

    private fun buildViewModel(repository: QuakeRepository): HomeViewModel {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val homeLocationStore = HomeLocationStore(QuakeDao(TerraWatchDb(freshDriver(context))))
        // Task 7 (Plan 3): a fresh, empty AlertRuleStore — this class's own two tests don't
        // exercise radius/minMag wiring (that's HomeViewModelTest's jvmTest job), only that
        // HomeViewModel still constructs and reaches Content/the offline banner end to end.
        val alertRuleStore = AlertRuleStore(QuakeDao(TerraWatchDb(freshDriver(context))))
        // Task 2 (Plan 4): clock pinned to the same 2_000_000L every repository built in this file
        // already uses — HomeViewModel.init's own retention pruneOldRows() call now runs
        // unconditionally on construction, and an unguarded real-wall-clock default would judge the
        // network-fetched fixture quake (timeMillis 1_950_000, see ONE_FEATURE_GEOJSON below) as
        // decades-old the instant it lands, pruning it out from under
        // homeScreen_movesFromLoadingToContentWithTheSeededQuake before the test's own
        // waitUntil(...) ever observes it. See HomeViewModelTest.kt's jvmTest suite (createVm's own
        // clock param) for the identical fix, with the fuller "confirmed by actually breaking every
        // test first" rationale.
        return HomeViewModel(repository, homeLocationStore, LocationProvider(context), alertRuleStore, clock = { 2_000_000L })
            .also { createdViewModels += it }
    }

    // Task 3 (Plan 3): HomeScreen's `selectionViewModel` parameter defaults to `koinViewModel()`
    // (see that composable's own kdoc) — passed explicitly here instead, same "real object graph,
    // no framework in the loop" philosophy this whole file already applies to HomeViewModel, and
    // for the identical reason: this class deliberately avoids `startKoin {}` (see this file's own
    // top-level kdoc). A fresh, empty SavedStateHandle is correct for both tests here — neither
    // exercises restore-from-process-death, which is proven separately by
    // QuakeSelectionViewModelTest's jvmTest suite instead.
    private fun buildSelectionViewModel(repository: QuakeRepository): QuakeSelectionViewModel =
        QuakeSelectionViewModel(repository, SavedStateHandle()).also { createdViewModels += it }

    // name = null -> in-memory (app.cash.sqldelight's AndroidSqliteDriver contract, same as
    // SQLiteOpenHelper's own) — isolated from the app's own "terrawatch.db" file that this same
    // device's manual QA pass (this task's Step 6) reads/writes real quakes into. Each call gets
    // its own fresh, empty database; tracked so tearDown() can close every one of them.
    private fun freshDriver(context: android.content.Context): AndroidSqliteDriver =
        AndroidSqliteDriver(TerraWatchDb.Schema, context, name = null).also { createdDrivers += it }

    private fun fakeRepositoryWithOneQuake(): QuakeRepository {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val dao = QuakeDao(TerraWatchDb(freshDriver(context)), clock = { FETCH_CLOCK_MILLIS })
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
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val dao = QuakeDao(TerraWatchDb(freshDriver(context)))
        val engine = MockEngine { respond("", HttpStatusCode.InternalServerError) }
        return QuakeRepository(
            UsgsApi(HttpClient(engine)),
            EmscLiveSource(HttpClient(engine)),
            dao,
            clock = { 2_000_000L },
        )
    }

    private companion object {
        const val FETCH_CLOCK_MILLIS = 5_000_000L

        // Same fixture (id/place/mag) as HomeViewModelTest's jvmTest suite — a failure here reads
        // against an already-understood fixture rather than a new one.
        val ONE_FEATURE_GEOJSON = """
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
    }
}
