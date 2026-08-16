package com.yugma.terrawatch.nav

import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Text
import androidx.compose.material3.adaptive.currentWindowAdaptiveInfo
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.yugma.terrawatch.ads.BannerAdSlot
import com.yugma.terrawatch.ads.adSlotVisible
import com.yugma.terrawatch.data.OnboardingStore
import com.yugma.terrawatch.detail.DetailNewsViewModel
import com.yugma.terrawatch.history.HistoryScreen
import com.yugma.terrawatch.home.HomeScreen
import com.yugma.terrawatch.home.HomeViewModel
import com.yugma.terrawatch.home.LayoutMode
import com.yugma.terrawatch.home.QuakeSelectionViewModel
import com.yugma.terrawatch.home.layoutMode
import com.yugma.terrawatch.insights.InsightsScreen
import com.yugma.terrawatch.monetization.EntitlementsProvider
import com.yugma.terrawatch.motion.LocalReducedMotion
import com.yugma.terrawatch.onboarding.OnboardingScreen
import com.yugma.terrawatch.paywall.PaywallScreen
import com.yugma.terrawatch.settings.SettingsScreen
import org.koin.compose.koinInject
import org.koin.compose.viewmodel.koinViewModel

/**
 * Task 4 (Plan 3): the app's nav destinations. [HOME]/[HISTORY]/[INSIGHTS] are the 3 persistent
 * tabs (bottom [NavigationBar] on phone, [NavigationRail] on desktop — see [AppNav]); [SETTINGS],
 * [ONBOARDING], and (Plan 4 Task 6) [PAYWALL] are stack-only routes reached from a tab (Home's gear
 * chip, the app's own conditional start destination, and Settings' "TerraWatch Plus" row,
 * respectively) rather than tabs of their own — none shows in the tab bar/rail (see [TAB_ROUTES]).
 *
 * Plain `String` constants, not `@Serializable` type-safe route classes (Navigation Compose
 * 2.9's other supported style): every screen here resolves its own ViewModel/state via Koin, not
 * via nav arguments, so there is no argument payload a route class would actually carry — and
 * `composeApp`'s Gradle module doesn't apply the `kotlinSerialization` compiler plugin type-safe
 * routes need (checked `composeApp/build.gradle.kts`'s `plugins {}` block), so adopting it here
 * would mean adding that plugin to a module that otherwise has no use for it, for zero behavioral
 * gain over four flat strings.
 */
object Routes {
    const val HOME = "home"
    const val HISTORY = "history"
    const val INSIGHTS = "insights"
    const val SETTINGS = "settings"
    const val ONBOARDING = "onboarding"
    const val PAYWALL = "paywall"
}

/** The 3 routes that show bottom-nav/rail chrome — [Routes.SETTINGS]/[Routes.ONBOARDING] render
 * full-screen with no tab chrome, same as a typical "settings is a stack push, not a tab" app. */
private val TAB_ROUTES = setOf(Routes.HOME, Routes.HISTORY, Routes.INSIGHTS)

/**
 * Plan 5's Settings-nav ad-reload follow-up (user dogfooding follow-up — the Settings-navigation gap
 * Task 3's own report named as a deliberate, out-of-scope finding: "TAB_ROUTES excludes
 * Routes.SETTINGS, so navigating Home→Settings→Home ALSO still structurally unmounts/remounts
 * BannerAdSlot's call site... which still triggers a real destroy()/fresh loadAd() on that particular
 * round trip" — task-3-report.md section 4/Concerns; full report: task-adview-settings-report.md).
 * Deliberately NOT labeled "Task 3b" despite directly following Task 3, the same way Task 2b/3b
 * themselves were inserted bug-fixes off a dogfooding report rather than numbered plan-5 tasks in
 * their own right — "Task 3b" is already the feed-sheet live-reveal fix
 * (`task-3b-feed-reveal-report.md`, committed earlier on this same branch); reusing that label here
 * would silently misattribute this fix's history to a different, already-shipped feature.
 * Which routes are ever ELIGIBLE to show an ad, as its own named concept —
 * deliberately NOT the same boolean as [showTabChrome] below, even though the two happen to share
 * membership today. [TAB_ROUTES] answers "does the bottom bar/rail show on this route" (a
 * navigation-chrome question); [AD_ELIGIBLE_ROUTES] answers "should [BannerAdSlot] ever be visible
 * on this route" (a monetization/ad-ethics-adjacent question). Reusing [showTabChrome] directly at
 * the ad call site would make that coupling implicit and accidental — hiding the ad on Settings
 * would read as a side effect of the bottom bar also being hidden there, rather than a rule a future
 * route can be opted into/out of on its own terms, independent of whatever the bottom bar does.
 * `= TAB_ROUTES` (not a second, independently-typed `setOf(...)` literal) because no route
 * currently wants one axis without the other — a real future divergence (e.g. a full-screen
 * tab-less route that SHOULD still carry ads) is then a one-line change to just this declaration,
 * with nothing else to touch.
 */
private val AD_ELIGIBLE_ROUTES = TAB_ROUTES

/**
 * The Settings-nav follow-up ([AD_ELIGIBLE_ROUTES]'s own kdoc above has the "not Task 3b" note),
 * TDD'd ([AppNavAdEligibilityTest], jvmTest — no Compose runtime needed, same
 * "extract the pure fn" convention [com.yugma.terrawatch.home.layoutMode]/
 * [com.yugma.terrawatch.home.shouldShowStalenessBanner] already established for this codebase):
 * pure route → ad-eligibility predicate. Kept OUTSIDE `core:ads`' [com.yugma.terrawatch.ads.
 * adSlotVisible] on purpose — that function's 3-input ad-ethics signature is spec §8 IMMUTABLE
 * (Plan 5 Task 3's own NON-NEGOTIABLE carries forward unchanged here too: no route input is ever
 * added to it). This is a SECOND, independent gate, ANDed with [com.yugma.terrawatch.ads.
 * adSlotVisible]'s result at the [AppNav] call site below — not a 4th input smuggled into that
 * function, and not a reason to weaken what "eligible" already means there: a Plus-free,
 * non-onboarding, detail-closed user on Settings still has `adSlotVisible(...) == true`;
 * [isAdEligibleRoute] is the separate reason the ad still doesn't show there.
 */
internal fun isAdEligibleRoute(route: String?): Boolean = route in AD_ELIGIBLE_ROUTES

/**
 * Task 13: `testTag`s for [AppBottomBar]/[AppNavigationRail]'s three tab items — `internal`, same
 * "so a test can pin it" convention `HistoryScreen.HISTORY_SUBTITLE`/`InsightsScreen.
 * INSIGHTS_SUBTITLE` already established, so `NavRoundTripTest` (androidInstrumentedTest) can drive
 * the Home->History->Insights->Settings->Home regression pin by tag rather than by label text (label
 * text doubles as both the tab's visible copy AND, before this, the only way to find it — a tag
 * keeps the test decoupled from a future copy change).
 */
internal const val NAV_HOME_TAG = "nav-home"
internal const val NAV_HISTORY_TAG = "nav-history"
internal const val NAV_INSIGHTS_TAG = "nav-insights"

/**
 * Task 4 (Plan 3) root nav composable — `App.kt` calls this instead of `HomeScreen` directly now.
 * Owns the [NavHost] plus whichever tab-switcher chrome fits the available width ([layoutMode],
 * reused verbatim from `home/LayoutMode.kt`). Plan 4 Task 4 (c): both this composable and
 * `HomeScreen`'s own identical call now feed `layoutMode()` the SAME
 * `currentWindowAdaptiveInfo().windowSizeClass` read (material3-adaptive's expanded-width
 * breakpoint, 840dp) rather than two independent `BoxWithConstraints` measurements of two different
 * available widths — see `layoutMode`'s own kdoc for the "900-980dp dead zone" bug this closes, so
 * "desktop" means the literal same thing, read the same way, in both places now.
 *
 * **THE regression this task exists to guard against** (plan Task 4 brief, quoting the Plan 2
 * lesson): naively tearing down and recreating `QuakeMap`'s composable — for ANY reason, not just
 * the original dual-call-site bug HomeScreen.kt's own kdoc documents — silently blanks
 * maplibre-compose's underlying AndroidView/GL surface on Android. A plain per-tab `NavHost`
 * destination swap is exactly this kind of teardown/recreate: leaving the "home" route removes
 * `HomeScreen` (and therefore `QuakeMap`) from composition entirely, and returning to it mounts a
 * structurally NEW instance. `launchSingleTop`/`restoreState`/`popUpTo(..., saveState = true)`
 * below (the plan's own prescribed recipe, and Google's standard bottom-nav pattern) makes the
 * RETURNING instance's [HomeViewModel]/[QuakeSelectionViewModel] state resume exactly where it
 * left off (both are resolved once, at `App()`, OUTSIDE this NavHost entirely — see [AppNav]'s
 * `homeViewModel`/`selectionViewModel` params and `App.kt`'s own kdoc — so their `viewModelScope`
 * coroutines, including the EMSC WebSocket, never stop running regardless of which tab is showing)
 * — it does NOT, by itself, prove `QuakeMap`'s own AndroidView survives the round trip without
 * blanking. That claim is only as good as the device screenshot after a real 3x round-trip proves
 * it is — see task-4-report.md's Device section for that evidence; this kdoc intentionally does
 * NOT assert a stronger guarantee than what was actually watched happen on 98bc1cd8.
 *
 * Task 13: [onboardingStore] defaults to `koinInject()` — same "defaulted Koin-resolved param a
 * test can override directly" shape `HomeScreen`'s own `selectionViewModel: QuakeSelectionViewModel
 * = koinViewModel()` already established — rather than the un-overridable `val onboardingStore =
 * koinInject<OnboardingStore>()` this body used to open with. `App.kt`'s real call site doesn't pass
 * it, so production behavior is byte-for-byte unchanged; `OnboardingGateTest`
 * (androidInstrumentedTest) passes a directly-constructed, Koin-free `OnboardingStore` instead, so
 * pinning "fresh install -> onboarding shown, onboarded -> home" needs no `startKoin{}` at all.
 *
 * Plan 4 Task 6: [entitlementsProvider] is the SAME "defaulted `koinInject()`" shape as
 * [onboardingStore] just above, for the identical reason — it feeds [adSlotVisible] below (spec §8,
 * IMMUTABLE) — one of the two ANDed conditions (the Settings-nav follow-up added [isAdEligibleRoute], the
 * route-based other half) that decide whether [BannerAdSlot] shows anything at all.
 */
@Composable
fun AppNav(
    homeViewModel: HomeViewModel,
    selectionViewModel: QuakeSelectionViewModel,
    // Plan 4 Task 5: same Activity-scoped, explicitly-threaded shape as selectionViewModel just
    // above (defaulted so `NavRoundTripTest`/`HomeFlowTest`/`OnboardingGateTest`'s existing
    // call sites - none of which pass this - keep compiling unchanged).
    detailNewsViewModel: DetailNewsViewModel = koinViewModel(),
    onboardingStore: OnboardingStore = koinInject(),
    entitlementsProvider: EntitlementsProvider = koinInject(),
) {
    // One-shot, read exactly once for this composable's whole lifetime (remember, no key) --
    // deliberately NOT re-read on every recomposition: "onboarded" only ever flips false -> true
    // once per install (OnboardingStore's own kdoc), and NavHost's own `startDestination` is only
    // ever consulted when its graph is first built anyway. A synchronous meta-table read directly
    // in composition (rather than dispatched off Main first, the way HomeViewModel.init's own
    // HomeLocationStore.get() call is) matches this app's existing "cheap, once, at cold start"
    // precedent instead: MainActivity.onCreate already does heavier synchronous DB work
    // (QuakeDao/createDatabase construction itself) directly on Main at this exact same moment in
    // the app's lifecycle, unchallenged across three prior tasks' reviews.
    val startDestination = remember { if (onboardingStore.isOnboarded()) Routes.HOME else Routes.ONBOARDING }
    val navController = rememberNavController()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route
    val showTabChrome = currentRoute in TAB_ROUTES

    // Plan 4 Task 6: adSlotVisible's 3 inputs. [isOnboarding] is computed generally here (not
    // hardcoded `false`) because the real call site below (the Settings-nav follow-up: no longer nested inside
    // `if (showTabChrome)` — see AD_ELIGIBLE_ROUTES'/isAdEligibleRoute's own kdoc above) gates its
    // OWN mount directly on `!isPlusActive && !isOnboarding`, not on TAB_ROUTES membership — this
    // val is what actually feeds that condition now, not merely a redundant-but-safe echo of an
    // exclusion TAB_ROUTES happened to already guarantee. [isDetailOpen]/[isPlusActive] are the two
    // inputs that actually vary while the ad slot stays mounted.
    val isOnboarding = currentRoute == Routes.ONBOARDING
    val selectedQuake by selectionViewModel.selectedQuake.collectAsState()
    val isDetailOpen = selectedQuake != null
    val isPlusActive by entitlementsProvider.isPlusActive.collectAsState()

    // Plan 4 Task 4 (c): ONE shared source of truth, replacing the former per-call-site
    // BoxWithConstraints measurement this kdoc used to describe disagreeing with HomeScreen's own
    // (see layoutMode()'s own kdoc, home/LayoutMode.kt, for the full "900-980dp dead zone" bug this
    // closes and why it closes it). currentWindowAdaptiveInfo() reads the FULL window's size
    // regardless of where in the composition tree it's called from — unlike a BoxWithConstraints,
    // which only ever sees whatever space its own parent handed it — so this and HomeScreen's own
    // identical call (HomeScreen.kt) can no longer structurally disagree: both resolve the exact
    // same WindowSizeClass for the exact same frame.
    val windowSizeClass = currentWindowAdaptiveInfo().windowSizeClass
    Box(Modifier.fillMaxSize()) {
        if (layoutMode(windowSizeClass) == LayoutMode.TWO_PANE) {
            Row(Modifier.fillMaxSize()) {
                if (showTabChrome) {
                    AppNavigationRail(currentRoute = currentRoute, navController = navController)
                }
                AppNavHost(
                    navController = navController,
                    startDestination = startDestination,
                    homeViewModel = homeViewModel,
                    selectionViewModel = selectionViewModel,
                    detailNewsViewModel = detailNewsViewModel,
                    onboardingStore = onboardingStore,
                    modifier = Modifier.weight(1f).fillMaxSize(),
                )
                // Plan 4 Task 6 SCOPE NOTE: no ad slot in TWO_PANE — this branch never reserved one
                // to begin with (Task 4's own placeholder Spacer only ever lived in the Column/
                // PHONE branch below), and the Android-only real-device verification scope
                // directive (in force since Plan 4 Task 4) means TWO_PANE is compile-only, never
                // runtime-verified on the one judged target (a phone). Revisit if/when a real
                // desktop/tablet pass is ever reopened.
            }
        } else {
            Column(Modifier.fillMaxSize()) {
                AppNavHost(
                    navController = navController,
                    startDestination = startDestination,
                    homeViewModel = homeViewModel,
                    selectionViewModel = selectionViewModel,
                    detailNewsViewModel = detailNewsViewModel,
                    onboardingStore = onboardingStore,
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                )
                // Task 4's ad-slot placeholder Spacer was replaced here (Plan 4 Task 6) by the real
                // BannerAdSlot — spec §8's IMMUTABLE ad-ethics rule, as the pure adSlotVisible truth
                // table (core:ads).
                //
                // Plan 5 Task 3 (user dogfooding: "ads appearing causes glitchy experience"): this
                // call site does TWO separate things, not one — see BannerAdSlot's own (expect)
                // kdoc for the full split. (a) This `if` gates whether BannerAdSlot is called AT
                // ALL: only while `!isPlusActive && !isOnboarding`, i.e. exactly the two axes that
                // are meant to be a genuine destroy()/recreate (Plus purchase, onboarding
                // finishing) rather than a frequent, in-session toggle. (b) The full 3-input
                // adSlotVisible (isDetailOpen included) still feeds `visible` below, interpreted by
                // BannerAdSlot as "collapse the reserved height + pause," never as "tear the AdView
                // down" — so isDetailOpen toggling (opening/closing the quake detail sheet) never
                // destroys/reloads the ad.
                //
                // The Settings-nav follow-up (user dogfooding follow-up — see AD_ELIGIBLE_ROUTES'/
                // isAdEligibleRoute's own kdoc above for the full gap this closes, named but
                // deliberately left unfixed by task-3-report.md section 4): THIS call site is no
                // longer nested inside `if (showTabChrome)` — that nesting was the bug. Settings/
                // Paywall navigation no longer tears this composable down and rebuilds a fresh
                // AdView, because neither route flips `isPlusActive` or `isOnboarding`,  the only
                // two things gate (a) above still checks. `isAdEligibleRoute(currentRoute)`, ANDed
                // into `visible` alongside the untouched 3-input adSlotVisible, is what keeps
                // Settings from ever visually showing an ad despite the AdView staying mounted
                // underneath it — the same "collapse reserved height + pause + View.GONE" path
                // adSlotVisible's own isDetailOpen=true case already takes, never a destroy.
                // AppBottomBar's own `if (showTabChrome)` gate (below) is now a fully independent
                // `if` — the two only ever rose and fell together before because they were written
                // inside the same block, not because either one's OWN lifecycle actually depended
                // on the other; conflating them is exactly what caused this bug.
                if (!isPlusActive && !isOnboarding) {
                    BannerAdSlot(
                        visible = adSlotVisible(
                            isPlusActive = isPlusActive,
                            isDetailOpen = isDetailOpen,
                            isOnboarding = isOnboarding,
                        ) && isAdEligibleRoute(currentRoute),
                        reducedMotion = LocalReducedMotion.current,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                if (showTabChrome) {
                    AppBottomBar(currentRoute = currentRoute, navController = navController)
                }
            }
        }
    }
}

/**
 * THE map-survival mechanism, corrected (Task 5, Plan 3 — Task 4 review flagged this kdoc's prior
 * wording as misattributed): `launchSingleTop` skips pushing a duplicate entry when the tapped tab
 * is already current; `restoreState`/`popUpTo(HOME, saveState = true)` is Navigation Compose's
 * standard "multi-tab back stack" pattern — each tab's `NavBackStackEntry`, and the
 * `SaveableStateRegistry` scoped to it, is saved rather than destroyed when you leave it, and
 * restored rather than rebuilt from scratch when you come back.
 *
 * That `SaveableStateRegistry` — NOT `ViewModelStore`/`SavedStateHandle` survival, this kdoc's own
 * prior wording — is what actually keeps `QuakeMap`'s camera position (pan/zoom) alive across a tab
 * round trip: maplibre-compose's `rememberCameraState()` calls `rememberSaveable(saver =
 * CameraStateSaver, ...)` internally (decompiled-verified against the real
 * `maplibre-compose-android-0.14.0.aar`, not assumed from the function's name alone —
 * `CameraStateKt.rememberCameraState`'s bytecode invokes
 * `androidx.compose.runtime.saveable.RememberSaveableKt.rememberSaveable` with
 * `org.maplibre.compose.camera.CameraStateSaver.INSTANCE` as the `Saver` argument), and
 * `rememberSaveable`'s own save/restore scope is exactly this `NavBackStackEntry`'s
 * `SaveableStateRegistry` — the thing `saveState = true` above is what preserves.
 *
 * [HomeViewModel]/[QuakeSelectionViewModel] were never at risk from tab navigation at all, and so
 * were never something this `popUpTo` recipe needed to protect on their behalf: both are resolved
 * once, at `App()`, OUTSIDE this whole `NavHost` (see [AppNav]'s own kdoc and `App.kt`'s) — neither
 * was ever inside a `NavBackStackEntry`-scoped `ViewModelStore` to begin with, so there was never a
 * destroy-on-navigate risk here for anything to guard against. Conflating "the camera position
 * survives" with "the ViewModels are Activity-scoped" (this kdoc's own prior wording did, by
 * crediting both to the same `NavBackStackEntry`/`ViewModelStore`/`SavedStateHandle` save) describes
 * two different, unrelated survival mechanisms as if they were one.
 *
 * [Routes.HOME] (not `navController.graph.findStartDestination()`) is the literal `popUpTo` target
 * by deliberate choice, not an oversight: this graph's OWN start destination is conditionally
 * [Routes.ONBOARDING] on a fresh install (see [AppNav]), which is popped off the back stack
 * entirely (`inclusive = true`) the moment onboarding finishes — `findStartDestination` would keep
 * resolving to onboarding's id even after it's gone, where [Routes.HOME] is always the correct
 * "first tab" target for every caller of this function (it's only ever invoked from tab chrome,
 * which never shows during onboarding — see [TAB_ROUTES]).
 */
private fun navigateToTab(navController: NavHostController, route: String) {
    navController.navigate(route) {
        launchSingleTop = true
        restoreState = true
        popUpTo(Routes.HOME) { saveState = true }
    }
}

// UI polish findings (docs/superpowers/plans/2026-08-16-ui-polish-findings.md), Part 3 item 3: none
// of this NavHost's composable() call sites set an explicit enterTransition/exitTransition, so tab
// switches (Home/History/Insights/Settings) fell through to Navigation Compose's own unreviewed
// default. A deliberate crossfade replaces that un-reviewed default with an intentional one -
// specifically NOT a lateral slide, since these are sibling tabs/a settings push, not a forward
// navigation-stack step. Duration/easing are the real M3 MotionTokens.kt values the doc sourced
// directly from this project's resolved material3:1.8.2 dependency (m3.material.io's own motion
// pages proved unfetchable - a client-rendered SPA, see the doc's Sources section) rather than
// guessed: Medium2 = 300ms; EmphasizedDecelerate = cubic-bezier(0.05, 0.7, 0.1, 1) for the entering
// tab, Standard = cubic-bezier(0.2, 0, 0, 1) for the exiting one. `MotionTokens` itself isn't part
// of material3's public API surface, so these are reconstructed directly as `CubicBezierEasing`
// (a stable, public, constructible type) from the doc's own sourced control points, rather than
// depended on internally.
private const val TAB_CROSSFADE_DURATION_MS = 300
private val TabEnterEasing = CubicBezierEasing(0.05f, 0.7f, 0.1f, 1f) // EmphasizedDecelerate
private val TabExitEasing = CubicBezierEasing(0.2f, 0f, 0f, 1f) // Standard

private fun tabEnterTransition(reducedMotion: Boolean): EnterTransition =
    if (reducedMotion) {
        EnterTransition.None
    } else {
        fadeIn(tween(TAB_CROSSFADE_DURATION_MS, easing = TabEnterEasing))
    }

private fun tabExitTransition(reducedMotion: Boolean): ExitTransition =
    if (reducedMotion) {
        ExitTransition.None
    } else {
        fadeOut(tween(TAB_CROSSFADE_DURATION_MS, easing = TabExitEasing))
    }

@Composable
private fun AppNavHost(
    navController: NavHostController,
    startDestination: String,
    homeViewModel: HomeViewModel,
    selectionViewModel: QuakeSelectionViewModel,
    detailNewsViewModel: DetailNewsViewModel,
    onboardingStore: OnboardingStore,
    modifier: Modifier = Modifier,
) {
    // Read once per recomposition of this composable (an accessibility-setting-driven value, not
    // something that changes mid-gesture) and captured by the plain (non-@Composable)
    // enterTransition/exitTransition lambdas below - the same "read LocalReducedMotion.current once,
    // thread the plain Boolean down" shape this app already uses throughout (e.g. BannerAdSlot's
    // `reducedMotion = LocalReducedMotion.current` at its AppNav call site below).
    val reducedMotion = LocalReducedMotion.current
    NavHost(navController = navController, startDestination = startDestination, modifier = modifier) {
        // Only the 4 routes the doc's own Part 3 table names (Home/History/Insights/Settings) get
        // the crossfade - Paywall/Onboarding keep whatever default Navigation Compose already
        // applies, deliberately out of this item's named scope (a first-run-only pager and a rare
        // purchase-flow push read differently from a "switching sibling tabs" crossfade).
        // popEnterTransition/popExitTransition default to enterTransition/exitTransition when not
        // set explicitly, so back-direction (e.g. a Settings->Home system-back) gets the identical
        // fade, not a directional asymmetry.
        composable(
            Routes.HOME,
            enterTransition = { tabEnterTransition(reducedMotion) },
            exitTransition = { tabExitTransition(reducedMotion) },
        ) {
            HomeScreen(
                viewModel = homeViewModel,
                selectionViewModel = selectionViewModel,
                detailNewsViewModel = detailNewsViewModel,
                onSettingsClick = { navController.navigate(Routes.SETTINGS) },
            )
        }
        // Task 5 (Plan 3): History's own real HistoryViewModel is resolved INSIDE HistoryScreen's
        // own defaulted `= koinViewModel()` parameter (same shape HomeScreen's own
        // selectionViewModel param used before Task 4 needed to override it) -- Compose Navigation
        // scopes that call to THIS route's own NavBackStackEntry automatically, giving History the
        // same "survives a tab-away/tab-back round trip for as long as launchSingleTop/restoreState
        // keeps this entry warm" behavior Home's own map/ViewModels get, with no special-casing
        // needed here. selectionViewModel is passed through explicitly (not defaulted) -- it must
        // be the SAME Activity-scoped instance Home shares, never a second nav-back-stack-entry-
        // scoped one (see HistoryScreen's own kdoc). detailNewsViewModel (Plan 4 Task 5): same
        // Activity-scoped sharing reasoning.
        composable(
            Routes.HISTORY,
            enterTransition = { tabEnterTransition(reducedMotion) },
            exitTransition = { tabExitTransition(reducedMotion) },
        ) {
            HistoryScreen(selectionViewModel = selectionViewModel, detailNewsViewModel = detailNewsViewModel)
        }
        // Task 6 (Plan 3): same shape as HISTORY above -- InsightsViewModel resolves via
        // InsightsScreen's own defaulted `= koinViewModel()` param, selectionViewModel threaded
        // through explicitly so a STRONGEST-card tap opens the same shared detail sheet.
        // detailNewsViewModel (Plan 4 Task 5): same Activity-scoped sharing reasoning; Insights'
        // OWN separate "In the news" card resolves InsightsNewsViewModel via its own defaulted
        // param instead, see InsightsScreen's own kdoc.
        composable(
            Routes.INSIGHTS,
            enterTransition = { tabEnterTransition(reducedMotion) },
            exitTransition = { tabExitTransition(reducedMotion) },
        ) {
            InsightsScreen(selectionViewModel = selectionViewModel, detailNewsViewModel = detailNewsViewModel)
        }
        // Task 7 (Plan 3): the real SettingsScreen replaces the placeholder — its own
        // `= koinViewModel()` default resolves SettingsViewModel scoped to this route's own
        // NavBackStackEntry (same shape History/Insights already use for their own ViewModels).
        // onBack pops this stack-only route — see SettingsScreen's own kdoc for why it needs one at
        // all (unlike HOME/HISTORY/INSIGHTS, this isn't a tab with its own back-stack root).
        // Plan 4 Task 6: onPlusClick pushes the new PAYWALL route — same "stack-only route reached
        // from a tab, popped via onBack" shape.
        composable(
            Routes.SETTINGS,
            enterTransition = { tabEnterTransition(reducedMotion) },
            exitTransition = { tabExitTransition(reducedMotion) },
        ) {
            SettingsScreen(
                onBack = { navController.popBackStack() },
                onPlusClick = { navController.navigate(Routes.PAYWALL) },
            )
        }
        // Plan 4 Task 6: the "TerraWatch Plus" paywall STUB (real purchases-kmp-ui wiring is Task
        // 8, once a RevenueCat account/product exists — see PaywallScreen's own kdoc). Stack-only,
        // same "reached from a tab, own onBack pops it" shape as SETTINGS/ONBOARDING above.
        composable(Routes.PAYWALL) { PaywallScreen(onBack = { navController.popBackStack() }) }
        // Task 8 (Plan 3): the real 3-step pager replaces the OnboardingPlaceholder this route
        // used to render (Task 4's own scaffolding, deleted below — see OnboardingScreen.kt's own
        // kdoc for the 3 steps). onFinish fires from EITHER the pager's own "Done" (final step) or
        // its top-right "Skip" (any step) — both mean the same thing to this call site: flip the
        // one-shot flag, then pop onboarding off the back stack entirely so it can never be reached
        // via system-back (see navigateToTab's own kdoc for why Routes.HOME, not
        // findStartDestination(), is this graph's correct post-onboarding "first tab").
        composable(Routes.ONBOARDING) {
            OnboardingScreen(
                onFinish = {
                    onboardingStore.setOnboarded()
                    navController.navigate(Routes.HOME) {
                        popUpTo(Routes.ONBOARDING) { inclusive = true }
                    }
                },
            )
        }
    }
}

@Composable
private fun AppBottomBar(currentRoute: String?, navController: NavHostController) {
    NavigationBar(
        // Glass per the allow-list (pill/banner/nav/sheet-header) -- same translucent-surface +
        // tonal-elevation treatment as StatusShield/StalenessBanner's own Surface, applied to
        // M3's NavigationBar container (whose own default is a flat, opaque surfaceContainer).
        containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.78f),
        tonalElevation = 4.dp,
    ) {
        TAB_ITEMS.forEach { item ->
            NavigationBarItem(
                selected = currentRoute == item.route,
                onClick = { navigateToTab(navController, item.route) },
                icon = item.icon,
                label = { Text(item.label) },
                modifier = Modifier.testTag(item.testTag),
            )
        }
    }
}

@Composable
private fun AppNavigationRail(currentRoute: String?, navController: NavHostController) {
    NavigationRail(
        containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.78f),
    ) {
        TAB_ITEMS.forEach { item ->
            NavigationRailItem(
                selected = currentRoute == item.route,
                onClick = { navigateToTab(navController, item.route) },
                icon = item.icon,
                label = { Text(item.label) },
                modifier = Modifier.testTag(item.testTag),
            )
        }
    }
}

private class TabItem(val route: String, val label: String, val testTag: String, val icon: @Composable () -> Unit)

private val TAB_ITEMS = listOf(
    TabItem(Routes.HOME, "Home", NAV_HOME_TAG) { HomeTabIcon() },
    TabItem(Routes.HISTORY, "History", NAV_HISTORY_TAG) { HistoryTabIcon() },
    TabItem(Routes.INSIGHTS, "Insights", NAV_INSIGHTS_TAG) { InsightsTabIcon() },
)
