package com.yugma.terrawatch.history

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.yugma.terrawatch.common.currentTimeMillis
import com.yugma.terrawatch.data.HistoryFilter
import com.yugma.terrawatch.data.HistoryPager
import com.yugma.terrawatch.data.PageResult
import com.yugma.terrawatch.data.QuakeRepository
import com.yugma.terrawatch.data.yearCeilingMillisExclusiveOrNull
import com.yugma.terrawatch.data.yearFloorMillisOrNull
import com.yugma.terrawatch.model.Quake
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

/** One sticky-header group — "AUGUST 2026" plus the quakes that fall inside it, already in
 * whatever order [groupByMonth]'s input arrived in (time-descending, per [QuakeRepository.pageBetween]). */
data class HistorySection(val label: String, val quakes: List<Quake>)

/**
 * History screen state — mirrors [com.yugma.terrawatch.home.HomeUiState]'s "one sealed state,
 * Content carries everything the screen needs" shape, but with a genuine terminal [Error] (unlike
 * Home, History has no permanent centerpiece that must always render — a screen with zero rows and
 * zero hope of getting any needs to say so).
 */
sealed interface HistoryUiState {
    /** First page for the current filter is still in flight and nothing is cached yet — skeleton. */
    data object LoadingFirst : HistoryUiState

    data class Content(
        val sections: List<HistorySection>,
        val loadingMore: Boolean,
        val endReached: Boolean,
        val loadMoreFailed: Boolean,
        /**
         * Round-3 review MAJOR (M-1) fix: true while [HistoryViewModel] is auto-paging deeper into
         * the archive because an active search has no visible match yet in whatever is cached so
         * far — see [HistoryViewModel.loadUntilDecided]'s own kdoc for the loop this drives, and
         * [HistoryViewModel.shouldAutoContinueSearch] for the exact decision behind it.
         * `HistoryScreen` uses this (together with [sections] being empty) to choose the "Searching
         * older quakes…" interim copy over the terminal "no matches" one — see that screen's own
         * `HistoryFooter`/`HistorySearchEmptyState` kdoc for the full state -> copy mapping.
         * Defaulted `false` so every pre-existing construction of this class (this file's own
         * [historyContentFrom], and any test that builds a [Content] directly) keeps compiling
         * unchanged — the field is only ever `true` for the one specific interim shape it exists
         * for.
         */
        val searchingArchive: Boolean = false,
    ) : HistoryUiState

    /** The archive is exhausted (or the current filter's year floor was already crossed) and not
     * one visible row matched the current filter's magnitude/year — i.e. SEARCH-OBLIVIOUS
     * emptiness (see [HistoryViewModel.loadUntilDecided]'s own `unsearchedVisible` check). Distinct
     * from a mid-walk [Content] with empty sections purely because a page's own batch happened to
     * add nothing new — that transient shape never reaches [HistoryViewModel.state] on its own.
     *
     * User review item 3 (history search): [Content] WITH empty `sections` IS now a legitimate,
     * durable published shape distinct from this one — a search text matching zero of an otherwise
     * non-empty cache (see [HistoryViewModel.renderFromCache]). Round-3 review MAJOR (M-1) fix:
     * `HistoryScreen`'s own UI layer used to treat the two identically for rendering purposes
     * (both "nothing to show right now") REGARDLESS of `Content.endReached` — that was the bug: a
     * search-caused empty `Content` published mid-walk (`endReached = false`,
     * [Content.searchingArchive] `true`) got the exact same static, no-further-paging treatment as
     * this genuinely-exhausted state, even though [HistoryViewModel.loadUntilDecided] might still
     * find a real match a few pages deeper. `HistoryScreen` now only folds the two together once a
     * `Content` is ALSO `endReached` — this state remains unconditionally terminal either way (it is
     * ONLY ever published once the archive-wide walk is over, search aside — see
     * [HistoryViewModel.loadUntilDecided]'s own `PageResult.End`/`PageResult.Failed` handling), so
     * nothing about ITS OWN meaning changed, only how `Content`'s emptiness is judged alongside it.
     * The search-active/inactive check that tells the two apart still lives in `HistoryScreen`, not
     * in this sealed hierarchy — adding a THIRD, search-specific state here would just be this exact
     * same "sections is empty" fact spelled two different ways.
     */
    data object Empty : HistoryUiState

    /** The very FIRST page for this filter failed and there is nothing cached to browse instead —
     * the only case where a page failure takes over the whole screen rather than showing up as an
     * end-of-list row (see [Content.loadMoreFailed] for that other case). */
    data class Error(val cause: Throwable) : HistoryUiState
}

private val MONTH_NAMES_FULL = arrayOf(
    "JANUARY", "FEBRUARY", "MARCH", "APRIL", "MAY", "JUNE",
    "JULY", "AUGUST", "SEPTEMBER", "OCTOBER", "NOVEMBER", "DECEMBER",
)

/**
 * Pure UTC month bucketing for History's sticky headers — "AUGUST 2026" style (full month name,
 * always with the year, spec's own mockup wording). [quakes] is expected already time-descending
 * (whatever [QuakeRepository.pageBefore] returned) — sections come out in first-seen order, which
 * is therefore already newest-month-first, with no separate sort step here.
 *
 * [nowMillis] is accepted but not otherwise consulted in this v1: every other pure quake-list
 * transform in this codebase takes a `nowMillis` (`pillStatus`, `QuakeCard`'s `metaLine`) because
 * each of THOSE genuinely needs "how long ago" math; a month LABEL is fully determined by a quake's
 * own `timeMillis` alone, independent of the current moment. Kept in the signature anyway — for
 * call-site symmetry with those other functions, and as a seam a future "highlight the current
 * month" treatment could use — rather than silently dropped, which would make this the one outlier
 * a caller has to remember doesn't take it.
 */
@OptIn(ExperimentalTime::class)
fun groupByMonth(quakes: List<Quake>, nowMillis: Long): List<HistorySection> {
    val order = mutableListOf<Pair<Int, Int>>() // (year, month ordinal), first-seen order
    val buckets = mutableMapOf<Pair<Int, Int>, MutableList<Quake>>()
    for (quake in quakes) {
        val dateTime = Instant.fromEpochMilliseconds(quake.timeMillis).toLocalDateTime(TimeZone.UTC)
        val key = dateTime.year to dateTime.month.ordinal
        val bucket = buckets.getOrPut(key) {
            order += key
            mutableListOf()
        }
        bucket.add(quake)
    }
    return order.map { key -> HistorySection("${MONTH_NAMES_FULL[key.second]} ${key.first}", buckets.getValue(key)) }
}

/**
 * Task 5 (Plan 3): the archive browser's ViewModel — an imperative, page-at-a-time state machine
 * (unlike [com.yugma.terrawatch.home.HomeViewModel]'s continuous `combine()` pipeline) since paging
 * is inherently a sequence of discrete steps, not a continuous stream. [repository] and [pager] are
 * this class's ONLY dependencies (no [com.yugma.terrawatch.data.HomeLocationStore]) — v1 scope: no
 * home-distance on History's cards/sheet, `HistoryScreen` passes `distanceKm = null` throughout
 * (see that file's own kdoc for why, and how a later pass could add it without touching this class).
 *
 * A single [state] is the whole public surface (besides [filter] for the screen's chip selection) —
 * `Content` carries `sections`/`loadingMore`/`endReached`/`loadMoreFailed` together, same
 * "one Content shape, not several independently-updated flows that could drift" discipline
 * [com.yugma.terrawatch.home.HomeUiState] already established.
 */
class HistoryViewModel(
    private val repository: QuakeRepository,
    private val pager: HistoryPager,
) : ViewModel() {
    private val _filter = MutableStateFlow(HistoryFilter())
    val filter: StateFlow<HistoryFilter> = _filter

    // User review item 3 (history search): raw, un-trimmed text straight from the search field —
    // session-only, same as [_filter] itself (this screen's filter has never been persisted; see
    // [com.yugma.terrawatch.data.HistoryFilter]'s own kdoc), so a fresh HistoryViewModel always
    // starts with an empty box. [effectiveSearchQuery] is what actually reaches the DB (trimmed,
    // blank collapsed to `null`) — this field stays the field's own literal contents so the search
    // box itself never silently mutates what the user typed.
    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery

    private val _state = MutableStateFlow<HistoryUiState>(HistoryUiState.LoadingFirst)
    val state: StateFlow<HistoryUiState> = _state

    // Guards against overlapping loadMore()/loadFirstPage() launches — same "track the one in-flight
    // Job, cancel before replacing it" shape as HomeViewModel's own retryJob/QuakeSelectionViewModel's
    // selectJob.
    private var loadJob: Job? = null

    // User review item 3: the identical "cancel the previous one before launching the next" guard
    // as [loadJob], scoped to [renderFromCache] instead — a fast typist re-triggering
    // [setSearchQuery] on every keystroke must not leave several overlapping re-renders racing to
    // write [_state] last; only the most recent keystroke's render should ever win.
    private var searchJob: Job? = null

    init {
        loadFirstPage()
    }

    /**
     * Infinite-scroll's own trigger (`HistoryScreen`'s "last-visible index" check calls this). A
     * no-op while a load is already in flight, or once [HistoryUiState.Content.endReached] is true
     * — safe to call redundantly (the screen's scroll-position trigger does exactly that), so it
     * costs the caller nothing to over-call this rather than hand-roll its own extra guard.
     *
     * Round-3 review MAJOR (M-1) fix: also this class's OTHER entry into [loadUntilDecided]'s walk —
     * [continueSearchingArchiveIfNeeded] calls this exact function (not a second, parallel copy of
     * its guards) so a search-triggered continuation and a scroll-triggered one can never race into
     * two overlapping [loadJob]s; whichever fires first wins, the other one no-ops right here. The
     * synchronous pre-launch state copy now also stamps [HistoryUiState.Content.searchingArchive]
     * from whatever [effectiveSearchQuery] reads AT THIS INSTANT — correct whether this call came
     * from a plain scroll (no search active, stays `false`) or a search hand-off (search active,
     * `true` from the very first published frame, not just once [loadUntilDecided]'s own loop
     * publishes its first interim step).
     */
    fun loadMore() {
        val current = _state.value
        val blocked = when (current) {
            is HistoryUiState.Content -> current.loadingMore || current.endReached
            HistoryUiState.LoadingFirst -> true // the initial load already covers this "page"
            HistoryUiState.Empty, is HistoryUiState.Error -> true // nothing to page from — use retry()/setFilter()
        }
        if (blocked || loadJob?.isActive == true) return
        if (current is HistoryUiState.Content) {
            _state.value = current.copy(loadingMore = true, searchingArchive = effectiveSearchQuery() != null)
        }
        loadJob = viewModelScope.launch { loadUntilDecided() }
    }

    /** Chip taps (`HistoryScreen`'s filter rows) call this with a `copy()` of the current [filter].
     * A no-op filter (same value) changes nothing — no reset, no re-fetch. [searchJob] is
     * cancelled alongside [loadJob] purely as tidiness (an in-flight search re-render would be a
     * guaranteed no-op the instant it tried to [publish] against the now-stale `filter` it
     * captured — see that function's own guard — this just avoids paying for a DB read whose
     * result is already known to be discarded). The search TEXT itself is deliberately left
     * untouched: filter and search are independent, simultaneously-applicable facets (the user's
     * own spec: "chips + search compose") — flipping a year chip while "japan" is typed should keep
     * narrowing to Japan-related quakes for the new year, not silently clear the box. */
    fun setFilter(newFilter: HistoryFilter) {
        if (newFilter == _filter.value) return
        loadJob?.cancel()
        searchJob?.cancel()
        _filter.value = newFilter
        loadFirstPage()
    }

    /**
     * User review item 3: [query] is matched, case-insensitively, as a substring of each cached
     * quake's `place` text, through [QuakeRepository.pageBetween]'s own `placeQuery` SQL predicate
     * (see that method's kdoc) — a real DB hit, not a filter over an already-materialized
     * `List<Quake>` snapshot, so a concurrent background write (Home's poll loop/live WebSocket
     * share this SAME `quake` table) is picked up immediately rather than only on this screen's own
     * next unrelated page load.
     *
     * Composes as AND with [filter]'s own minMag/year (unchanged) — both predicates narrow the SAME
     * underlying cached range, never independently or as an either-or.
     *
     * Round-3 review MAJOR (M-1) fix, behavior change from this function's own pre-fix self:
     * [renderFromCache] alone is still exactly what runs first — a pure LOCAL re-read of whatever is
     * ALREADY cached, no network — but it no longer gets the last word. [continueSearchingArchiveIfNeeded]
     * runs right after it and, ONLY when that local re-read came up empty with more archive left to
     * check, hands off to [loadMore] to keep walking the archive for a match. This is a deliberate
     * reversal of this function's own former "NEVER calls [HistoryPager.loadNext] or otherwise
     * touches the network" contract — that was the round-3 review's own MAJOR finding: a search term
     * absent from whatever happened to be cached so far had no way to ever reach a real, deeper
     * match, even though [HistoryUiState.Content.endReached] was still `false` and the rest of the
     * archive had never actually been asked. See [loadUntilDecided]'s own kdoc for the walk itself.
     *
     * Still deliberately does NOT cancel/restart [loadJob] directly: an archive fetch already in
     * flight when this is called (e.g. the user had scrolled to the bottom just before typing, or an
     * earlier keystroke's own [continueSearchingArchiveIfNeeded] is already mid-walk) keeps running
     * exactly as it would with no search active — [loadMore]'s own `loadJob?.isActive == true` guard
     * is what actually prevents a second, overlapping walk from this call, not an explicit cancel
     * here. That existing walk is never stale against the NEW [query] either:
     * [loadUntilDecided]'s loop re-reads [effectiveSearchQuery] fresh before every page decision, so
     * the very next page it evaluates already judges itself against whatever was JUST typed — this
     * is also what makes clearing the search mid-walk self-correcting with no cancellation needed
     * (see [shouldAutoContinueSearch]'s own kdoc).
     */
    fun setSearchQuery(query: String) {
        if (query == _searchQuery.value) return
        _searchQuery.value = query
        searchJob?.cancel()
        searchJob = viewModelScope.launch {
            renderFromCache()
            continueSearchingArchiveIfNeeded()
        }
    }

    /**
     * Round-3 review MAJOR (M-1) fix: [renderFromCache] only ever re-reads what THIS filter has
     * ALREADY cached — a freshly-typed search with no match yet in that cached window has nothing
     * further to gain from another local-only re-render. If the state [renderFromCache] just
     * published is exactly that shape (a search-caused empty [HistoryUiState.Content] with more
     * archive left to check), this hands off to [loadMore] — the EXACT SAME entry point
     * scroll-triggered pagination already uses, with the exact same guards (a no-op if a fetch is
     * already in flight, or if [HistoryUiState.Content.endReached] is already true) — so
     * [loadUntilDecided]'s own loop does the actual walking, publishing its own interim "still
     * looking" state as it goes rather than this function driving any of that itself.
     */
    private fun continueSearchingArchiveIfNeeded() {
        val current = _state.value
        if (current is HistoryUiState.Content && current.sections.isEmpty() && effectiveSearchQuery() != null) {
            loadMore()
        }
    }

    /** [HistoryUiState.Error]'s retry CTA re-attempts the first load; a [HistoryUiState.Content]
     * with [HistoryUiState.Content.loadMoreFailed] re-attempts the next page via [loadMore] (which
     * is safe to call here — a failed load leaves `loadingMore=false`/`endReached=false`, so
     * [loadMore]'s own guard does not block it). Any other state: nothing to retry. */
    fun retry() {
        when (val current = _state.value) {
            is HistoryUiState.Error -> loadFirstPage()
            is HistoryUiState.Content -> if (current.loadMoreFailed) loadMore()
            HistoryUiState.LoadingFirst, HistoryUiState.Empty -> Unit
        }
    }

    private fun loadFirstPage() {
        _state.value = HistoryUiState.LoadingFirst
        loadJob = viewModelScope.launch { loadUntilDecided() }
    }

    /**
     * The one loop every load path ([loadFirstPage]/[loadMore]) funnels through. Keeps calling
     * [HistoryPager.loadNext] until it has something definitive to show: a [PageResult.Loaded] page
     * that actually produced at least one VISIBLE (search-applied) row, or a terminal
     * [PageResult.End]/[PageResult.Failed].
     *
     * Round-3 review MAJOR (M-1) fix, superseding this function's own prior design: a year filter's
     * page can legitimately add zero visible rows if this page's whole batch spilled outside the
     * requested year, in which case this always kept walking rather than surfacing a false
     * [HistoryUiState.Empty] while more pages remain untried — that part was always correct and is
     * unchanged. What WAS wrong: a search matching zero of a page's rows used to be treated as
     * definitive too, the instant that page had ANY real (just not search-matching) content —
     * dead-ending the search right there even with [PageResult.End] nowhere in sight. Fixed via
     * [shouldAutoContinueSearch]: when a search is active and this page's own search-applied
     * [visibleItems] read is still empty, this publishes the interim "still looking"
     * [HistoryUiState.Content] (`sections` empty, [HistoryUiState.Content.searchingArchive] `true`)
     * and does NOT return — falls through to this same `while`, which calls [HistoryPager.loadNext]
     * again for the NEXT page, and this same check re-decides fresh against IT. The loop only
     * actually stops, for a search, once a page's [visibleItems] read comes back non-empty (a real
     * match — the `visible.isNotEmpty()` branch below) or a [PageResult.End]/[PageResult.Failed]
     * arrives (this function's other two `when` arms, both unchanged: still gated on
     * [visibleAndUnsearched]'s SEARCH-OBLIVIOUS `unsearchedVisible` half, so a search that never
     * matches anything still correctly lands on [HistoryUiState.Content] with `endReached = true`
     * — or genuine archive-wide [HistoryUiState.Empty] if not even the plain filter, search aside,
     * ever matched anything — rather than a network-retriable [HistoryUiState.Error]).
     *
     * Belt-and-suspenders filter check: [setFilter] already cancels [loadJob] before starting a new
     * one, so an in-flight call here should never outlive a filter change (coroutine cancellation is
     * cooperative and this loop's only suspension point, [HistoryPager.loadNext], checks for it) —
     * the explicit `_filter.value == filter` guard below is a self-documenting invariant, not the
     * actual enforcement mechanism.
     */
    private suspend fun loadUntilDecided() {
        val filter = _filter.value
        while (_filter.value == filter) {
            when (val result = pager.loadNext(filter)) {
                is PageResult.Loaded -> {
                    val searchQuery = effectiveSearchQuery()
                    val visible = visibleItems(filter, searchQuery)
                    if (visible.isNotEmpty()) {
                        // A genuine match — present whether or not a search is active. With no
                        // search active this is also this branch's exact pre-fix behavior
                        // reproduced byte-for-byte: `visibleItems(filter, null)` IS
                        // `visibleAndUnsearched(filter)`'s own `unsearchedVisible` half (see that
                        // function's kdoc), so "visible is non-empty" and the old "unsearchedVisible
                        // is non-empty" checks agree completely once there is nothing to search for.
                        publish(filter, historyContentFrom(visible, endReached = false, loadMoreFailed = false))
                        return
                    }
                    if (shouldAutoContinueSearch(searchActive = searchQuery != null, visibleEmpty = true)) {
                        publish(
                            filter,
                            historyContentFrom(
                                visible, endReached = false, loadMoreFailed = false,
                                loadingMore = true, searchingArchive = true,
                            ),
                        )
                    }
                    // Otherwise: not searching, and nothing in this page fell inside the filter's
                    // year window — keep walking silently, exactly as before this fix.
                }
                PageResult.End -> {
                    val (visible, unsearchedVisible) = visibleAndUnsearched(filter)
                    publish(
                        filter,
                        if (unsearchedVisible.isEmpty()) HistoryUiState.Empty
                        else historyContentFrom(visible, endReached = true, loadMoreFailed = false),
                    )
                    return
                }
                is PageResult.Failed -> {
                    val (visible, unsearchedVisible) = visibleAndUnsearched(filter)
                    publish(
                        filter,
                        if (unsearchedVisible.isEmpty()) HistoryUiState.Error(result.cause)
                        else historyContentFrom(visible, endReached = false, loadMoreFailed = true),
                    )
                    return
                }
            }
        }
    }

    /** Only actually writes [_state] if [filter] is still the active one — a narrow defense
     * against a `setFilter()` slipping in on Main during this loop's last suspension point, between
     * the loop's own `while (_filter.value == filter)` check and this call (cooperative
     * cancellation should already prevent it in practice; this is the belt half of the
     * belt-and-suspenders this function's own kdoc mentions). Also the guard that makes a stale,
     * superseded [renderFromCache] publish (a search re-render that finishes after [setFilter] has
     * already moved on) a safe no-op instead of clobbering the new filter's fresh
     * [HistoryUiState.LoadingFirst].
     */
    private fun publish(filter: HistoryFilter, state: HistoryUiState) {
        if (_filter.value == filter) _state.value = state
    }

    /**
     * User review item 3 (history search): re-derives [_state] purely from whatever's already
     * cached for the active [filter] + [_searchQuery], with no [pager]/network call — the function
     * [setSearchQuery] launches on every keystroke.
     *
     * A no-op while nothing is cached yet to search over: [HistoryUiState.LoadingFirst] has no
     * cache at all (the in-flight first load will itself publish through [historyContentFrom],
     * which reads [_searchQuery] fresh, once it resolves), and [HistoryUiState.Error] has nothing
     * cached either (only [retry] can recover from that state). [HistoryUiState.Content]'s own
     * `loadingMore` is preserved verbatim (not forced back to `false` the way a genuine page-load
     * publish always does) — a search re-render is not a page settling, so it must not make an
     * actually-still-in-flight [loadMore] spinner disappear early. [HistoryUiState.Content.searchingArchive]
     * is preserved the same verbatim way, for the same reason: round-3 review MAJOR (M-1) fix — this
     * function is also the FIRST thing every keystroke runs (see [setSearchQuery]), so if an earlier
     * keystroke's own [continueSearchingArchiveIfNeeded] already has [loadJob] mid-walk when this one
     * fires, that walk's own `searchingArchive = true` must not be clobbered back to `false` here
     * just because this particular local re-render found nothing — [continueSearchingArchiveIfNeeded]
     * (called right after this, from [setSearchQuery]) is what decides whether a NEW walk needs to
     * start, never this function.
     */
    private suspend fun renderFromCache() {
        val filter = _filter.value
        val current = _state.value
        val loadingMore = (current as? HistoryUiState.Content)?.loadingMore ?: false
        val searchingArchive = (current as? HistoryUiState.Content)?.searchingArchive ?: false
        val endReached = when (current) {
            is HistoryUiState.Content -> current.endReached
            HistoryUiState.Empty -> true
            HistoryUiState.LoadingFirst, is HistoryUiState.Error -> return
        }
        val loadMoreFailed = (current as? HistoryUiState.Content)?.loadMoreFailed ?: false
        val visible = visibleItems(filter, effectiveSearchQuery())
        publish(
            filter,
            HistoryUiState.Content(
                sections = groupByMonth(visible, currentTimeMillis()),
                loadingMore = loadingMore,
                endReached = endReached,
                loadMoreFailed = loadMoreFailed,
                searchingArchive = searchingArchive,
            ),
        )
    }

    /**
     * Re-reads the local cache for display. Task 5 fix round 1 (review Critical): the display
     * window is now derived from [HistoryPager]'s own current cursor for [filter] — "everything
     * cached between where this filter's walk has gotten to and its ceiling" — not from a
     * session-local fetch-count tally (the original `loadedCount` field, removed). That tally
     * could desync from the real cache in three ways a review caught: revisiting a filter
     * mid-session (`setFilter` zeroed it unconditionally, even though [HistoryPager]'s own
     * per-filter cursor was untouched), an app restart (a fresh tally starts at 0 while the cursor
     * correctly resumes from persisted meta), and worst, a restart while offline (the tally stays 0
     * because the fetch itself fails, so the old `pageBefore(..., limit = 0, ...)` was
     * unconditionally empty — a fully-cached archive rendered as a blank `Error`/`Empty`,
     * contradicting the "cached pages browse offline" contract). A cursor-derived range has no
     * separate state to desync: whatever [HistoryPager.currentCursor] answers — freshly advanced
     * this session, resumed from a persisted meta row, or simply untouched by an unrelated fetch
     * failure — IS the true lower bound of what this filter has ever cached.
     *
     * SQL-bounded by the year's ceiling on the way in (excludes newer, unrelated rows — e.g. Home's
     * own always-running 24h feed poll writes into this SAME `quake` table, so a year=2025 filter
     * must not let today's 2026 arrivals leak in — bounding by
     * [HistoryFilter.yearCeilingMillisExclusiveOrNull] in the SQL itself, not after the fact, is
     * what prevents that), then client-side floor-filtered against [HistoryFilter.yearFloorMillisOrNull]
     * (the "client-side floor filter" the plan calls for — [QuakeRepository.pageBetween] has no
     * lower-CEILING-side bound of its own for this; the floor check is still needed on top of the
     * cursor-as-lower-bound, separately, because a year-filtered walk's LAST page can legitimately
     * spill below the floor — see [HistoryPager]'s own kdoc — leaving the cursor itself sitting
     * below the floor after that spill).
     *
     * See [QuakeRepository.pageBetween]'s own kdoc for the cross-filter cache-bleed semantics this
     * range read carries (rows shown are always correct, but can be broader than what this exact
     * filter's own walk fetched) and the accepted no-`LIMIT` performance tradeoff.
     *
     * User review item 3: [placeQuery] threads straight through to [QuakeRepository.pageBetween]'s
     * own new optional predicate — `null` here reproduces this function's exact pre-search
     * behavior, byte-for-byte (every pre-existing caller of the ORIGINAL single-arg [visibleItems]
     * effectively passed `null` implicitly; every one of them is preserved via
     * [visibleAndUnsearched]'s own "search-oblivious" half).
     */
    private suspend fun visibleItems(filter: HistoryFilter, placeQuery: String?): List<Quake> {
        val ceilingExclusive = filter.yearCeilingMillisExclusiveOrNull() ?: Long.MAX_VALUE
        val lowerInclusive = pager.currentCursor(filter)
        val rows = repository.pageBetween(lowerInclusive, ceilingExclusive, filter.minMag, placeQuery)
        val floor = filter.yearFloorMillisOrNull()
        return if (floor != null) rows.filter { it.timeMillis >= floor } else rows
    }

    /** [_searchQuery]'s raw field contents, trimmed and collapsed to `null` when blank — the shape
     * [visibleItems]'/[QuakeRepository.pageBetween]'s own `placeQuery IS NULL` branch expects, and
     * the single place this trim/blank-collapse decision is made (both [loadUntilDecided] — via
     * [visibleAndUnsearched] — and [renderFromCache] funnel through here, so the two can never
     * apply search text inconsistently). */
    private fun effectiveSearchQuery(): String? = _searchQuery.value.trim().ifBlank { null }

    /**
     * User review item 3: returns BOTH the search-applied display rows ([Pair.first]) and the
     * search-OBLIVIOUS rows [loadUntilDecided]'s own [PageResult.End]/[PageResult.Failed] handling
     * uses to tell "the plain filter itself never matched anything, archive-wide" apart from "the
     * filter matched something, just not this active search" — still needed there (unchanged by the
     * round-3 M-1 fix: only [loadUntilDecided]'s [PageResult.Loaded] arm stopped needing this pair,
     * since [shouldAutoContinueSearch] decides that one off [effectiveSearchQuery] and `visible`
     * alone — see that function's own kdoc). This function itself still never touches the network
     * either way (a plain local DB read, same as [visibleItems] alone); it's [loadMore]/
     * [continueSearchingArchiveIfNeeded] that may go on to actually fetch more, not this. When no
     * search is active ([effectiveSearchQuery] is `null`) the two halves are the exact SAME single DB
     * read, reused — this function costs nothing extra over the pre-search version of this class for
     * the common case (nobody has touched the search box); only a genuinely active search pays for
     * the second, still-local (no network) read.
     */
    private suspend fun visibleAndUnsearched(filter: HistoryFilter): Pair<List<Quake>, List<Quake>> {
        val query = effectiveSearchQuery()
        val visible = visibleItems(filter, query)
        val unsearchedVisible = if (query == null) visible else visibleItems(filter, placeQuery = null)
        return visible to unsearchedVisible
    }

    private fun historyContentFrom(
        visible: List<Quake>,
        endReached: Boolean,
        loadMoreFailed: Boolean,
        loadingMore: Boolean = false,
        searchingArchive: Boolean = false,
    ) = HistoryUiState.Content(
        sections = groupByMonth(visible, currentTimeMillis()),
        loadingMore = loadingMore,
        endReached = endReached,
        loadMoreFailed = loadMoreFailed,
        searchingArchive = searchingArchive,
    )
}

/**
 * Round-3 review MAJOR (M-1)'s fix, reduced to its own pure decision — pulled out of
 * [HistoryViewModel.loadUntilDecided] specifically so it is directly unit-testable with no
 * ViewModel/MockEngine/coroutine harness. Mirrors the review's own framing of the fix ("auto-continue
 * paging while `searchActive && visibleEmpty && !endReached && !inFlight`") for the two conjuncts
 * that are genuine per-call unknowns; the other two hold structurally at [HistoryViewModel.loadUntilDecided]'s
 * one call site and need no runtime check here:
 * - `!endReached` — this is only ever consulted from the [PageResult.Loaded] arm of that function's
 *   `when`; [PageResult.End] is a separate arm entirely, never reachable at the same time.
 * - `!inFlight` — [HistoryViewModel.loadUntilDecided] runs its ENTIRE walk on one sequential
 *   coroutine; [HistoryPager.loadNext] is awaited to completion before this predicate is ever
 *   consulted, so there is never a second, overlapping fetch for this call to race against.
 *
 * `searchActive` is read fresh (via [HistoryViewModel.effectiveSearchQuery]) before every single page
 * decision, never captured once at the top of the walk — which is also what makes clearing the search
 * mid-walk self-correcting with no explicit cancellation needed: the very next page this predicate is
 * consulted for already sees `searchActive = false`, so [HistoryViewModel.loadUntilDecided]'s loop
 * naturally stops treating a real, non-search-matching page as "keep walking" and instead publishes it
 * as the genuine match it now is (see [HistoryViewModel.loadUntilDecided]'s own `visible.isNotEmpty()`
 * branch).
 */
internal fun shouldAutoContinueSearch(searchActive: Boolean, visibleEmpty: Boolean): Boolean =
    searchActive && visibleEmpty
