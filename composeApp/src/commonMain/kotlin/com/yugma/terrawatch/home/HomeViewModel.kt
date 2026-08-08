package com.yugma.terrawatch.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.yugma.terrawatch.data.QuakeRepository
import com.yugma.terrawatch.data.RefreshStatus
import com.yugma.terrawatch.map.QuakePin
import com.yugma.terrawatch.model.Quake
import com.yugma.terrawatch.model.magnitudeBand
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
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

/** What one [QuakeRepository.recentQuakes] emission reduces to, computed off-main (see [flowOn]
 * below) before [HomeViewModel.refreshFailed] gets combined in to build [HomeUiState.Content]. */
private data class HomeSnapshot(
    val quakes: List<Quake>,
    val pins: List<QuakePin>,
    val lastUpdatedMillis: Long?,
)

class HomeViewModel(private val repository: QuakeRepository) : ViewModel() {
    private val _state = MutableStateFlow<HomeUiState>(HomeUiState.Loading)
    val state: StateFlow<HomeUiState> = _state

    // Drives Task 10's pin-drop animation. Re-exposed as-is: HomeViewModel adds no logic on top of
    // what QuakeRepository already decided (previous == null at ingest time) — see
    // QuakeRepository.insertedQuakeIds's own kdoc for why updates/revisions don't emit here.
    val newQuakeIds: SharedFlow<String> = repository.insertedQuakeIds

    // Fix Round 2 (review finding): this used to be a `val status = repository.refreshFeed()`
    // local, captured ONCE inside the same coroutine that then went on to collect
    // recentQuakes() forever, re-reading that same frozen `status` on every emission — so a
    // failed initial refresh stayed flagged in every future Content, permanently, even once a
    // later live/refresh update proved data was flowing again. Now a mutable StateFlow, combined
    // into Content below rather than captured once: [init]'s refresh-loop coroutine sets it true
    // only when refreshFeed() itself fails; a second coroutine (also in init) clears it back to
    // false the moment there's direct proof a quake actually got written. See that coroutine's own
    // comment for why "a fresh insertedQuakeIds emission" was picked over the alternative floated
    // for this fix ("any recentQuakes emission that grows the quake count after the failure").
    private val refreshFailed = MutableStateFlow(false)

    init {
        // The refresh loop. Fix Round 2 (review finding): this used to run in the SAME coroutine
        // as, and immediately before, `repository.recentQuakes().collect { ... }` below — since
        // refreshFeed() suspends on the network, that delayed the very first read of the
        // (possibly already-populated) local cache behind a network round-trip that has nothing
        // to do with it. Splitting the two into independent coroutines means a pre-seeded cache
        // paints instantly, whether or not — and however long before — this refresh resolves.
        viewModelScope.launch {
            val status = repository.refreshFeed()
            if (status == RefreshStatus.FAILED) refreshFailed.value = true
            repository.startLive(viewModelScope)
        }

        // The cache-driven state loop. Starts collecting immediately — does NOT wait on the
        // refresh-loop launch above (see its comment).
        viewModelScope.launch {
            // A child of this launch, not a third top-level `viewModelScope.launch`: clearing
            // refreshFailed is part of this same responsibility (keeping Content's refreshFailed
            // flag honest), not an independent concern — it's cancelled together with the
            // collection below, not separately.
            //
            // Picked over the fix's other suggested option ("any recentQuakes emission that grows
            // the quake count after the failure") as the simpler-and-equally-correct one: a fresh
            // insertedQuakeIds emission is direct, unambiguous proof that ingest() just wrote a
            // genuinely new quake (live- or refresh-triggered) — no "previous count" bookkeeping,
            // and no first-emission-doesn't-count edge case to get right. It's also exactly the
            // same signal Task 10's pin-drop animation already keys off of for "a new quake
            // landed", so this reuses rather than duplicates that notion of "data is flowing".
            launch {
                repository.insertedQuakeIds.collect { refreshFailed.value = false }
            }
            // Fix Round 2 (review finding): pin mapping and the lastFetchedAtMillis() read used to
            // run directly inside collect{}'s lambda — i.e. on Dispatchers.Main, once per
            // recentQuakes() emission. Both now happen inside this upstream .map{}, pushed off
            // Main via flowOn(Dispatchers.Default); collect{} below only assigns the already-built
            // result to _state.value.
            combine(
                repository.recentQuakes()
                    .map { quakes ->
                        HomeSnapshot(
                            quakes = quakes,
                            pins = quakes.map { it.toPin() },
                            lastUpdatedMillis = repository.lastFetchedAtMillis(),
                        )
                    }
                    .flowOn(Dispatchers.Default),
                refreshFailed,
            ) { snapshot, failed ->
                HomeUiState.Content(
                    pins = snapshot.pins,
                    quakes = snapshot.quakes,
                    // TODO(Task 10): bind to repository's live-WS connection state once that
                    // exists. For now this only means "startLive() was called", the same
                    // placeholder FeedViewModel uses (see FeedUiState.Content's own TODO).
                    isLive = true,
                    lastUpdatedMillis = snapshot.lastUpdatedMillis,
                    refreshFailed = failed,
                )
            }.collect { content -> _state.value = content }
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
