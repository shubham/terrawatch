package com.yugma.terrawatch

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.yugma.terrawatch.feed.FeedScreen
import com.yugma.terrawatch.feed.FeedViewModel

@Composable
fun App(feedViewModel: FeedViewModel) {
    MaterialTheme {
        Surface(Modifier.fillMaxSize()) { FeedScreen(feedViewModel) }
    }
}
