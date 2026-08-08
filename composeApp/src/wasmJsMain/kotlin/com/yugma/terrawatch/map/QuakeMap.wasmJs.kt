package com.yugma.terrawatch.map

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color

// HOTFIX (Task 6 follow-up), signature grown in Task 8: still a temporary placeholder — Task 12
// replaces this with the real FallbackMapPane. The spike
// (docs/superpowers/plans/plan-2-spike-maplibre.md) found maplibre-compose publishes no wasmJs
// target at all (confirmed against Maven Central's artifact listing and the library's own build
// script) — depending on it from commonMain broke :composeApp:wasmJsBrowserDistribution outright.
// Android-only live map for now per the spike decision. [newQuakeId] and [onPinTap] are accepted
// (matching the expect signature) but unused — there is nothing tappable to wire them to on this
// placeholder, and wasmJs's App() doesn't even reach HomeScreen yet (web data layer is Plan 3;
// wasmJsMain/main.kt renders a separate WebPlaceholder() today).
// 0xFFD9E9F4 mirrors core:ui's TerraColors.Water, hardcoded rather than depending on core:ui here
// — this is a hotfix placeholder, not a feature; core:ui's magnitudeColor has nothing to color yet.
@Composable
actual fun QuakeMap(
    pins: List<QuakePin>,
    newQuakeId: String?,
    onPinTap: (String) -> Unit,
    modifier: Modifier,
) {
  Box(modifier.fillMaxSize().background(Color(0xFFD9E9F4))) {
    Text("${pins.size} quakes — map on Android", modifier = Modifier.align(Alignment.Center))
  }
}
