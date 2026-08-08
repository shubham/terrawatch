package com.yugma.terrawatch

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.yugma.terrawatch.feed.FeedScreen
import com.yugma.terrawatch.feed.FeedViewModel
import org.koin.compose.viewmodel.koinViewModel

// No KoinContext/KoinApplication wrapper: both platform entry points (MainActivity, jvmMain's
// main()) call startKoin {} imperatively before setContent {}/application {}, which registers
// Koin's GlobalContext — koinViewModel()'s default Koin lookup (LocalKoinApplication falling back
// to KoinPlatform.getKoin()) resolves against that automatically. Koin's own KoinContext composable
// is deprecated as of koin-compose 4.x precisely because it's redundant once startKoin() has run.
@Composable
fun App() {
    val feedViewModel = koinViewModel<FeedViewModel>()
    MaterialTheme {
        Surface(Modifier.fillMaxSize()) { FeedScreen(feedViewModel) }
    }
}
