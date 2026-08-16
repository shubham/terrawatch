package com.yugma.terrawatch.map

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.yugma.terrawatch.model.GeoPoint

// Task 12: replaces the earlier bare Water-color/pin-count hotfix placeholder with the real
// FallbackMapPane — see that file's own kdoc for the maplibre-compose spike decision (the library
// publishes no wasmJs artifact at all) this exists to work around. [newQuakeId] and
// [onDebugLongPress] are still accepted, matching the shared `expect` signature every actual must
// satisfy, but neither is forwarded into FallbackMapPane: there is no pin-drop animation off
// Android (per the spike decision, FallbackMapPane's own kdoc) and no gesture surface worth a
// debug-inject long-press on a pane with no camera to center on. [homeLocation]/[radiusKm] (Task 7,
// Plan 3) ARE forwarded — FallbackMapPane draws its own Canvas-projected ring approximation. This
// actual itself still isn't reachable yet — wasmJsMain/main.kt renders WebPlaceholder(), not
// HomeScreen (web's data layer is Plan 3) — but it now matches jvm's real fallback instead of a
// bespoke placeholder, ready the moment web's App() does reach HomeScreen.
//
// Task 1 (Plan 5): [startupCameraTarget]/[onStartupCameraApplied]/[recenterTarget]/
// [onRecenterApplied] are accepted for expect/actual signature parity only — same "no camera to
// move" reasoning QuakeMap.jvm.kt's own identical addition documents.
@Composable
actual fun QuakeMap(
    pins: List<QuakePin>,
    newQuakeId: String?,
    onPinTap: (String) -> Unit,
    modifier: Modifier,
    onDebugLongPress: (lat: Double, lon: Double) -> Unit,
    homeLocation: GeoPoint?,
    radiusKm: Double,
    startupCameraTarget: GeoPoint?,
    onStartupCameraApplied: () -> Unit,
    recenterTarget: GeoPoint?,
    onRecenterApplied: () -> Unit,
) {
  FallbackMapPane(pins = pins, onPinTap = onPinTap, modifier = modifier, homeLocation = homeLocation, radiusKm = radiusKm)
}
