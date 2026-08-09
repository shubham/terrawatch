package com.yugma.terrawatch.map

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.yugma.terrawatch.model.GeoPoint
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
 * - `QuakeMap.jvm.kt` / `QuakeMap.wasmJs.kt` — Task 12: both now delegate straight to
 *   [FallbackMapPane] (see its own kdoc for the maplibre-compose JDK-25/no-wasmJs-artifact spike
 *   decision) — a real equirectangular pin render, not the earlier `pins.size`-count hotfix
 *   placeholder. Android stays the only *live*, tile-backed map in Plan 2 (and the only judged
 *   target, per the spike decision); jvm/wasmJs get real pins on a static backdrop instead of a
 *   live tile map.
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
 * @param homeLocation Task 7 (Plan 3), USER REQUIREMENT: when non-null, every actual draws a
 *   subtle home-radius ring ([radiusKm], Safe-green, ~25% alpha fill + 1.5dp stroke) centered here —
 *   android: a `circlePolygon`-traced GeoJSON polygon `FillLayer`/`LineLayer` pair (MapLibre's
 *   `CircleLayer` radius is a fixed on-screen pixel size, not a real-world distance, so it can't
 *   draw this directly — see QuakeMap.android.kt's own kdoc); jvm/wasmJs forward straight to
 *   `FallbackMapPane`'s own Canvas-projected approximation. Null (never shown) exactly matches
 *   `PillStatus.Kind.ASK_LOCATION`'s own "no reference point yet" state.
 * @param radiusKm the ring's radius — ignored when [homeLocation] is null. Defaults to
 *   [com.yugma.terrawatch.data.AlertRuleStore.DEFAULT_RADIUS_KM]'s own value (100.0) so a caller
 *   that doesn't yet thread a real store-fed value through still renders a geographically-honest
 *   ring rather than an arbitrary placeholder number — `HomeScreen`'s real call sites always pass
 *   `HomeViewModel.nearbyRadiusKm`'s live value instead of relying on this default.
 */
@Composable
expect fun QuakeMap(
    pins: List<QuakePin>,
    newQuakeId: String?,
    onPinTap: (String) -> Unit,
    modifier: Modifier = Modifier,
    onDebugLongPress: (lat: Double, lon: Double) -> Unit = { _, _ -> },
    homeLocation: GeoPoint? = null,
    radiusKm: Double = 100.0,
)
