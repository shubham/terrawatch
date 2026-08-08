package com.yugma.terrawatch.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.yugma.terrawatch.data.HomeLocationStore
import com.yugma.terrawatch.data.QuakeRepository
import com.yugma.terrawatch.data.RefreshStatus
import com.yugma.terrawatch.location.LocationProvider
import com.yugma.terrawatch.map.QuakePin
import com.yugma.terrawatch.model.GeoPoint
import com.yugma.terrawatch.model.MagRevision
import com.yugma.terrawatch.model.Quake
import com.yugma.terrawatch.model.QuakeStatus
import com.yugma.terrawatch.model.Source
import com.yugma.terrawatch.model.magnitudeBand
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlin.random.Random
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

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

class HomeViewModel(
    private val repository: QuakeRepository,
    private val homeLocationStore: HomeLocationStore,
    private val locationProvider: LocationProvider,
) : ViewModel() {
    private val _state = MutableStateFlow<HomeUiState>(HomeUiState.Loading)
    val state: StateFlow<HomeUiState> = _state

    // Drives Task 10's pin-drop animation. Re-exposed as-is: HomeViewModel adds no logic on top of
    // what QuakeRepository already decided (previous == null at ingest time) — see
    // QuakeRepository.insertedQuakeIds's own kdoc for why updates/revisions don't emit here.
    val newQuakeIds: SharedFlow<String> = repository.insertedQuakeIds

    // Task 9: the pill's other dependency, besides the quake list itself — pillStatus(quakes,
    // home, now) is computed in HomeScreen's composition (cheap, pure), fed by this. Null until
    // the init{} load below resolves (store empty AND no fix yet, or still loading) — pillStatus
    // treats null exactly like "not yet known" (Kind.ASK_LOCATION), which is the correct answer
    // in both cases: nothing to be unsafe *about* without a reference point.
    private val _homeLocation = MutableStateFlow<GeoPoint?>(null)
    val homeLocation: StateFlow<GeoPoint?> = _homeLocation

    // Task 9: how many quakes have arrived since the feed sheet was last dragged open — the
    // sheet's "N NEW" chip. Incremented alongside refreshFailed's clearing below (same triggering
    // event: a genuinely new quake, per insertedQuakeIds' own not-on-updates contract), reset by
    // [markSheetExpanded] when HomeScreen observes the sheet reach SheetValue.Expanded.
    private val _newSinceExpand = MutableStateFlow(0)
    val newSinceExpand: StateFlow<Int> = _newSinceExpand

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

        // Task 9: home location, resolved once at startup. Dispatchers.Default because
        // HomeLocationStore.get() is a synchronous DAO read (SQLDelight) and LocationProvider's
        // android actual reads a system service — neither belongs on Main. A stored point always
        // wins over asking the platform again; a freshly-resolved fix gets remembered as home so
        // this only ever asks the platform once (matches the brief's `get() ?: current()?.also
        // { set(it) }` — HomeLocationStore.set() is itself an ordinary synchronous DAO write, and
        // running it here, still on Dispatchers.Default, keeps it off Main too).
        viewModelScope.launch(Dispatchers.Default) {
            val stored = homeLocationStore.get()
            _homeLocation.value = stored ?: locationProvider.current()?.also { homeLocationStore.set(it) }
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
            //
            // Task 9 extends this same collector with the feed sheet's "N NEW" counter, rather
            // than adding a second independent collect{} on the same hot SharedFlow: both effects
            // react to the exact same event (ingest() just wrote a genuinely new quake), so it's
            // one subscription with two consequences, not two subscriptions.
            launch {
                repository.insertedQuakeIds.collect {
                    refreshFailed.value = false
                    _newSinceExpand.value += 1
                }
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
                // Task 10: the Task 8/Plan 1 TODO dies here — isLive now reflects whether the
                // EMSC WebSocket is actually open (QuakeRepository.liveConnected ->
                // EmscLiveSource.connected), not merely "startLive() was called".
                repository.liveConnected,
            ) { snapshot, failed, live ->
                HomeUiState.Content(
                    pins = snapshot.pins,
                    quakes = snapshot.quakes,
                    isLive = live,
                    lastUpdatedMillis = snapshot.lastUpdatedMillis,
                    refreshFailed = failed,
                )
            }.collect { content -> _state.value = content }
        }
    }

    /** Called by HomeScreen when the feed sheet reaches [androidx.compose.material3.SheetValue]
     * `.Expanded` — the user has now seen the list, so the "N NEW" chip resets. */
    fun markSheetExpanded() {
        _newSinceExpand.value = 0
    }

    /**
     * Task 10 device-verification hook: manufactures a fake M6.0 "quake" at the given point and
     * pushes it through the exact same [QuakeRepository.ingest] path a real live-WS or feed-poll
     * quake would take — the pin-drop animation, the feed sheet's "N NEW" chip, and
     * [refreshFailed] clearing all fire exactly as they would for a real arrival, because
     * `ingest()` has no way to tell this one didn't come off the wire. This method itself carries
     * no debug/release branch (HomeViewModel stays platform/build-type agnostic, matching the rest
     * of this class) — gating to debug builds only happens at the call site, where QuakeMap's
     * Android actual decides whether to attach the long-press gesture that invokes this at all.
     * The id includes a random suffix (not just the timestamp) so two presses landing in the same
     * millisecond still both count as "new" rather than one silently overwriting the other.
     */
    @OptIn(ExperimentalTime::class)
    fun injectDebugQuake(lat: Double, lon: Double) {
        viewModelScope.launch {
            val now = Clock.System.now().toEpochMilliseconds()
            val id = "debug-$now-${Random.nextInt(100_000)}"
            repository.ingest(
                Quake(
                    id = id,
                    timeMillis = now,
                    lat = lat,
                    lon = lon,
                    depthKm = 10.0,
                    mag = 6.0,
                    magType = "mw",
                    place = "Debug-injected M6.0",
                    tsunami = false,
                    felt = null,
                    status = QuakeStatus.AUTOMATIC,
                    sources = mapOf(Source.USGS to id),
                    revisions = listOf(MagRevision(6.0, "mw", now, Source.USGS)),
                    updatedAtMillis = now,
                ),
            )
        }
    }

    private fun Quake.toPin() = QuakePin(
        id = id,
        lat = lat,
        lon = lon,
        mag = mag,
        band = magnitudeBand(mag),
        isNew = false, // Task 10 keys the pin-drop animation off QuakeMap's newQuakeId param instead.
    )
}
