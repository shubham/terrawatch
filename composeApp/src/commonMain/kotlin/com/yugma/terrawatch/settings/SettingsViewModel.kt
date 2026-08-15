package com.yugma.terrawatch.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.yugma.terrawatch.data.AlertRuleStore
import com.yugma.terrawatch.data.HomeLocationStore
import com.yugma.terrawatch.data.ThemeSetting
import com.yugma.terrawatch.data.ThemeStore
import com.yugma.terrawatch.model.GeoPoint
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
 */
class SettingsViewModel(
    private val alertRuleStore: AlertRuleStore,
    private val themeStore: ThemeStore,
    private val homeLocationStore: HomeLocationStore,
) : ViewModel() {
    private val _nearbyRadiusKm = MutableStateFlow(AlertRuleStore.DEFAULT_RADIUS_KM)
    val nearbyRadiusKm: StateFlow<Double> = _nearbyRadiusKm

    private val _minMag = MutableStateFlow(AlertRuleStore.DEFAULT_MIN_MAG)
    val minMag: StateFlow<Double> = _minMag

    private val _theme = MutableStateFlow(ThemeSetting.SYSTEM)
    val theme: StateFlow<ThemeSetting> = _theme

    private val _homeLocation = MutableStateFlow<GeoPoint?>(null)
    val homeLocation: StateFlow<GeoPoint?> = _homeLocation

    init {
        viewModelScope.launch { alertRuleStore.nearbyRadiusKm.collect { _nearbyRadiusKm.value = it } }
        viewModelScope.launch { alertRuleStore.minMag.collect { _minMag.value = it } }
        viewModelScope.launch { themeStore.theme.collect { _theme.value = it } }
        viewModelScope.launch(Dispatchers.Default) {
            _homeLocation.value = homeLocationStore.get()
        }
        viewModelScope.launch {
            homeLocationStore.updates.collect { point -> _homeLocation.value = point }
        }
    }

    // Final review (F4): setNearbyRadius/setMinMag are called directly from the slider's
    // onValueChange (a rapid-fire Compose callback while the user drags), and AlertRuleStore's
    // setters are plain synchronous DAO writes (metaPut) — same "off Main" treatment this class's
    // own init block already gives homeLocationStore.get() above, for the same reason: a slider
    // drag must not block Main with a SQLite write per pixel of travel.
    fun setNearbyRadius(km: Double) {
        viewModelScope.launch(Dispatchers.Default) { alertRuleStore.setNearbyRadius(km) }
    }

    fun setMinMag(mag: Double) {
        viewModelScope.launch(Dispatchers.Default) { alertRuleStore.setMinMag(mag) }
    }

    fun setTheme(setting: ThemeSetting) = themeStore.setTheme(setting)
}
