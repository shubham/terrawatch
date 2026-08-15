package com.yugma.terrawatch.ui

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.test.platform.app.InstrumentationRegistry
import app.cash.sqldelight.driver.android.AndroidSqliteDriver
import com.yugma.terrawatch.data.AlertRuleStore
import com.yugma.terrawatch.data.HomeLocationStore
import com.yugma.terrawatch.data.OnboardingStore
import com.yugma.terrawatch.data.QuakeRepository
import com.yugma.terrawatch.database.QuakeDao
import com.yugma.terrawatch.database.TerraWatchDb
import com.yugma.terrawatch.home.HOME_MAP_CONTAINER_TAG
import com.yugma.terrawatch.home.HomeViewModel
import com.yugma.terrawatch.home.QuakeSelectionViewModel
import com.yugma.terrawatch.location.LocationProvider
import com.yugma.terrawatch.nav.AppNav
import com.yugma.terrawatch.network.EmscLiveSource
import com.yugma.terrawatch.network.UsgsApi
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Rule
import org.junit.Test

/**
 * Task 13: the onboarding-gate regression pin — `AppNav.kt`'s own
 * `remember { if (onboardingStore.isOnboarded()) Routes.HOME else Routes.ONBOARDING }` start-
 * destination logic, exercised on-device against both of its branches, the one piece of Task 8's
 * first-run flow that had no instrumented (or any) coverage before this task ("No TDD (composition);
 * instrumented smoke Task 13" — plan Task 8 brief).
 *
 * Deliberately Koin-free, unlike [NavRoundTripTest] in this same package: [AppNav] now takes
 * [OnboardingStore] as a directly-overridable parameter (see that composable's own kdoc for the
 * Task 13 change, mirroring `HomeScreen`'s pre-existing `selectionViewModel = koinViewModel()`
 * shape), and neither branch this test drives ever navigates to History/Insights/Settings — the
 * three routes that would actually require a started Koin (see [NavRoundTripTest]'s own kdoc). The
 * unonboarded branch composes only [com.yugma.terrawatch.onboarding.OnboardingScreen]'s first pager
 * page (`HorizontalPager`'s default `beyondViewportPageCount` composes no neighbor pages, and this
 * test never scrolls it), which itself calls no `koinInject`/`koinViewModel` — only LocationStep (page 1) does
 * (`LocationStep`'s `koinInject<LocationRequester>()`) — and the onboarded branch composes
 * [com.yugma.terrawatch.home.HomeScreen] directly from the explicitly-constructed `homeViewModel`/
 * `selectionViewModel` pair below, the same "real object graph, no framework in the loop" shape
 * [HomeFlowTest] already established. This is the "in-memory store... wiring cheap" path the
 * controller's own Task 13 dispatch called out as the preferred option over documenting a skip.
 *
 * Each test method builds its OWN fresh in-memory driver/dao/`OnboardingStore` (see [buildGraph]) —
 * unlike [NavRoundTripTest]'s single guarded, never-stopped Koin graph, there is no shared-singleton
 * hazard here to guard against: two directly-constructed `OnboardingStore` instances over two
 * different fresh drivers cannot leak an "onboarded" flag from one test method into the other,
 * which a single process-wide Koin `OnboardingStore` singleton (shared across every `@Test` that
 * ever resolved it) demonstrably could.
 */
class OnboardingGateTest {
    @get:Rule
    val composeTestRule = createComposeRule()

    private val createdViewModels = mutableListOf<ViewModel>()
    private val createdDrivers = mutableListOf<AndroidSqliteDriver>()

    @After
    fun tearDown() {
        // Same discipline as HomeFlowTest.tearDown — see that class's own kdoc.
        createdViewModels.forEach { it.viewModelScope.cancel() }
        createdViewModels.clear()
        createdDrivers.forEach { it.close() }
        createdDrivers.clear()
    }

    @Test
    fun freshDatastore_onboardingStepOneShown_homeNotComposed() {
        val graph = buildGraph()
        // Never call setOnboarded() -- OnboardingStore.isOnboarded() defaults false on a brand-new
        // meta table (OnboardingStoreTest, core:data jvmTest, pins this exact default).

        composeTestRule.setContent {
            AppNav(graph.homeViewModel, graph.selectionViewModel, graph.onboardingStore)
        }

        composeTestRule.waitUntil(timeoutMillis = 10_000) {
            composeTestRule.onAllNodesWithText(ONBOARDING_STEP_ONE_TITLE).fetchSemanticsNodes().isNotEmpty()
        }
        // Exact string grepped from OnboardingScreen.kt's own ONBOARDING_STEPS[0].title -- not
        // paraphrased (same discipline ComponentsTest's own exact-string assertions already apply).
        composeTestRule.onNodeWithText(ONBOARDING_STEP_ONE_TITLE).assertExists()
        // The negative half of the pin: a fresh install must NOT land on Home underneath/instead of
        // onboarding -- home-map-container (HomeScreen.kt) only ever composes on the HOME route.
        composeTestRule.onNodeWithTag(HOME_MAP_CONTAINER_TAG).assertDoesNotExist()
    }

    @Test
    fun onboarded_homeShownDirectly_onboardingNeverComposed() {
        val graph = buildGraph()
        graph.onboardingStore.setOnboarded()

        composeTestRule.setContent {
            AppNav(graph.homeViewModel, graph.selectionViewModel, graph.onboardingStore)
        }

        composeTestRule.waitUntil(timeoutMillis = 10_000) {
            composeTestRule.onAllNodesWithTag(HOME_MAP_CONTAINER_TAG).fetchSemanticsNodes().isNotEmpty()
        }
        composeTestRule.onNodeWithTag(HOME_MAP_CONTAINER_TAG).assertExists()
        // The negative half: an already-onboarded install must never show onboarding's step-one
        // copy even momentarily as this graph's own NavHost start destination.
        composeTestRule.onNodeWithText(ONBOARDING_STEP_ONE_TITLE).assertDoesNotExist()
    }

    private fun buildGraph(): TestGraph {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val dao = QuakeDao(TerraWatchDb(freshDriver(context)))
        // Always-500 engine -- this pin is about which ROUTE composes first, not about any
        // screen's fetched data (same reasoning NavRoundTripTest's own MockEngine comment gives).
        val engine = MockEngine { respond("", HttpStatusCode.InternalServerError) }
        val http = HttpClient(engine)
        val repository = QuakeRepository(UsgsApi(http), EmscLiveSource(http), dao, clock = { FAKE_CLOCK_MILLIS })
        val homeViewModel = HomeViewModel(repository, HomeLocationStore(dao), LocationProvider(context), AlertRuleStore(dao))
            .also { createdViewModels += it }
        val selectionViewModel = QuakeSelectionViewModel(repository, SavedStateHandle())
            .also { createdViewModels += it }
        return TestGraph(homeViewModel, selectionViewModel, OnboardingStore(dao))
    }

    // name = null -> fresh in-memory driver, isolated from the app's own "terrawatch.db" file and
    // from every OTHER call to this same function (including the other @Test method's) -- see
    // HomeFlowTest.freshDriver's own kdoc for the identical isolation reasoning.
    private fun freshDriver(context: android.content.Context): AndroidSqliteDriver =
        AndroidSqliteDriver(TerraWatchDb.Schema, context, name = null).also { createdDrivers += it }

    private data class TestGraph(
        val homeViewModel: HomeViewModel,
        val selectionViewModel: QuakeSelectionViewModel,
        val onboardingStore: OnboardingStore,
    )

    private companion object {
        const val FAKE_CLOCK_MILLIS = 2_000_000L
        const val ONBOARDING_STEP_ONE_TITLE = "Know the ground beneath you"
    }
}
