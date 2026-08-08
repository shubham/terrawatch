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
        result.replacesId?.let { dao.delete(it) }
        dao.replace(result.canonical)   // NOT upsert() — reconciler already resolved recency; see Task 9 DAO notes
        alerts.evaluate(previous, result.canonical, rules, home)?.let { _alertEvents.tryEmit(it) }
    }

    private companion object {
        const val FEED_ETAG_KEY = "feed_etag"
        const val WINDOW_MS = 90_000L
    }
}
