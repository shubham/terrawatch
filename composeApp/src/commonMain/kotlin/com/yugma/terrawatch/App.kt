package com.yugma.terrawatch

import androidx.compose.material3.Surface
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import com.yugma.terrawatch.home.HomeScreen
import com.yugma.terrawatch.home.HomeViewModel
import com.yugma.terrawatch.motion.LocalReducedMotion
import com.yugma.terrawatch.motion.systemReducedMotion
import com.yugma.terrawatch.ui.theme.TerraTheme
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
@Composable
fun App() {
    val homeViewModel = koinViewModel<HomeViewModel>()
    TerraTheme {
        // Task 10: resolved once here (composition root) and handed down via CompositionLocal so
        // every screen/component that gates motion off LocalReducedMotion reads the same answer
        // without each re-deriving the platform signal itself.
        CompositionLocalProvider(LocalReducedMotion provides systemReducedMotion()) {
            Surface(Modifier.fillMaxSize()) { HomeScreen(homeViewModel) }
        }
    }
}
