package com.yugma.terrawatch.feed

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.yugma.terrawatch.data.QuakeRepository
import com.yugma.terrawatch.data.RefreshStatus
import com.yugma.terrawatch.model.Quake
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

sealed interface FeedUiState {
    data object Loading : FeedUiState
    // isLive currently just means "startLive() was called", not that the WS is actually
    // connected. TODO(Plan 2): reflect actual WS connection state, not merely "startLive was called".
    data class Content(val quakes: List<Quake>, val isLive: Boolean) : FeedUiState
    data class Error(val message: String) : FeedUiState
}

class FeedViewModel(private val repository: QuakeRepository) : ViewModel() {
    private val _state = MutableStateFlow<FeedUiState>(FeedUiState.Loading)
    val state: StateFlow<FeedUiState> = _state

    init {
        viewModelScope.launch {
            val status = repository.refreshFeed()
            repository.startLive(viewModelScope)
            // recentQuakes() emits its current snapshot immediately on subscribe — on a fresh
            // install with zero local rows, that's an empty list, unconditionally. Deciding the
            // state per-emission (rather than setting Error once beforehand) means: an empty DB
            // plus a failed refresh surfaces the error instead of a silent blank screen, but the
            // moment real data lands (this refresh succeeded, or startLive() ingests one later),
            // that same collector self-heals to Content on the next emission.
            repository.recentQuakes().collect { quakes ->
                _state.value = when {
                    quakes.isNotEmpty() -> FeedUiState.Content(quakes, isLive = true)
                    status == RefreshStatus.FAILED -> FeedUiState.Error("Couldn't reach USGS")
                    else -> FeedUiState.Content(quakes, isLive = true)
                }
            }
        }
    }
}
