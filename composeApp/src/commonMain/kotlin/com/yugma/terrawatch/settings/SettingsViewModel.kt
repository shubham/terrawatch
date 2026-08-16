package com.yugma.terrawatch.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
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
}
