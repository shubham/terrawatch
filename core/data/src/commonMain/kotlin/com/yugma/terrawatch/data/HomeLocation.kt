package com.yugma.terrawatch.data

import com.yugma.terrawatch.database.QuakeDao
import com.yugma.terrawatch.model.GeoPoint
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow

/**
 * Home coordinates, persisted as two scalar rows in the existing key/value meta table (no schema
 * change — same table [QuakeDao.metaGet]/[QuakeDao.metaPut] already use for feed_etag). Pill logic
 * (Task 9) reads via [get], falling back to `LocationProvider.current()` when this returns null,
 * and calls [set] to remember a granted fix as home.
 *
 * Task 2 (Plan 3), "close the location loop": [updates] emits every [set] call's point so a live
 * collector (`HomeViewModel.homeLocation` — see its own kdoc) can react to a location resolving or
 * changing mid-session. Without this, a permission grant or city pick landing after
 * `HomeViewModel`'s one-shot `get()`-or-`current()` init read had already settled would never reach
 * the already-composed pill — the exact "ASK pill frozen even after granting, needs an app restart"
 * gap this task closes.
 */
class HomeLocationStore(private val dao: QuakeDao) {
    // extraBufferCapacity = 4, not the default 0: [set] is a plain synchronous function (called from
    // Compose click handlers, Dispatchers.Default ViewModel init blocks, and an Activity's
    // lifecycleScope alike), so it publishes via [MutableSharedFlow.tryEmit] rather than the
    // suspending emit() — tryEmit on a zero-capacity SharedFlow drops the value outright whenever no
    // collector is suspended and ready for it right at that instant. A small buffer means a set()
    // that happens to race a collector's own subscribe-in-progress (e.g. HomeViewModel's init,
    // still on its way from get()/current() to subscribing here) is not silently lost. 4 (not 1)
    // just mirrors this codebase's other event SharedFlows (QuakeRepository.insertedQuakeIds/
    // alertEvents use 16 for a much higher-volume stream) — a manual location change is rare enough
    // that even capacity 1 would suffice in practice, but the extra headroom costs nothing.
    private val _updates = MutableSharedFlow<GeoPoint>(extraBufferCapacity = 4)
    val updates: SharedFlow<GeoPoint> = _updates

    fun get(): GeoPoint? {
        val lat = dao.metaGet(LAT_KEY)?.toDoubleOrNull() ?: return null
        val lon = dao.metaGet(LON_KEY)?.toDoubleOrNull() ?: return null
        return GeoPoint(lat, lon)
    }

    fun set(point: GeoPoint) {
        dao.metaPut(LAT_KEY, point.lat.toString())
        dao.metaPut(LON_KEY, point.lon.toString())
        _updates.tryEmit(point)
    }

    private companion object {
        const val LAT_KEY = "home_lat"
        const val LON_KEY = "home_lon"
    }
}
