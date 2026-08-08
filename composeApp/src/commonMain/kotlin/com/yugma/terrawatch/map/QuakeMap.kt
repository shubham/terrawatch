package com.yugma.terrawatch.map

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/**
 * Renders the quake map. Android-only real map for now — see
 * docs/superpowers/plans/plan-2-spike-maplibre.md for the full spike findings behind this split:
 * maplibre-compose publishes no wasmJs target at all, and its desktop artifact requires a JDK 25
 * runtime this project's Gradle toolchain doesn't provide (both discovered only after the
 * dependency's original commonMain placement broke `:composeApp:jvmTest` and
 * `:composeApp:wasmJsBrowserDistribution`, per Task 7's stash-bisect).
 *
 * `actual` implementations:
 * - `QuakeMap.android.kt` — the real maplibre-compose render.
 * - `QuakeMap.jvm.kt` / `QuakeMap.wasmJs.kt` — a static placeholder pane, until Task 12's real
 *   `FallbackMapPane` lands.
 *
 * This stays the ONE composable this codebase owns for the map; Task 8 extends its signature
 * (adds `pins`, `newQuakeId`, `onPinTap`) rather than reaching into maplibre-compose APIs from
 * screen code, so any future library API churn stays contained to the android actual.
 */
@Composable
expect fun QuakeMap(modifier: Modifier = Modifier)
