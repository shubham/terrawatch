package com.yugma.terrawatch.map

import android.content.Context
import android.content.pm.ApplicationInfo
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.yugma.terrawatch.model.MagnitudeBand
import com.yugma.terrawatch.motion.LocalReducedMotion
import com.yugma.terrawatch.ui.theme.TerraColors
import com.yugma.terrawatch.ui.theme.magnitudeColor
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.maplibre.compose.camera.CameraPosition
import org.maplibre.compose.camera.rememberCameraState
import org.maplibre.compose.expressions.dsl.asString
import org.maplibre.compose.expressions.dsl.case
import org.maplibre.compose.expressions.dsl.const
import org.maplibre.compose.expressions.dsl.feature
import org.maplibre.compose.expressions.dsl.not
import org.maplibre.compose.expressions.dsl.switch
import org.maplibre.compose.layers.CircleLayer
import org.maplibre.compose.map.MaplibreMap
import org.maplibre.compose.sources.GeoJsonData
import org.maplibre.compose.sources.GeoJsonOptions
import org.maplibre.compose.sources.rememberGeoJsonSource
import org.maplibre.compose.style.BaseStyle
import org.maplibre.compose.util.ClickResult
import org.maplibre.spatialk.geojson.Feature
import org.maplibre.spatialk.geojson.FeatureCollection
import org.maplibre.spatialk.geojson.Point
import org.maplibre.spatialk.geojson.Position

// OpenFreeMap's "liberty" style — free, no API key, no attribution HTML to inject beyond what the
// style JSON already declares (OpenFreeMap + OpenMapTiles + OpenStreetMap contributors).
private const val OPENFREEMAP_LIBERTY_STYLE_URL = "https://tiles.openfreemap.org/styles/liberty"

// Roughly centers the populated world (equatorial Atlantic/Africa) rather than the (0,0) Gulf of
// Guinea null-island default, at a zoom that shows most continents at once.
private const val WORLD_CENTER_LATITUDE = 20.0
private const val WORLD_CENTER_LONGITUDE = 0.0
private const val WORLD_ZOOM = 1.5

private const val PIN_STROKE_WIDTH_DP = 1.5f

// Task 10: pin-drop pop + rings.
private const val RING_MAX_RADIUS_DP = 48f
private const val RING_DURATION_MS = 900
private const val RING_STAGGER_MS = 300L
private const val RING_START_ALPHA = 0.7f
// Generous slack (~0.75s at 60fps) for HomeViewModel's `pins` StateFlow to catch up to a
// just-fired `newQuakeId` — see the LaunchedEffect below for why the two aren't guaranteed to
// land in the same composition pass.
private const val NEW_PIN_LOOKUP_MAX_FRAMES = 45

// Task 10 clustering (deferred from Task 8 by controller decision) — see
// ClusteredLowModerateLayer's own kdoc for the validation trail and the "why merge LOW+MODERATE"
// reasoning. clusterMaxZoom=3 matches the original Task 8 plan sketch's "cluster at zoom<3.
private const val CLUSTER_RADIUS_PX = 50
private const val CLUSTER_MAX_ZOOM = 3
private const val CLUSTER_MIN_POINTS = 3
private const val CLUSTER_BUBBLE_RADIUS_DP = 14f

// STRONG/MAJOR/UNKNOWN keep Task 8's original always-all-bands per-band treatment (see
// QuakeBandCircleLayer's kdoc for why that shape matters); LOW/MODERATE move to
// ClusteredLowModerateLayer below. Iteration order preserved from the original
// MagnitudeBand.entries pass (STRONG, then MAJOR, then UNKNOWN) — see that kdoc for the
// draw-order rationale this keeps.
private val UNCLUSTERED_BANDS = listOf(MagnitudeBand.STRONG, MagnitudeBand.MAJOR, MagnitudeBand.UNKNOWN)

/**
 * Task 8: live magnitude-banded quake pins. maplibre-compose 0.14.0's runtime-layer API confirmed
 * directly against the library's own compiled classes (extracted from the resolved
 * `maplibre-compose-android`/`geojson-jvm` artifacts and inspected with `javap`, per the spike's
 * "VALIDATE before building" instruction — not re-derived from the spike doc's prose alone):
 * `rememberGeoJsonSource`/`GeoJsonData.Features`/`CircleLayer`'s exact parameter names (`source`,
 * `color`, `radius`, `strokeColor`, `strokeWidth`, `onClick`) and the click handler's
 * `ClickResult.Consume`/`Pass` return type were all read back from the real bytecode's constant
 * pool, not assumed from the spike's paraphrase.
 *
 * Deliberately NOT using per-feature paint expressions (`feature["band"]`, `match`/`step`) or
 * GeoJsonOptions clustering: the spike explicitly flagged both as verified-in-source-only, not
 * exercised end-to-end. Instead, [pins] are grouped by [MagnitudeBand] in plain Kotlin and each
 * band gets its own `GeoJsonSource` + `CircleLayer` pair with constant paint values — still fully
 * data-driven per quake (which band's source a pin lands in is exactly a function of its
 * magnitude), just without the extra, unverified expression-DSL surface.
 *
 * Clustering deferred to Task 10 by controller decision (2026-08-08): the spike's cluster pattern
 * was inferred, not verified, against the real library API, and today's live pin count (~250) is
 * bearable unclustered in the meantime — revisit once there's a verified `GeoJsonOptions` API to
 * build the 3-layer cluster/unclustered pattern on. (Fix Round 2: replaces a prior version of this
 * note that quoted the task brief as allowing the deferral "ONLY if ... <1h work" — no such
 * passage exists in the brief; see task-8-report.md's Fix Round 2 corrections section.)
 *
 * BUG FIX (post-Task-8-device-verify): pin updates must flow through `GeoJsonSource.setData` on a
 * source that stays `remember`-ed for the composable's whole lifetime — never through tearing down
 * and recreating a layer/source. The original version only called [QuakeBandCircleLayer] for bands
 * that *currently* had pins (`pins.groupBy{...}.toSortedMap{...}.forEach{...}`), with no `key()`.
 * That's fine while the set of non-empty bands only ever grows, but the moment a band transiently
 * empties out (e.g. its quakes age out of `recentQuakes()`'s 24h window) while a *different* band
 * still has pins, Compose's positional slot-matching (nothing here was keyed) can silently rebind
 * an existing composition slot — with its already-`remember`-ed `GeoJsonSource` — from one band to
 * another: e.g. MODERATE's slot starts getting fed STRONG's pins and a `CircleLayer` `id` that
 * changes from `"quake-pins-moderate"` to `"quake-pins-strong"` on what the underlying native layer
 * still thinks is the same layer. That's exactly the class of "recreate the style instead of
 * updating the source" bug that produced a silent blank render elsewhere in this screen (see
 * HomeScreen.kt's fix note) — never triggered in this file specifically as far as I could
 * reproduce, but a real latent risk once the live feed's population shifts over time, not just
 * grows. Fixed by always composing exactly [MagnitudeBand.entries]' 5 bands, every recomposition,
 * each wrapped in `key(band)` — the composition's shape and each band's identity are now invariant
 * regardless of which bands currently have pins; only the data fed into each band's persistent,
 * already-`remember`-ed `GeoJsonSource` ever changes.
 *
 * Task 10 additions (this file):
 * - **Pin-drop pop + rings**: on [newQuakeId] resolving to a real pin in [pins], the matching pin
 *   gets a spring scale-in pop and two expanding, fading rings — see [NewQuakePinOverlay] and
 *   [NewQuakeRingLayer]. Implementation choice (brief gave freedom here): rings/pop are rendered as
 *   real map-space `CircleLayer`s (radius/opacity driven by an `Animatable` read directly in the
 *   composable body, which drives a fresh layer paint update every animation frame) rather than a
 *   screen-space Compose `Canvas` overlay reprojected via `CameraState`'s screen-position API.
 *   `CameraProjection.screenLocationFromPosition`/`positionFromScreenLocation` DO exist (confirmed
 *   via `javap` against `CameraProjection.class`) — the map-space route was chosen anyway because
 *   it's immune to pan/zoom drift during the ~1.2s animation (a screen-space overlay would need to
 *   re-project every frame the camera moves, and MapLibre's embedded native GL view makes it
 *   unclear whether a Compose overlay would even receive the map's own pan gestures cleanly
 *   underneath it) and reuses the exact same `GeoJsonSource`/`CircleLayer` idiom already proven
 *   stable in this file rather than introducing a second rendering mechanism for one feature.
 * - **Clustering** (validated + implemented, see [ClusteredLowModerateLayer]).
 * - **Debug long-press inject hook** (device verification only, debug builds): see
 *   [isDebuggableBuild] and the `pointerInput` wiring below.
 */
@Composable
actual fun QuakeMap(
    pins: List<QuakePin>,
    newQuakeId: String?,
    onPinTap: (String) -> Unit,
    modifier: Modifier,
    onDebugLongPress: (lat: Double, lon: Double) -> Unit,
) {
  val cameraState =
      rememberCameraState(
          firstPosition =
              CameraPosition(
                  target = Position(longitude = WORLD_CENTER_LONGITUDE, latitude = WORLD_CENTER_LATITUDE),
                  zoom = WORLD_ZOOM,
              )
      )

  // Task 10: the signature pin-drop moment. `pinsState` lets the LaunchedEffect below always see
  // the LATEST [pins] without restarting every time [pins] itself changes reference — the effect
  // is keyed only on [newQuakeId] (HomeScreen already de-bounces/expires it to one live value per
  // arrival, 1.5s window — see HomeScreen.kt), which changes far less often than [pins] does.
  val pinsState = rememberUpdatedState(pins)
  val reducedMotion = LocalReducedMotion.current
  var activePin by remember { mutableStateOf<QuakePin?>(null) }
  val scale = remember { Animatable(0f) }
  val ring1Progress = remember { Animatable(0f) }
  val ring2Progress = remember { Animatable(0f) }

  LaunchedEffect(newQuakeId) {
    if (newQuakeId == null || reducedMotion) return@LaunchedEffect
    // HomeViewModel's newQuakeIds (SharedFlow) and its pins-bearing state (StateFlow) are two
    // independently-collected upstreams (see HomeViewModel.kt's init block) — nothing guarantees
    // the pin carrying this id has already landed in `pins` the moment this effect starts. Poll
    // across a few frames rather than assume same-frame arrival; gives up cleanly (no animation,
    // no crash) if the id never shows up at all (e.g. it aged out already).
    var pin: QuakePin? = null
    var attempts = 0
    while (pin == null && attempts < NEW_PIN_LOOKUP_MAX_FRAMES) {
      pin = pinsState.value.firstOrNull { it.id == newQuakeId }
      if (pin == null) {
        withFrameNanos {}
        attempts++
      }
    }
    val resolved = pin ?: return@LaunchedEffect

    activePin = resolved
    try {
      scale.snapTo(0f)
      ring1Progress.snapTo(0f)
      ring2Progress.snapTo(0f)
      coroutineScope {
        launch { scale.animateTo(1f, spring(dampingRatio = Spring.DampingRatioMediumBouncy)) }
        launch { ring1Progress.animateTo(1f, tween(RING_DURATION_MS)) }
        launch {
          delay(RING_STAGGER_MS)
          ring2Progress.animateTo(1f, tween(RING_DURATION_MS))
        }
      }
    } finally {
      // Guard: if a second quake's arrival already superseded us (a newer LaunchedEffect
      // instance, with its own activePin already set), don't clobber ITS state on our way out —
      // matters when this coroutine is cancelled mid-animation rather than completing normally.
      if (activePin?.id == resolved.id) activePin = null
    }
  }

  // Task 10 device-verification hook: long-press anywhere on the map, in a debuggable build only,
  // injects a fake quake at the current camera center (no screen-to-latlon projection needed for
  // that — "map center" IS `cameraState.position.target`).
  //
  // NOT `detectTapGestures(onLongPress = ...)` (the brief's literal suggestion) — device-verified
  // that it never fires here: `detectTapGestures`'s `awaitFirstDown()` defaults to
  // `requireUnconsumed = true` at `PointerEventPass.Main`, and MapLibre's embedded native map view
  // claims the down event for its own pan-gesture tracking before a `Main`-pass Compose detector
  // ever sees it as unconsumed (confirmed by adding temporary logging: the pointerInput block
  // started, but `onLongPress` never fired across multiple real, timed long-press attempts on
  // both a real device and the emulator — a touch-slop/consumption issue, not a duration one).
  // Hand-rolling the same detection at `PointerEventPass.Initial` — Compose's first, top-down pass
  // — observes the down event BEFORE it reaches MapLibre's view, without ever calling `consume()`,
  // so normal pan/pinch is completely unaffected (re-verified on device after this fix). Simpler
  // than the full drag-cancels-long-press logic `detectTapGestures` has (a `withTimeoutOrNull`
  // around `waitForUpOrCancellation` can't perfectly distinguish "timed out" from "cancelled" —
  // both land on `null`) — an acceptable simplification for a debug-only verification hook, not a
  // production gesture: worst case a rare cancellation also fires the inject, which is harmless.
  //
  // BUG FIX (device-verified): `pointerInput(onDebugLongPress)` used [onDebugLongPress] itself as
  // the restart key — but that lambda is a fresh instance every HomeScreen recomposition (it
  // closes over `viewModel`, which Compose can't prove stable), and the FIRST successful inject
  // itself triggers exactly such a recomposition (newSinceExpand/pins both change). That restarted
  // the gesture coroutine mid-press, and the restarted `awaitFirstDown` immediately saw the
  // already-down finger as a fresh press, arming a SECOND long-press timer before the real lift —
  // one physical long-press produced "2 NEW" instead of "1 NEW" on device. Fixed the standard
  // Compose way: key `pointerInput` on the stable `Unit` (never restarts) and read the callback
  // through `rememberUpdatedState` so it's always current without forcing a restart.
  val context = LocalContext.current
  val debuggableBuild = remember(context) { isDebuggableBuild(context) }
  val currentOnDebugLongPress = rememberUpdatedState(onDebugLongPress)
  val mapModifier = if (debuggableBuild) {
    modifier.pointerInput(Unit) {
      awaitEachGesture {
        awaitFirstDown(pass = PointerEventPass.Initial)
        val releasedOrCancelled = withTimeoutOrNull(viewConfiguration.longPressTimeoutMillis) {
          waitForUpOrCancellation(pass = PointerEventPass.Initial)
        }
        if (releasedOrCancelled == null) {
          val target = cameraState.position.target
          currentOnDebugLongPress.value(target.latitude, target.longitude)
        }
      }
    }
  } else {
    modifier
  }

  MaplibreMap(
      modifier = mapModifier,
      baseStyle = BaseStyle.Uri(OPENFREEMAP_LIBERTY_STYLE_URL),
      cameraState = cameraState,
  ) {
    // Bottom-most: the expanding rings, so they never sit visually on top of any pin (this one or
    // any other quake's, if one happens to be nearby).
    NewQuakeRingLayer(id = "quake-ring-1", pin = activePin, progress = ring1Progress)
    NewQuakeRingLayer(id = "quake-ring-2", pin = activePin, progress = ring2Progress)

    // The pin currently mid-pop is excluded from its normal band layer below and drawn ONLY via
    // [NewQuakePinOverlay] instead (top-most, animated) — otherwise the base layer would render it
    // at full resting size from the very first frame, defeating the pop-from-nothing effect.
    val excludeId = activePin?.id
    val visiblePins = if (excludeId != null) pins.filter { it.id != excludeId } else pins
    val pinsByBand = visiblePins.groupBy { it.band }

    ClusteredLowModerateLayer(
        pins = pinsByBand[MagnitudeBand.LOW].orEmpty() + pinsByBand[MagnitudeBand.MODERATE].orEmpty(),
        onPinTap = onPinTap,
    )
    // Fixed order preserved from Task 8 (see UNCLUSTERED_BANDS) so MAJOR/STRONG pins' CircleLayers
    // are added — and therefore drawn — after LOW/MODERATE's: more severe quakes stay visually on
    // top when pins overlap at low zoom. Always all 3, never conditional on which currently have
    // pins — see this file's kdoc BUG FIX note for why.
    for (band in UNCLUSTERED_BANDS) {
      key(band) {
        QuakeBandCircleLayer(band, pinsByBand[band].orEmpty(), onPinTap)
      }
    }

    // Top-most: the animating pin itself, so it's never covered by any static layer beneath it.
    NewQuakePinOverlay(pin = activePin, scale = scale)
  }
}

private fun isDebuggableBuild(context: Context): Boolean =
    (context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0

@Composable
private fun QuakeBandCircleLayer(band: MagnitudeBand, pins: List<QuakePin>, onPinTap: (String) -> Unit) {
  // Fix Round 2 (entangled minor): was recomputed unconditionally on every recomposition of this
  // composable, allocating a brand-new FeatureCollection/GeoJsonData.Features even when [pins]
  // hadn't changed reference-wise (e.g. a recomposition triggered by something unrelated further
  // up the tree). remember(pins) confines that allocation — and whatever rememberGeoJsonSource
  // does internally in response to a "new" GeoJsonData instance — to actual pin-data changes.
  val geoJsonData = remember(pins) { GeoJsonData.Features(pins.toFeatureCollection()) }
  val source = rememberGeoJsonSource(geoJsonData)
  CircleLayer(
      id = "quake-pins-${band.name.lowercase()}",
      source = source,
      color = const(magnitudeColor(band)),
      radius = const(pinRadiusDp(band).dp),
      strokeColor = const(Color.White),
      strokeWidth = const(PIN_STROKE_WIDTH_DP.dp),
      onClick = { features ->
        // Fix Round 2 (entangled minor): used to unconditionally return Consume even when no
        // feature id resolved (features empty, or a feature with a null/non-string id) — Pass
        // lets a click that hit nothing meaningful here fall through instead of being silently
        // swallowed. The Log.d that used to sit here (its one job — proving onPinTap(id) actually
        // fires — was already discharged and recorded on-device in Fix Round 1's report) is
        // removed rather than DEBUG-gated: this module has no BuildConfig (buildFeatures.buildConfig
        // is not enabled anywhere in this project), so gating it would mean adding a build feature
        // for one log line.
        val id = features.firstOrNull()?.id?.content
        if (id == null) {
          ClickResult.Pass
        } else {
          onPinTap(id)
          ClickResult.Consume
        }
      },
  )
}

/**
 * Task 10 clustering (deferred from Task 8 by controller decision; validated against the real
 * 0.14.0 bytecode the same way Task 8's own pin layer was — `javap` against the resolved
 * `maplibre-compose-android` artifact's `GeoJsonOptions.class`, not the spike doc's prose alone):
 * `cluster`/`clusterRadius`/`clusterMaxZoom`/`clusterMinPoints` are real, present fields (confirmed
 * directly in the class's constant pool) — the spike's flagged uncertainty was warranted caution,
 * not a real gap.
 *
 * Only LOW+MODERATE ever cluster (controller decision, Task 10 brief): they're the bands that
 * actually crowd at low zoom, and merging them into ONE clustered source — rather than clustering
 * each band's existing per-band source independently — is deliberate: clustering per band would
 * still leave a separate LOW-cluster-bubble and MODERATE-cluster-bubble sitting on top of each
 * other at the same spot, exactly the "fragments clusters" outcome the brief called out.
 * STRONG/MAJOR/UNKNOWN keep Task 8's original per-band treatment untouched ([QuakeBandCircleLayer]
 * above) — "alerts must never hide" (brief) means those never collapse into a bubble regardless of
 * how many land in one spot.
 *
 * Two layers share this one source — the standard MapLibre cluster pattern the spike flagged as
 * "verify once" (now verified against real bytecode rather than assumed, and against a REAL
 * COMPILE — see below):
 * - a cluster-bubble [CircleLayer], filtered to features carrying the synthetic `point_count`
 *   property MapLibre adds to cluster representative points (`feature.has("point_count")`, real:
 *   confirmed via `javap` against `Feature.class`'s `has` method, and now compiles clean);
 * - an unclustered-leaf [CircleLayer], filtered to the ABSENCE of that property
 *   (`!feature.has("point_count")` — `not` confirmed as a real operator on `Expression<Boolean
 *   Value>` via `javap` against `DecisionKt.class`'s `notOperator` method), colored/sized per-pin
 *   by the `band` GeoJSON property (added in [toFeatureCollection]) via [switch]/[case] — the one
 *   per-feature paint expression this file uses, likewise confirmed in bytecode AND by a real
 *   compile (note: `switch`'s cases are a `vararg`, not the `Array` its javap signature erases to —
 *   `switch(input, case(...), case(...), fallback = ...)`, no `arrayOf` wrapper). `onClick` is
 *   wired identically to [QuakeBandCircleLayer]'s so tapping an unclustered low/moderate pin still
 *   opens the detail sheet exactly as it did pre-clustering.
 *
 * Cluster COUNT LABEL: attempted via a `SymbolLayer` composable with `textField` bound to
 * `point_count.convertToString()` (`StringValue extends FormattedValue`, confirmed in
 * `expressions/value/values.kt`'s compiled interface hierarchy, so it should satisfy
 * `textField`'s `Expression<out FormattedValue>` parameter) — SKIPPED, per the brief's own
 * "<30min else plain circles" allowance: the real Kotlin compiler (not just javap) rejects the
 * call with `Cannot access 'class SymbolLayer : FeatureLayer': it is internal in file` — this
 * library's `layers` package apparently has an internal `SymbolLayer` class name-clashing with the
 * public `SymbolLayer(...)` composable function (the same pattern `CircleLayer` uses without
 * incident elsewhere in this file — the class backing that one evidently isn't similarly
 * restricted, or the resolver picks the function first when there's no clash). Plain, unlabeled
 * cluster bubbles ship instead; a count label is a real follow-up, not a dead end — this needs
 * either a workaround for the resolution clash or a look at whether a differently-shaped call
 * avoids triggering it, neither of which fits this task's time budget for a secondary feature.
 *
 * Cluster bubble radius is a fixed [CLUSTER_BUBBLE_RADIUS_DP] (not scaled by point_count) — a
 * deliberate v1 simplification, doubly justified now that there's no count label to make "how
 * many" legible any other way than glancing at relative bubble sizes, which a fixed radius doesn't
 * do — noted as a natural next improvement alongside the label itself.
 */
@Composable
private fun ClusteredLowModerateLayer(pins: List<QuakePin>, onPinTap: (String) -> Unit) {
  val geoJsonData = remember(pins) { GeoJsonData.Features(pins.toFeatureCollection()) }
  val source = rememberGeoJsonSource(
      geoJsonData,
      GeoJsonOptions(
          cluster = true,
          clusterRadius = CLUSTER_RADIUS_PX,
          clusterMaxZoom = CLUSTER_MAX_ZOOM,
          clusterMinPoints = CLUSTER_MIN_POINTS,
      ),
  )
  val isCluster = feature.has("point_count")
  val bandExpr = feature["band"].asString()

  CircleLayer(
      id = "quake-cluster-bubble",
      source = source,
      filter = isCluster,
      color = const(TerraColors.Ink.copy(alpha = 0.72f)),
      radius = const(CLUSTER_BUBBLE_RADIUS_DP.dp),
      strokeColor = const(Color.White),
      strokeWidth = const(PIN_STROKE_WIDTH_DP.dp),
  )
  CircleLayer(
      id = "quake-pins-low-moderate",
      source = source,
      filter = !isCluster,
      color = switch(
          bandExpr,
          case(MagnitudeBand.LOW.name, const(magnitudeColor(MagnitudeBand.LOW))),
          case(MagnitudeBand.MODERATE.name, const(magnitudeColor(MagnitudeBand.MODERATE))),
          fallback = const(magnitudeColor(MagnitudeBand.LOW)),
      ),
      radius = switch(
          bandExpr,
          case(MagnitudeBand.LOW.name, const(pinRadiusDp(MagnitudeBand.LOW).dp)),
          case(MagnitudeBand.MODERATE.name, const(pinRadiusDp(MagnitudeBand.MODERATE).dp)),
          fallback = const(pinRadiusDp(MagnitudeBand.LOW).dp),
      ),
      strokeColor = const(Color.White),
      strokeWidth = const(PIN_STROKE_WIDTH_DP.dp),
      onClick = { features ->
        val id = features.firstOrNull()?.id?.content
        if (id == null) {
          ClickResult.Pass
        } else {
          onPinTap(id)
          ClickResult.Consume
        }
      },
  )
}

/**
 * One of the two Task 10 "pulse ring" layers. [pin] is null whenever no pin-drop animation is
 * in-flight — in that case the underlying source is an empty FeatureCollection and the layer draws
 * nothing (always mounted, never conditionally added/removed — same "always compose, only data
 * changes" discipline as [QuakeBandCircleLayer]'s own fix note, applied here so this layer never
 * needs an unmount/remount cycle). [progress] (0f..1f) drives radius (0 -> [RING_MAX_RADIUS_DP])
 * and fading opacity ([RING_START_ALPHA] -> 0) directly off `Animatable.value`, which is itself
 * Compose-observable — reading it here means this composable (and therefore the layer's paint
 * properties) re-evaluates on every animation frame while [progress] is being animated.
 */
@Composable
private fun NewQuakeRingLayer(id: String, pin: QuakePin?, progress: Animatable<Float, AnimationVector1D>) {
  val geoJsonData = remember(pin?.id) { GeoJsonData.Features(listOfNotNull(pin).toFeatureCollection()) }
  val source = rememberGeoJsonSource(geoJsonData)
  CircleLayer(
      id = id,
      source = source,
      color = const(magnitudeColor(pin?.band ?: MagnitudeBand.UNKNOWN)),
      radius = const((RING_MAX_RADIUS_DP * progress.value).dp),
      opacity = const(RING_START_ALPHA * (1f - progress.value)),
  )
}

/**
 * The Task 10 pin-drop "pop" itself — a single-feature layer, always on top (see the call site in
 * [QuakeMap]), rendering the currently-animating pin at `pinRadiusDp(band) * scale.value` so it
 * grows from nothing (scale 0) through the spring's natural overshoot (~1.15x, an emergent
 * property of `Spring.DampingRatioMediumBouncy`'s underdamped physics, not a separate hardcoded
 * keyframe) and settles at its true resting size (scale 1) — at which point it's visually
 * indistinguishable from [QuakeBandCircleLayer]'s own rendering of the same pin, so the handoff
 * back to the normal band layer (once the animation completes and [QuakeMap] stops excluding this
 * pin's id from it) is seamless.
 */
@Composable
private fun NewQuakePinOverlay(pin: QuakePin?, scale: Animatable<Float, AnimationVector1D>) {
  val geoJsonData = remember(pin?.id) { GeoJsonData.Features(listOfNotNull(pin).toFeatureCollection()) }
  val source = rememberGeoJsonSource(geoJsonData)
  val band = pin?.band ?: MagnitudeBand.UNKNOWN
  CircleLayer(
      id = "quake-pin-overlay",
      source = source,
      color = const(magnitudeColor(band)),
      radius = const((pinRadiusDp(band) * scale.value).dp),
      strokeColor = const(Color.White),
      strokeWidth = const(PIN_STROKE_WIDTH_DP.dp),
  )
}

private fun List<QuakePin>.toFeatureCollection() =
    FeatureCollection(
        map { pin ->
          Feature(
              geometry = Point(Position(longitude = pin.lon, latitude = pin.lat)),
              // "band" (Task 10): read back by ClusteredLowModerateLayer's per-feature color/radius
              // expression once LOW+MODERATE pins share a single clustered source and can no longer
              // rely on "which source this feature lives in" to imply which band it's in.
              properties = JsonObject(mapOf("band" to JsonPrimitive(pin.band.name))),
              id = JsonPrimitive(pin.id),
          )
        }
    )

// pinRadiusDp mapping: controller decision (Fix Round 2 dispatch, 2026-08-08) — LOW=4,
// MODERATE=6, STRONG=8, MAJOR=10, UNKNOWN=3. Explicit per-band values now, not a formula:
// UNKNOWN is deliberately the SMALLEST radius of all five (smaller than LOW) so a
// missing-magnitude pin can never visually dominate a real one.
//
// This supersedes the original report's literal reading of the brief's interfaces-block formula
// ("8 + band.ordinal*4 dp-equivalent" -> LOW=8/MODERATE=12/STRONG=16/MAJOR=20/UNKNOWN=24), which
// put UNKNOWN largest of all five bands — flagged there as a likely oversight, now corrected here
// by explicit controller decision rather than by extending the formula's own ordinal logic
// (which has no way to produce a smallest-of-all-five UNKNOWN value).
//
// (Fix Round 2 also corrects a second, separate error from the original report: a "LOW 4dp ->
// MAJOR 10dp" formula was attributed there to "the brief's other mention" — no such passage
// exists in the brief either; see task-8-report.md's Fix Round 2 corrections section.)
private fun pinRadiusDp(band: MagnitudeBand): Float = when (band) {
  MagnitudeBand.LOW -> 4f
  MagnitudeBand.MODERATE -> 6f
  MagnitudeBand.STRONG -> 8f
  MagnitudeBand.MAJOR -> 10f
  MagnitudeBand.UNKNOWN -> 3f
}
