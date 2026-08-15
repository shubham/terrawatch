package com.yugma.terrawatch.ui

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.platform.app.InstrumentationRegistry
import app.cash.sqldelight.driver.android.AndroidSqliteDriver
import com.yugma.terrawatch.App
import com.yugma.terrawatch.data.OnboardingStore
import com.yugma.terrawatch.database.QuakeDao
import com.yugma.terrawatch.database.TerraWatchDb
import com.yugma.terrawatch.di.appModule
import com.yugma.terrawatch.di.ensureKoinStarted
import com.yugma.terrawatch.history.HISTORY_SUBTITLE
import com.yugma.terrawatch.home.HOME_MAP_CONTAINER_TAG
import com.yugma.terrawatch.home.SETTINGS_GEAR_TAG
import com.yugma.terrawatch.insights.INSIGHTS_SUBTITLE
import com.yugma.terrawatch.location.LocationProvider
import com.yugma.terrawatch.nav.NAV_HISTORY_TAG
import com.yugma.terrawatch.nav.NAV_HOME_TAG
import com.yugma.terrawatch.nav.NAV_INSIGHTS_TAG
import com.yugma.terrawatch.settings.SETTINGS_BACK_TAG
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import org.junit.Rule
import org.junit.Test
import org.koin.core.context.GlobalContext

/**
 * Task 13: THE white-screen regression pin (plan Task 4 brief, quoted in `AppNav.kt`'s own kdoc:
 * "verify no white screen on return to Home tab"), automated on-device instead of only ever proven
 * by a device-screenshot-after-a-manual-round-trip (Task 4's own device evidence, task-4-report.md).
 * Drives the REAL production composition root — [App] itself, not a hand-rolled stand-in — through
 * every one of `AppNav.kt`'s 4 real destinations (Home -> History -> Insights -> back to Home ->
 * Settings (via the gear chip, History/Insights have none of their own) -> back to Home), by
 * `testTag` (see `AppNav.kt`/`HomeScreen.kt`/`SettingsScreen.kt`'s own kdocs for why tag, not label
 * text), and asserts [HOME_MAP_CONTAINER_TAG] — the literal `Box` wrapping
 * [com.yugma.terrawatch.map.QuakeMap] — still exists in the semantics tree at the end. A blanked/
 * torn-down map would either remove this node entirely or leave it composed-but-empty; existence is
 * the honest floor this test can check without a pixel-level "is the map surface actually painted"
 * assertion (no such API exists for a MapLibre AndroidView from Compose UI test).
 *
 * Koin, unlike [HomeFlowTest]'s deliberate avoidance of it (see that class's own kdoc): unavoidable
 * here, because `AppNav.kt`'s History/Insights/Settings routes each resolve their own ViewModel via
 * their screen's own defaulted `= koinViewModel()` parameter, with no override plumbed through
 * `AppNav` the way Home's `homeViewModel`/`selectionViewModel` already are — there is no way to
 * mount the real History/Insights/Settings screens without a started Koin. Guarded by the same
 * `GlobalContext.getOrNull() == null` shape [com.yugma.terrawatch.di.ensureKoinStarted] itself
 * checks internally (Round 2 correction: `MainActivity.onCreate` no longer computes this check
 * itself post-C1-fix — it calls that function unconditionally and trusts its own internal guard;
 * see that function's own kdoc): this is the only class in this instrumented suite that starts
 * Koin, but the guard is kept anyway so a future second Koin-using test class added to this same
 * `androidInstrumentedTest` source set (all of which share ONE Instrumentation process/JVM per
 * `HomeFlowTest`'s own kdoc) fails safe (reuses the already-started graph) instead of crashing on
 * `KoinAppAlreadyStartedException`. Never stopped, for the same reason `HomeFlowTest` never starts
 * it: no natural teardown point shared safely across sibling test classes in one process.
 *
 * Round 2 (review finding, EVIDENCE INTEGRITY — traced against source, not assumed): this class
 * used to call `startKoin {}` directly with its own hand-built [appModule] graph instead of going
 * through [com.yugma.terrawatch.di.ensureKoinStarted] the way both real entry points
 * (`MainActivity.onCreate`, `AlertDigestWorker.doWork`) do. That skipped `initShareContext`/
 * `initAlertDigestSchedulerContext` entirely — both only ever run from INSIDE that function as of
 * the C1 fix above — leaving `AlertDigestScheduler`'s (and Share's) `lateinit appContext` holder
 * uninitialized for the whole test. Harmless for Legs 0-3 (Home/History/Insights never touch
 * either), but Leg 4/5's Settings navigation renders the ALERTS row, which calls
 * `AlertDigestScheduler.isEnqueued()` — an unconditional read of that uninitialized `appContext`,
 * i.e. a live `UninitializedPropertyAccessException` the instant this test actually reached
 * Settings. Fixed by routing through [com.yugma.terrawatch.di.ensureKoinStarted] itself (see
 * [ensureKoinStartedAndOnboarded]'s own kdoc for how it still gets its throwaway in-memory
 * driver/mock HTTP client via that function's new Round 2 seam) so this test now exercises the
 * IDENTICAL bootstrap path production does, not a parallel one two of its three init calls could
 * silently diverge from again.
 *
 * The [MockEngine] backing this Koin graph's one shared [HttpClient] always answers 500 — this test
 * cares about NAVIGATION surviving, not about any screen's fetched data (History's own
 * `HistoryViewModel.init` fires a real page load immediately, landing on its Error/Empty state
 * either way; `HistoryHeader()`/`InsightsHeader()` both render unconditionally above that `when
 * (state)` branch — see those screens' own source — so the header text this test asserts on never
 * depends on the mocked response succeeding).
 */
class NavRoundTripTest {
    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun homeToHistoryToInsightsToSettingsToHome_homeMapContainerSurvivesTheRoundTrip() {
        ensureKoinStartedAndOnboarded()

        composeTestRule.setContent { App() }

        // Leg 0: Home, fresh composition.
        waitForTag(HOME_MAP_CONTAINER_TAG)

        // Leg 1: Home -> History. HISTORY_SUBTITLE (not the bare "History" title) is the
        // assertion: with the bottom nav bar still showing (History is a TAB_ROUTES member), the
        // "History" tab label and HistoryHeader's own title both legitimately render the word
        // "History" at once -- a real device failure caught exactly this on the first run of this
        // test ("Expected exactly '1' node but found '2' nodes... contains 'History'"). The
        // subtitle only ever renders once, from HistoryHeader, so it stays an unambiguous proof
        // the screen itself (not just the tab label) is showing.
        composeTestRule.onNodeWithTag(NAV_HISTORY_TAG).performClick()
        waitForText(HISTORY_SUBTITLE)
        composeTestRule.onNodeWithText(HISTORY_SUBTITLE).assertExists()

        // Leg 2: History -> Insights. Same "assert the subtitle, not the ambiguous bare title"
        // reasoning as Leg 1 -- the Insights tab label and InsightsHeader's title would collide
        // the identical way.
        composeTestRule.onNodeWithTag(NAV_INSIGHTS_TAG).performClick()
        waitForText(INSIGHTS_SUBTITLE)
        composeTestRule.onNodeWithText(INSIGHTS_SUBTITLE).assertExists()

        // Leg 3: Insights -> back to Home (the tab round trip itself — the exact shape THE
        // regression, per AppNav.kt's kdoc, is about).
        composeTestRule.onNodeWithTag(NAV_HOME_TAG).performClick()
        waitForTag(HOME_MAP_CONTAINER_TAG)

        // Leg 4: Home -> Settings (gear chip — Settings has no tab of its own).
        composeTestRule.onNodeWithTag(SETTINGS_GEAR_TAG).performClick()
        waitForText("Settings")

        // Leg 5: Settings -> back to Home (pop). THE assertion: the map container survived every
        // leg above, not just a single tab-away/tab-back.
        composeTestRule.onNodeWithTag(SETTINGS_BACK_TAG).performClick()
        waitForTag(HOME_MAP_CONTAINER_TAG)
        composeTestRule.onNodeWithTag(HOME_MAP_CONTAINER_TAG).assertExists()
    }

    private fun waitForTag(tag: String) {
        composeTestRule.waitUntil(timeoutMillis = 10_000) {
            composeTestRule.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()
        }
    }

    private fun waitForText(text: String) {
        composeTestRule.waitUntil(timeoutMillis = 10_000) {
            composeTestRule.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()
        }
    }

    /**
     * Builds the exact same [appModule] graph `MainActivity.onCreate` builds for real, through the
     * SAME [com.yugma.terrawatch.di.ensureKoinStarted] bootstrap both real entry points call — not
     * a hand-rolled `startKoin` of its own (Round 2 fix: see this class's own top-of-file kdoc for
     * the live crash that bootstrap-skipping shortcut caused). That function's new `storeOverride`/
     * `httpClientOverride` seam (see its own kdoc) is what lets this call keep using a fresh,
     * throwaway in-memory driver in place of `DriverFactory`'s named "terrawatch.db" — see
     * [HomeFlowTest.freshDriver]'s own kdoc for why a named driver would be wrong here: this
     * device's manual-QA passes read/write real quakes into that file — and the same always-500
     * [MockEngine] this class's own top-of-file kdoc explains, instead of letting
     * [com.yugma.terrawatch.di.ensureKoinStarted]'s default parameters build a real `DriverFactory`/
     * real `OkHttp` client (which its two real call sites still get, untouched, since they never
     * pass either argument). Then flips the resolved [OnboardingStore] singleton to onboarded
     * BEFORE [App] ever composes — `AppNav`'s `startDestination` is read exactly once, via
     * `remember {}` with no key (see that composable's own kdoc), so this must happen before
     * `setContent {}`, not after.
     */
    private fun ensureKoinStartedAndOnboarded() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        if (GlobalContext.getOrNull() == null) {
            val driver = AndroidSqliteDriver(TerraWatchDb.Schema, context, name = null)
            val dao = QuakeDao(TerraWatchDb(driver))
            val http = HttpClient(MockEngine { respond("", HttpStatusCode.InternalServerError) })
            ensureKoinStarted(context, LocationProvider(context), storeOverride = dao, httpClientOverride = http)
        }
        GlobalContext.get().get<OnboardingStore>().setOnboarded()
    }
}
