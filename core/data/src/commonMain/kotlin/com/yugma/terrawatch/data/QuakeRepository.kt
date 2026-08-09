package com.yugma.terrawatch.data

import com.yugma.terrawatch.database.QuakeDao
import com.yugma.terrawatch.model.GeoPoint
import com.yugma.terrawatch.model.Quake
import com.yugma.terrawatch.network.EmscLiveSource
import com.yugma.terrawatch.network.FeedResult
import com.yugma.terrawatch.network.UsgsApi
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

enum class RefreshStatus { UPDATED, NOT_MODIFIED, FAILED }

/**
 * The single ingest path for every quake source (poll, live WebSocket, archive backfill).
 * Every write funnels through [ingest], which window-queries the DAO for a same-event
 * candidate, hands it to [DedupeEngine] for reconciliation, persists the reconciled
 * canonical, and evaluates [AlertRuleEngine] against the previous/current pair.
 */
class QuakeRepository(
    private val api: UsgsApi,
    private val live: EmscLiveSource,
    private val dao: QuakeDao,
    private val dedupe: DedupeEngine = DedupeEngine(),
    private val alerts: AlertRuleEngine = AlertRuleEngine(),
    private val clock: () -> Long,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.Default,
) {
    private val _alertEvents = MutableSharedFlow<AlertEvent>(extraBufferCapacity = 16)
    val alertEvents: SharedFlow<AlertEvent> = _alertEvents

    // Task 8: fires the canonical id exactly when ingest() resolves a genuinely-new quake
    // (previous == null below) — never on an update/revision to an already-stored row. Home's map
    // (via HomeViewModel) uses this to drive the pin-drop animation (Task 10), which must not
    // replay every time an already-seen quake merely gets a magnitude revision.
    private val _insertedQuakeIds = MutableSharedFlow<String>(extraBufferCapacity = 16)
    val insertedQuakeIds: SharedFlow<String> = _insertedQuakeIds

    // Task 10: truthful LIVE indicator — HomeUiState.isLive binds to this instead of the old
    // "startLive() was called" placeholder (the Task 10 TODO from Plan 1 dies here). Pure
    // pass-through: EmscLiveSource is the only thing that actually knows whether a WebSocket
    // session is open right now, so this repository has no logic of its own to add on top.
    val liveConnected: StateFlow<Boolean> get() = live.connected

    // Guards ingest()'s read-reconcile-write critical section: window-query, dedupe match, and
    // the replaceAndDelete() commit must run as one atomic unit across concurrent callers (poll,
    // live WebSocket, archive backfill can all be ingesting at once). Without this, two
    // concurrent ingests of the same event's twin variants can each read the pre-merge window,
    // independently decide "no match yet", and both insert — leaving duplicate rows for what
    // DedupeEngine would have reconciled into one.
    private val ingestMutex = Mutex()

    /**
     * [windowMs] before "now" is computed once, at call time — the returned Flow re-queries
     * against that frozen cutoff, it does not slide forward as time passes.
     *
     * Task 1 (Plan 3): this function itself still does not slide — it deliberately stays a
     * single frozen-cutoff query per call, so it has exactly one, easily-tested responsibility.
     * The sliding window is a CALLER concern: [com.yugma.terrawatch.home.HomeViewModel] now
     * re-subscribes on every poll tick (its `pollTick` `StateFlow`, `flatMapLatest`'d over a call
     * to this function) rather than collecting one call's Flow forever, so the cutoff effectively
     * advances once per poll even though any single subscription's cutoff is still frozen for its
     * own lifetime. This was previously a documented gap (Plan 2 entry conditions: "callers
     * wanting a sliding window re-call this on a timer") — now actually wired up, not just noted.
     */
    fun recentQuakes(windowMs: Long = 86_400_000): Flow<List<Quake>> =
        dao.recent(clock() - windowMs)

    /** Pass-through for Home's staleness banner (Task 8) — see [QuakeDao.lastFetchedAtMillis]. */
    fun lastFetchedAtMillis(): Long? = dao.lastFetchedAtMillis()

    /**
     * Task 11: thin DAO pass-through for the detail sheet's selection — `HomeViewModel.select(id)`
     * needs exactly "look up one quake by id right now," which [QuakeDao.byId] already does
     * synchronously. `suspend` + [ioDispatcher] moves that synchronous SQLDelight read off Main,
     * same convention every other DAO-touching method in this class already follows.
     */
    suspend fun byId(id: String): Quake? = withContext(ioDispatcher) { dao.byId(id) }

    suspend fun refreshFeed(): RefreshStatus = withContext(ioDispatcher) {
        when (val result = api.fetchFeed(previousEtag = dao.metaGet(FEED_ETAG_KEY))) {
            is FeedResult.Fresh -> {
                result.quakes.forEach { ingest(it) }
                result.etag?.let { dao.metaPut(FEED_ETAG_KEY, it) }
                RefreshStatus.UPDATED
            }
            FeedResult.NotModified -> RefreshStatus.NOT_MODIFIED
            is FeedResult.Failure -> RefreshStatus.FAILED
        }
    }

    suspend fun startLive(scope: CoroutineScope) {
        scope.launch { live.events().collect { ingest(it) } }
    }

    /**
     * Fix Round 1 (I2): the debug long-press hook's ONLY write path — never [ingest]. [ingest]
     * runs [quake] through [DedupeEngine] against whatever real quakes already sit in its
     * window-query; a fake, hardcoded-location debug quake landing within [DedupeEngine]'s match
     * radius/window of a genuine one can get merged INTO that real row (adopting its id), which
     * both corrupts the real quake's stored data and means the pin-drop animation/"N NEW" chip
     * fire for the wrong id (or not at all, if the merge resolves to an id already on screen). It
     * also used to run [AlertRuleEngine] against the fake quake's magnitude, which can fire a real
     * [AlertEvent] (e.g. the world M6.0 rule) purely from a debug tap — a debug-only verification
     * hook must never be able to trigger production alerting.
     *
     * Bypasses both: writes [quake] directly via [QuakeDao.replace] (unconditional, no dedupe
     * match/merge) and emits its id on [insertedQuakeIds] directly (still the same signal the
     * pin-drop animation and the feed sheet's "N NEW" chip key off of, so the debug hook still
     * exercises that whole path honestly) — but never touches [AlertRuleEngine]. Guarded by the
     * same [ingestMutex] as [ingest] so a debug inject can't race a real concurrent ingest's
     * read-reconcile-write section.
     */
    suspend fun ingestDebugBypassingDedupe(quake: Quake) {
        withContext(ioDispatcher) {
            ingestMutex.withLock {
                dao.replace(quake)
                _insertedQuakeIds.tryEmit(quake.id)
            }
        }
    }

    /**
     * Fix Round 1 (I2): sweeps up every quake [ingestDebugBypassingDedupe] has ever written on
     * this device (identified by HomeViewModel's "debug-" id prefix — see
     * [QuakeDao.deleteByIdPrefix]) — called unconditionally from HomeViewModel's init so a
     * debuggable build never accumulates fake rows across sessions. Harmless to call on a release
     * build/device that has never used the debug hook: zero rows ever match the prefix, so this is
     * a no-op delete.
     */
    suspend fun purgeDebugQuakes() = withContext(ioDispatcher) {
        dao.deleteByIdPrefix(DEBUG_QUAKE_ID_PREFIX)
    }

    /**
     * Task 5 (Plan 3): returns the actual ingested batch, not just a count — [HistoryPager] needs
     * the batch's own oldest [Quake.timeMillis] to advance its paging cursor, and the raw network
     * response is the only unambiguous place to read that from. Re-deriving it from the DB after
     * the fact (e.g. re-querying the most-recent N rows) would be ambiguous whenever [DedupeEngine]
     * merges two of THIS SAME batch's own events into one stored row (plausible for multi-agency or
     * revision-heavy archive spans), which shrinks the visible row count below what was actually
     * fetched. Deliberately widened from the original `Int` (Plan 1's own draft signature, "returns
     * rows ingested") the moment this task became this function's first real caller — grepped the
     * whole repo first (EVIDENCE INTEGRITY): zero other production call sites and no test pins the
     * old `Int` shape (`grep -rn loadArchivePage`), so this is a safe, unshared widening, not a
     * breaking change to any existing consumer. Callers that only want the count still have it for
     * free via `.size`.
     */
    suspend fun loadArchivePage(beforeMillis: Long, minMag: Double? = null): List<Quake> = withContext(ioDispatcher) {
        val page = api.queryArchive(endTimeMillis = beforeMillis, minMagnitude = minMag)
        page.forEach { ingest(it) }
        page
    }

    /**
     * Task 5 (Plan 3): thin DAO pass-through — same "suspend + [ioDispatcher]" shape as [byId].
     * Both [HistoryPager] (this module) and `HistoryViewModel` (composeApp — re-querying the local
     * cache for display after every page load/filter change) only depend on this repository, never
     * [QuakeDao] directly, so this is the one seam either needs.
     */
    suspend fun pageBefore(timeMillis: Long, limit: Int, minMag: Double? = null): List<Quake> =
        withContext(ioDispatcher) { dao.pageBefore(timeMillis, limit, minMag) }

    /**
     * Task 5 (Plan 3): [HistoryPager]'s cursor persistence — a suspend + [ioDispatcher] pass-through
     * around a synchronous meta-table read/write, scoped to this ONE purpose (a dedicated method
     * pair, not a generic key/value pass-through) so this repository's public surface doesn't grow
     * an arbitrary KV store just to satisfy one caller. The `"history_cursor_"` prefix is owned
     * entirely here — [HistoryPager] only ever hands this a bare per-filter key — so there is a
     * single place that could ever collide with [FEED_ETAG_KEY] or a future meta key, not two
     * independently-formatted ones.
     */
    suspend fun historyCursor(filterKey: String): Long? =
        withContext(ioDispatcher) { dao.metaGet("history_cursor_$filterKey")?.toLongOrNull() }

    suspend fun setHistoryCursor(filterKey: String, cursorMillis: Long) {
        withContext(ioDispatcher) { dao.metaPut("history_cursor_$filterKey", cursorMillis.toString()) }
    }

    suspend fun ingest(
        incoming: Quake,
        rules: List<AlertRule> = DEFAULT_RULES,
        home: GeoPoint? = null,
    ) {
        withContext(ioDispatcher) {
            ingestMutex.withLock {
                val window = dao.pageBefore(
                    timeMillis = incoming.timeMillis + WINDOW_MS,
                    limit = 50,
                    minMag = null,
                ).filter { it.timeMillis >= incoming.timeMillis - WINDOW_MS }
                val previousById = dao.byId(incoming.id)
                val result = dedupe.reconcile(window, incoming)
                val previous = previousById ?: result.replacesId?.let { dao.byId(it) }
                    ?: dao.byId(result.canonical.id)?.takeIf { it.id != incoming.id }
                // Every stale row must go, and there can be up to two of them simultaneously — they are
                // NOT mutually exclusive (Task 9 review round 3 disproved that assumption):
                //  - replacesId: canonical adopted a DIFFERENT id than the matched row's, so the OLD
                //    row stored under the match's id is superseded and must go.
                //  - incoming.id: canonical adopted some OTHER id while incoming was ALREADY stored
                //    under its own id (e.g. an EMSC event whose epicenter drifts into a USGS twin's
                //    radius on a later revision) — that old row is now an orphan. Left behind, ingest()
                //    keeps reading it back as "previous" on every future update for that id, freezing
                //    the alert-crossing baseline and re-firing every single time (Task 9 review,
                //    Critical 1).
                // Both can fire on the SAME call: DedupeEngine.merge() can pick canonical.id from
                // incoming.sources[USGS], which is not guaranteed to equal incoming.id (UsgsFeedParser
                // derives them from different feed fields — the `ids` alias list vs. the top-level
                // feature id) — so canonical.id can be a THIRD value, distinct from both the matched
                // row's id and incoming's own id, orphaning both if only one were deleted.
                val deleteIds = listOfNotNull(
                    result.replacesId,
                    incoming.id.takeIf { it != result.canonical.id },
                ).distinct().filter { it != result.canonical.id }
                // Delete + write as ONE transaction (Task 9 review, Important 2): separate calls are
                // separate commits, so a live recentQuakes() collector observes the transient state in
                // between (an empty list, if a deleted row was the only one in view), and a crash
                // between commits can permanently lose the quake.
                dao.replaceAndDelete(result.canonical, deleteIds)   // NOT upsert() — reconciler already resolved recency; see Task 9 DAO notes
                if (previous == null) _insertedQuakeIds.tryEmit(result.canonical.id)
                alerts.evaluate(previous, result.canonical, rules, home)?.let { _alertEvents.tryEmit(it) }
            }
        }
    }

    private companion object {
        const val FEED_ETAG_KEY = "feed_etag"
        const val WINDOW_MS = 90_000L
        // Fix Round 1 (I2): must match HomeViewModel.injectDebugQuake's id prefix exactly —
        // see [purgeDebugQuakes]/[QuakeDao.deleteByIdPrefix].
        const val DEBUG_QUAKE_ID_PREFIX = "debug-"
    }
}
