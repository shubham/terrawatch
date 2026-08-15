package com.yugma.terrawatch.map

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.yugma.terrawatch.model.GeoPoint

// Task 12: replaces the earlier bare Water-color/pin-count hotfix placeholder with the real
// FallbackMapPane — see that file's own kdoc for the maplibre-compose spike decision (no JDK 25+
// runtime available to this project's toolchain) this exists to work around. [newQuakeId] and
// [onDebugLongPress] are still accepted, matching the shared `expect` signature every actual must
// satisfy, but neither is forwarded into FallbackMapPane: there is no pin-drop animation off
// Android (per the spike decision, FallbackMapPane's own kdoc) and no gesture surface worth a
// debug-inject long-press on a pane with no camera to center on. [homeLocation]/[radiusKm] (Task 7,
// Plan 3) ARE forwarded — FallbackMapPane draws its own Canvas-projected ring approximation.
//
// Task 1 (Plan 5): [startupCameraTarget]/[onStartupCameraApplied]/[recenterTarget]/
// [onRecenterApplied] are accepted for expect/actual signature parity only, same shape as
// [newQuakeId]/[onDebugLongPress] above — FallbackMapPane has no `CameraState` of its own to move
// (a static equirectangular projection, not a live camera), so there is nothing here to apply or
// consume; the "applied" callbacks are simply never invoked on this actual.
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
