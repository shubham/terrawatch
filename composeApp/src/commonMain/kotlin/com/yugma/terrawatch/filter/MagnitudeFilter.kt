package com.yugma.terrawatch.filter

/**
 * User review items 3+4: the shared magnitude-filter chip vocabulary — "All / 4.0+ / 5.0+ / 6.0+",
 * ONE definition consumed by both [com.yugma.terrawatch.history.HistoryScreen]'s archive filter row
 * (replacing that screen's own former, independent "All/M4.5+/M6+" set) and
 * [com.yugma.terrawatch.home.FeedSheet]'s dashboard-list filter control — per the user's own
 * explicit instruction ("shared vocabulary"), so the two screens' chip sets can never independently
 * drift (e.g. one gaining a "7.0+" tier the other lacks). `Pair<String, Double?>` — label to the
 * `minMag` value a tap should apply; `null` is the "All" chip, matching
 * [com.yugma.terrawatch.data.HistoryFilter.minMag]'s own nullable shape exactly (no floor at all,
 * not merely "floor zero").
 *
 * A plain top-level `val`, not an `enum class`: every real consumer already wants exactly this
 * `(label, value)` shape for a direct `forEach { (label, value) -> ... }` chip-row render (see
 * `HistoryScreen.kt`'s pre-existing `MAG_CHIPS` this replaces) — an enum would need its own
 * label/value accessors for no behavioral gain over a plain list literal.
 */
val MAGNITUDE_FILTER_CHIPS: List<Pair<String, Double?>> = listOf(
    "All" to null,
    "4.0+" to 4.0,
    "5.0+" to 5.0,
    "6.0+" to 6.0,
)

/**
 * The shared "does this quake's magnitude satisfy this filter" predicate — the CLIENT-SIDE
 * (Kotlin) twin of `Quake.sq`'s own `pageBetween` SQL predicate (`:minMag IS NULL OR mag >=
 * :minMag`), same truth table, same "an unknown/null magnitude never satisfies a real floor"
 * ruling `QuakeDao`/`InMemoryQuakeStore` already apply server-side. History never needs this
 * function directly — its own filtering happens in SQL, at the DAO layer — but the dashboard feed
 * sheet's list (and the two-pane desktop/tablet panel's own copy of it) has no per-request SQL
 * query of its own to filter in: [com.yugma.terrawatch.home.HomeViewModel.state]'s `quakes` list is
 * a single already-fetched (last-24h, unfiltered) window that MUST stay whole for
 * [com.yugma.terrawatch.home.pillStatus]'s own safety evaluation and the map's pin list (see
 * `HomeScreen.kt`'s own kdoc note on why filtering happens at the Compose call-site boundary, not
 * inside the ViewModel's `state`) — this is the pure function that boundary applies.
 */
fun quakeMatchesMagFilter(mag: Double?, minMag: Double?): Boolean =
    minMag == null || (mag != null && mag >= minMag)
