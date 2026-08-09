package com.yugma.terrawatch.data

import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import kotlin.time.ExperimentalTime

/**
 * History's archive filter — magnitude and year, independently combinable (spec §3.4's filter-chip
 * row and year-chip row are two separate, simultaneously-applicable controls).
 *
 * Deviation, documented: the plan's own drafted interface
 * (`docs/superpowers/plans/2026-08-09-terrawatch-plan-3-screens.md` Task 5) sketched
 * `yearRange: IntRange?`; this task's actual dispatch pinned a single `year: Int?` instead, and the
 * shipped UI is single-select year CHIPS ("2026 / 2025 / All" — one tap, one year, never a span),
 * so a nullable `Int` is the honest shape for what a chip tap actually produces. Recorded here per
 * this plan's own "document a deviation with its reason" convention (see e.g. Task 4's
 * navigation-compose version pick).
 */
data class HistoryFilter(val minMag: Double? = null, val year: Int? = null)

/**
 * [HistoryPager.loadNext]'s outcome — turns [QuakeRepository.loadArchivePage]'s "throws by design"
 * contract into a plain value every caller can `when` over. Sealed on three cases, not
 * [com.yugma.terrawatch.network.FeedResult]'s two-success-plus-failure shape: archive paging has a
 * genuine terminal state ([End] — nothing more exists for this filter) that a live poll never has.
 */
sealed interface PageResult {
    /** [count] rows were fetched AND ingested this call. Never zero — see [End]. */
    data class Loaded(val count: Int) : PageResult

    data class Failed(val cause: Throwable) : PageResult

    /**
     * Nothing more to page for this [HistoryFilter] — either the archive itself is exhausted (a
     * 0-row page came back) or, for a year-filtered walk, paging has already crossed below that
     * year's Jan-1 floor. Terminal for this filter: every subsequent [HistoryPager.loadNext] call
     * with the same [HistoryFilter] short-circuits straight back to this without another network
     * round trip (see [HistoryPager]'s own kdoc).
     */
    data object End : PageResult
}

/**
 * Task 5 (Plan 3): wraps [QuakeRepository.loadArchivePage] — documented to throw by design ("the
 * History feature's caller wraps", see that function's own kdoc) — behind [PageResult], so
 * `HistoryViewModel` never has to catch anything itself. Tracks an independent paging cursor per
 * [HistoryFilter]: flipping from, say, "M6+/2026" to "All/2025" starts its own fresh walk backward
 * through time rather than resuming wherever the previous filter's cursor had gotten to (see the
 * "filter isolation" test).
 *
 * Cursor storage is two-tier: an in-memory [Map] for same-session speed (every [loadNext] after the
 * first, for a filter already touched this session, needs no DB round trip to find its cursor),
 * backed by [QuakeRepository]'s `historyCursor`/`setHistoryCursor` meta-table pass-throughs so a
 * filter's progress survives this ViewModel/session ending — reopening History on "M6+/All"
 * tomorrow resumes from today's oldest-loaded point instead of re-walking pages already seen (see
 * the "persistence roundtrip" test). [clock] is this class's only platform-time seam (mirrors
 * [QuakeRepository]'s own injected `clock` constructor param) — used solely to seed a year-LESS
 * filter's very first cursor at "now".
 *
 * A [HistoryFilter.year] never reaches the network directly: [QuakeRepository.loadArchivePage] has
 * no start-time/year parameter, only `beforeMillis`/`minMag` (see its own kdoc) — so a year filter
 * instead (a) seeds its FIRST cursor at that year's Dec 31 23:59:59.999 UTC
 * (`yearCeilingMillisExclusiveOrNull() - 1`), so the very first page starts exactly at the
 * requested year's end, and (b) refuses to even attempt a [QuakeRepository.loadArchivePage] call
 * once the cursor has already fallen below that year's Jan 1 00:00:00 UTC floor
 * ([HistoryFilter.yearFloorMillisOrNull]). This IS the "client-side floor filter (stop at Jan 1)"
 * the plan calls for: the FDSN endpoint itself has no start-time bound to ask for, so this class is
 * what actually enforces one, by simply declining to page any further south of it. A page that
 * straddles the boundary (fewer than the fetch `limit` events exist in-year, so this same page's
 * tail spills into the prior year) still reports [PageResult.Loaded] honestly — the spill rows land
 * in the DB like any other archive row; `HistoryViewModel`'s own display query is what actually
 * excludes them from what the user SEES for a year-filtered view. This class's job stops at "don't
 * keep asking the network for more."
 */
class HistoryPager(
    private val repository: QuakeRepository,
    private val clock: () -> Long,
) {
    // Same-session fast path — see this class's own kdoc. Keyed by the whole HistoryFilter value
    // (a data class, so two equal filters anywhere in the app share one cursor entry by design).
    private val cursors = mutableMapOf<HistoryFilter, Long>()

    suspend fun loadNext(filter: HistoryFilter): PageResult {
        val cursor = cursorFor(filter)
        val floor = filter.yearFloorMillisOrNull()
        if (floor != null && cursor < floor) return PageResult.End

        return runCatching { repository.loadArchivePage(beforeMillis = cursor, minMag = filter.minMag) }
            .fold(
                onSuccess = { page ->
                    if (page.isEmpty()) {
                        PageResult.End
                    } else {
                        // The batch's own minimum, not a DB re-query — see loadArchivePage's kdoc
                        // for why the raw response is the only unambiguous source for this.
                        setCursor(filter, page.minOf { it.timeMillis })
                        PageResult.Loaded(page.size)
                    }
                },
                onFailure = { PageResult.Failed(it) },
            )
    }

    private suspend fun cursorFor(filter: HistoryFilter): Long =
        cursors[filter] ?: resolveInitialCursor(filter).also { cursors[filter] = it }

    private suspend fun resolveInitialCursor(filter: HistoryFilter): Long =
        repository.historyCursor(filter.stableKey()) ?: initialCursor(filter)

    private suspend fun setCursor(filter: HistoryFilter, value: Long) {
        cursors[filter] = value
        repository.setHistoryCursor(filter.stableKey(), value)
    }

    private fun initialCursor(filter: HistoryFilter): Long =
        filter.yearCeilingMillisExclusiveOrNull()?.minus(1) ?: clock()

    // A descriptive, deterministic string key — not a numeric .hashCode() (the plan's own literal
    // "history_cursor_<filterhash>" wording) — deliberately: Kotlin's compiler-generated data class
    // hashCode is a pure function of each property's own hashCode on any ONE platform, but this is
    // a Kotlin Multiplatform module (android/jvm/wasmJs); a hand-formatted string built directly
    // from the two field values is unambiguously stable across all three without relying on
    // Double/Int.hashCode() cross-platform parity, and is human-readable in a debug DB dump for
    // free.
    private fun HistoryFilter.stableKey(): String = "min${minMag ?: "x"}_yr${year ?: "x"}"
}

/**
 * Inclusive lower bound, in UTC millis, of [HistoryFilter.year] — null when no year filter is set
 * (meaning "no lower bound"). Public: both [HistoryPager]'s own paging-floor check and composeApp's
 * `HistoryViewModel` (re-querying the local cache for display) need the exact same "what does this
 * year mean, in millis" definition, defined once here rather than risking two modules' own copies
 * drifting apart.
 */
fun HistoryFilter.yearFloorMillisOrNull(): Long? = year?.let { startOfYearUtcMillis(it) }

/**
 * Exclusive upper bound, in UTC millis, of [HistoryFilter.year] — the start of the FOLLOWING year,
 * not "Dec 31 23:59:59" inclusive, so it composes directly with [com.yugma.terrawatch.database.QuakeDao.pageBefore]'s
 * own `timeMillis <` semantics with no off-by-one at the yyyy-12-31T23:59:59.999 boundary. Null
 * when no year filter is set.
 */
fun HistoryFilter.yearCeilingMillisExclusiveOrNull(): Long? = year?.let { startOfYearUtcMillis(it + 1) }

@OptIn(ExperimentalTime::class)
private fun startOfYearUtcMillis(year: Int): Long =
    LocalDateTime(year, 1, 1, 0, 0, 0).toInstant(TimeZone.UTC).toEpochMilliseconds()
