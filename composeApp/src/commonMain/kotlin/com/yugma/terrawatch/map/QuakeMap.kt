package com.yugma.terrawatch.map

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.yugma.terrawatch.model.MagnitudeBand

/**
 * One quake, reduced to exactly what the map needs to draw a pin for it. Built by
 * `HomeViewModel` from a `Quake` + its `magnitudeBand(mag)`.
 *
 * [isNew] is reserved for Task 10's pin-drop animation and is always `false` as of Task 8 —
 * `QuakeMap`'s separate `newQuakeId` parameter (below), not this per-pin flag, is what Task 10
 * will actually key the animation off of.
 */
data class QuakePin(
    val id: String,
    val lat: Double,
    val lon: Double,
    val mag: Double?,
    val band: MagnitudeBand,
    val isNew: Boolean,
)

/**
 * Renders the quake map with live, magnitude-banded pins — see
 * docs/superpowers/plans/plan-2-spike-maplibre.md for the full spike findings behind the
 * per-target split: maplibre-compose publishes no wasmJs target at all, and its desktop artifact
 * requires a JDK 25 runtime this project's Gradle toolchain doesn't provide (both discovered only
 * after the dependency's original commonMain placement broke `:composeApp:jvmTest` and
 * `:composeApp:wasmJsBrowserDistribution`, per Task 7's stash-bisect).
 *
 * `actual` implementations:
 * - `QuakeMap.android.kt` — the real maplibre-compose render: one `GeoJsonSource` + `CircleLayer`
 *   pair per [MagnitudeBand] present in [pins], radius/color/stroke driven by the band, tap
 *   hit-testing via the layer's own `onClick`.
 * - `QuakeMap.jvm.kt` / `QuakeMap.wasmJs.kt` — a static placeholder pane (Task 6 decision: no live
 *   map off-Android in Plan 2) with a `pins.size` count overlay, until Task 12's real
 *   `FallbackMapPane` lands.
 *
 * @param pins every quake currently in view, already reduced to pin-drawing essentials.
 * @param newQuakeId the most recently-inserted quake's id, if any — accepted by every actual as of
 *   Task 8 but not yet used to animate anything (Task 10).
 * @param onPinTap invoked with a tapped pin's [QuakePin.id] (Task 9 opens the detail sheet from
 *   this; Task 8 proved the callback fires — a real device tap resolved a real quake id,
 *   cross-checked against the device's own DB, see task-8-report.md's Fix Round 1 — via a logcat
 *   marker on the Android actual that Fix Round 2 then removed as no-longer-needed debug noise).
 * @param onDebugLongPress Task 10 device-verification hook: invoked with a lat/lon when the user
 *   long-presses the map. Real WS quake arrivals are rare on demand, so this is how the pin-drop
 *   animation gets exercised deliberately on a real device — wired by HomeScreen to
 *   `HomeViewModel.injectDebugQuake`. The Android actual only ever attaches the long-press gesture
 *   that invokes this in a debuggable build (checked via `ApplicationInfo.FLAG_DEBUGGABLE` at the
 *   call site, not a `BuildConfig` field — this module still has none, see QuakeMap.android.kt's
 *   Task 8 fix note); jvm/wasmJs accept the parameter to keep the expect/actual signature aligned
 *   but their placeholder panes have nothing to attach a gesture to.
 */
@Composable
expect fun QuakeMap(
    pins: List<QuakePin>,
    newQuakeId: String?,
    onPinTap: (String) -> Unit,
    modifier: Modifier = Modifier,
    onDebugLongPress: (lat: Double, lon: Double) -> Unit = { _, _ -> },
)
