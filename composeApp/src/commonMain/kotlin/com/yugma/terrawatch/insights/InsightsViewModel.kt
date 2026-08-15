package com.yugma.terrawatch.insights

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.yugma.terrawatch.data.QuakeRepository
import com.yugma.terrawatch.database.BandCount
import com.yugma.terrawatch.database.DayCount
import com.yugma.terrawatch.model.MagnitudeBand
import com.yugma.terrawatch.model.Quake
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch

/** One UTC calendar day, in millis - shared by [InsightsViewModel]'s own bucket math and
 * `InsightsScreen`'s bar-chart baseline date labels (same package, no import needed - kept
 * `internal`, not `private`, purely so the screen can reuse it without redefining the same magic
 * number a second time). */
internal const val DAY_MILLIS = 86_400_000L

/**
 * The two periods Insights supports (plan Task 6 brief: "period StateFlow (SEVEN_DAYS/
 * THIRTY_DAYS)" - the approved mockup's third "1y" segment is NOT part of this task's own pinned
 * interface, so it is deliberately not offered here; a future task can add it as a third enum
 * entry without touching anything else in this file). [days] drives both the DAO query's own
 * `sinceMillis` (via [InsightsViewModel]'s bucket math) AND the exact bar count `BarChart` renders
 * - the two can never drift apart since both are derived from this one field.
 */
enum class InsightsPeriod(val days: Long, val shortLabel: String, val label: String) {
    SEVEN_DAYS(7, "7d", "7 DAYS"),
    THIRTY_DAYS(30, "30d", "30 DAYS"),
}

sealed interface InsightsUiState {
    data object Loading : InsightsUiState

    data class Content(
        val dayCounts: List<Long>,
        val bands: List<Pair<MagnitudeBand, Long>>,
        val strongest: Quake?,
        val periodLabel: String,
        // Fix round (review I1): the UTC epoch-day bucket `computeContent` used as "today" when it
        // built `dayCounts` - NOT re-derivable from a live clock at render time. `InsightsScreen`
        // used to do exactly that (re-read its own live-ticking `nowMillis` to compute a baseline
        // label's end date), which drifts a day off from this value the instant a UTC midnight
        // passes between a recompute and a later render with no recompute in between - a real bug,
        // not a hypothetical one, since this class deliberately does NOT recompute on a timer (only
        // on a period flip or a `recentQuakes` invalidation tick). `InsightsScreen.dayCountLabels`
        // now derives its baseline dates from THIS value alone, never from its own ticker. Widens
        // `Content` beyond the plan brief's originally-pinned 4-field shape
        // (dayCounts/bands/strongest/periodLabel) - a controller-approved fix-round addition.
        val nowBucketAtCompute: Long,
        // Plan 4 Task 5 (Insights density backfill): null unless [worldwideCountIfThin] actually
        // fetched (or served a fresh-enough cached) FDSN total - see that function's own kdoc for
        // the exact gate (THIRTY_DAYS period AND cachedCount < 100). `InsightsScreen.densityCaption`
        // is the pure function that turns this into the visible "N cached · M worldwide" text -
        // null here simply means no caption, never an error state of its own.
        val worldwideCount: Long? = null,
    ) : InsightsUiState

    /** The current period's window has zero quakes at all (`bands.sumOf { it.second } == 0L`,
     * equivalently `dayCounts.sum() == 0L` - both count every row in-window, just bucketed
     * differently). Distinct from a merely-quiet period with a FEW quakes, which is ordinary
     * [Content] (all-zero bars/bands except the small real numbers). */
    data object Empty : InsightsUiState

    /** Unlikely in practice - every read behind this state is a local, offline SQLite query with
     * no network involved (plan brief: "Error unlikely [pure DB] but keep shape") - kept for the
     * same reason [com.yugma.terrawatch.history.HistoryUiState.Error] exists: the plan's global
     * four-states rule applies to every new screen, this one included, and a local DB CAN still
     * fail (disk full, corrupt file) even if it practically never does. */
    data class Error(val cause: Throwable) : InsightsUiState
}

/**
 * Pure gap-fill for [InsightsViewModel]'s "quakes per day" bar chart: [counts] is
 * [QuakeDao.quakesPerDay]'s sparse result (one entry per bucket that has >=1 quake - see that
 * function's own kdoc), [sinceBucket]/[nowBucket] the inclusive UTC epoch-day range the caller
 * actually asked for. Returns exactly `nowBucket - sinceBucket + 1` entries, in ascending bucket
 * order, `0L` for every bucket [counts] doesn't mention - so `BarChart` always renders one bar per
 * calendar day in the period, zero-quake days included, rather than a chart that silently
 * compresses when a quiet stretch has no data to contribute a bar for.
 */
fun fillDayGaps(counts: List<DayCount>, sinceBucket: Long, nowBucket: Long): List<Long> {
    if (nowBucket < sinceBucket) return emptyList()
    val byBucket = counts.associate { it.dayBucket to it.n }
    return (sinceBucket..nowBucket).map { bucket -> byBucket[bucket] ?: 0L }
}

// The four bands users actually care about for "how big were this period's quakes" always show,
// zero-count included - a distribution that silently omits MAJOR because nothing that big happened
// reads as missing data, not as reassuring news. UNKNOWN is deliberately NOT in this fixed list -
// see fillBandGaps's own kdoc.
private val ALWAYS_SHOWN_BANDS = listOf(MagnitudeBand.LOW, MagnitudeBand.MODERATE, MagnitudeBand.STRONG, MagnitudeBand.MAJOR)

/**
 * Pure gap-fill for [InsightsViewModel]'s "by magnitude" distribution: [counts] is
 * [QuakeDao.bandDistribution]'s sparse result. Always returns [ALWAYS_SHOWN_BANDS] in that fixed
 * order (zero-filled if a band had no matches); [MagnitudeBand.UNKNOWN] is appended ONLY when its
 * own count is nonzero - most periods have zero null-magnitude quakes, and a permanent "Unknown: 0"
 * row would just be visual noise for a case that will almost never actually happen.
 */
fun fillBandGaps(counts: List<BandCount>): List<Pair<MagnitudeBand, Long>> {
    val byBand = counts.associate { it.band to it.n }
    val shown = ALWAYS_SHOWN_BANDS + listOfNotNull(MagnitudeBand.UNKNOWN.takeIf { (byBand[it] ?: 0L) > 0L })
    return shown.map { it to (byBand[it] ?: 0L) }
}

/**
 * Task 6 (Plan 3): the Insights screen's ViewModel - offline-pure for its three core cards (every
 * read behind [dayCounts]/[bands]/[strongest] is a local SQLite aggregate query through
 * [repository], zero network calls anywhere in producing them; airplane mode changes nothing about
 * those three cards, still the "offline mode still renders" proof screen the plan brief calls out).
 *
 * Plan 4 Task 5 (Insights density backfill) is the ONE deliberate, narrow exception, added on top
 * rather than woven through: [worldwideCountIfThin] optionally makes a single, 6h-cached, best-effort
 * FDSN `/count` call (via [repository]'s own [QuakeRepository.worldwideCount]/
 * [QuakeRepository.worldwideCountCache] - never a direct network dependency of this class) to
 * populate [InsightsUiState.Content.worldwideCount] with a "N cached · M worldwide" disclosure
 * caption. It is structurally incapable of blocking, gating, or replacing the three core cards:
 * [computeContent] always computes [dayCounts]/[bands]/[strongest] first and unconditionally, and
 * [worldwideCountIfThin] itself just returns null on ANY failure (see its own kdoc) - the caption
 * silently absent is the only possible symptom of a GDELT/FDSN outage, never a broken Insights tab.
 * See [InsightsNewsViewModel] for the SEPARATE, sibling "In the news" card - deliberately NOT folded
 * in here at all, network-touching from the start, for exactly the reason this class stays this
 * narrowly amended rather than absorbing a second, unrelated network feature too.
 *
 * Two independent triggers recompute [state], each with different Loading semantics - the
 * distinction is deliberate, not an oversight:
 *  - [setPeriod] (a user tapping the 7d/30d toggle) always flashes [InsightsUiState.Loading] on
 *    the way to the new period's [InsightsUiState.Content] - same "flip -> Loading -> new Content"
 *    contract `com.yugma.terrawatch.history.HistoryViewModel.setFilter` already established for
 *    this codebase's other filter-like control, even though the underlying read is fast.
 *  - a [QuakeRepository.recentQuakes] emission (new data landed in the `quake` table - poll tick
 *    or live WebSocket arrival, used here purely as a cheap "something changed" invalidation
 *    signal, its own payload is discarded) recomputes QUIETLY, with no interstitial Loading -
 *    mirrors `com.yugma.terrawatch.home.HomeViewModel`'s own poll-tick behavior, where new data
 *    updates Content's numbers in place rather than re-showing a skeleton after the first
 *    successful load.
 *
 * Both triggers funnel through the same [computeGeneration] fence [com.yugma.terrawatch.home.
 * HomeViewModel.refreshGeneration] already established: whichever trigger STARTED most recently
 * is the only one allowed to actually write [state] once its (possibly-not-last-to-finish) read
 * resolves, so a live quake arriving mid-period-flip can never stomp the flip's fresher result
 * with a stale one (or vice versa).
 */
class InsightsViewModel(
    private val repository: QuakeRepository,
    private val clock: () -> Long,
) : ViewModel() {
    private val _period = MutableStateFlow(InsightsPeriod.SEVEN_DAYS)
    val period: StateFlow<InsightsPeriod> = _period

    private val _state = MutableStateFlow<InsightsUiState>(InsightsUiState.Loading)
    val state: StateFlow<InsightsUiState> = _state

    // Main-confined `var`, same "only ever mutated from Main, so no concurrent-mutation hazard"
    // reasoning as HomeViewModel's own refreshGeneration - both writers below (the period
    // collector and the recentQuakes collector) increment it synchronously, before either ever
    // suspends, so there is no race on the increment itself, only on which write actually lands.
    private var computeGeneration = 0L

    init {
        // User-driven: every period change gets its own Loading -> Content/Empty/Error cycle.
        viewModelScope.launch {
            _period.collect { period ->
                val gen = ++computeGeneration
                _state.value = InsightsUiState.Loading
                publish(period, gen)
            }
        }
        // Background-driven: a change to the `quake` table (poll tick, live arrival, anything else
        // that writes through QuakeRepository.ingest) recomputes the CURRENT period silently. Drops
        // the first (subscribe-time "current state") emission - only genuine subsequent changes
        // should trigger a recompute here; the very first load is already covered by the collector
        // above (backed by `_period`'s own initial value).
        //
        // Fix round (review I2): `.conflate()` - SQLDelight's own table-changed notification is
        // table-level, not row-level (see `QuakeDao.replaceAndDelete`'s own kdoc), so a single batched
        // ingest of N quakes (a typical poll tick landing several new events at once) fires N
        // separate "quake table changed" notifications, one per `replaceAndDelete` transaction
        // commit - without this, that is N redundant recomputes queued back-to-back for data this
        // collector only ever reduces to "something changed, recompute once." `.conflate()` keeps
        // only the latest pending signal once a `publish()` call is already in flight, collapsing
        // an N-row batch down to (at most) one recompute after the busy period ends, instead of N.
        viewModelScope.launch {
            repository.recentQuakes().drop(1).conflate().collect {
                val gen = ++computeGeneration
                publish(_period.value, gen)
            }
        }
    }

    fun setPeriod(newPeriod: InsightsPeriod) {
        _period.value = newPeriod
    }

    /** [InsightsUiState.Error]'s Retry CTA (plan's global four-states constraint: "Error (with
     * Retry)" on every new screen) - re-attempts the CURRENT period, with the same user-driven
     * Loading flash [setPeriod] gets (a retry is every bit as much a deliberate user action). */
    fun retry() {
        val gen = ++computeGeneration
        viewModelScope.launch {
            _state.value = InsightsUiState.Loading
            publish(_period.value, gen)
        }
    }

    private suspend fun publish(period: InsightsPeriod, gen: Long) {
        val result = runCatching { computeContent(period) }
        if (gen != computeGeneration) return // superseded by a newer trigger - drop this stale result
        _state.value = result.fold(onSuccess = { it }, onFailure = { InsightsUiState.Error(it) })
    }

    private suspend fun computeContent(period: InsightsPeriod): InsightsUiState {
        val nowBucket = clock() / DAY_MILLIS
        val sinceBucket = nowBucket - (period.days - 1)
        val sinceMillis = sinceBucket * DAY_MILLIS
        val dayCounts = fillDayGaps(repository.quakesPerDay(sinceMillis), sinceBucket, nowBucket)
        val bands = fillBandGaps(repository.bandDistribution(sinceMillis))
        return if (bands.sumOf { it.second } == 0L) {
            InsightsUiState.Empty
        } else {
            InsightsUiState.Content(
                dayCounts = dayCounts,
                bands = bands,
                strongest = repository.strongest(sinceMillis),
                periodLabel = period.label,
                nowBucketAtCompute = nowBucket,
                worldwideCount = worldwideCountIfThin(period, cachedCount = dayCounts.sum(), sinceMillis = sinceMillis, nowMillis = clock()),
            )
        }
    }

    /**
     * Plan 4 Task 5 (Insights density backfill): null in every case except "the user is looking at
     * the 30-day chart AND the local cache in that window looks thin (< [DENSITY_TRIGGER_THRESHOLD]
     * rows)" - the brief's own trigger condition, gating BOTH whether this ever calls the network at
     * all (a healthy cache needs no disclosure) and, since that is the only condition under which a
     * value is ever populated, whether [InsightsScreen.densityCaption] ever has anything to show.
     *
     * 6h cache via [QuakeRepository.worldwideCountCache]/[QuakeRepository.setWorldwideCountCache]
     * ("cached in meta 6h" per the brief) - a fresh-enough cached value is returned WITHOUT a
     * network call; a stale-or-absent cache triggers exactly one [QuakeRepository.worldwideCount]
     * call. On that call's failure, falls back to whatever cached value exists (even if stale)
     * rather than dropping straight to null - a slightly-stale global count is a more honest
     * disclosure than none at all when the network happens to be down right this instant, same
     * "the cache stays browsable" spirit this app already applies to quake data itself.
     */
    private suspend fun worldwideCountIfThin(period: InsightsPeriod, cachedCount: Long, sinceMillis: Long, nowMillis: Long): Long? {
        if (period != InsightsPeriod.THIRTY_DAYS || cachedCount >= DENSITY_TRIGGER_THRESHOLD) return null
        val cached = repository.worldwideCountCache()
        if (cached != null && nowMillis - cached.fetchedAtMillis < DENSITY_CACHE_TTL_MILLIS) return cached.count
        val fetched = repository.worldwideCount(startTimeMillis = sinceMillis, endTimeMillis = nowMillis)
        if (fetched == null) return cached?.count
        repository.setWorldwideCountCache(fetched, nowMillis)
        return fetched
    }

    private companion object {
        const val DENSITY_TRIGGER_THRESHOLD = 100L
        const val DENSITY_CACHE_TTL_MILLIS = 6 * 60 * 60 * 1000L
    }
}
