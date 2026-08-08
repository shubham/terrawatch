package com.yugma.terrawatch.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.yugma.terrawatch.data.QuakeRepository
import com.yugma.terrawatch.data.RefreshStatus
import com.yugma.terrawatch.map.QuakePin
import com.yugma.terrawatch.model.Quake
import com.yugma.terrawatch.model.magnitudeBand
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

sealed interface HomeUiState {
    data object Loading : HomeUiState

    // NOTE: no Error terminal state here (unlike FeedUiState) — the map is the app's centerpiece
    // and must always render, even over an empty/never-fetched cache. A failed refresh only flips
    // [refreshFailed]; HomeScreen turns that into a banner over the still-visible map instead of
    // replacing it. Empty is just Content(pins = emptyList(), quakes = emptyList(), ...).
    data class Content(
        val pins: List<QuakePin>,
        val quakes: List<Quake>,
        val isLive: Boolean,
        val lastUpdatedMillis: Long?,
        val refreshFailed: Boolean,
    ) : HomeUiState
}

class HomeViewModel(private val repository: QuakeRepository) : ViewModel() {
    private val _state = MutableStateFlow<HomeUiState>(HomeUiState.Loading)
    val state: StateFlow<HomeUiState> = _state

    // Drives Task 10's pin-drop animation. Re-exposed as-is: HomeViewModel adds no logic on top of
    // what QuakeRepository already decided (previous == null at ingest time) — see
    // QuakeRepository.insertedQuakeIds's own kdoc for why updates/revisions don't emit here.
    val newQuakeIds: SharedFlow<String> = repository.insertedQuakeIds

    init {
        viewModelScope.launch {
            val status = repository.refreshFeed()
            repository.startLive(viewModelScope)
            // Same self-healing shape as FeedViewModel: recentQuakes() emits its current snapshot
            // immediately on subscribe (empty, on a fresh install), and every emission after that
            // recomputes state fresh — so a later successful live update still lands even after a
            // failed initial refreshFeed(). Unlike FeedViewModel, a failed refresh on an empty
            // cache never becomes a terminal Error: it's still Content, just flagged.
            repository.recentQuakes().collect { quakes ->
                _state.value = HomeUiState.Content(
                    pins = quakes.map { it.toPin() },
                    quakes = quakes,
                    // TODO(Task 10): bind to repository's live-WS connection state once that
                    // exists. For now this only means "startLive() was called", the same
                    // placeholder FeedViewModel uses (see FeedUiState.Content's own TODO).
                    isLive = true,
                    lastUpdatedMillis = repository.lastFetchedAtMillis(),
                    refreshFailed = status == RefreshStatus.FAILED,
                )
            }
        }
    }

    private fun Quake.toPin() = QuakePin(
        id = id,
        lat = lat,
        lon = lon,
        mag = mag,
        band = magnitudeBand(mag),
        isNew = false, // Task 10 wires pin-drop animation off newQuakeIds / QuakeMap's newQuakeId.
    )
}
