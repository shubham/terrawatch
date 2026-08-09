package com.yugma.terrawatch.di

import com.yugma.terrawatch.data.AlertRuleStore
import com.yugma.terrawatch.data.HistoryPager
import com.yugma.terrawatch.data.HomeLocationStore
import com.yugma.terrawatch.data.OnboardingStore
import com.yugma.terrawatch.data.QuakeRepository
import com.yugma.terrawatch.data.ThemeStore
import com.yugma.terrawatch.database.QuakeDao
import com.yugma.terrawatch.history.HistoryViewModel
import com.yugma.terrawatch.home.HomeViewModel
import com.yugma.terrawatch.home.QuakeSelectionViewModel
import com.yugma.terrawatch.insights.InsightsViewModel
import com.yugma.terrawatch.location.LocationProvider
import com.yugma.terrawatch.location.LocationRequester
import com.yugma.terrawatch.network.EmscLiveSource
import com.yugma.terrawatch.network.UsgsApi
import com.yugma.terrawatch.settings.SettingsViewModel
import io.ktor.client.HttpClient
import org.koin.core.module.Module
import org.koin.core.module.dsl.viewModel
import org.koin.dsl.module
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

// Platform entry points supply: HttpClient (engine differs), QuakeDao (driver differs), and
// LocationProvider (android needs a Context, jvm/wasmJs need nothing — see LocationProvider.kt's
// no-declared-constructor rationale, mirroring DriverFactory).
// kotlinx.datetime.Clock is now a deprecated typealias for kotlin.time.Clock (Kotlin 2.1+, still
// @ExperimentalTime as of Kotlin 2.2) — same migration as kotlinx.datetime.Instant elsewhere in
// this codebase (see EmscParser.kt). Importing the stdlib type directly avoids a typealias
// nested-object resolution quirk seen when going through the kotlinx.datetime alias.
@OptIn(ExperimentalTime::class)
fun appModule(http: HttpClient, dao: QuakeDao, locationProvider: LocationProvider): Module = module {
    single { UsgsApi(http) }
    single { EmscLiveSource(http) }
    single { dao }
    // Task 7 (Plan 3), USER REQUIREMENT: alertRuleStore/homeLocationStore are the two optional
    // trailing params QuakeRepository.kt's own kdoc documents — this is the one real call site that
    // ever supplies both, so refreshFeed()/startLive() actually evaluate AlertRuleEngine against the
    // user's stored radius/minMag and real home, not the compile-time DEFAULT_RULES/null every test
    // construction implicitly falls back to.
    single { QuakeRepository(get(), get(), get(), clock = { Clock.System.now().toEpochMilliseconds() }, alertRuleStore = get(), homeLocationStore = get()) }
    single { HomeLocationStore(get()) }
    // Task 7 (Plan 3): AlertRuleStore/ThemeStore are plain singles (same "no ViewModel needed"
    // shape as HomeLocationStore/OnboardingStore above) — HomeViewModel/SettingsViewModel both
    // depend on AlertRuleStore via constructor injection, and App() resolves ThemeStore directly
    // via koinInject() at the composition root (see App.kt's own kdoc), exactly like AppNav.kt
    // already does for OnboardingStore.
    single { AlertRuleStore(get()) }
    single { ThemeStore(get()) }
    // Task 4 (Plan 3): resolved via koinInject<OnboardingStore>() at AppNav's composition root
    // (same non-ViewModel "plain single, plain koinInject()" shape LocationAskDialog.kt already
    // uses for HomeLocationStore/LocationRequester) rather than through any ViewModel constructor
    // — nothing else in this graph needs it.
    single { OnboardingStore(get()) }
    single { locationProvider }
    // Task 2 (Plan 3): unlike locationProvider above (built at each platform's entry point and
    // handed in, since android's actual needs a Context the shared expect signature can't carry),
    // LocationRequester's no-arg constructor is uniform across every target — see its own kdoc —
    // so it's constructed directly here instead. LocationAskDialog (composeApp/location) resolves
    // this via koinInject(), not through HomeViewModel's constructor: nothing else in this graph
    // needs it.
    single { LocationRequester() }
    // viewModel {} (not factory {}) — scopes HomeViewModel to the platform ViewModelStore via
    // koin-compose-viewmodel's koinViewModel<HomeViewModel>() at the App() call site, instead of
    // minting a fresh instance (and a fresh startLive() collector) on every recomposition/rotation.
    // Task 9: HomeViewModel gains the pill's home-location dependency — HomeLocationStore and
    // LocationProvider are both already singles above, so get()/get() resolve them by the
    // constructor's own parameter types, same pattern as QuakeRepository's get()-per-param above.
    // Fix Round 1 (entangled minor): FeedViewModel/FeedScreen registration removed — dead since
    // Task 8 made Home the app's real screen (see App.kt); nothing has referenced either since,
    // and they carried a latent second startLive() collector (see FeedViewModel's own former
    // init{}) that this repository never needed twice. Both deleted outright, not just
    // unregistered — see task-10-report.md's Fix Round 1 for the removal record.
    viewModel { HomeViewModel(get(), get(), get(), get()) }
    // Task 3 (Plan 3): QuakeSelectionViewModel's second constructor param is
    // androidx.lifecycle.SavedStateHandle, which has no `single {}`/`factory {}` registration
    // anywhere in this module — and needs none. Koin's own ViewModel factory
    // (KoinViewModelFactory.create, koin-core-viewmodel) wraps the platform CreationExtras in an
    // AndroidParametersHolder before resolving this definition; that ParametersHolder subclass's
    // getOrNull(SavedStateHandle::class) unconditionally answers
    // SavedStateHandleSupport.createSavedStateHandle(extras) instead of falling through to the
    // scope's bean graph, so plain get() resolves it exactly like any other constructor param —
    // verified against koin-core-viewmodel 4.1.0's actual bytecode (not just assumed) plus Koin's
    // own current docs ("Add SavedStateHandle to your ViewModel constructor - Koin injects it
    // automatically"). See QuakeSelectionViewModel's own kdoc for the fuller citation.
    viewModel { QuakeSelectionViewModel(get(), get()) }
    // Task 5 (Plan 3): HistoryPager's clock seam is only ever consulted for a year-LESS filter's
    // very first cursor ("now") — same injected-at-the-platform-boundary shape as QuakeRepository's
    // own `clock` two lines up, not a default baked into core:data (which stays platform-clock-
    // decoupled/testable — see HistoryPager's own kdoc).
    single { HistoryPager(get(), clock = { Clock.System.now().toEpochMilliseconds() }) }
    // History's own tab-scoped ViewModel — resolved via HistoryScreen's own defaulted
    // `= koinViewModel()` parameter (not threaded through AppNav like HomeViewModel/
    // QuakeSelectionViewModel are), since nothing else in this graph needs to share it; Compose
    // Navigation scopes that call to the "history" route's own NavBackStackEntry automatically.
    viewModel { HistoryViewModel(get(), get()) }
    // Task 6 (Plan 3): Insights' own tab-scoped ViewModel — same "resolved via the screen's own
    // defaulted koinViewModel() param" shape as HistoryViewModel just above. `clock` mirrors
    // HistoryPager's own injected seam two lines up (real wall-clock at the platform boundary,
    // fully substitutable in tests).
    viewModel { InsightsViewModel(get(), clock = { Clock.System.now().toEpochMilliseconds() }) }
    // Task 7 (Plan 3): Settings' own tab-scoped ViewModel — resolved via SettingsScreen's own
    // defaulted `= koinViewModel()` param, same shape as HistoryViewModel/InsightsViewModel above.
    viewModel { SettingsViewModel(get(), get(), get()) }
}
