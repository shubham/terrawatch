package com.yugma.terrawatch

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.yugma.terrawatch.feed.FeedScreen
import com.yugma.terrawatch.feed.FeedViewModel
import com.yugma.terrawatch.map.QuakeMap
import org.koin.compose.viewmodel.koinViewModel

// TEMPORARY SPIKE GATE (Plan 2, Task 6 — maplibre-compose decision gate).
// When true, App() renders the bare QuakeMap() spike instead of the real FeedScreen, so the map
// can be visually/behaviorally verified on every target without wiring a real nav destination for
// it yet. Task 8 deletes this flag entirely and gives QuakeMap a permanent home in HomeScreen.
// Do NOT build additional product UI behind this gate — see
// docs/superpowers/plans/plan-2-spike-maplibre.md for what this spike found and decided.
private const val SHOW_MAP_SPIKE = true

// No KoinContext/KoinApplication wrapper: both platform entry points (MainActivity, jvmMain's
// main()) call startKoin {} imperatively before setContent {}/application {}, which registers
// Koin's GlobalContext — koinViewModel()'s default Koin lookup (LocalKoinApplication falling back
// to KoinPlatform.getKoin()) resolves against that automatically. Koin's own KoinContext composable
// is deprecated as of koin-compose 4.x precisely because it's redundant once startKoin() has run.
@Composable
fun App() {
    if (SHOW_MAP_SPIKE) {
        MaterialTheme {
            Surface(Modifier.fillMaxSize()) { QuakeMap(Modifier.fillMaxSize()) }
        }
        return
    }
    val feedViewModel = koinViewModel<FeedViewModel>()
    MaterialTheme {
        Surface(Modifier.fillMaxSize()) { FeedScreen(feedViewModel) }
    }
}
