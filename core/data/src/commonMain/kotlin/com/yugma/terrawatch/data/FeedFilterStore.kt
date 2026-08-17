package com.yugma.terrawatch.data

import com.yugma.terrawatch.database.QuakeStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart

/**
 * User review items 3+4: the dashboard feed sheet's own list-scoped magnitude filter — "All /
 * 4.0+ / 5.0+ / 6.0+", the SAME shared chip vocabulary [History's HistoryFilter][HistoryFilter]
 * exposes for the archive screen (see that class's own kdoc for the vocabulary migration). Unlike
 * [HistoryFilter] (a plain in-memory `MutableStateFlow`, confirmed session-only — History's own
 * filter is never written to the meta table anywhere in this codebase), the feed sheet's choice is
 * an explicit, binding user instruction to PERSIST across restarts ("user should be able to
 * filter" + "User choice PERSISTS") — so this gets its own dedicated store, same one-purpose-per-
 * store shape [AlertRuleStore]/[HomeLocationStore]/[VisitStore] already establish over the shared
 * [QuakeStore] meta table (no schema change).
 *
 * Shape mirrors [AlertRuleStore] almost exactly (a `Flow<T>` derived from one shared `updates`
 * pulse, `distinctUntilChanged` so an unrelated store write can never cause a spurious re-emission,
 * a synchronous `current...()` escape hatch) — the one real difference is nullability: [minMag] is
 * `Double?`, not [AlertRuleStore.minMag]'s plain `Double`, because "All" (no floor at all) is a
 * real, distinct, user-selectable chip value here, not merely an unset/default state — see
 * [readMinMag]'s own kdoc for how a genuine stored "All" is told apart from "never configured".
 *
 * MAP PINS UNAFFECTED, by construction: nothing in this store (or its one consumer,
 * [com.yugma.terrawatch.home.HomeViewModel]) ever touches
 * [com.yugma.terrawatch.data.QuakeRepository.recentQuakes]/the pin list `HomeScreen.kt` feeds
 * `QuakeMap` — this filter is threaded ONLY into the feed sheet's/two-pane panel's own displayed
 * list (and, for coherence, the "N NEW" counter that describes that same list) at the Compose/
 * ViewModel boundary, never into what the map renders. See `HomeScreen.kt`'s own kdoc note at its
 * `FeedSheet`/`FeedList` call sites.
 */
class FeedFilterStore(private val dao: QuakeStore) {
    private val _updates = MutableSharedFlow<Unit>(replay = 1, extraBufferCapacity = 4)
    val updates: SharedFlow<Unit> = _updates

    val minMag: Flow<Double?> = _updates
        .map { readMinMag() }
        .onStart { emit(readMinMag()) }
        .distinctUntilChanged()

    /** [minMag] is null for "All" — writes a dedicated sentinel string (never a real magnitude
     * value, so it can never collide with a stored numeric threshold on read) rather than leaving
     * the meta row absent, which [readMinMag] must otherwise be unable to tell apart from "this
     * device has never configured a feed filter at all" (the genuine unset-default-to-4.0 case). */
    fun setMinMag(minMag: Double?) {
        dao.metaPut(KEY, minMag?.toString() ?: ALL_SENTINEL)
        _updates.tryEmit(Unit)
    }

    /** Synchronous escape hatch — same role as [AlertRuleStore.currentMinMag]'s own kdoc: a caller
     * that already has a plain, non-suspending code path (none currently do; kept for the same
     * "escape hatch available, not yet needed" symmetry with the store this one is modeled on)
     * doesn't need Flow/coroutine ceremony for a single synchronous meta read. */
    internal fun currentMinMag(): Double? = readMinMag()

    /**
     * Three-way read: (1) no row ever written -> [DEFAULT_MIN_MAG] (4.0, the user's own explicit
     * "first-run default 4.0+" instruction); (2) the row holds [ALL_SENTINEL] -> `null` (a genuine,
     * explicitly-chosen "All"); (3) anything else -> parsed as a `Double`, degrading to
     * [DEFAULT_MIN_MAG] on a corrupt/unparseable value rather than throwing (same "never crash on a
     * data-shape surprise" posture this codebase applies elsewhere, e.g. [AlertRuleStore]'s own
     * corrupt-value-yields-default read paths). No clamping to the chip set's own three real values
     * (4.0/5.0/6.0) the way [AlertRuleStore]'s own M2/M4.0 rulings clamp — unlike that store's
     * slider (a continuous range needing a floor/ceiling), every real writer here is one of exactly
     * four fixed chip taps (`MAGNITUDE_FILTER_CHIPS`), so an out-of-band stored value can only ever
     * originate from manual DB tampering, not a legitimate future caller this store needs to defend
     * against.
     */
    private fun readMinMag(): Double? {
        val stored = dao.metaGet(KEY) ?: return DEFAULT_MIN_MAG
        if (stored == ALL_SENTINEL) return null
        return stored.toDoubleOrNull() ?: DEFAULT_MIN_MAG
    }

    companion object {
        /** The user's own explicit, binding instruction: "in the list default should be 4.0 and
         * above" — a fresh install (or any device that has never touched this filter) opens the
         * feed sheet already scoped to M4.0+, not the unfiltered "All" a naive absent-row read
         * might otherwise suggest. */
        const val DEFAULT_MIN_MAG = 4.0

        private const val KEY = "feed_filter_minmag"
        private const val ALL_SENTINEL = "all"
    }
}
