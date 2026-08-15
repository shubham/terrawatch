package com.yugma.terrawatch.model

/**
 * Task 2 (Plan 5): a user-saved place beyond home — [point] drives the worker's own per-place alert
 * evaluation ([com.yugma.terrawatch.data.AlertRuleEngine], via a rule centered here instead of home)
 * and the Home screen's quick-switch chips (camera fly + a session-only pill preview); [alertType]
 * is this place's own opt-in level, independent of every other favorite's and of home's own
 * (store-fed, always-on) near/world rules.
 *
 * [id] mirrors [com.yugma.terrawatch.database.QuakeDao]'s generated `favorite_place` row id — an
 * auto-incrementing `Long` (SQLite `INTEGER PRIMARY KEY AUTOINCREMENT`), never reused/recycled by
 * this table's own auto-increment semantics, so it's stable enough to key a remove/alert-type-change
 * call against even after other favorites are added or removed around it.
 */
data class FavoritePlace(
    val id: Long,
    val label: String,
    val point: GeoPoint,
    val alertType: FavoriteAlertType,
)

/**
 * Task 2 (Plan 5): a favorite's own alert opt-in level — [ALL] mirrors home's "near" rule semantics
 * (any quake at/above the app's current min-magnitude setting, within the current nearby radius,
 * centered on this place instead of home); [MAJOR_ONLY] narrows that to M≥6.0 only (mirrors the
 * "world" rule's own fixed threshold, but radius-bounded to this place rather than unbounded);
 * [OFF] means this favorite drives NO alert rule at all (it still shows in Settings/the quick-switch
 * chips — this only opts it out of `AlertDigestWorker`'s evaluation, see [com.yugma.terrawatch.data.
 * AlertDigestSupport]'s own `buildDigestRules`).
 */
enum class FavoriteAlertType {
    ALL, MAJOR_ONLY, OFF;

    companion object {
        /**
         * Safe-parse for the persisted TEXT column ([com.yugma.terrawatch.database.QuakeDao]'s own
         * `favorite_place.alertType`) — an unrecognized or `null` value degrades quietly to [ALL]
         * (the same "everything, no extra filtering" shape a brand-new favorite gets) rather than
         * throwing, matching this codebase's established "never crash on a data-shape surprise"
         * posture (e.g. [com.yugma.terrawatch.database.bandFromLabel]).
         */
        fun fromStored(value: String?): FavoriteAlertType = entries.firstOrNull { it.name == value } ?: ALL
    }
}
