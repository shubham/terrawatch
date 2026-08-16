package com.yugma.terrawatch.data

import com.yugma.terrawatch.database.QuakeStore
import com.yugma.terrawatch.model.FavoriteAlertType
import com.yugma.terrawatch.model.FavoritePlace
import com.yugma.terrawatch.model.GeoPoint
import kotlinx.coroutines.flow.Flow

/**
 * Task 2 (Plan 5): favorites beyond home — a thin CRUD wrapper over [QuakeStore]'s new
 * favorite-place methods, same "store wraps dao, dao does the real work" shape [HomeLocationStore]/
 * [AlertRuleStore] already establish for this module (both constructed over the SAME widened
 * [QuakeStore] interface, not a second, parallel storage abstraction).
 *
 * Unlike [HomeLocationStore]/[AlertRuleStore] (a handful of scalar meta rows), this store's backing
 * data is a real list that grows/shrinks/reorders its own rows — [favorites] is therefore a plain
 * pass-through [Flow] (mirrors [QuakeRepository.recentQuakes]'s own "the dao already returns a
 * reactive Flow, nothing to add" shape) rather than the `get()` + `updates: SharedFlow` split those
 * two use: there is no "current snapshot, read synchronously" call anywhere this store needs to
 * support (every real caller — [com.yugma.terrawatch.home.HomeViewModel], `SettingsViewModel`, the
 * worker's own multi-place evaluation — already collects reactively or takes `.first()`).
 */
class FavoritePlaceStore(private val dao: QuakeStore) {
    val favorites: Flow<List<FavoritePlace>> = dao.favoritePlaces()

    /** Adds a new favorite — [alertType] defaults to [FavoriteAlertType.ALL] (a brand-new favorite
     * starts fully opted in, matching the "everything, no extra filtering" semantics home's own
     * "near" rule already has by default). */
    fun add(label: String, point: GeoPoint, alertType: FavoriteAlertType = FavoriteAlertType.ALL) {
        dao.insertFavoritePlace(label, point, alertType)
    }

    fun remove(id: Long) {
        dao.deleteFavoritePlace(id)
    }

    /** The per-row alert-type control's write path (Settings' Places section) — see
     * [QuakeStore.updateFavoritePlaceAlertType]'s own kdoc. */
    fun setAlertType(id: Long, alertType: FavoriteAlertType) {
        dao.updateFavoritePlaceAlertType(id, alertType)
    }
}
