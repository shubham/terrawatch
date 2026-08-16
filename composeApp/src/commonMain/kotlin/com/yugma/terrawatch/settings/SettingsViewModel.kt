package com.yugma.terrawatch.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.yugma.terrawatch.alerts.AlertDigestScheduler
import com.yugma.terrawatch.data.AlertRuleStore
import com.yugma.terrawatch.data.FavoritePlaceStore
import com.yugma.terrawatch.data.HomeLocationStore
import com.yugma.terrawatch.data.ThemeSetting
import com.yugma.terrawatch.data.ThemeStore
import com.yugma.terrawatch.model.FavoriteAlertType
import com.yugma.terrawatch.model.FavoritePlace
import com.yugma.terrawatch.model.GeoPoint
import com.yugma.terrawatch.monetization.EntitlementsProvider
import com.yugma.terrawatch.monetization.canAddFavorite
import com.yugma.terrawatch.notifications.NotificationAlertsUiState
import com.yugma.terrawatch.notifications.NotificationPermissionCondition
import com.yugma.terrawatch.notifications.NotificationPermissionRequester
import com.yugma.terrawatch.notifications.reduceNotificationPermissionState
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * Task 7 (Plan 3): the Settings screen's ViewModel — a thin, live mirror of three stores
 * ([AlertRuleStore], [ThemeStore], [HomeLocationStore]) into [StateFlow]s [SettingsScreen] can
 * `collectAsState()` directly, plus the matching setters. No logic of its own beyond that mirroring
 * — every actual rule (defaults, corrupt-value fallback, round-tripping) lives in the stores
 * themselves and is already TDD'd there (AlertRuleStoreTest/ThemeStoreTest/HomeLocationTest); this
 * class's own tests only need to prove the WIRING (a store change reaches this VM's StateFlow, a
 * setter call reaches the store), same split HomeViewModelTest already draws for its own
 * `homeLocation`/`nearbyRadiusKm`/`minMag` StateFlows over the identical stores.
 *
 * [homeLocation] mirrors [HomeViewModel][com.yugma.terrawatch.home.HomeViewModel]'s own field
 * exactly: an initial one-shot [HomeLocationStore.get] off [Dispatchers.Default] (a synchronous DAO
 * read — see that store's own kdoc), plus a live [HomeLocationStore.updates] collector so a city
 * pick from THIS screen's own "Change" button (or a location grant landing while Settings happens
 * to be open) updates the saved-place row immediately, no navigation-away-and-back needed.
 *
 * Plan 4 Task 6: [isPlusActive] backs the new "TerraWatch Plus" row/status text — unlike
 * [nearbyRadiusKm]/[minMag]/[theme]/[homeLocation] above, it needs no separate `viewModelScope`
 * mirroring collector at all: [EntitlementsProvider.isPlusActive] is ALREADY a live `StateFlow`
 * (not a suspend-`get()`-plus-`Flow`-`updates` split the way the store classes are), so exposing it
 * directly is both simpler and more correct than reinventing that mirroring ceremony for a value
 * that's already exactly the right shape.
 *
 * Fix (post-Plan-5 tail, RESULTS.md round2 concern #6): [alertsUiState]/[alertsEnqueued]/
 * [refreshAlertsState] are a deliberate exception to this class's own "thin mirror, no logic of its
 * own" framing above — see [refreshAlertsState]'s own kdoc for the device-verified bug that forced
 * that. Notably, this is ALSO a deliberate exception to a DIFFERENT established convention:
 * `HomeScreen.kt`'s own kdoc documents this app's usual "resolve a platform requester directly at
 * the composable that needs LIVE permission state" rule (why `UseMyLocationAction`, a few files
 * over, still reads location permission via a bare `koinInject<LocationRequester>()` +
 * `rememberLocationCondition`, not through a ViewModel). That rule is about not duplicating a
 * one-shot ViewModel dependency for an unrelated live-tracking need; it was never a reason THIS
 * class couldn't own a fresh, dedicated seam when the actual bug needs one thing a bare composable
 * `remember` cannot give it — a place to OWN the "ensure the worker's enqueued" side effect
 * idempotently across recompositions AND be unit-tested in jvmTest with a fake that flips between
 * calls (see [readNotificationCondition]'s own kdoc for why a plain composable-level read couldn't
 * be given that same test coverage). Location's own read stays exactly as it was — this fix is
 * scoped to notifications only, not a signal to migrate every permission read onto this class.
 */
class SettingsViewModel(
    private val alertRuleStore: AlertRuleStore,
    private val themeStore: ThemeStore,
    private val homeLocationStore: HomeLocationStore,
    entitlementsProvider: EntitlementsProvider,
    // Task 2 (Plan 5): the Places section's own favorites list — same "constructor param, thin
    // mirroring in init{}" shape every other store dependency on this class already uses.
    private val favoritePlaceStore: FavoritePlaceStore,
    // Flake-hardening pass (2026-08-16): this class shares HomeViewModel's exact
    // unpinned-`Dispatchers.Default` shape (the init{} homeLocation one-shot load below, plus
    // setNearbyRadius/setMinMag/addFavorite/removeFavorite/setFavoriteAlertType) — same seam,
    // same reasoning, ported here for consistency once HomeViewModel's own [ioDispatcher]
    // (composeApp home/HomeViewModel.kt) closed the identical ~10-15% TestMainDispatcher race
    // there. Appended as a 6th, DEFAULTED param — `AppModule.kt`'s real Koin wiring
    // (`SettingsViewModel(get(), get(), get(), get(), get())`) constructs this class positionally
    // only up through [favoritePlaceStore], so it needs no change to keep compiling.
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.Default,
    // Fix (post-Plan-5 tail, RESULTS.md round2 concern #6): the ALERTS row's live-refresh trio —
    // see [alertsUiState]/[refreshAlertsState]'s own kdocs for the device-verified bug this
    // closes. Same "append a new, DEFAULTED param" shape [ioDispatcher] just established one line
    // up — `AppModule.kt` needs no change here either. Function types, deliberately NOT the
    // concrete [NotificationPermissionRequester]/[AlertDigestScheduler] expect/actual classes
    // themselves: neither class's jvm actual can be substituted with a controllable jvmTest fake
    // that "flips between calls" (`NotificationPermissionRequester.jvm.kt` hardcodes
    // [NotificationPermissionCondition.PRE_33]; `AlertDigestScheduler.jvm.kt` hardcodes
    // isEnqueued=false/triggerNow/ensureEnqueued=no-op — see each file's own kdoc), the same
    // fakeability gap [EntitlementsProvider] (already an interface) exists to close for
    // [isPlusActive] below (`SettingsViewModelTest`'s own `FakeEntitlementsProvider`). A function
    // type buys the identical seam with no new interface/actual/Koin registration needed — each
    // default just calls straight through to the real, Koin-registered class's own method; both
    // classes' real behavior lives in a module-level global (`controller`/`appContext`), not
    // per-instance state, so a freshly-constructed instance here behaves identically to Koin's own
    // singleton (see each expect class's own kdoc for that "explicit no-arg constructor, uniform
    // across every target" shape).
    private val readNotificationCondition: () -> NotificationPermissionCondition =
        { NotificationPermissionRequester().currentCondition() },
    private val isDigestEnqueued: suspend () -> Boolean = { AlertDigestScheduler().isEnqueued() },
    private val ensureDigestEnqueued: () -> Unit = { AlertDigestScheduler().ensureEnqueued() },
) : ViewModel() {
    private val _nearbyRadiusKm = MutableStateFlow(AlertRuleStore.DEFAULT_RADIUS_KM)
    val nearbyRadiusKm: StateFlow<Double> = _nearbyRadiusKm

    private val _minMag = MutableStateFlow(AlertRuleStore.DEFAULT_MIN_MAG)
    val minMag: StateFlow<Double> = _minMag

    private val _theme = MutableStateFlow(ThemeSetting.SYSTEM)
    val theme: StateFlow<ThemeSetting> = _theme

    private val _homeLocation = MutableStateFlow<GeoPoint?>(null)
    val homeLocation: StateFlow<GeoPoint?> = _homeLocation

    val isPlusActive: StateFlow<Boolean> = entitlementsProvider.isPlusActive

    // Task 2 (Plan 5): the Places section's favorites list — same "MutableStateFlow seeded empty,
    // mirrored live in init{}" shape [HomeViewModel.favorites] already establishes for the identical
    // store.
    private val _favorites = MutableStateFlow<List<FavoritePlace>>(emptyList())
    val favorites: StateFlow<List<FavoritePlace>> = _favorites

    // --- Fix (post-Plan-5 tail, RESULTS.md round2 concern #6): ALERTS row live refresh ------------

    /** Seeded from [readNotificationCondition] at construction (the exact same "one read right now"
     * shape [homeLocation]'s own one-shot init{} load takes, just synchronous rather than
     * dispatched — [NotificationPermissionRequester.currentCondition] is a cheap in-memory read,
     * not a DB hit, so nothing here needs to move off Main the way that store read does).
     * [refreshAlertsState] is what keeps this live after construction — see that method's own kdoc
     * for the device-verified gap it closes. */
    private val _alertsUiState = MutableStateFlow(reduceNotificationPermissionState(readNotificationCondition()))
    val alertsUiState: StateFlow<NotificationAlertsUiState> = _alertsUiState

    /** Whether `AlertDigestWorker`'s periodic schedule is ACTUALLY enqueued right now — same
     * "honest, not inferred from permission alone" posture `AlertsPermissionRow`'s own prior
     * `LaunchedEffect(condition) { enqueued = scheduler.isEnqueued() }` already had (see
     * `SettingsScreen.kt`'s own kdoc), just re-homed here so [refreshAlertsState] can drive it. */
    private val _alertsEnqueued = MutableStateFlow(false)
    val alertsEnqueued: StateFlow<Boolean> = _alertsEnqueued

    init {
        viewModelScope.launch { alertRuleStore.nearbyRadiusKm.collect { _nearbyRadiusKm.value = it } }
        viewModelScope.launch { alertRuleStore.minMag.collect { _minMag.value = it } }
        viewModelScope.launch { themeStore.theme.collect { _theme.value = it } }
        // Flake-hardening pass (2026-08-16): was a hard-coded Dispatchers.Default -- now the
        // pinnable [ioDispatcher] (defaults identically). See its own kdoc above.
        viewModelScope.launch(ioDispatcher) {
            _homeLocation.value = homeLocationStore.get()
        }
        viewModelScope.launch {
            homeLocationStore.updates.collect { point -> _homeLocation.value = point }
        }
        viewModelScope.launch {
            favoritePlaceStore.favorites.collect { places -> _favorites.value = places }
        }
        // Fix (post-Plan-5 tail): the same "off Main" treatment homeLocation's own one-shot load
        // gets two lines up — isDigestEnqueued (real android: a suspend WorkManager query, see
        // AlertDigestScheduler's own kdoc) has no business blocking Main either.
        viewModelScope.launch(ioDispatcher) {
            _alertsEnqueued.value = isDigestEnqueued()
        }
    }

    // Final review (F4): setNearbyRadius/setMinMag are called directly from the slider's
    // onValueChange (a rapid-fire Compose callback while the user drags), and AlertRuleStore's
    // setters are plain synchronous DAO writes (metaPut) — same "off Main" treatment this class's
    // own init block already gives homeLocationStore.get() above, for the same reason: a slider
    // drag must not block Main with a SQLite write per pixel of travel.
    fun setNearbyRadius(km: Double) {
        viewModelScope.launch(ioDispatcher) { alertRuleStore.setNearbyRadius(km) }
    }

    fun setMinMag(mag: Double) {
        viewModelScope.launch(ioDispatcher) { alertRuleStore.setMinMag(mag) }
    }

    fun setTheme(setting: ThemeSetting) = themeStore.setTheme(setting)

    // --- Task 2 (Plan 5): the Places section's favorites CRUD + the FIRST REAL Plus gate ---------

    /**
     * Whether "Add place" can open the city picker right now — [com.yugma.terrawatch.monetization.
     * canAddFavorite]'s pure decision, applied to THIS instant's own [favorites] count and
     * [isPlusActive] value. `SettingsScreen`'s own "Add place" row calls this synchronously at tap
     * time (both [favorites]/[isPlusActive] are [StateFlow]s, so `.value` is always current, no
     * suspension needed) to decide between opening [com.yugma.terrawatch.location.CityPickerDialog]
     * and routing to the paywall instead — see that screen's own kdoc for the gate-blocked path.
     */
    fun canAddFavorite(): Boolean = canAddFavorite(currentCount = _favorites.value.size, isPlus = isPlusActive.value)

    /** Same "off Main" treatment [setNearbyRadius]/[setMinMag] already give their own store writes
     * above, for the identical reason (a SQLite write triggered from a Compose click handler). */
    fun addFavorite(label: String, point: GeoPoint, alertType: FavoriteAlertType = FavoriteAlertType.ALL) {
        viewModelScope.launch(ioDispatcher) { favoritePlaceStore.add(label, point, alertType) }
    }

    fun removeFavorite(id: Long) {
        viewModelScope.launch(ioDispatcher) { favoritePlaceStore.remove(id) }
    }

    /** The per-row alert-type segmented control's write path. */
    fun setFavoriteAlertType(id: Long, alertType: FavoriteAlertType) {
        viewModelScope.launch(ioDispatcher) { favoritePlaceStore.setAlertType(id, alertType) }
    }

    // --- Fix (post-Plan-5 tail, RESULTS.md round2 concern #6): ALERTS row live refresh -------------

    /**
     * `SettingsScreen`'s own resume-lifecycle hook calls this (same `LifecycleEventObserver`/
     * `ON_RESUME` pattern [com.yugma.terrawatch.notifications.rememberNotificationCondition]/
     * [com.yugma.terrawatch.location.rememberLocationCondition] already establish for the identical
     * "did the user just come back from system Settings" moment — see `AlertsPermissionRow`'s own
     * kdoc for why THIS row's read moved here instead of staying a bare composable-local read like
     * those two: the actual device-verified bug needed a place to OWN a one-time side effect across
     * recompositions, not just re-read a value).
     *
     * Device-verified root cause (98bc1cd8, Android 14, fresh install — see this session's own
     * RESULTS.md): granting POST_NOTIFICATIONS through the EXTERNAL system Settings page (this
     * row's own "Open Settings" deep link, or a manual visit) and returning via recents — WITHOUT
     * an app restart — left this row's status text stuck on "Off" even though the permission
     * condition itself had genuinely become [NotificationPermissionCondition.GRANTED]. Proven via
     * the explainer/"Open Settings" affordance correctly disappearing on-device (i.e.
     * [reduceNotificationPermissionState]'s condition→[NotificationAlertsUiState] half already
     * worked — `rememberNotificationCondition`'s own `ON_RESUME` re-read is NOT broken) while the
     * status text stayed wrong: `alertsRowStatusText` requires BOTH `uiState == ENABLED` AND
     * `enqueued`, and this app's periodic digest worker is only ever scheduled from `MainActivity.
     * onCreate`'s cold-start `enqueueDigestWorkerIfPermitted` or the in-app permission dialog's own
     * `ActivityResultCallback` — NEITHER of which runs for a same-session, system-Settings-only
     * grant. [AlertDigestScheduler.isEnqueued] was answering `false` honestly; nothing had ever
     * called the function that would make it true.
     *
     * The fix: re-read the condition (same as the composable-level pattern already did) AND, new
     * here, call [ensureDigestEnqueued] whenever that re-read finds alerts newly [
     * NotificationAlertsUiState.ENABLED] — [AlertDigestScheduler.ensureEnqueued]'s own real android
     * implementation is idempotent (`ExistingPeriodicWorkPolicy.UPDATE`), so calling it on every
     * resume rather than only on the false→true transition costs nothing and needs no extra
     * "already ensured this session" flag.
     */
    fun refreshAlertsState() {
        viewModelScope.launch(ioDispatcher) {
            val newUiState = reduceNotificationPermissionState(readNotificationCondition())
            _alertsUiState.value = newUiState
            if (newUiState == NotificationAlertsUiState.ENABLED) ensureDigestEnqueued()
            _alertsEnqueued.value = isDigestEnqueued()
        }
    }
}
