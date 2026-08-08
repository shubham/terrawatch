package com.yugma.terrawatch.map

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import org.maplibre.compose.camera.CameraPosition
import org.maplibre.compose.camera.rememberCameraState
import org.maplibre.compose.map.MaplibreMap
import org.maplibre.compose.style.BaseStyle
import org.maplibre.spatialk.geojson.Position

// OpenFreeMap's "liberty" style — free, no API key, no attribution HTML to inject beyond what the
// style JSON already declares (OpenFreeMap + OpenMapTiles + OpenStreetMap contributors).
private const val OPENFREEMAP_LIBERTY_STYLE_URL = "https://tiles.openfreemap.org/styles/liberty"

// Roughly centers the populated world (equatorial Atlantic/Africa) rather than the (0,0) Gulf of
// Guinea null-island default, at a zoom that shows most continents at once.
private const val WORLD_CENTER_LATITUDE = 20.0
private const val WORLD_CENTER_LONGITUDE = 0.0
private const val WORLD_ZOOM = 1.5

/**
 * SPIKE (Plan 2, Task 6): minimal maplibre-compose render — world basemap only, no quake pins yet.
 *
 * This is the ONE composable this codebase owns for the map; Task 8 extends its signature (adds
 * `pins`, `newQuakeId`, `onPinTap`) rather than reaching into maplibre-compose APIs from screen
 * code, so any future library API churn stays contained to this file. See
 * docs/superpowers/plans/plan-2-spike-maplibre.md for what was verified on each platform and the
 * exact 0.14.0 API this task discovered for markers/clustering/re-tinting.
 */
@Composable
fun QuakeMap(modifier: Modifier = Modifier) {
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
  )
}
