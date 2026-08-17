package com.yugma.terrawatch.di

import com.yugma.terrawatch.alerts.AlertDigestScheduler
import com.yugma.terrawatch.data.AlertRuleStore
import com.yugma.terrawatch.data.FavoritePlaceStore
import com.yugma.terrawatch.data.FeedFilterStore
import com.yugma.terrawatch.data.HistoryPager
import com.yugma.terrawatch.data.HomeLocationStore
import com.yugma.terrawatch.data.OnboardingStore
import com.yugma.terrawatch.data.QuakeRepository
import com.yugma.terrawatch.data.ThemeStore
import com.yugma.terrawatch.data.VisitStore
import com.yugma.terrawatch.database.QuakeStore
import com.yugma.terrawatch.detail.DetailNewsViewModel
import com.yugma.terrawatch.history.HistoryViewModel
import com.yugma.terrawatch.home.HomeViewModel
import com.yugma.terrawatch.home.QuakeSelectionViewModel
import com.yugma.terrawatch.insights.InsightsNewsViewModel
import com.yugma.terrawatch.insights.InsightsViewModel
import com.yugma.terrawatch.location.LocationProvider
import com.yugma.terrawatch.location.LocationRequester
import com.yugma.terrawatch.monetization.EntitlementsProvider
import com.yugma.terrawatch.network.EmscLiveSource
import com.yugma.terrawatch.network.GdeltClient
import com.yugma.terrawatch.network.UsgsApi
import com.yugma.terrawatch.notifications.NotificationPermissionRequester
import com.yugma.terrawatch.settings.SettingsViewModel
import io.ktor.client.HttpClient
import org.koin.core.module.Module
import org.koin.core.module.dsl.viewModel
import org.koin.dsl.module
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

// Platform entry points supply: HttpClient (engine differs), a QuakeStore (android/jvm hand in a
// real QuakeDao over their own DriverFactory; wasmJs hands in an InMemoryQuakeStore — Task 9, Plan
// 3, see QuakeStore's own kdoc for why), LocationProvider (android needs a Context, jvm/wasmJs
// need nothing — see LocationProvider.kt's no-declared-constructor rationale, mirroring
// DriverFactory), and (Plan 4 Task 6) an EntitlementsProvider — android's `KoinBootstrap.android.kt`
// resolves the real gate (RevenueCatEntitlements when a key is configured, AlwaysFreeEntitlements
// otherwise — this repo's actual state throughout Task 6, no RC account yet); jvm/wasmJs's own
// main()s always pass AlwaysFreeEntitlements directly (Android-only runtime scope directive).
// kotlinx.datetime.Clock is now a deprecated typealias for kotlin.time.Clock (Kotlin 2.1+, still
// @ExperimentalTime as of Kotlin 2.2) — same migration as kotlinx.datetime.Instant elsewhere in
// this codebase (see EmscParser.kt). Importing the stdlib type directly avoids a typealias
// nested-object resolution quirk seen when going through the kotlinx.datetime alias.
@OptIn(ExperimentalTime::class)
fun appModule(
    http: HttpClient,
    dao: QuakeStore,
    locationProvider: LocationProvider,
    entitlementsProvider: EntitlementsProvider,
): Module = module {
    single { UsgsApi(http) }
    single { EmscLiveSource(http) }
    // Plan 4 Task 5: GDELT DOC 2.0 API client - a separate HttpClient dependency reuse (same `http`
    // single every other network class here already shares), not a separate engine/base-url wiring
    // per platform - GdeltClient's own baseUrl parameter is a plain default, unlike UsgsApi's
    // baseFeedUrl which is also always the default in production.
    single { GdeltClient(http) }
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
    // Task 2 (Plan 5): favorites beyond home — same "plain single over the shared QuakeStore" shape
    // as AlertRuleStore/HomeLocationStore just above. Consumed by HomeViewModel (quick-switch chips
    // + worker-side favorites read) and SettingsViewModel (the Places section) via constructor
    // injection, and directly by AlertDigestWorker (androidMain) via `koin.get()`.
    single { FavoritePlaceStore(get()) }
    // feat/feed-visit-ux: same "plain single over the shared QuakeStore" shape as
    // AlertRuleStore/HomeLocationStore/FavoritePlaceStore above. Consumed by HomeViewModel (the
    // since-last-visit summary's read side) and MainActivity.android.kt (the write side, on
    // Activity onStop — see that class's own kdoc for why the write lives there rather than here).
    single { VisitStore(get()) }
    // User review items 3+4: same "plain single over the shared QuakeStore" shape as
    // AlertRuleStore/HomeLocationStore/FavoritePlaceStore/VisitStore above. Consumed by
    // HomeViewModel alone (the feed sheet's persisted magnitude filter).
    single { FeedFilterStore(get()) }
    // Task 4 (Plan 3): resolved via koinInject<OnboardingStore>() at AppNav's composition root
    // (same non-ViewModel "plain single, plain koinInject()" shape LocationAskDialog.kt already
    // uses for HomeLocationStore/LocationRequester) rather than through any ViewModel constructor
    // — nothing else in this graph needs it.
    single { OnboardingStore(get()) }
    single { locationProvider }
    // Plan 4 Task 6: resolved via koinInject<EntitlementsProvider>() at AppNav's composition root
    // (same non-ViewModel "plain single, plain koinInject()" shape OnboardingStore above already
    // uses) for the ad-slot gate, AND through SettingsViewModel's constructor for the "TerraWatch
    // Plus" row's mirrored isPlusActive — same "platform entry point builds it, hands in an
    // already-constructed instance" shape locationProvider itself already establishes just above.
    single { entitlementsProvider }
    // Task 2 (Plan 3): unlike locationProvider above (built at each platform's entry point and
    // handed in, since android's actual needs a Context the shared expect signature can't carry),
    // LocationRequester's no-arg constructor is uniform across every target — see its own kdoc —
    // so it's constructed directly here instead. LocationAskDialog (composeApp/location) resolves
    // this via koinInject(), not through HomeViewModel's constructor: nothing else in this graph
    // needs it.
    single { LocationRequester() }
    // Plan 4 Task 3: same no-arg-constructor, uniform-across-targets shape as LocationRequester
    // just above — see each class's own kdoc. NotificationPermissionRequester is resolved via
    // koinInject() from OnboardingScreen's step 3 and SettingsScreen's ALERTS row;
    // AlertDigestScheduler from SettingsScreen's ALERTS row only (its "is the worker enqueued"
    // query + the debug immediate-trigger long-press).
    single { NotificationPermissionRequester() }
    single { AlertDigestScheduler() }
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
    // Task 2 (Plan 5): favoritePlaceStore is a NAMED arg (skipping clock/locationRequester's own
    // defaults, both already documented above as production-real defaults nothing needs to
    // override) so the REAL, persisted FavoritePlaceStore single reaches HomeViewModel — its own
    // default (a throwaway InMemoryQuakeStore-backed instance) exists purely so jvmTest/HomeFlowTest/
    // OnboardingGateTest's pre-existing 4-arg construction keeps compiling, not for production use.
    // feat/feed-visit-ux: visitStore is a NAMED arg (skipping clock/locationRequester's own
    // defaults, same reasoning favoritePlaceStore's own comment above already gives) so the REAL,
    // persisted VisitStore single reaches HomeViewModel — its own default (a throwaway
    // InMemoryQuakeStore-backed instance) exists purely so jvmTest/HomeFlowTest/OnboardingGateTest's
    // pre-existing construction keeps compiling, not for production use.
    // User review items 3+4: feedFilterStore is a NAMED arg, same reasoning as visitStore's own
    // comment immediately above — the REAL, persisted FeedFilterStore single (not its own
    // throwaway-InMemoryQuakeStore-backed default) is what makes "User choice PERSISTS" actually
    // true in production.
    viewModel { HomeViewModel(get(), get(), get(), get(), favoritePlaceStore = get(), visitStore = get(), feedFilterStore = get()) }
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
    // Plan 4 Task 5: DetailSheet's "In the news" state machine - see that class's own kdoc for why
    // it's a sibling of QuakeSelectionViewModel rather than folded into it. No SavedStateHandle
    // dance needed (unlike QuakeSelectionViewModel just above) - a plain constructor, so
    // koinViewModel<DetailNewsViewModel>() resolves it the same simple way HistoryViewModel/
    // InsightsViewModel already do below. Resolved once in App.kt and threaded through AppNav to
    // Home/History/Insights explicitly (same Activity-scoped sharing shape as selectionViewModel
    // itself - see App.kt's own kdoc) rather than each screen's own defaulted koinViewModel()
    // default resolving a separate per-tab instance, which would silently desync from whichever
    // quake selectionViewModel says is actually selected once the user switches tabs.
    viewModel { DetailNewsViewModel(get()) }
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
    // Plan 4 Task 5: Insights' OWN "In the news" card (M6+/7d, independent of InsightsViewModel -
    // see InsightsNewsViewModel's own kdoc for why it's a deliberate sibling, not a widening of
    // InsightsViewModel's constructor). Resolved via InsightsScreen's own second `= koinViewModel()`
    // default parameter, same shape HistoryViewModel/InsightsViewModel/SettingsViewModel below
    // already use for their own tab-scoped ViewModels - nothing else in this graph needs to share
    // this one instance the way selectionViewModel/DetailNewsViewModel above do.
    viewModel { InsightsNewsViewModel(get(), get(), clock = { Clock.System.now().toEpochMilliseconds() }) }
    // Task 7 (Plan 3): Settings' own tab-scoped ViewModel — resolved via SettingsScreen's own
    // defaulted `= koinViewModel()` param, same shape as HistoryViewModel/InsightsViewModel above.
    // Plan 4 Task 6: 4th constructor param (EntitlementsProvider) backs the new "TerraWatch Plus"
    // row's mirrored isPlusActive — get() resolves the SAME single registered just above.
    // Task 2 (Plan 5): 5th constructor param (FavoritePlaceStore) backs the Places section's own
    // favorites list — get() resolves the SAME single HomeViewModel's own registration above uses.
    viewModel { SettingsViewModel(get(), get(), get(), get(), get()) }
}
