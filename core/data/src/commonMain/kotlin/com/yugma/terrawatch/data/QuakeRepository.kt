package com.yugma.terrawatch.data

import com.yugma.terrawatch.database.QuakeDao
import com.yugma.terrawatch.model.GeoPoint
import com.yugma.terrawatch.model.Quake
import com.yugma.terrawatch.network.EmscLiveSource
import com.yugma.terrawatch.network.FeedResult
import com.yugma.terrawatch.network.UsgsApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.launch

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
) {
    private val _alertEvents = MutableSharedFlow<AlertEvent>(extraBufferCapacity = 16)
    val alertEvents: SharedFlow<AlertEvent> = _alertEvents

    /**
     * [windowMs] before "now" is computed once, at call time — the returned Flow re-queries
     * against that frozen cutoff, it does not slide forward as time passes. Callers wanting a
     * sliding window re-call this on a timer (Plan 2).
     */
    fun recentQuakes(windowMs: Long = 86_400_000): Flow<List<Quake>> =
        dao.recent(clock() - windowMs)

    suspend fun refreshFeed(): RefreshStatus =
        when (val result = api.fetchFeed(previousEtag = dao.metaGet(FEED_ETAG_KEY))) {
            is FeedResult.Fresh -> {
                result.quakes.forEach { ingest(it) }
                result.etag?.let { dao.metaPut(FEED_ETAG_KEY, it) }
                RefreshStatus.UPDATED
            }
            FeedResult.NotModified -> RefreshStatus.NOT_MODIFIED
            is FeedResult.Failure -> RefreshStatus.FAILED
        }

    suspend fun startLive(scope: CoroutineScope) {
        scope.launch { live.events().collect { ingest(it) } }
    }

    suspend fun loadArchivePage(beforeMillis: Long, minMag: Double? = null): Int {
        val page = api.queryArchive(endTimeMillis = beforeMillis, minMagnitude = minMag)
        page.forEach { ingest(it) }
        return page.size
    }

    suspend fun ingest(
        incoming: Quake,
        rules: List<AlertRule> = DEFAULT_RULES,
        home: GeoPoint? = null,
    ) {
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
        alerts.evaluate(previous, result.canonical, rules, home)?.let { _alertEvents.tryEmit(it) }
    }

    private companion object {
        const val FEED_ETAG_KEY = "feed_etag"
        const val WINDOW_MS = 90_000L
    }
}
