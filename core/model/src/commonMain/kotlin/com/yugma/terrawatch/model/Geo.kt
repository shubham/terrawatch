package com.yugma.terrawatch.model

import kotlin.math.PI
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

data class GeoPoint(val lat: Double, val lon: Double)

private const val EARTH_RADIUS_KM = 6371.0088
private fun Double.toRadians() = this * PI / 180.0
private fun Double.toDegrees() = this * 180.0 / PI

fun haversineKm(a: GeoPoint, b: GeoPoint): Double {
    val dLat = (b.lat - a.lat).toRadians()
    val dLon = (b.lon - a.lon).toRadians()
    val s = sin(dLat / 2) * sin(dLat / 2) +
        cos(a.lat.toRadians()) * cos(b.lat.toRadians()) * sin(dLon / 2) * sin(dLon / 2)
    return 2 * EARTH_RADIUS_KM * asin(sqrt(s.coerceAtMost(1.0)))
}

/**
 * Task 7 (Plan 3), the user-settable home-radius map ring: a closed, [points]-vertex ring
 * approximating a circle of [radiusKm] around [center], traced via the haversine "destination
 * point given distance and bearing" formula ([destinationPoint] below) swept over [points]
 * equally-spaced bearings from 0..360°. MapLibre's `CircleLayer` radius is a fixed on-screen PIXEL
 * size (constant regardless of zoom, not a real-world distance — see `QuakeMap.android.kt`'s own
 * kdoc for the spike finding this works around), so a geographically-honest "everything within N km
 * of home" ring needs an actual polygon traced in lat/lon space instead of a circle primitive.
 * `FallbackMapPane` reuses the exact same ring for its own Canvas-projected approximation — one
 * source of truth for "what does the ring look like" regardless of which target renders it.
 *
 * "Closed ring" is a hard contract, not an incidental property: a GeoJSON `Polygon`'s ring (and
 * `FallbackMapPane`'s own `Canvas` `Path`) both need an explicit last-vertex-equals-first-vertex to
 * render a closed shape rather than an open, gapped one. Guaranteed BY CONSTRUCTION here — the last
 * element is literally [List.first] appended back on, not a fresh `destinationPoint(..., 360.0)`
 * call that would only be closed up to floating-point noise (`sin`/`cos` of a bearing computed from
 * `360.0.toRadians()` lands extremely close to, but not bit-identical to, `sin`/`cos` of
 * `0.0.toRadians()` — close enough for rendering, not close enough to assert exact equality against
 * in a test without an epsilon).
 */
fun circlePolygon(center: GeoPoint, radiusKm: Double, points: Int = 64): List<GeoPoint> {
    require(points >= 3) { "circlePolygon needs at least 3 points to trace a ring, got $points" }
    val ring = (0 until points).map { i -> destinationPoint(center, radiusKm, bearingDegrees = 360.0 * i / points) }
    return ring + ring.first()
}

/**
 * The haversine "destination point given distance and bearing" formula (the direct geodesic
 * problem) — [haversineKm]'s paired inverse (the indirect problem, "distance given two points").
 * Standard spherical-earth formula, using the same [EARTH_RADIUS_KM] sphere [haversineKm] already
 * assumes, for consistency: see e.g. https://www.movable-type.co.uk/scripts/latlong.html,
 * "Destination point given distance and bearing from start point".
 */
private fun destinationPoint(start: GeoPoint, distanceKm: Double, bearingDegrees: Double): GeoPoint {
    val angularDistance = distanceKm / EARTH_RADIUS_KM
    val bearing = bearingDegrees.toRadians()
    val lat1 = start.lat.toRadians()
    val lon1 = start.lon.toRadians()

    val lat2 = asin(
        sin(lat1) * cos(angularDistance) + cos(lat1) * sin(angularDistance) * cos(bearing),
    )
    val lon2 = lon1 + atan2(
        sin(bearing) * sin(angularDistance) * cos(lat1),
        cos(angularDistance) - sin(lat1) * sin(lat2),
    )
    // Normalize into [-180, 180) — a ring near the antimeridian can otherwise produce a raw lon2
    // outside the conventional range. `+540 (=180+360), %360, -180` is the standard idiom: adding a
    // multiple of 360 large enough that the operand to `%` is always non-negative (any lon2 this
    // function can actually produce is nowhere near the -540° bound that would require) sidesteps
    // Kotlin's dividend-signed `%` giving a negative remainder for negative input.
    val normalizedLonDegrees = ((lon2.toDegrees() + 540.0) % 360.0) - 180.0
    return GeoPoint(lat2.toDegrees(), normalizedLonDegrees)
}
