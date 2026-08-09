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
 * whatever order [groupByMonth]'s input arrived in (time-descending, per [QuakeRepository.pageBefore]). */
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
    ) : HistoryUiState

    /** The archive is exhausted (or the current filter's year floor was already crossed) and not
     * one visible row matched the current filter. Distinct from [Content] with empty sections
     * mid-walk — that transient shape never reaches [HistoryViewModel.state] (see [loadUntilDecided]). */
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

    private val _state = MutableStateFlow<HistoryUiState>(HistoryUiState.LoadingFirst)
    val state: StateFlow<HistoryUiState> = _state

    // How many rows THIS filter's walk has ingested so far (across every successful PageResult.Loaded
    // since the last setFilter reset) — the `limit` for [visibleItems]' own re-query of the cache,
    // so the display always tries to show "everything fetched so far for this filter", not an
    // arbitrary fixed page size.
    private var loadedCount = 0

    // Guards against overlapping loadMore()/loadFirstPage() launches — same "track the one in-flight
    // Job, cancel before replacing it" shape as HomeViewModel's own retryJob/QuakeSelectionViewModel's
    // selectJob.
    private var loadJob: Job? = null

    init {
        loadFirstPage()
    }

    /**
     * Infinite-scroll's own trigger (`HistoryScreen`'s "last-visible index" check calls this). A
     * no-op while a load is already in flight, or once [HistoryUiState.Content.endReached] is true
     * — safe to call redundantly (the screen's scroll-position trigger does exactly that), so it
     * costs the caller nothing to over-call this rather than hand-roll its own extra guard.
     */
    fun loadMore() {
        val current = _state.value
        val blocked = when (current) {
            is HistoryUiState.Content -> current.loadingMore || current.endReached
            HistoryUiState.LoadingFirst -> true // the initial load already covers this "page"
            HistoryUiState.Empty, is HistoryUiState.Error -> true // nothing to page from — use retry()/setFilter()
        }
        if (blocked || loadJob?.isActive == true) return
        if (current is HistoryUiState.Content) _state.value = current.copy(loadingMore = true)
        loadJob = viewModelScope.launch { loadUntilDecided() }
    }

    /** Chip taps (`HistoryScreen`'s filter rows) call this with a `copy()` of the current [filter].
     * A no-op filter (same value) changes nothing — no reset, no re-fetch. */
    fun setFilter(newFilter: HistoryFilter) {
        if (newFilter == _filter.value) return
        loadJob?.cancel()
        _filter.value = newFilter
        loadedCount = 0
        loadFirstPage()
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
     * that actually produced at least one VISIBLE row (see [visibleItems] — a year filter's page can
     * legitimately add zero visible rows if this page's whole batch spilled outside the requested
     * year, in which case this keeps walking rather than surfacing a false [HistoryUiState.Empty]
     * while more pages remain untried), or a terminal [PageResult.End]/[PageResult.Failed].
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
                    loadedCount += result.count
                    val visible = visibleItems(filter)
                    if (visible.isNotEmpty()) {
                        publish(filter, historyContent(visible, endReached = false, loadMoreFailed = false))
                        return
                    }
                    // Nothing in this page fell inside the filter's year window — keep walking.
                }
                PageResult.End -> {
                    val visible = visibleItems(filter)
                    publish(filter, if (visible.isEmpty()) HistoryUiState.Empty else historyContent(visible, endReached = true, loadMoreFailed = false))
                    return
                }
                is PageResult.Failed -> {
                    val visible = visibleItems(filter)
                    publish(
                        filter,
                        if (visible.isEmpty()) HistoryUiState.Error(result.cause)
                        else historyContent(visible, endReached = false, loadMoreFailed = true),
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
     * belt-and-suspenders this function's own kdoc mentions). */
    private fun publish(filter: HistoryFilter, state: HistoryUiState) {
        if (_filter.value == filter) _state.value = state
    }

    /**
     * Re-reads the local cache for display: everything [loadedCount] rows deep, matching
     * [HistoryFilter.minMag], SQL-bounded by the year's ceiling (excludes newer, un-related rows —
     * e.g. Home's own always-running 24h feed poll writes into this SAME `quake` table, so a
     * year=2025 filter must not let today's 2026 arrivals leak into "top N most recent" — bounding
     * by [HistoryFilter.yearCeilingMillisExclusiveOrNull] in the SQL itself, not after the fact,
     * is what prevents that), then client-side floor-filtered against
     * [HistoryFilter.yearFloorMillisOrNull] (the "client-side floor filter" the plan calls for —
     * [QuakeRepository.pageBefore] has no lower-bound parameter to push this into SQL too).
     */
    private suspend fun visibleItems(filter: HistoryFilter): List<Quake> {
        val ceilingExclusive = filter.yearCeilingMillisExclusiveOrNull() ?: Long.MAX_VALUE
        val rows = repository.pageBefore(ceilingExclusive, loadedCount, filter.minMag)
        val floor = filter.yearFloorMillisOrNull()
        return if (floor != null) rows.filter { it.timeMillis >= floor } else rows
    }

    private fun historyContent(visible: List<Quake>, endReached: Boolean, loadMoreFailed: Boolean) =
        HistoryUiState.Content(
            sections = groupByMonth(visible, currentTimeMillis()),
            loadingMore = false,
            endReached = endReached,
            loadMoreFailed = loadMoreFailed,
        )
}
