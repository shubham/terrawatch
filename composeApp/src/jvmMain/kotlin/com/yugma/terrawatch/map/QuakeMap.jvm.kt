package com.yugma.terrawatch.map

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

// Task 12: replaces the earlier bare Water-color/pin-count hotfix placeholder with the real
// FallbackMapPane — see that file's own kdoc for the maplibre-compose spike decision (no JDK 25+
// runtime available to this project's toolchain) this exists to work around. [newQuakeId] and
// [onDebugLongPress] are still accepted, matching the shared `expect` signature every actual must
// satisfy, but neither is forwarded into FallbackMapPane: there is no pin-drop animation off
// Android (per the spike decision, FallbackMapPane's own kdoc) and no gesture surface worth a
// debug-inject long-press on a pane with no camera to center on.
@Composable
actual fun QuakeMap(
    pins: List<QuakePin>,
    newQuakeId: String?,
    onPinTap: (String) -> Unit,
    modifier: Modifier,
    onDebugLongPress: (lat: Double, lon: Double) -> Unit,
) {
  FallbackMapPane(pins = pins, onPinTap = onPinTap, modifier = modifier)
}
