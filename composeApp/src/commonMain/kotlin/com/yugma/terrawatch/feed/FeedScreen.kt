package com.yugma.terrawatch.feed

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
fun FeedScreen(viewModel: FeedViewModel) {
    val state by viewModel.state.collectAsState()
    when (val s = state) {
        FeedUiState.Loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
        }
        is FeedUiState.Error -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(s.message, style = MaterialTheme.typography.bodyLarge)
        }
        is FeedUiState.Content -> LazyColumn(Modifier.fillMaxSize().padding(16.dp)) {
            items(s.quakes, key = { it.id }) { q ->
                Row(
                    Modifier.fillMaxWidth().padding(vertical = 8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Column {
                        Text(q.place, style = MaterialTheme.typography.bodyLarge)
                        Text("depth ${q.depthKm ?: "?"} km", style = MaterialTheme.typography.bodySmall)
                    }
                    Text("M ${q.mag ?: "?"}", style = MaterialTheme.typography.titleMedium)
                }
            }
        }
    }
}
