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
import com.yugma.terrawatch.model.GeoPoint
import com.yugma.terrawatch.model.MagnitudeBand
import com.yugma.terrawatch.model.circlePolygon
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
import org.maplibre.compose.layers.FillLayer
import org.maplibre.compose.layers.LineLayer
import org.maplibre.compose.map.MaplibreMap
import org.maplibre.compose.sources.GeoJsonData
import org.maplibre.compose.sources.GeoJsonOptions
import org.maplibre.compose.sources.rememberGeoJsonSource
import org.maplibre.compose.style.BaseStyle
import org.maplibre.compose.util.ClickResult
import org.maplibre.spatialk.geojson.Feature
import org.maplibre.spatialk.geojson.FeatureCollection
import org.maplibre.spatialk.geojson.Point
import org.maplibre.spatialk.geojson.Polygon
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

// Task 7 (Plan 3), USER REQUIREMENT: the home-radius ring — Safe green, ~25% fill opacity, 1.5dp
// outline, per the plan's own spec. 64 vertices matches circlePolygon's own default (smooth enough
// at any on-screen zoom a phone map realistically renders at; see Geo.kt's own kdoc).
private const val HOME_RADIUS_RING_POINTS = 64
private const val HOME_RADIUS_FILL_OPACITY = 0.25f
private const val HOME_RADIUS_STROKE_WIDTH_DP = 1.5f
private const val HOME_RADIUS_RING_SOURCE_ID = "home-radius-ring"

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
 *
 * Task 7 (Plan 3) addition, USER REQUIREMENT — the home-radius ring ([HomeRadiusRingLayer]):
 * MapLibre's `CircleLayer` `radius` paint property is a fixed ON-SCREEN PIXEL size (constant
 * regardless of zoom — confirmed against the style spec, not assumed), not a real-world meters/km
 * radius, so it cannot draw "everything within N km of home" directly (that's exactly why the
 * pin/ring-animation layers above all size themselves in `.dp`, a screen unit, and never claim to
 * represent a ground distance). A GeoJSON polygon traced in actual lat/lon space is the correct
 * primitive instead: [com.yugma.terrawatch.model.circlePolygon] (core:model, TDD'd standalone)
 * generates the ring's vertices via the haversine destination-point formula, and a `FillLayer` +
 * `LineLayer` pair (both confirmed real, public composables via the same `javap`-against-the-
 * resolved-artifact discipline this file's own kdoc already documents for `CircleLayer`/
 * `SymbolLayer` — `FillLayer`/`LineLayer` share `CircleLayer`'s exact "internal backing class +
 * public composable facade of the same name" shape) renders it, matching the pattern this file
 * already established for the pin-drop rings ([NewQuakeRingLayer]) and cluster bubbles
 * ([ClusteredLowModerateLayer]).
 *
 * Task 10 (Plan 3, item d — spec §4.4's Offline row, "Map desaturates"): investigated whether this
 * live map can desaturate itself while offline, time-boxed (~20 min) and validated against the
 * library's real compiled classes rather than assumed. `javap`/sources inspection of the resolved
 * `maplibre-compose-android-0.14.0.aar` confirms `org.maplibre.compose.layers.RasterLayer` is a
 * real, public composable with a real `saturation: Expression<FloatValue>` paint parameter (range
 * `[-1..1]`, alongside `brightnessMin`/`brightnessMax`/`contrast`/`hueRotate`) — but it paints a
 * `RasterSource`/`RasterDemSource`, and this map's basemap (`OPENFREEMAP_LIBERTY_STYLE_URL`,
 * OpenFreeMap's "liberty" style) is VECTOR-tiled with no raster source anywhere in this
 * composition, so `RasterLayer.saturation` has nothing here to attach to. A `classes.jar`-wide scan
 * for `colorFilter`/`colorMatrix`/`grayscale`/`desaturate`-shaped symbols across the whole artifact
 * returned nothing — there is no style-wide/vector desaturation hook in this library's public API.
 * A Compose-level `graphicsLayer`/color-matrix wrapper around the whole [MaplibreMap] composable
 * was also considered as a maplibre-independent alternative, but this file's own
 * `AndroidMapView`/`RenderOptions` sources confirm the map is backed by a Surface/Texture/GL-
 * rendered native view — exactly the class of Android content a Compose draw-phase color filter is
 * known not to reliably reach (a SurfaceView/GL surface composites outside the normal View
 * `Canvas` pipeline that trick relies on) — and with neither the real device nor this
 * environment's emulator able to render this style correctly at investigation time (the
 * emulator's own documented "map renders black" Zscaler issue — see this plan's own progress
 * ledger), there was no way to visually confirm such a wrapper would actually desaturate the tiles
 * rather than silently do nothing. Per the brief's own allowance ("implement if clean, else SKIP
 * with honest note"): **SKIPPED for Android** — no `offline`/desaturation parameter was added to
 * this composable at all (unlike the jvm/wasmJs fallback pane, a plain `Canvas` draw with no such
 * rendering-surface constraint — desaturation there is out of THIS dispatch's scope, deferred
 * platform). The offline banner (`HomeScreen`'s `StalenessBanner`, gated on the same
 * `shouldShowStalenessBanner(...)` verdict) already communicates the same "you're offline"
 * information textually on every layout that hosts this map, so no alert-relevant information is
 * lost — only this map's own visual treatment stays unchanged while offline.
 */
@Composable
actual fun QuakeMap(
    pins: List<QuakePin>,
    newQuakeId: String?,
    onPinTap: (String) -> Unit,
    modifier: Modifier,
    onDebugLongPress: (lat: Double, lon: Double) -> Unit,
    homeLocation: GeoPoint?,
    radiusKm: Double,
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
  // arrival, 2.5s window as of Fix Round 1 — see HomeScreen.kt), which changes far less often than
  // [pins] does.
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
  // so normal pan/pinch is completely unaffected (re-verified on device after this fix).
  //
  // FIX ROUND 1 (Critical 1, device-verified regression): the original version of this hand-rolled
  // detector still wrapped `waitForUpOrCancellation(pass = Initial)` in a
  // `withTimeoutOrNull(longPressTimeoutMillis) { ... }` and treated a `null` result as "long
  // press fired." That is wrong: `waitForUpOrCancellation` has no touch-slop check of its own
  // (verified against the foundation 1.7.8 sources) and returns `null` on a plain gesture
  // cancellation too — which is exactly what an ordinary pan/pinch produces once MapLibre's
  // embedded view starts consuming the drag. `withTimeoutOrNull` can't tell "the wrapped call
  // returned null because it was cancelled, well before the timeout" apart from "the timeout
  // itself fired" — both surface as the same outer `null`. Net effect: any pan or pinch gesture
  // lasting longer than the system long-press duration (~500ms) silently injected a fake quake.
  // The previous kdoc here called this "an acceptable simplification... worst case a rare
  // cancellation also fires the inject, which is harmless" — that was wrong on both counts: it is
  // not rare (any unhurried pan/pinch triggers it) and not harmless (it pollutes real data with a
  // fake quake and animates a pin the user never asked for).
  //
  // Rewritten to track the gesture by hand instead of trusting `waitForUpOrCancellation`'s own
  // ambiguous null: capture the down position, then loop reading raw pointer events at the same
  // `Initial` pass. The loop returns (a non-null `Unit`, via the bare `return@withTimeoutOrNull`)
  // on every disqualifying condition — the tracked pointer lifting, or moving past
  // `viewConfiguration.touchSlop` — and otherwise just keeps waiting. Because the loop never
  // returns on its own, the ONLY way `withTimeoutOrNull` comes back `null` is for the timeout
  // itself to elapse while the loop is still running unaborted: finger still down, still within
  // slop, for the full long-press duration. That is a real, stationary long press.
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
        val down = awaitFirstDown(pass = PointerEventPass.Initial)
        val downPosition = down.position
        val pointerId = down.id
        val abortedEarly = withTimeoutOrNull(viewConfiguration.longPressTimeoutMillis) {
          while (true) {
            val event = awaitPointerEvent(pass = PointerEventPass.Initial)
            val change = event.changes.firstOrNull { it.id == pointerId }
                ?: return@withTimeoutOrNull // pointer vanished from the stream entirely
            if (!change.pressed) return@withTimeoutOrNull // lifted before the timeout elapsed
            val movedPastSlop =
                (change.position - downPosition).getDistance() > viewConfiguration.touchSlop
            if (movedPastSlop) return@withTimeoutOrNull // panning/pinching, not holding still
          }
        }
        if (abortedEarly == null) {
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
    // Bottom-most of all: the home-radius ring, so it never sits on top of a pin or the pin-drop
    // animation rings below. Always composed (never conditional on homeLocation != null) — see
    // HomeRadiusRingLayer's own kdoc for why: the exact same "always mount, only the underlying
    // source's data changes" discipline this file's BUG FIX note (top of file) already established
    // for the band layers, applied here too.
    HomeRadiusRingLayer(homeLocation = homeLocation, radiusKm = radiusKm)

    // The expanding rings, so they never sit visually on top of any pin (this one or
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

/**
 * Task 7 (Plan 3), USER REQUIREMENT: the home-radius ring — see [QuakeMap]'s own kdoc for why this
 * needs a real GeoJSON polygon (traced via [circlePolygon]) rather than `CircleLayer`'s fixed-pixel
 * `radius`.
 *
 * [homeLocation] null (no reference point yet — [com.yugma.terrawatch.data.PillStatus.Kind.ASK_LOCATION])
 * degrades to an EMPTY `FeatureCollection`, drawing nothing — same "always mounted, data-only
 * change" shape [NewQuakeRingLayer]'s own kdoc documents for its `pin == null` case, deliberately
 * NOT a conditional `if (homeLocation != null) { FillLayer(...); LineLayer(...) }` call at the
 * composition level: this file's own top-of-file BUG FIX note is exactly the lesson that
 * conditionally adding/removing a maplibre-compose layer/source — rather than always composing it
 * and only ever changing the DATA fed into an already-`remember`-ed source — risks silently
 * recreating the underlying native style and blanking the map.
 *
 * `remember(homeLocation, radiusKm)` (both plain, `equals`-comparable values — `GeoPoint` is a data
 * class) is what makes the ring "update live on radius/home change" (this task's own brief): a
 * Settings slider drag reaching `HomeViewModel.nearbyRadiusKm` and flowing into this composable's
 * `radiusKm` parameter invalidates this `remember` block, which calls `GeoJsonSource`'s own
 * `setData` (via `rememberGeoJsonSource`, the same incremental-update path every other source in
 * this file already relies on) with a freshly-traced, larger/smaller ring — no source/layer
 * teardown, so no blank-map risk.
 *
 * Fill opacity (not the fill color's own alpha channel) is what carries [HOME_RADIUS_FILL_OPACITY]
 * — the idiomatic MapLibre/Mapbox style-spec mechanism for "this shape is N% opaque" as a paint
 * property independent of the color itself, matching how `fill-opacity`/`line-opacity` are
 * conventionally used across real map styles (and avoiding compounding two separate alpha values
 * into one, which stacking a `color.copy(alpha=...)` AND `opacity` together would do).
 *
 * Parameter names below (`color`/`opacity`/`width`, NOT `fillColor`/`fillOpacity`/`lineWidth`) were
 * confirmed against the real compiled artifact, not guessed — `javap -v` on the resolved
 * `maplibre-compose-android-0.14.0.aar`'s `FillLayerKt`/`LineLayerKt` classes decodes each
 * composable's embedded Compose-compiler `sourceInformation` string (`C(FillLayer)N(id,source,
 * ...,opacity,color,...)` / `C(LineLayer)N(id,source,...,color,...,width,...)`), which lists every
 * parameter's REAL declared name in order — the same technique that, applied to `CircleLayerKt`,
 * reproduces this file's own already-working `CircleLayer(id, source, color, radius, strokeColor,
 * strokeWidth, onClick)` call verbatim, cross-validating the method. This is very likely the exact
 * "different, separately-invalid argument" this file's own kdoc left unresolved after the original
 * `SymbolLayer(textField = ...)` cluster-label attempt failed with this identical "it is internal"
 * error (see [ClusteredLowModerateLayer]'s kdoc) — a guessed `fieldPrefix`-style parameter name that
 * doesn't exist makes Kotlin's overload resolution discard the real composable function candidate
 * entirely and fall through to the (inaccessible) backing class constructor instead, which is
 * exactly the misleading error shape both attempts hit.
 *
 * **KNOWN v1 LIMITATION (Fix Round 1, I2, controller-approved doc-only fix — not corrected here):**
 * a home within `radiusKm` of the ±180° antimeridian (real seismic zones sit exactly here —
 * Kamchatka, the Aleutians, Fiji/Tonga) renders a CORRUPTED ring: [circlePolygon] normalizes each
 * vertex's own longitude independently, but never splits the ring itself where it crosses ±180°, so
 * this composable's GeoJSON `Polygon` ends up with consecutive vertices that jump straight across
 * the map (e.g. `+179.6` to `-179.6`) instead of taking the short real-world hop across the date
 * line — the rendered shape looks like it spans the whole world instead of a small local loop.
 * **Alert correctness is unaffected**: the pill's CALM/ALERT verdict is computed via
 * [com.yugma.terrawatch.model.haversineKm] directly against the raw center/radius, never by testing
 * membership in this rendered polygon, so a miscolored ring never implies a wrong verdict. See
 * [circlePolygon]'s own kdoc for the full explanation and the correct fix (a `MultiPolygon` split,
 * deferred to the Plan 4 backlog) — repeated here only so anyone reading this composable's own
 * source doesn't have to go looking for the caveat in a different module.
 */
@Composable
private fun HomeRadiusRingLayer(homeLocation: GeoPoint?, radiusKm: Double) {
  val geoJsonData = remember(homeLocation, radiusKm) {
    val feature = homeLocation?.let { center ->
      val ring = circlePolygon(center, radiusKm, points = HOME_RADIUS_RING_POINTS)
          .map { point -> Position(longitude = point.lon, latitude = point.lat) }
      Feature(
          geometry = Polygon(listOf(ring)),
          properties = JsonObject(emptyMap()),
          id = JsonPrimitive(HOME_RADIUS_RING_SOURCE_ID),
      )
    }
    GeoJsonData.Features(FeatureCollection(listOfNotNull(feature)))
  }
  val source = rememberGeoJsonSource(geoJsonData)
  FillLayer(
      id = "$HOME_RADIUS_RING_SOURCE_ID-fill",
      source = source,
      color = const(TerraColors.Safe),
      opacity = const(HOME_RADIUS_FILL_OPACITY),
  )
  LineLayer(
      id = "$HOME_RADIUS_RING_SOURCE_ID-outline",
      source = source,
      color = const(TerraColors.Safe),
      width = const(HOME_RADIUS_STROKE_WIDTH_DP.dp),
  )
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
 * "<30min else plain circles" allowance: the original attempt hit a real Kotlin-compiler error,
 * `Cannot access 'class SymbolLayer : FeatureLayer': it is internal in file`. Plain, unlabeled
 * cluster bubbles ship instead.
 *
 * FIX ROUND 1 CORRECTION (I3): the paragraph that used to sit here claimed `CircleLayer`'s backing
 * class is NOT internal (unlike `SymbolLayer`'s), as the explanation for that compiler error — an
 * inference, stated as settled fact without being checked. It was checked during this fix round
 * and is FALSE: `CircleLayer` and `SymbolLayer` share the identical shape (an internal backing
 * class plus a public composable facade function of the same name), and the exact call this kdoc
 * quotes above — `SymbolLayer(textField = feature["point_count"].convertToString())` — actually
 * COMPILES cleanly in isolation. So the real cause of the original failure is NOT a
 * SymbolLayer-vs-CircleLayer asymmetry; it remains unknown, but is most likely a different,
 * separately-invalid argument elsewhere in the original attempted call, which made Kotlin's
 * overload resolution fall through to the inaccessible internal constructor instead of the public
 * composable function (this exact misleading "it is internal" error shape is a known symptom of
 * that failure mode, not proof the symbol is categorically unreachable). Labels stay skipped for
 * this fix round — re-deriving the original call's precise failing shape is itself real follow-up
 * work — but the next attempt should start from the confirmed-compiling shape above rather than
 * assuming `SymbolLayer` can't be called here. Full correction record: task-10-report.md, "Fix
 * Round 1".
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
