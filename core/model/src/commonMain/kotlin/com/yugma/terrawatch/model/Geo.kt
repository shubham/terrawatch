package com.yugma.terrawatch.model

import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.PI

data class GeoPoint(val lat: Double, val lon: Double)

private const val EARTH_RADIUS_KM = 6371.0088
private fun Double.toRadians() = this * PI / 180.0

fun haversineKm(a: GeoPoint, b: GeoPoint): Double {
    val dLat = (b.lat - a.lat).toRadians()
    val dLon = (b.lon - a.lon).toRadians()
    val s = sin(dLat / 2) * sin(dLat / 2) +
        cos(a.lat.toRadians()) * cos(b.lat.toRadians()) * sin(dLon / 2) * sin(dLon / 2)
    return 2 * EARTH_RADIUS_KM * asin(sqrt(s.coerceAtMost(1.0)))
}
