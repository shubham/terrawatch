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
    data class Content(val quakes: List<Quake>, val isLive: Boolean) : FeedUiState
    data class Error(val message: String) : FeedUiState
}

class FeedViewModel(private val repository: QuakeRepository) : ViewModel() {
    private val _state = MutableStateFlow<FeedUiState>(FeedUiState.Loading)
    val state: StateFlow<FeedUiState> = _state

    init {
        viewModelScope.launch {
            val status = repository.refreshFeed()
            if (status == RefreshStatus.FAILED) {
                _state.value = FeedUiState.Error("Couldn't reach USGS")
            }
            repository.startLive(viewModelScope)
            repository.recentQuakes().collect { quakes ->
                _state.value = FeedUiState.Content(quakes, isLive = true)
            }
        }
    }
}
