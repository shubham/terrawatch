package com.yugma.terrawatch.map

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color

// HOTFIX (Task 6 follow-up): temporary placeholder — Task 12 replaces this with the real
// FallbackMapPane. The spike (docs/superpowers/plans/plan-2-spike-maplibre.md) found
// maplibre-compose publishes no wasmJs target at all (confirmed against Maven Central's artifact
// listing and the library's own build script) — depending on it from commonMain broke
// :composeApp:wasmJsBrowserDistribution outright. Android-only live map for now per the spike
// decision.
// 0xFFD9E9F4 mirrors core:ui's TerraColors.Water, hardcoded rather than depending on core:ui here
// — composeApp doesn't depend on core:ui yet (Task 8 adds it), and this is a hotfix, not a feature.
@Composable
actual fun QuakeMap(modifier: Modifier) {
  Box(modifier.fillMaxSize().background(Color(0xFFD9E9F4))) {
    Text("Map — Android build", modifier = Modifier.align(Alignment.Center))
  }
}
