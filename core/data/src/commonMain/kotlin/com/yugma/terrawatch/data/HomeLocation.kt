package com.yugma.terrawatch.data

import com.yugma.terrawatch.database.QuakeDao
import com.yugma.terrawatch.model.GeoPoint

/**
 * Home coordinates, persisted as two scalar rows in the existing key/value meta table (no schema
 * change — same table [QuakeDao.metaGet]/[QuakeDao.metaPut] already use for feed_etag). Pill logic
 * (Task 9) reads via [get], falling back to `LocationProvider.current()` when this returns null,
 * and calls [set] to remember a granted fix as home.
 */
class HomeLocationStore(private val dao: QuakeDao) {
    fun get(): GeoPoint? {
        val lat = dao.metaGet(LAT_KEY)?.toDoubleOrNull() ?: return null
        val lon = dao.metaGet(LON_KEY)?.toDoubleOrNull() ?: return null
        return GeoPoint(lat, lon)
    }

    fun set(point: GeoPoint) {
        dao.metaPut(LAT_KEY, point.lat.toString())
        dao.metaPut(LON_KEY, point.lon.toString())
    }

    private companion object {
        const val LAT_KEY = "home_lat"
        const val LON_KEY = "home_lon"
    }
}
