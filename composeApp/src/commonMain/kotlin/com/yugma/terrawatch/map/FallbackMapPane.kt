package com.yugma.terrawatch.map

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import com.yugma.terrawatch.model.MagnitudeBand
import com.yugma.terrawatch.ui.theme.TerraColors
import com.yugma.terrawatch.ui.theme.TerraRadii
import com.yugma.terrawatch.ui.theme.magnitudeColor

// Task 12 (spike decision, docs/superpowers/plans/2026-08-08-terrawatch-plan-2-ui-shell.md's Task
// 12 section, backed by docs/superpowers/plans/plan-2-spike-maplibre.md): maplibre-compose
// publishes no wasmJs artifact at all, and its desktop artifact requires a JDK 25+ runtime this
// project's Gradle 8.14 (JDK 17 toolchain) can't itself launch on — confirmed empirically in the
// spike, not assumed from docs. So jvm (desktop) and wasmJs (web) share this ONE static-but-honest
// fallback instead of a live tile map: an equirectangular (plate carrée) world projection drawn
// straight onto a Compose Canvas, fed the SAME pins QuakeMap's Android actual would render. There
// is no basemap artwork here (no coastlines/borders/labels) — a flat Water-color rect stands in
// for "the world" — so there is no OSM/OpenFreeMap attribution burden: no map tiles are rendered by
// this pane, only real pin data on a plain background. Android remains the only LIVE map target in
// Plan 2 (and the only judged one, per the spike decision); a real desktop/web tile map is deferred
// to Plan 3, once the JDK 25 constraint has a resolution path.
private const val HIT_RADIUS_DP = 24f

private const val CAPTION = "Live map on Android — showing latest quakes"

/**
 * Equirectangular projection: longitude maps linearly across the full [-180, 180] range to
 * `[0, widthPx]`; see [projectLat] for the paired latitude axis. No distortion correction (e.g.
 * Mercator) — this is "place a pin roughly where a quake was on a whole-world glance," not a
 * navigable map, so the well-known area distortion near the poles is an accepted simplification.
 */
internal fun projectLon(lon: Double, widthPx: Float): Float = ((lon + 180.0) / 360.0 * widthPx).toFloat()

/** Latitude maps linearly across `[90, -90]` to `[0, heightPx]` — `90 - lat`, not `lat` directly,
 * so north stays at the top of the canvas the way every reader expects a world map to be oriented. */
internal fun projectLat(lat: Double, heightPx: Float): Float = ((90.0 - lat) / 180.0 * heightPx).toFloat()

/**
 * Half of `QuakeMap.android.kt`'s `pinRadiusDp` band table (LOW=4, MODERATE=6, STRONG=8, MAJOR=10,
 * UNKNOWN=3 dp, per that file's Fix Round 2 controller decision) — this pane draws the entire
 * world in whatever width the map pane/browser window happens to have, so full Android-sized pins
 * would overlap constantly at any real live-feed pin density. UNKNOWN stays the smallest of all
 * five bands here too, same reasoning as the Android actual: a missing-magnitude pin must never
 * visually dominate a real one.
 */
internal fun fallbackPinRadiusDp(band: MagnitudeBand): Float = when (band) {
    MagnitudeBand.LOW -> 2f
    MagnitudeBand.MODERATE -> 3f
    MagnitudeBand.STRONG -> 4f
    MagnitudeBand.MAJOR -> 5f
    MagnitudeBand.UNKNOWN -> 1.5f
}

/**
 * The closest [QuakePin] to [tap], provided it's within [radiusPx] of its own projected position —
 * `null` when [pins] is empty or every pin is further away than that. A plain linear scan, not a
 * spatial index: this pane never has to hit-test more pins than `QuakeMap`'s Android actual already
 * renders live (on the order of a few hundred), nowhere near where an O(n) scan per tap would be
 * felt.
 */
internal fun nearestPinWithin(
    pins: List<QuakePin>,
    tap: Offset,
    widthPx: Float,
    heightPx: Float,
    radiusPx: Float,
): String? {
    var bestId: String? = null
    var bestDistanceSq = Float.MAX_VALUE
    for (pin in pins) {
        val dx = projectLon(pin.lon, widthPx) - tap.x
        val dy = projectLat(pin.lat, heightPx) - tap.y
        val distanceSq = dx * dx + dy * dy
        if (distanceSq <= radiusPx * radiusPx && distanceSq < bestDistanceSq) {
            bestDistanceSq = distanceSq
            bestId = pin.id
        }
    }
    return bestId
}

/**
 * The desktop/web stand-in for [QuakeMap]'s live tile map — see this file's own top-of-file kdoc
 * for the spike decision behind it. Pins carry every bit of real information a live map pin would
 * (position + magnitude band, via [magnitudeColor]); tapping one within [HIT_RADIUS_DP]dp of its
 * projected position calls [onPinTap] with its id, wired by `QuakeMap.jvm.kt`/`QuakeMap.wasmJs.kt`
 * to the exact same `HomeViewModel.select` a real map pin tap uses — the fallback is a rendering
 * compromise, not a feature compromise: desktop's detail sheet opens from a fallback pin tap same
 * as it would from a card tap.
 *
 * Deliberately takes only [pins]/[onPinTap]/[modifier] — no `newQuakeId`: unlike `QuakeMap`'s
 * Android actual, there is no pin-drop-pop animation here at all. `QuakeMap.jvm.kt`/
 * `QuakeMap.wasmJs.kt` still accept `newQuakeId` (the shared `expect` signature demands it) but
 * never forward it into this pane — a brand new arrival simply appears in the next [pins] list this
 * pane redraws, with no separate "new" treatment.
 */
@Composable
fun FallbackMapPane(
    pins: List<QuakePin>,
    onPinTap: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(modifier.fillMaxSize().background(TerraColors.Water)) {
        Canvas(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(pins) {
                    detectTapGestures { tap ->
                        val radiusPx = HIT_RADIUS_DP.dp.toPx()
                        nearestPinWithin(pins, tap, size.width.toFloat(), size.height.toFloat(), radiusPx)
                            ?.let(onPinTap)
                    }
                },
        ) {
            for (pin in pins) {
                drawCircle(
                    color = magnitudeColor(pin.band),
                    radius = fallbackPinRadiusDp(pin.band).dp.toPx(),
                    center = Offset(projectLon(pin.lon, size.width), projectLat(pin.lat, size.height)),
                )
            }
        }
        // Same "glass" treatment (78%-alpha surface + tonal/shadow elevation) HomeScreen's own
        // StalenessBanner and StatusShield use for floating chrome over the map — keeps this
        // caption legible over pins/water without inventing a new visual language for one label.
        Surface(
            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 16.dp),
            shape = RoundedCornerShape(TerraRadii.pill),
            color = MaterialTheme.colorScheme.surface.copy(alpha = 0.78f),
            tonalElevation = 4.dp,
            shadowElevation = 2.dp,
        ) {
            Text(
                text = CAPTION,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
    }
}
