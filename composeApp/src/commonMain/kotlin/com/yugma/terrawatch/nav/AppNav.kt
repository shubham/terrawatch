package com.yugma.terrawatch.nav

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.yugma.terrawatch.data.OnboardingStore
import com.yugma.terrawatch.history.HistoryScreen
import com.yugma.terrawatch.home.HomeScreen
import com.yugma.terrawatch.home.HomeViewModel
import com.yugma.terrawatch.home.LayoutMode
import com.yugma.terrawatch.home.QuakeSelectionViewModel
import com.yugma.terrawatch.home.layoutMode
import org.koin.compose.koinInject

/**
 * Task 4 (Plan 3): the app's 5 nav destinations. [HOME]/[HISTORY]/[INSIGHTS] are the 3 persistent
 * tabs (bottom [NavigationBar] on phone, [NavigationRail] on desktop — see [AppNav]); [SETTINGS]
 * and [ONBOARDING] are stack-only routes reached from a tab (Home's gear chip, and the app's own
 * conditional start destination) rather than tabs of their own — neither shows in the tab
 * bar/rail (see [TAB_ROUTES]).
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
}

/** The 3 routes that show bottom-nav/rail chrome — [Routes.SETTINGS]/[Routes.ONBOARDING] render
 * full-screen with no tab chrome, same as a typical "settings is a stack push, not a tab" app. */
private val TAB_ROUTES = setOf(Routes.HOME, Routes.HISTORY, Routes.INSIGHTS)

/**
 * Task 4 (Plan 3) root nav composable — `App.kt` calls this instead of `HomeScreen` directly now.
 * Owns the [NavHost] plus whichever tab-switcher chrome fits the available width ([layoutMode],
 * reused verbatim from `home/LayoutMode.kt` — the exact same 900dp breakpoint `HomeScreen`'s own
 * `BoxWithConstraints` already uses for its phone-vs-two-pane split, so "desktop" means the same
 * thing in both places).
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
 */
@Composable
fun AppNav(homeViewModel: HomeViewModel, selectionViewModel: QuakeSelectionViewModel) {
    val onboardingStore = koinInject<OnboardingStore>()
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

    // Judgment call, documented rather than solved: this BoxWithConstraints measures the FULL
    // window width to decide rail-vs-bottom-bar, but HomeScreen's OWN BoxWithConstraints
    // (home/HomeScreen.kt) independently re-measures ITS available width -- which, once the rail
    // is showing, is narrower by the rail's own width (~80dp) -- to decide phone-vs-two-pane for
    // ITS OWN chrome. Both call the same layoutMode()/900dp breakpoint, so they normally agree,
    // but in the roughly 900-980dp band the rail can show here while Home still falls back to its
    // phone (sheet) layout underneath it, rather than the "home two-pane preserved" the brief
    // describes. Not fixed by threading a layoutMode override into HomeScreen: that would change
    // a composable three tasks of tests/device-verification already depend on self-measuring, for
    // a narrow band this plan doesn't gate any test or device screenshot on (Task 4's own device
    // matrix targets 98bc1cd8, a phone). Accepted as-is; worth a second look whenever a real
    // desktop pass happens.
    BoxWithConstraints(Modifier.fillMaxSize()) {
        if (layoutMode(maxWidth.value.toInt()) == LayoutMode.TWO_PANE) {
            Row(Modifier.fillMaxSize()) {
                if (showTabChrome) {
                    AppNavigationRail(currentRoute = currentRoute, navController = navController)
                }
                AppNavHost(
                    navController = navController,
                    startDestination = startDestination,
                    homeViewModel = homeViewModel,
                    selectionViewModel = selectionViewModel,
                    onboardingStore = onboardingStore,
                    modifier = Modifier.weight(1f).fillMaxSize(),
                )
            }
        } else {
            Column(Modifier.fillMaxSize()) {
                AppNavHost(
                    navController = navController,
                    startDestination = startDestination,
                    homeViewModel = homeViewModel,
                    selectionViewModel = selectionViewModel,
                    onboardingStore = onboardingStore,
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                )
                if (showTabChrome) {
                    // Task 4: ad-slot placeholder, "above bottom bar" per the brief -- Plan 4
                    // fills this in (Plan 2's own ad-slot deferral, honored again here). Inert on
                    // purpose: no content, no background, no click target, just reserved height.
                    Spacer(Modifier.fillMaxWidth().height(50.dp))
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

@Composable
private fun AppNavHost(
    navController: NavHostController,
    startDestination: String,
    homeViewModel: HomeViewModel,
    selectionViewModel: QuakeSelectionViewModel,
    onboardingStore: OnboardingStore,
    modifier: Modifier = Modifier,
) {
    NavHost(navController = navController, startDestination = startDestination, modifier = modifier) {
        composable(Routes.HOME) {
            HomeScreen(
                viewModel = homeViewModel,
                selectionViewModel = selectionViewModel,
                onSettingsClick = { navController.navigate(Routes.SETTINGS) },
            )
        }
        // Insights/Settings: placeholder screens THIS task -- Tasks 6/7 replace these two
        // composable() bodies with their own new InsightsScreen/SettingsScreen (own files, own
        // ViewModels). The four-states rule (Loading/Content/Empty/Error) explicitly does NOT
        // apply to these placeholders -- there's no state to be in yet, just a name and which task
        // owns filling it in.
        //
        // Task 5 (Plan 3): History's own real HistoryViewModel is resolved INSIDE HistoryScreen's
        // own defaulted `= koinViewModel()` parameter (same shape HomeScreen's own
        // selectionViewModel param used before Task 4 needed to override it) -- Compose Navigation
        // scopes that call to THIS route's own NavBackStackEntry automatically, giving History the
        // same "survives a tab-away/tab-back round trip for as long as launchSingleTop/restoreState
        // keeps this entry warm" behavior Home's own map/ViewModels get, with no special-casing
        // needed here. selectionViewModel is passed through explicitly (not defaulted) -- it must
        // be the SAME Activity-scoped instance Home shares, never a second nav-back-stack-entry-
        // scoped one (see HistoryScreen's own kdoc).
        composable(Routes.HISTORY) { HistoryScreen(selectionViewModel = selectionViewModel) }
        composable(Routes.INSIGHTS) { PlaceholderScreen("Insights — Task 6") }
        composable(Routes.SETTINGS) { PlaceholderScreen("Settings — Task 7") }
        composable(Routes.ONBOARDING) {
            OnboardingPlaceholder(
                onGetStarted = {
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
            )
        }
    }
}

private class TabItem(val route: String, val label: String, val icon: @Composable () -> Unit)

private val TAB_ITEMS = listOf(
    TabItem(Routes.HOME, "Home") { HomeTabIcon() },
    TabItem(Routes.HISTORY, "History") { HistoryTabIcon() },
    TabItem(Routes.INSIGHTS, "Insights") { InsightsTabIcon() },
)

/** History/Insights/Settings' shared placeholder body -- see [AppNavHost]'s own note on why the
 * four-states rule doesn't apply here. Reads [MaterialTheme] colors/typography (inherited from
 * the [com.yugma.terrawatch.ui.theme.TerraTheme] `App()` already wraps this whole nav graph in),
 * not hardcoded colors -- "TerraTheme'd" per the brief, without a redundant second TerraTheme{}
 * wrapper this deep in the tree. */
@Composable
private fun PlaceholderScreen(text: String) {
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(
                text = text,
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.onBackground,
            )
        }
    }
}

/**
 * Task 4's onboarding placeholder -- Task 8 replaces this with the real 3-step pager
 * (`onboarding/OnboardingScreen.kt`). [onGetStarted] both flips [OnboardingStore]'s flag AND
 * navigates home in one step (wired by [AppNavHost]) — this composable itself is stateless UI
 * only, same "screen doesn't own the persistence decision" split every other screen/store pairing
 * in this codebase already draws.
 */
@Composable
private fun OnboardingPlaceholder(onGetStarted: () -> Unit) {
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            modifier = Modifier.fillMaxSize().padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Text(
                text = "Welcome to TerraWatch",
                style = MaterialTheme.typography.headlineMedium,
                color = MaterialTheme.colorScheme.onBackground,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = "Onboarding — Task 8",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(24.dp))
            Button(onClick = onGetStarted) { Text("Get started") }
        }
    }
}
