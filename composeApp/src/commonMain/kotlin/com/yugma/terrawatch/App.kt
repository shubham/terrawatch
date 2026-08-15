package com.yugma.terrawatch

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.Surface
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import com.yugma.terrawatch.data.ThemeSetting
import com.yugma.terrawatch.data.ThemeStore
import com.yugma.terrawatch.data.resolveDarkTheme
import com.yugma.terrawatch.detail.DetailNewsViewModel
import com.yugma.terrawatch.home.HomeViewModel
import com.yugma.terrawatch.home.QuakeSelectionViewModel
import com.yugma.terrawatch.home.rememberQuakeSelectionExtras
import com.yugma.terrawatch.motion.LocalReducedMotion
import com.yugma.terrawatch.motion.systemReducedMotion
import com.yugma.terrawatch.nav.AppNav
import com.yugma.terrawatch.ui.theme.TerraTheme
import org.koin.compose.koinInject
import org.koin.compose.viewmodel.koinViewModel

// No KoinContext/KoinApplication wrapper: both platform entry points (MainActivity, jvmMain's
// main()) call startKoin {} imperatively before setContent {}/application {}, which registers
// Koin's GlobalContext — koinViewModel()'s default Koin lookup (LocalKoinApplication falling back
// to KoinPlatform.getKoin()) resolves against that automatically. Koin's own KoinContext composable
// is deprecated as of koin-compose 4.x precisely because it's redundant once startKoin() has run.
//
// Task 8: Home (full-bleed live map + pins) is the app's real screen now — the Task 6 map spike
// gate (SHOW_MAP_SPIKE) is gone. TerraTheme (not a bare MaterialTheme) wraps everything from here
// down so the Calm Guardian ColorScheme/Typography — magnitude colors, bold numerals — actually
// reach the UI; this is its first real call site (core:ui's Task 5 tokens existed but were unused
// by any screen until now). FeedScreen/FeedViewModel (the pre-Task-8 screen) were deleted in Task
// 10 Fix Round 1 — dead since this screen took over and never claimed by Task 9's detail sheet.
//
// Task 4 (Plan 3): calls AppNav() instead of HomeScreen() directly now — see AppNav.kt's own kdoc
// for the 5-route NavHost + tab chrome this delegates to. Both homeViewModel AND
// selectionViewModel are resolved here, at this exact composition point, BEFORE AppNav/NavHost
// exist at all — "scope to the ACTIVITY" (this task's brief) means exactly this: neither
// koinViewModel() call below is nested inside any NavHost `composable()` block, so neither ties
// its instance to a nav-back-stack-entry-scoped ViewModelStore that Navigation Compose could
// tear down independently of the other. Home/History/Insights (History/Insights once Tasks 5/6
// replace their placeholders) all resolve the SAME selectionViewModel instance via AppNav's
// explicit parameter passing, not via a second, independent koinViewModel() call each — that
// second call would each resolve against whatever the CURRENT nav back stack entry happens to be,
// which is not what "shared instance across tabs" means. QuakeSelectionViewModel used to be
// HomeScreen's own defaulted `= koinViewModel()` parameter (Task 3) specifically so this override
// would be possible once Task 4 needed it — see that composable's own kdoc.
// Task 7 (Plan 3): TerraTheme's darkTheme now resolves through the Settings screen's theme radio
// (System/Light/Dusk) instead of always deferring to isSystemInDarkTheme(). ThemeStore is
// koinInject()'d directly here — same non-ViewModel "plain single, plain koinInject()" shape
// AppNav.kt already uses for OnboardingStore — rather than through a ViewModel App() would
// otherwise have no other reason to own. collectAsState(initial = SYSTEM) is safe against a
// "flash of the wrong theme" on cold start: ThemeStore.theme's own onStart{} block (see that
// class's kdoc) emits the real stored value synchronously the moment this collector subscribes,
// well before the first frame actually paints, so SYSTEM here is a type-safe placeholder for an
// initial value collectAsState requires, not a value this composable ever visibly renders with.
// Plan 4 Task 3: [pendingQuakeId]/[onQuakeIdConsumed] are the tap-through deep link from a digest
// notification — both default so every pre-existing call site (jvmMain's main(), wasmJs's own
// entry point, both bare `App()` calls) keeps compiling unchanged; only MainActivity's android
// actual ever supplies a non-null id (a notification's own PendingIntent extra — see
// AlertDigestWorker's kdoc). [onQuakeIdConsumed] lets the caller clear its own held state once
// this composable has acted on the id, so a later recomposition triggered by something unrelated
// doesn't call [QuakeSelectionViewModel.select] a second time with the same stale id.
@Composable
fun App(pendingQuakeId: String? = null, onQuakeIdConsumed: () -> Unit = {}) {
    val homeViewModel = koinViewModel<HomeViewModel>()
    // Task 9 (Plan 3) + desktop hotfix: rememberQuakeSelectionExtras() is null only on Android —
    // see that function's own kdoc (QuakeSelectionExtras.kt) for the real crash this works around
    // (Koin's SavedStateHandle auto-injection needs a SavedStateRegistryOwner that neither wasmJs's
    // ComposeViewport nor desktop's Window {} supply by default; Android's ComponentActivity
    // already does, so this branch is a pure no-op there — koinViewModel<QuakeSelectionViewModel>()'s
    // own default `extras` is untouched on Android only).
    val quakeSelectionExtras = rememberQuakeSelectionExtras()
    val selectionViewModel = if (quakeSelectionExtras != null) {
        koinViewModel<QuakeSelectionViewModel>(extras = quakeSelectionExtras)
    } else {
        koinViewModel<QuakeSelectionViewModel>()
    }
    // Plan 4 Task 5: same "resolved once here, threaded through AppNav explicitly" shape as
    // selectionViewModel just above (see that val's own kdoc) - DetailNewsViewModel needs no
    // SavedStateHandle extras dance (plain constructor), so a bare koinViewModel() call is enough.
    val detailNewsViewModel = koinViewModel<DetailNewsViewModel>()
    val themeStore = koinInject<ThemeStore>()
    val themeSetting by themeStore.theme.collectAsState(initial = ThemeSetting.SYSTEM)
    // Plan 4 Task 3: keyed on pendingQuakeId itself, not Unit -- a SECOND notification tapped while
    // this composable is already alive (MainActivity's singleTask onNewIntent path) hands down a
    // NEW, different id, which must re-run this effect and open THAT quake's sheet; a Unit key
    // would only ever fire once for this composable's whole lifetime. `null` (the default/
    // already-consumed state) intentionally does nothing -- select() needs a real id.
    LaunchedEffect(pendingQuakeId) {
        pendingQuakeId?.let { id ->
            selectionViewModel.select(id)
            onQuakeIdConsumed()
        }
    }
    TerraTheme(darkTheme = resolveDarkTheme(themeSetting, isSystemInDarkTheme())) {
        // Task 10: resolved once here (composition root) and handed down via CompositionLocal so
        // every screen/component that gates motion off LocalReducedMotion reads the same answer
        // without each re-deriving the platform signal itself.
        CompositionLocalProvider(LocalReducedMotion provides systemReducedMotion()) {
            Surface(Modifier.fillMaxSize()) {
                AppNav(
                    homeViewModel = homeViewModel,
                    selectionViewModel = selectionViewModel,
                    detailNewsViewModel = detailNewsViewModel,
                )
            }
        }
    }
}
