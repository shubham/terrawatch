package com.yugma.terrawatch.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.yugma.terrawatch.data.AlertRuleStore
import com.yugma.terrawatch.data.HomeLocationStore
import com.yugma.terrawatch.data.QuakeRepository
import com.yugma.terrawatch.data.RefreshStatus
import com.yugma.terrawatch.location.LocationAskUiState
import com.yugma.terrawatch.location.LocationProvider
import com.yugma.terrawatch.location.LocationRequester
import com.yugma.terrawatch.location.reduceLocationPermissionState
import com.yugma.terrawatch.map.QuakePin
import com.yugma.terrawatch.model.GeoPoint
import com.yugma.terrawatch.model.MagRevision
import com.yugma.terrawatch.model.Quake
import com.yugma.terrawatch.model.QuakeStatus
import com.yugma.terrawatch.model.Source
import com.yugma.terrawatch.model.magnitudeBand
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.random.Random
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

// Task 1 (Plan 3): the refresh loop's cadence — see HomeViewModel.init's poll loop below. 60s
// balances "the map's staleness banner threshold is 10 minutes" (HomeScreen.STALE_AFTER_MILLIS)
// against not hammering the USGS feed; the etag/If-None-Match round-trip (UsgsApi.fetchFeed) means
// a no-op tick is one small conditional-GET, not a full re-download.
private const val POLL_INTERVAL_MILLIS = 60_000L

// Task 2 (Plan 4), F1 retention ruling (plan-3-exit-conditions.md carried item): pruneOldRows's
// cutoff window — see HomeViewModel.init's own comment for the call site.
private const val RETENTION_WINDOW_MILLIS = 30L * 24 * 60 * 60 * 1000 // 30 days = 2_592_000_000

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

// Task 1 (Plan 3): flatMapLatest below (the sliding-window re-subscription) is the only
// experimental-API surface this class touches — opted in at the class level rather than the
// function level because it's used inside init{}, which (unlike a named function) cannot itself
// carry an @OptIn annotation. Task 2 (Plan 4) adds ExperimentalTime to this same class-level
// opt-in — [clock]'s default value expression below needs it, same reason [injectDebugQuake]'s own
// (now redundant-but-harmless) local `@OptIn(ExperimentalTime::class)` already needed it.
@OptIn(ExperimentalCoroutinesApi::class, ExperimentalTime::class)
class HomeViewModel(
    private val repository: QuakeRepository,
    private val homeLocationStore: HomeLocationStore,
    private val locationProvider: LocationProvider,
    private val alertRuleStore: AlertRuleStore,
    // Task 2 (Plan 4): retention's cutoff clock — real wall-clock by default (matches
    // QuakeRepository/HistoryPager/InsightsViewModel's own injectable-clock-at-the-platform-
    // boundary convention exactly), overridable by tests. Appended as a 5th, DEFAULTED param
    // specifically so it doesn't disturb any existing positional 4-arg construction (AppModule.kt's
    // Koin wiring, HomeFlowTest/OnboardingGateTest's androidInstrumentedTest call sites) — none of
    // them need to change to keep compiling.
    private val clock: () -> Long = { Clock.System.now().toEpochMilliseconds() },
    // Task 1 (Plan 5): appended as a 6th, DEFAULTED param — same "doesn't disturb any existing
    // positional construction" reasoning [clock] itself already established just above (see
    // HomeViewModelTest's createVm(), HomeFlowTest/OnboardingGateTest's own construction sites —
    // none supply a 6th argument, all keep compiling unchanged). LocationRequester()'s no-arg
    // constructor is real and uniform on every target (see its own kdoc) — same "a real, working
    // default, not a test stub" shape [clock]'s own default expression already is — so production
    // Koin wiring (AppModule.kt's `HomeViewModel(get(), get(), get(), get())`) needs no change
    // either; it simply keeps falling through to this default, exactly like it already does for
    // [clock].
    private val locationRequester: LocationRequester = LocationRequester(),
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

    // Task 1 (Plan 5), USER REQUIREMENT: the cold-start camera-centering signal — see
    // startupCameraTarget's own kdoc (CameraTarget.kt) for the decision this reduces to. Null means
    // "nothing to do", either because the decision itself resolved null, or because a non-null
    // value was already applied and consumed (see consumeStartupCameraTarget below). ONE-SHOT by
    // construction: the init{} block below writes this exactly once, and HomeViewModel itself is
    // constructed exactly once per real process start — Koin's `viewModel {}` scoping (koin-
    // compose-viewmodel's koinViewModel<HomeViewModel>() at App()'s composition root) means a
    // rotation/config change reuses this SAME instance rather than re-running init{}, so this
    // StateFlow can never spontaneously become non-null a second time for the life of the process.
    private val _startupCameraTarget = MutableStateFlow<GeoPoint?>(null)
    val startupCameraTarget: StateFlow<GeoPoint?> = _startupCameraTarget

    // Task 1 (Plan 5): the my-location FAB's own recenter target — independent of
    // [_startupCameraTarget] (a different trigger, a different zoom level at the call site, and no
    // rotation-survival requirement of its own — see recenterToCurrentLocation's own kdoc below).
    private val _recenterTarget = MutableStateFlow<GeoPoint?>(null)
    val recenterTarget: StateFlow<GeoPoint?> = _recenterTarget

    // Task 1 (Plan 5): the FAB's "Location unavailable" snackbar trigger — a hot, fire-and-forget
    // event (same SharedFlow shape [newQuakeIds] above already establishes for exactly this
    // "notify once, don't replay" reason), not sticky state: a recomposition well after the tap
    // that caused it must never re-show the same snackbar.
    private val _locationUnavailableEvents = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val locationUnavailableEvents: SharedFlow<Unit> = _locationUnavailableEvents

    // Task 7 (Plan 3), USER REQUIREMENT: the pill's radius/minMag are now user-settable (Settings
    // screen slider -> AlertRuleStore) - every real pillStatus() call site (HomeScreen) threads that
    // live value through explicitly, never relying on pillStatus()'s own default parameters. Fix
    // Round 1 (entangled minor): radiusKm's default itself now points straight at
    // AlertRuleStore.DEFAULT_RADIUS_KM rather than the independent hardcoded 500.0 it used to be
    // (see PillStatus.kt) - so even that unreachable-in-production default can no longer drift from
    // the real one; minMag's default stays a plain hardcoded 4.5.
    // Seeded with the store's own compile-time defaults (not e.g. 0.0) so the very first composition
    // — before either collector below has resolved a real DB read — already renders with the exact
    // same numbers a fresh/never-configured store would produce, rather than a momentarily-wrong
    // "radius 0" pill. Same "MutableStateFlow seeded with a sane default, then updated by a live
    // collector" shape as [homeLocation] two lines up, minus that one's null/no-value-yet state:
    // unlike home location, there is no "not yet known" state for a radius/threshold to be in.
    private val _nearbyRadiusKm = MutableStateFlow(AlertRuleStore.DEFAULT_RADIUS_KM)
    val nearbyRadiusKm: StateFlow<Double> = _nearbyRadiusKm

    private val _minMag = MutableStateFlow(AlertRuleStore.DEFAULT_MIN_MAG)
    val minMag: StateFlow<Double> = _minMag

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
    // into Content below rather than captured once.
    //
    // Task 1 (Plan 3): [refreshOnce] (shared by the poll loop and [retryNow]) is now the primary
    // writer — it sets this true on EITHER a throw or a [RefreshStatus.FAILED] result, and false
    // on ANY successful outcome (UPDATED or NOT_MODIFIED alike; see its own kdoc). The
    // insertedQuakeIds collector below is kept as a SECOND, independent writer: it is the only
    // thing that clears this for a quake arriving off the LIVE WebSocket, which never goes
    // through [refreshOnce] at all. Picked over the alternative floated for the original fix
    // ("any recentQuakes emission that grows the quake count after the failure") as the simpler
    // and equally-correct one — a fresh insertedQuakeIds emission is direct, unambiguous proof
    // ingest() just wrote a genuinely new quake, no "previous count" bookkeeping required.
    private val refreshFailed = MutableStateFlow(false)

    // Task 2 (Plan 3) carry-in — the Task 1 entry-conditions debt this closes: "a slow-failed poll
    // landing after a live-clear must not re-raise the banner." [refreshOnce] captures this at call
    // time and only writes [refreshFailed] if it's still current when that (possibly slow) call's
    // result lands; the [insertedQuakeIds] collector below bumps it on every live-clear, so a
    // [refreshOnce] attempt that was already in flight when a genuinely new quake proved the feed
    // healthy again has its now-stale write silently discarded instead of stomping back over that
    // more-recent truth. Plain Main-confined `var` (like [retryJob] below, or the former
    // `selectJob` this class carried before Task 3 (Plan 3) moved it to QuakeSelectionViewModel)
    // — both the writer (refreshOnce, via the poll loop/retryNow, always viewModelScope-launched
    // with no dispatcher override) and the other writer (the insertedQuakeIds collector below,
    // same constraint) only ever run on Main, so there's no concurrent-mutation hazard to guard
    // against.
    private var refreshGeneration = 0L

    // Task 1 (Plan 3): bumped by every [refreshOnce] attempt (loop tick or [retryNow]) so the
    // quakes-list collector below re-subscribes to [QuakeRepository.recentQuakes] against a FRESH
    // cutoff each time, rather than the cutoff that function's own Flow froze once at whatever
    // moment it was first subscribed (see that function's own kdoc). The tick's numeric value
    // carries no meaning of its own — only its role as a change-notification for
    // [kotlinx.coroutines.flow.flatMapLatest] does.
    private val pollTick = MutableStateFlow(0L)

    // Task 1 (Plan 3): guards [retryNow] against a re-tap while its own [refreshOnce] call is
    // still in flight — see that function's own kdoc. Purely a private implementation detail of
    // that one function, same "cancel a still-in-flight Job before/instead of starting another"
    // pattern QuakeSelectionViewModel's own `selectJob` uses (Task 3, Plan 3 — split out of this
    // class, see that class's kdoc) for the exact same reason.
    private var retryJob: Job? = null

    init {
        // Fix Round 1 (I2): sweeps out any "debug-"-prefixed rows a previous debug-long-press
        // session left behind (see injectDebugQuake below) — unconditional, not debug-build-gated,
        // by controller decision: it's a single indexed DELETE ... WHERE id LIKE 'debug-%' that
        // matches zero rows on every device that has never used the hook (i.e. every real user,
        // every release build), so gating it would add a platform-specific debug-build check to a
        // ViewModel that is otherwise deliberately platform/build-type agnostic, to guard against a
        // cost that doesn't exist. Independent top-level launch — nothing else in this class
        // depends on it completing, and it must not block/delay the cache-driven or refresh loops
        // below.
        //
        // Task 2 (Plan 4), F1 retention ruling (plan-3-exit-conditions.md carried item): folded
        // into this SAME launch (not a third one) — pruneOldRows is the identical "startup
        // housekeeping, unconditional, nothing else waits on it" concern purgeDebugQuakes already
        // is, just a different retention rule (age-based, feed/live-origin-only, vs.
        // prefix-based). cutoff = clock() - RETENTION_WINDOW_MILLIS (30 days); see
        // QuakeDao.pruneOldRows/Quake.sq's own kdoc for the full 'archive'/'debug'-exemption
        // ruling. [clock] (not a direct Clock.System.now() call, unlike injectDebugQuake below) is
        // required here specifically because this runs unconditionally on EVERY construction — an
        // unguarded real-clock cutoff would judge every test fixture in this whole codebase's suite
        // (which all use tiny epoch-relative timeMillis, not real wall-clock timestamps) as
        // decades-old and silently prune it the instant any HomeViewModel is built; confirmed by
        // actually wiring it that way first and watching nearly every HomeViewModelTest case fail
        // (EVIDENCE INTEGRITY) before adding this seam.
        viewModelScope.launch {
            repository.purgeDebugQuakes()
            repository.pruneOldRows(clock() - RETENTION_WINDOW_MILLIS)
        }

        // The refresh loop. Fix Round 2 (review finding, still true here): this runs in a
        // SEPARATE coroutine from `repository.recentQuakes().collect { ... }` below — since
        // refreshFeed() suspends on the network, sharing one coroutine would delay the very first
        // read of the (possibly already-populated) local cache behind a network round-trip that
        // has nothing to do with it. A pre-seeded cache paints instantly, whether or not — and
        // however long before — this refresh resolves.
        //
        // Task 1 (Plan 3, the "one-shot fetch" debt this task pays down): what used to be a
        // single refreshFeed() call is now a poll loop — refreshOnce() again every
        // [POLL_INTERVAL_MILLIS] for as long as this ViewModel lives. repository.startLive() is
        // still called exactly once, right after the FIRST attempt settles (unchanged sequencing
        // from before this task) — calling it once at the top means a refresh throw further down
        // this same coroutine can never re-invoke it, and since [QuakeRepository.startLive] itself
        // launches its collector directly on the [viewModelScope] it's handed (a sibling of this
        // coroutine, not a child), the LIVE WebSocket path is already structurally independent of
        // whatever happens to the refresh loop afterward — a later throw inside [refreshOnce]
        // (caught by its own runCatching) can't reach it either way.
        viewModelScope.launch {
            refreshOnce()
            repository.startLive(viewModelScope)
            while (isActive) {
                delay(POLL_INTERVAL_MILLIS)
                refreshOnce()
            }
        }

        // Task 9: home location, resolved once at startup. Dispatchers.Default because
        // HomeLocationStore.get() is a synchronous DAO read (SQLDelight) and LocationProvider's
        // android actual reads a system service — neither belongs on Main. A stored point always
        // wins over asking the platform again; a freshly-resolved fix gets remembered as home so
        // this only ever asks the platform once (matches the brief's `get() ?: current()?.also
        // { set(it) }` — HomeLocationStore.set() is itself an ordinary synchronous DAO write, and
        // running it here, still on Dispatchers.Default, keeps it off Main too).
        //
        // Task 1 (Plan 5): this block now ALSO resolves [_startupCameraTarget] — folded into the
        // SAME launch rather than a separate one, since it needs the exact same [stored]/[fix]
        // values this block already computes (a second independent call to
        // [locationProvider.current] would be a redundant platform read for no benefit).
        // [permissionGranted] is resolved via [locationRequester] (the same reducer Settings'/
        // onboarding's own location-ask UI already reads —
        // [com.yugma.terrawatch.location.reduceLocationPermissionState]) rather than inferred from
        // [fix]'s nullity: [LocationProvider.current]'s own contract already returns null without
        // permission (see its own kdoc), which WOULD make the two collapse to an identical outcome
        // here today — but [startupCameraTarget]'s own contract wants an explicit, real signal, not
        // one silently borrowed from a different class's side effect that could drift later.
        //
        // BEHAVIOR NOTE: before this task, [locationProvider.current] was only ever called when
        // [stored] was null (the elvis operator's lazy right-hand side) — a device with an
        // already-known home never re-read the platform location at all. It's now called on EVERY
        // start (gated only on [permissionGranted], not on [stored]'s nullity), because comparing a
        // fresh fix against the stored reference point is the whole point of this task's feature
        // (e.g. the user travelled since their last session — home is still the old city, but the
        // map should open on where they actually are now). [LocationProvider.current]'s android
        // actual is a cheap, side-effect-free `LocationManager.getLastKnownLocation` cache read (no
        // active GPS request, no permission dialog — permission is already resolved by this point),
        // so one extra call per cold start is a deliberate, low-cost trade, not an oversight. The
        // `stored ?: fix?.also { homeLocationStore.set(it) }` write below still only ever fires
        // when [stored] is null, exactly as before — [fix] being eagerly resolved doesn't change
        // WHEN the store gets written, only when the platform gets asked.
        viewModelScope.launch(Dispatchers.Default) {
            val stored = homeLocationStore.get()
            val permissionGranted =
                reduceLocationPermissionState(locationRequester.currentCondition()) == LocationAskUiState.GRANTED
            val fix = if (permissionGranted) locationProvider.current() else null
            _homeLocation.value = stored ?: fix?.also { homeLocationStore.set(it) }
            _startupCameraTarget.value =
                startupCameraTarget(savedTarget = stored, fix = fix, permissionGranted = permissionGranted)
        }

        // Task 2 (Plan 3), "close the location loop": homeLocation used to be resolved exactly
        // once, above — a grant (MainActivity's permission callback) or a city pick
        // (LocationAskDialog/CityPickerDialog) landing at any later point in the session would
        // write HomeLocationStore but never reach this already-running ViewModel, leaving the ASK
        // pill frozen until the next process restart re-ran init{}. A separate, long-lived
        // collector (not folded into the one-shot block above, which only runs once) is what
        // actually closes that loop: every [HomeLocationStore.updates] emission — including the
        // one this SAME block's own `homeLocationStore.set(it)` call above may have just fired —
        // updates [_homeLocation] directly. Re-applying the startup value here too is harmless
        // (StateFlow conflates an equal consecutive value), so there's no need to coordinate
        // ordering between this collector's subscribe and the one-shot block's own write.
        viewModelScope.launch {
            homeLocationStore.updates.collect { point -> _homeLocation.value = point }
        }

        // Task 7 (Plan 3), USER REQUIREMENT: mirrors the homeLocationStore.updates collector just
        // above — AlertRuleStore.nearbyRadiusKm/minMag are themselves live Flows (see that class's
        // own kdoc for why, unlike HomeLocationStore's get()+updates split), so a plain collect{}
        // is all this needs: no separate one-shot initial read, since the store's own Flow already
        // emits its current value to a fresh subscriber (onStart{} in AlertRuleStore).
        viewModelScope.launch {
            alertRuleStore.nearbyRadiusKm.collect { radiusKm -> _nearbyRadiusKm.value = radiusKm }
        }
        viewModelScope.launch {
            alertRuleStore.minMag.collect { minMag -> _minMag.value = minMag }
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
                    // Task 2 (Plan 3): a live-clear invalidates any [refreshOnce] attempt already
                    // in flight — see [refreshGeneration]'s own kdoc. Bumped here (not just
                    // wherever [refreshOnce] itself runs) because this is the ONLY place a clear
                    // can originate from outside that function entirely (a live-WebSocket-sourced
                    // insert never goes through [refreshOnce] at all).
                    refreshGeneration++
                    _newSinceExpand.value += 1
                }
            }
            // Fix Round 2 (review finding): pin mapping and the lastFetchedAtMillis() read used to
            // run directly inside collect{}'s lambda — i.e. on Dispatchers.Main, once per
            // recentQuakes() emission. Both now happen inside this upstream .map{}, pushed off
            // Main via flowOn(Dispatchers.Default); collect{} below only assigns the already-built
            // result to _state.value.
            //
            // Task 1 (Plan 3): recentQuakes() itself still returns a single frozen-cutoff Flow
            // (see its own kdoc) — the sliding window lives here, in re-subscribing via
            // flatMapLatest every time [pollTick] changes. flatMapLatest (not flatMapMerge/Concat)
            // matters: it CANCELS the previous tick's Flow before starting the next one, so an
            // old, now-stale-cutoff subscription never keeps emitting alongside the fresh one.
            combine(
                pollTick.flatMapLatest { repository.recentQuakes() }
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

    /**
     * Task 1 (Plan 3): one refresh attempt — the body shared by the poll loop in [init] and
     * [retryNow], so a manual retry is exactly as honest as a scheduled tick rather than a
     * hand-rolled duplicate of this logic.
     *
     * [QuakeRepository.refreshFeed] can THROW (a DB error — see its own kdoc/the Plan 2 entry
     * conditions this task closes out) despite its return type being a plain status enum; the
     * network layer underneath it never throws by contrast (`UsgsApi.fetchFeed` always resolves
     * to a [RefreshStatus] value, converting network/HTTP failure into [RefreshStatus.FAILED]
     * itself). [runCatching] here is what makes a throw and a [RefreshStatus.FAILED] result look
     * identical from the poll loop's point of view — both just mark [refreshFailed] true — so
     * that same `while` loop in [init] can keep ticking forever no matter which kind of failure
     * hits it.
     *
     * ANY successful outcome clears [refreshFailed], not only [RefreshStatus.UPDATED]: a
     * [RefreshStatus.NOT_MODIFIED] poll is just as much proof the feed is reachable and healthy
     * as one that happened to find something new — the old code had no path that ever cleared a
     * previously-failed flag from a no-op-but-successful poll at all.
     *
     * Task 2 (Plan 3) carry-in — the Task 1 entry-conditions debt: [gen] snapshots
     * [refreshGeneration] at the moment THIS call starts; the write below only actually lands if
     * [refreshGeneration] is still exactly [gen] once [repository.refreshFeed] (which can suspend
     * for a while — it's a real network round trip) finally resolves. A live arrival while this
     * call was in flight bumps [refreshGeneration] (see the [repository.insertedQuakeIds] collector
     * in [init]), which is what makes a slow, now-stale FAILED (or thrown) result unable to stomp
     * back over a [refreshFailed] the user already watched clear — without this fence, whichever of
     * the two happened to write last always won, regardless of which one was actually more recent
     * in wall-clock terms.
     *
     * Plan 3 Task 2 review ruling (documented here per that review, no behavior change): the same
     * fence also means a [retryNow] tap issued WHILE an older poll tick is still in flight makes
     * that older tick's eventual result irrelevant the instant the retry starts — [gen] advances
     * again, so the poll's write is fenced out too, exactly like a live-clear fences out a stale
     * FAILED above. In other words, **the latest-STARTED attempt's result governs [refreshFailed]
     * — a user retry supersedes an in-flight poll's verdict**, not just the reverse. Raised as an
     * open question during that review and accepted as intended semantics, not a bug: it is
     * self-healing (the poll loop's own next tick will re-assert FAILED if the retry's optimism
     * was wrong) and consistent with the same "newest subscription wins" spirit
     * [pollTick]'s own `flatMapLatest` already applies elsewhere in this class.
     */
    private suspend fun refreshOnce() {
        val gen = ++refreshGeneration
        runCatching { repository.refreshFeed() }
            .fold(
                onSuccess = { status ->
                    if (gen == refreshGeneration) refreshFailed.value = (status == RefreshStatus.FAILED)
                },
                onFailure = { if (gen == refreshGeneration) refreshFailed.value = true },
            )
        pollTick.value += 1
    }

    /**
     * Task 1 (Plan 3): the staleness banner's "Retry" CTA (see HomeScreen's `StalenessBanner`) —
     * an immediate, user-triggered refresh attempt, independent of the poll loop's own
     * [POLL_INTERVAL_MILLIS] cadence.
     *
     * Coalesced via [retryJob]: a re-tap while the previous call's [refreshOnce] is still
     * suspended on the network is dropped rather than stacking a second concurrent
     * [QuakeRepository.refreshFeed] call — the user only ever wants ONE retry in flight, and an
     * ignored re-tap costs nothing since the first tap is already doing exactly what the second
     * one asked for. [Job.isActive] is checked (not e.g. a plain Boolean) so the guard clears
     * itself automatically the moment the in-flight call actually finishes, success or failure,
     * with no separate reset step to forget.
     */
    fun retryNow() {
        if (retryJob?.isActive == true) return
        retryJob = viewModelScope.launch { refreshOnce() }
    }

    /** Called by HomeScreen when the feed sheet reaches [androidx.compose.material3.SheetValue]
     * `.Expanded` — the user has now seen the list, so the "N NEW" chip resets. */
    fun markSheetExpanded() {
        _newSinceExpand.value = 0
    }

    /** Called by `QuakeMap` once it has actually applied [startupCameraTarget] to the camera —
     * clears the signal so a later recomposition/rotation can never re-apply the same cold-start
     * jump again (mirrors `MainActivity`'s own `pendingQuakeId`/`onQuakeIdConsumed` "consume once"
     * shape). */
    fun consumeStartupCameraTarget() {
        _startupCameraTarget.value = null
    }

    /** Called by `QuakeMap` once it has actually applied [recenterTarget] to the camera — same
     * one-shot "consume, don't replay" contract as [consumeStartupCameraTarget]. */
    fun consumeRecenterTarget() {
        _recenterTarget.value = null
    }

    /**
     * Task 1 (Plan 5), USER REQUIREMENT (dogfooding feedback item 2, "a button to recenter on
     * me"): the my-location FAB's tap action — a fresh, one-shot fix, independent of
     * [homeLocation]/[startupCameraTarget]: this never writes [HomeLocationStore] and never moves
     * the alert ring/pill's own reference point, only the camera — "where is the device right
     * now, for the map" is a strictly narrower question than "where is home."
     *
     * The FAB itself is only ever visible when permission is granted (`HomeScreen`'s own live gate,
     * via [com.yugma.terrawatch.location.rememberLocationCondition]), so a null result here in
     * practice means "permission granted, but nothing cached yet" (e.g. an emulator with no
     * location provider enabled) rather than a missing permission — either way, the brief's own
     * "brief snackbar" treatment applies identically.
     */
    fun recenterToCurrentLocation() {
        viewModelScope.launch(Dispatchers.Default) {
            val fix = locationProvider.current()
            if (fix != null) _recenterTarget.value = fix else _locationUnavailableEvents.emit(Unit)
        }
    }

    /**
     * Task 10 device-verification hook: manufactures a fake M6.0 "quake" at the given point.
     *
     * Fix Round 1 (I2, review finding): used to push this through the exact same
     * [QuakeRepository.ingest] real quakes take — but that runs the fake through [DedupeEngine]
     * (risking a merge into a real nearby quake's row, corrupting it) and [AlertRuleEngine] (a
     * debug tap must never fire a real, user-visible alert). Now calls
     * [QuakeRepository.ingestDebugBypassingDedupe] instead — same [insertedQuakeIds] signal (so
     * the pin-drop animation and the feed sheet's "N NEW" chip still fire honestly), no dedupe, no
     * alert evaluation. `refreshFailed` clearing is the one behavior this genuinely no longer
     * shares with a real arrival, since that's wired off the same `insertedQuakeIds` collector
     * regardless of which ingest path fired it — unchanged, still fires here too.
     *
     * This method itself carries no debug/release branch (HomeViewModel stays platform/build-type
     * agnostic, matching the rest of this class) — gating to debug builds only happens at the call
     * site, where QuakeMap's Android actual decides whether to attach the long-press gesture that
     * invokes this at all. The id keeps its "debug-" prefix (required — this class's [init] above
     * calls [QuakeRepository.purgeDebugQuakes] unconditionally, which keys off this exact prefix
     * via [QuakeDao.deleteByIdPrefix] to sweep these rows back out) plus a random suffix so two
     * presses landing in the same millisecond still both count as "new" rather than one silently
     * overwriting the other. `place` is prefixed "[DEBUG]" so a fake row is never mistaken for a
     * real quake if one ever surfaces somewhere this purge doesn't reach.
     */
    @OptIn(ExperimentalTime::class)
    fun injectDebugQuake(lat: Double, lon: Double) {
        viewModelScope.launch {
            val now = Clock.System.now().toEpochMilliseconds()
            val id = "debug-$now-${Random.nextInt(100_000)}"
            repository.ingestDebugBypassingDedupe(
                Quake(
                    id = id,
                    timeMillis = now,
                    lat = lat,
                    lon = lon,
                    depthKm = 10.0,
                    mag = 6.0,
                    magType = "mw",
                    place = "[DEBUG] Injected M6.0",
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
