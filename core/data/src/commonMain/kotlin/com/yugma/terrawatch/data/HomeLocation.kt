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
    // Task 3 (Plan 3) carry-in — the Task 2 ledger minor: this used to be replay = 0, with a kdoc
    // claiming extraBufferCapacity alone meant a racing collector's set() "is not silently lost."
    // That claim was only half true: extraBufferCapacity governs BACKPRESSURE for a collector that
    // is already subscribed (or mid-subscribe) when set() fires — a collector that subscribes
    // strictly AFTER set() has already returned got nothing at all under replay = 0, and had no
    // way to learn the current point short of a fresh set() happening to fire again later.
    // replay = 1 is what actually covers that genuinely-late-subscriber case (see
    // HomeLocationTest's "a subscriber that joins after set still receives the latest point" —
    // red under replay = 0, green here): a NEW collector immediately receives whatever point the
    // most recent [set] call published, exactly like [HomeViewModel.homeLocation]'s own
    // init-block kdoc already assumes when it says "re-applying the startup value here too is
    // harmless" — that assumption is only actually sound with a real replay cache backing it.
    //
    // extraBufferCapacity = 4 (unchanged): [set] is a plain synchronous function (called from
    // Compose click handlers, Dispatchers.Default ViewModel init blocks, and an Activity's
    // lifecycleScope alike), so it publishes via [MutableSharedFlow.tryEmit] rather than the
    // suspending emit() — tryEmit on a zero-EXTRA-capacity SharedFlow can still drop a value if an
    // already-subscribed-but-momentarily-busy collector isn't ready to receive it right at that
    // instant (replay's 1 slot alone doesn't cover a burst of several sets in a row). 4 (not 1)
    // just mirrors this codebase's other event SharedFlows (QuakeRepository.insertedQuakeIds/
    // alertEvents use 16 for a much higher-volume stream) — a manual location change is rare enough
    // that even capacity 1 would suffice in practice, but the extra headroom costs nothing.
    private val _updates = MutableSharedFlow<GeoPoint>(replay = 1, extraBufferCapacity = 4)
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
