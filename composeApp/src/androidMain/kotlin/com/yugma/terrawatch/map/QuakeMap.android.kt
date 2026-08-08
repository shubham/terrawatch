package com.yugma.terrawatch.map

import android.util.Log
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
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
private const val LOG_TAG = "TerraWatchMap"

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
 * magnitude), just without the extra, unverified expression-DSL surface. Clustering is skipped
 * entirely per the brief's own allowance ("ONLY if ... <1h work; else skip, note, defer to Plan
 * 3") — the 3-layer cluster/unclustered pattern needs the same unverified expression machinery
 * this task is deliberately avoiding, so it's deferred, not attempted.
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
  val source = rememberGeoJsonSource(GeoJsonData.Features(pins.toFeatureCollection()))
  CircleLayer(
      id = "quake-pins-${band.name.lowercase()}",
      source = source,
      color = const(magnitudeColor(band)),
      radius = const(pinRadiusDp(band).dp),
      strokeColor = const(Color.White),
      strokeWidth = const(PIN_STROKE_WIDTH_DP.dp),
      onClick = { features ->
        val id = features.firstOrNull()?.id?.content
        Log.d(LOG_TAG, "onPinTap(id=$id)")
        id?.let(onPinTap)
        ClickResult.Consume
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

// Brief's interfaces block (authoritative): "pin size 8+band.ordinal*4 dp-equivalent". Applied
// literally across all five MagnitudeBand values: LOW=8, MODERATE=12, STRONG=16, MAJOR=20,
// UNKNOWN=24 — note UNKNOWN (ordinal 4, magnitude data missing) ends up the largest radius, larger
// than MAJOR. That's very likely an oversight in a formula meant for the four numbered severities,
// but "interfaces authoritative" per the task brief and UNKNOWN pins are rare in practice (feeds
// almost always carry a magnitude) — flagging here rather than silently special-casing it.
private fun pinRadiusDp(band: MagnitudeBand): Float = 8f + band.ordinal * 4f
