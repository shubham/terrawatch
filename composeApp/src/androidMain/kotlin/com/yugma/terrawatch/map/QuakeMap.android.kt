package com.yugma.terrawatch.map

import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.yugma.terrawatch.model.MagnitudeBand
import com.yugma.terrawatch.ui.theme.magnitudeColor
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.maplibre.compose.camera.CameraPosition
import org.maplibre.compose.camera.rememberCameraState
import org.maplibre.compose.expressions.dsl.const
import org.maplibre.compose.layers.CircleLayer
import org.maplibre.compose.map.MaplibreMap
import org.maplibre.compose.sources.GeoJsonData
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
 */
@Composable
actual fun QuakeMap(
    pins: List<QuakePin>,
    newQuakeId: String?,
    onPinTap: (String) -> Unit,
    modifier: Modifier,
) {
  val cameraState =
      rememberCameraState(
          firstPosition =
              CameraPosition(
                  target = Position(longitude = WORLD_CENTER_LONGITUDE, latitude = WORLD_CENTER_LATITUDE),
                  zoom = WORLD_ZOOM,
              )
      )
  MaplibreMap(
      modifier = modifier,
      baseStyle = BaseStyle.Uri(OPENFREEMAP_LIBERTY_STYLE_URL),
      cameraState = cameraState,
  ) {
    val pinsByBand = pins.groupBy { it.band }
    // Fixed order (enum declaration order: LOW, MODERATE, STRONG, MAJOR, UNKNOWN) so MAJOR/STRONG
    // pins' CircleLayers are added — and therefore drawn — after LOW/MODERATE's: more severe
    // quakes stay visually on top when pins overlap at low zoom. Always all 5, never conditional
    // on which bands currently have pins — see the fix note above for why.
    for (band in MagnitudeBand.entries) {
      key(band) {
        QuakeBandCircleLayer(band, pinsByBand[band].orEmpty(), onPinTap)
      }
    }
  }
}

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

private fun List<QuakePin>.toFeatureCollection() =
    FeatureCollection(
        map { pin ->
          Feature(
              geometry = Point(Position(longitude = pin.lon, latitude = pin.lat)),
              properties = JsonObject(emptyMap()),
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
