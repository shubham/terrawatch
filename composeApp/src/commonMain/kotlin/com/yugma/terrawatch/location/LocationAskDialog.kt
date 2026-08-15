package com.yugma.terrawatch.location

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.yugma.terrawatch.data.HomeLocationStore
import com.yugma.terrawatch.model.GeoPoint
import kotlinx.coroutines.launch
import org.koin.compose.koinInject

/** One of [PRESET_CITIES]' entries — a name paired with the coordinates [CityPickerDialog] writes
 * into [HomeLocationStore] when tapped. */
data class PresetCity(val name: String, val point: GeoPoint)

/**
 * The ten manual-picker options [CityPickerDialog] offers — literal lat/lon per this task's brief,
 * spanning enough of the globe (India, East/Southeast Asia, Europe/Turkey, the Americas) that most
 * users land reasonably close to at least one without an exact match.
 *
 * Fix Round 1 (entangled minor): this kdoc used to cite a fixed "pill's own 500 km alert radius" as
 * why "reasonably close" was a meaningful bar — stale since Task 7 (Plan 3) made the radius
 * user-settable ([com.yugma.terrawatch.data.AlertRuleStore], Settings' 50/100/250/500/1000 km
 * slider, defaulting to 100 km, not a fixed 500). "Reasonably close" is still a real bar across most
 * of that range (100–1000 km), a much tighter one at the narrowest 50 km step — but this list's job
 * is a rough global starting point regardless of whatever radius is currently configured, not a
 * promise tied to any one of its values.
 */
val PRESET_CITIES: List<PresetCity> = listOf(
    PresetCity("Bengaluru", GeoPoint(12.9716, 77.5946)),
    PresetCity("Delhi", GeoPoint(28.6139, 77.2090)),
    PresetCity("Mumbai", GeoPoint(19.0760, 72.8777)),
    PresetCity("Tokyo", GeoPoint(35.6762, 139.6503)),
    PresetCity("Jakarta", GeoPoint(-6.2088, 106.8456)),
    PresetCity("Istanbul", GeoPoint(41.0082, 28.9784)),
    PresetCity("Los Angeles", GeoPoint(34.0522, -118.2437)),
    PresetCity("Mexico City", GeoPoint(19.4326, -99.1332)),
    PresetCity("Santiago", GeoPoint(-33.4489, -70.6693)),
    PresetCity("Athens", GeoPoint(37.9838, 23.7275)),
)

private val CITY_LIST_HEIGHT = 320.dp

/**
 * The status pill's [com.yugma.terrawatch.data.PillStatus.Kind.ASK_LOCATION] face, opened by a tap
 * (HomeScreen's `onPillClick`) — the design spec's "calm ask"
 * (docs/superpowers/specs/2026-08-08-terrawatch-design.md §4.4, Location denied/unset row): "two
 * equal buttons ... Choose city ... and Allow location." This task's own dispatch names the two
 * buttons "Use my location"/"Choose city" — followed verbatim here as the more specific, later
 * instruction.
 *
 * "Choose city" always renders in M3 [AlertDialog]'s `confirmButton` slot — the one parameter that
 * isn't nullable, so it's the one guaranteed to appear on every target, which is exactly what this
 * task's brief calls for on jvm/wasmJs ("ask dialog shows ONLY 'Choose city'"). "Use my location"
 * only renders when [canRequestLocation] is true (android only), in the `dismissButton` slot, which
 * M3 happily omits when null — this is a structural consequence of the M3 API, not a claim that
 * "Choose city" is somehow the more primary of the two "equal" actions the spec describes.
 *
 * Tapping "Choose city" swaps this composable's own body over to [CityPickerDialog] (a second,
 * stacked [AlertDialog]) rather than HomeScreen tracking a completely independent dialog — one
 * `showCityPicker` flag scoped to this composable is all either path needs, and picking a city (or
 * backing out of the picker) both close the WHOLE ask flow via the same [onDismiss].
 */
@Composable
fun LocationAskDialog(onDismiss: () -> Unit) {
    var showCityPicker by remember { mutableStateOf(false) }
    if (showCityPicker) {
        CityPickerDialog(onDismiss = onDismiss)
    } else {
        val locationRequester = koinInject<LocationRequester>()
        AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text("Where are you?") },
            text = {
                Text(
                    "TerraWatch checks your location against nearby earthquakes so the status " +
                        "pill can warn you when one's close. It stays on this device — never " +
                        "uploaded or shared.",
                )
            },
            confirmButton = {
                TextButton(onClick = { showCityPicker = true }) {
                    Text("Choose city")
                }
            },
            dismissButton = if (canRequestLocation()) {
                {
                    TextButton(onClick = { locationRequester.request(); onDismiss() }) {
                        Text("Use my location")
                    }
                }
            } else {
                null
            },
        )
    }
}

/**
 * The manual location picker — reused directly by [LocationAskDialog]'s "Choose city" button today,
 * and (Task 2, Plan 5) by Settings' "Add place" row for the favorites list, via [onCityPicked].
 * Public and self-contained — writes [HomeLocationStore] itself via Koin rather than threading a
 * callback back up for the DEFAULT (home-change) path — for exactly the reuse [PlaceRow][com.yugma.
 * terrawatch.settings.PlaceRow]'s own "Change" button already exercises.
 *
 * [com.yugma.terrawatch.home.HomeViewModel.homeLocation] picks up the default path's write via
 * [HomeLocationStore.updates] (this task's other half) — this composable's only job there is the
 * tap-to-write-and-dismiss itself, not telling anyone about it directly.
 *
 * Task 2 (Plan 5): [onCityPicked], when non-null, is called INSTEAD of the default
 * `store.set(...)` write — every existing call site (this file's own [LocationAskDialog], Settings'
 * "Change" home row) omits it and keeps its exact pre-existing behavior unchanged. Settings' "Add
 * place" row passes a non-null one that adds a NEW favorite instead of overwriting home. [onDismiss]
 * still fires either way — picking a place (a preset tap, or a resolved "Use my location" fix) always
 * closes this dialog, regardless of which path handled the pick.
 *
 * The "Use my location" row ([UseMyLocationRow]) only renders when [onCityPicked] is non-null AND
 * [canRequestLocation] is true (android only) — the default home-"Change" path already has its OWN
 * dedicated "Use my location" entry point (`SettingsScreen.kt`'s `UseMyLocationAction`, next to the
 * "Change" button itself), so adding a second one here for that same path would be a redundant,
 * confusing second affordance for the identical action; the favorites "Add place" path has no
 * existing equivalent, which is exactly the gap this row closes, per this task's own dispatch
 * ("'Add place' → CityPickerDialog reuse + 'Use my location' option").
 */
@Composable
fun CityPickerDialog(onDismiss: () -> Unit, onCityPicked: ((PresetCity) -> Unit)? = null) {
    val store = koinInject<HomeLocationStore>()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Choose a city") },
        text = {
            Column {
                if (onCityPicked != null && canRequestLocation()) {
                    UseMyLocationRow(
                        onPicked = { fix ->
                            onCityPicked(PresetCity(CURRENT_LOCATION_LABEL, fix))
                            onDismiss()
                        },
                        modifier = Modifier.padding(bottom = 8.dp),
                    )
                }
                LazyColumn(modifier = Modifier.height(CITY_LIST_HEIGHT)) {
                    items(PRESET_CITIES, key = { it.name }) { city ->
                        Text(
                            text = city.name,
                            style = MaterialTheme.typography.bodyLarge,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    if (onCityPicked != null) onCityPicked(city) else store.set(city.point)
                                    onDismiss()
                                }
                                .padding(vertical = 12.dp),
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}

/** [CityPickerDialog]'s synthesized label for a resolved "Use my location" fix — a fixed,
 * point-in-time coordinate snapshot, not a live-tracking favorite, so "Current location" (not "My
 * location", which would misleadingly suggest it stays up to date as the device moves) is the
 * honest description added to the favorites list. `internal` so a future device-verification pass
 * can pin it without a Compose runtime. */
internal const val CURRENT_LOCATION_LABEL = "Current location"

/**
 * Task 2 (Plan 5): the add-favorite reuse's own "Use my location" row — mirrors `SettingsScreen.kt`'s
 * `UseMyLocationAction` GRANTED/CAN_ASK/NEEDS_SETTINGS state machine (same [rememberLocationCondition]/
 * [reduceLocationPermissionState] pair, same rationale-then-ask shape for [LocationAskUiState.CAN_ASK])
 * rather than reusing that `private` composable directly — it writes [HomeLocationStore] unconditionally,
 * where this needs to hand a resolved [GeoPoint] to [onPicked] instead (this dialog's own add-favorite
 * caller, not home). GRANTED taps resolve a fresh [LocationProvider.current] fix — `null` (no fix
 * cached, e.g. an emulator with no location provider enabled) shows a brief inline "Location
 * unavailable" line rather than silently doing nothing.
 */
@Composable
private fun UseMyLocationRow(onPicked: (GeoPoint) -> Unit, modifier: Modifier = Modifier) {
    val locationRequester = koinInject<LocationRequester>()
    val locationProvider = koinInject<LocationProvider>()
    val condition = rememberLocationCondition(locationRequester)
    var showRationale by remember { mutableStateOf(false) }
    var unavailable by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    Column(modifier) {
        when (reduceLocationPermissionState(condition)) {
            LocationAskUiState.GRANTED -> {
                TextButton(
                    onClick = {
                        unavailable = false
                        scope.launch {
                            val fix = locationProvider.current()
                            if (fix != null) onPicked(fix) else unavailable = true
                        }
                    },
                ) { Text("Use my location") }
                if (unavailable) {
                    Text(
                        text = "Location unavailable",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            LocationAskUiState.CAN_ASK -> {
                if (showRationale) {
                    Text(
                        text = "TerraWatch only uses your location to compare it against nearby " +
                            "earthquakes — it never leaves this device.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    TextButton(onClick = { showRationale = false; locationRequester.request() }) {
                        Text("Continue")
                    }
                } else {
                    TextButton(
                        onClick = {
                            if (locationRequester.shouldShowRationale()) {
                                showRationale = true
                            } else {
                                locationRequester.request()
                            }
                        },
                    ) { Text("Use my location") }
                }
            }
            LocationAskUiState.NEEDS_SETTINGS -> {
                Text(
                    text = "Location is off — enable it in system Settings.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                TextButton(onClick = { locationRequester.openSettings() }) { Text("Open Settings") }
            }
        }
    }
}
