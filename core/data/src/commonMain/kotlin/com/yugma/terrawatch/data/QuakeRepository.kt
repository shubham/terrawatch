package com.yugma.terrawatch.data

import com.yugma.terrawatch.database.BandCount
import com.yugma.terrawatch.database.DayCount
import com.yugma.terrawatch.database.QuakeStore
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

/** [QuakeRepository.worldwideCountCache]'s stored shape — see that method's own kdoc. */
data class WorldwideCountCache(val count: Long, val fetchedAtMillis: Long)

/**
 * The single ingest path for every quake source (poll, live WebSocket, archive backfill).
 * Every write funnels through [ingest], which window-queries the DAO for a same-event
 * candidate, hands it to [DedupeEngine] for reconciliation, persists the reconciled
 * canonical, and evaluates [AlertRuleEngine] against the previous/current pair.
 */
class QuakeRepository(
    private val api: UsgsApi,
    private val live: EmscLiveSource,
    // Task 9 (Plan 3): widened from the concrete QuakeDao to the QuakeStore interface it now
    // implements — see QuakeStore's own kdoc for the web-enablement spike this came from. Every
    // method this class calls on `dao` (recent/lastFetchedAtMillis/byId/metaGet/metaPut/replace/
    // deleteByIdPrefix/pageBetween/quakesPerDay/bandDistribution/strongest/pageBefore/
    // replaceAndDelete) is on that interface — this is a pure type-widening, zero-behavior-change
    // edit (jvmTest's existing 287 cases construct this with a real QuakeDao, unmodified, and stay
    // green because QuakeDao IS-A QuakeStore).
    private val dao: QuakeStore,
    private val dedupe: DedupeEngine = DedupeEngine(),
    private val alerts: AlertRuleEngine = AlertRuleEngine(),
    private val clock: () -> Long,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.Default,
    // Task 7 (Plan 3), USER REQUIREMENT: store-fed alert rules - see [currentRules]'s own kdoc.
    // Both optional/defaulted to null (not required constructor params) purely for backward
    // compatibility: every existing caller across this module's own test suite, HomeViewModelTest,
    // HistoryPagerTest, QuakeSelectionViewModelTest, InsightsViewModelTest and HomeFlowTest
    // constructs this class WITHOUT either (grepped before adding these - EVIDENCE INTEGRITY),
    // proving pre-existing default-rule behavior (DEFAULT_RULES, home=null) that must keep
    // compiling and behaving identically. AppModule.kt's real Koin wiring is the one call site that
    // ever supplies both.
    private val alertRuleStore: AlertRuleStore? = null,
    private val homeLocationStore: HomeLocationStore? = null,
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
                // Task 7 (Plan 3): read once per batch, not once per quake - the whole batch from
                // one feed response shares the same "what does the store say right now" snapshot,
                // exactly like [loadArchivePage] already reads [clock] once for [DomainQuake.
                // fetchedAtMillis] across its own forEach below rather than re-reading per row.
                val rules = currentRules()
                val home = currentHome()
                result.quakes.forEach { ingest(it, rules = rules, home = home, origin = QuakeStore.ORIGIN_FEED) }
                result.etag?.let { dao.metaPut(FEED_ETAG_KEY, it) }
                RefreshStatus.UPDATED
            }
            FeedResult.NotModified -> RefreshStatus.NOT_MODIFIED
            is FeedResult.Failure -> RefreshStatus.FAILED
        }
    }

    suspend fun startLive(scope: CoroutineScope) {
        // Task 7 (Plan 3): unlike refreshFeed's one-snapshot-per-batch above, a live event arrives
        // one at a time, potentially hours apart - currentRules()/currentHome() are read fresh for
        // EACH event, so a radius/home change the user makes mid-session is honored by the very
        // next live arrival, not just the next poll tick.
        scope.launch {
            live.events().collect {
                ingest(it, rules = currentRules(), home = currentHome(), origin = QuakeStore.ORIGIN_LIVE)
            }
        }
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
                dao.replace(quake, origin = QuakeStore.ORIGIN_DEBUG)
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
     * Task 2 (Plan 4), F1 retention ruling (plan-3-exit-conditions.md carried item): deletes every
     * 'feed'/'live'-origin row older than [cutoffMillis] — 'archive' rows (History's backfill) and
     * 'debug' rows stay exempt. See [QuakeDao.pruneOldRows]/[QuakeStore.pruneOldRows]'s own kdoc
     * for the full ruling. Called unconditionally from `HomeViewModel.init` alongside
     * [purgeDebugQuakes], with `cutoffMillis = now - 30 days` — same "independent housekeeping,
     * nothing else waits on it" shape that function's own kdoc documents.
     */
    suspend fun pruneOldRows(cutoffMillis: Long) = withContext(ioDispatcher) {
        dao.pruneOldRows(cutoffMillis)
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
     *
     * Task 7 (Plan 3) note: deliberately NOT wired to [currentRules]/[currentHome] the way
     * [refreshFeed]/[startLive] are (see [currentRules]'s own kdoc) — this is History's
     * archive-backfill path, which can page in quakes from years ago as the user scrolls.
     *
     * Task 2 (Plan 4), F5 guard (plan-3-exit-conditions.md carried item, closed this task): this
     * used to fall through to [ingest]'s own compile-time `rules = DEFAULT_RULES` default, which
     * DID evaluate [AlertRuleEngine] (harmless only because nothing consumed [alertEvents] yet) —
     * the exact hazard that carried item flagged: "the instant Plan 4 wires real notifications off
     * [alertEvents], a user deep-scrolling History past old M6+ quakes will notification-storm on
     * events years old." Now passes `rules = emptyList()` explicitly, not merely "unwired from the
     * store" — [AlertRuleEngine.evaluate]'s own `for (rule in rules)` loop never runs at all here,
     * so archive ingestion cannot alert regardless of magnitude/home/store state, a stronger and
     * simpler guarantee than any store-wiring choice could give (QuakeRepositoryTest's own
     * `loadArchivePage never alerts` case pins this against a quake that WOULD otherwise trip the
     * "world" rule). Also tags every row [QuakeStore.ORIGIN_ARCHIVE] — see [pruneOldRows]'s own
     * kdoc for why that matters: History's "cached pages browse offline" contract wants these rows
     * kept, exempt from retention, unlike a 'feed'/'live' row of the same age.
     */
    suspend fun loadArchivePage(beforeMillis: Long, minMag: Double? = null): List<Quake> = withContext(ioDispatcher) {
        val page = api.queryArchive(endTimeMillis = beforeMillis, minMagnitude = minMag)
        page.forEach { ingest(it, rules = emptyList(), origin = QuakeStore.ORIGIN_ARCHIVE) }
        page
    }

    /**
     * Task 5 fix round 1 (Plan 3, review Critical): `HistoryViewModel`'s display query — everything
     * cached between [HistoryPager]'s own current cursor position for a filter and that filter's
     * ceiling, no `LIMIT`. Thin DAO pass-through, same "suspend + [ioDispatcher]" shape as [byId];
     * `HistoryViewModel` only depends on this repository, never [QuakeDao] directly, so this is the
     * one seam it needs for display reads (cursor persistence is the separate `historyCursor`/
     * `setHistoryCursor` pair below).
     *
     * Replaces the original Task 5 shape — a `pageBefore(ceiling, limit = loadedCount, minMag)`
     * call, where `loadedCount` was a session-local tally of how many rows THIS ViewModel instance
     * had fetched via [HistoryPager.loadNext] this session. That tally is a fundamentally different
     * quantity than "how much is actually cached for this filter," and desynced from it in three
     * ways a code review caught (none guarded by a test until this fix): (a) revisiting a filter
     * mid-session — [HistoryPager]'s own per-filter cursor is unaffected by `setFilter`, but
     * `loadedCount` was unconditionally zeroed by it, so a revisit re-derived the display window
     * from whatever ONE fresh fetch returned, not the filter's real cumulative depth; (b) an app
     * restart — a fresh `HistoryViewModel` starts `loadedCount` at 0 even though [HistoryPager]
     * correctly resumes its cursor from the persisted meta row, so the display window shrank to
     * whatever the first post-restart fetch happened to return; (c) restart-while-offline — the
     * worst case: `loadedCount` stays 0 because the first fetch attempt FAILS, so `pageBefore(...,
     * limit = 0, ...)` is a SQL `LIMIT 0` — unconditionally empty — turning a fully-cached archive
     * into a full-screen `Error`/`Empty`, exactly contradicting the "cached pages browse offline"
     * contract. A cursor-derived range has no separate tally to desync: the display window is
     * always "whatever this filter's cursor say it's covered," recomputed fresh every call, whether
     * that cursor came from this session's own paging, from persisted meta after a restart, or was
     * simply never touched by an unrelated fetch failure.
     *
     * **Cross-filter cache-bleed, documented honestly (a reviewer-requested clarification, not a
     * new behavior this fix introduces — the original `pageBefore`-based query had the identical
     * property, just harder to notice with a `LIMIT` in the way):** this range query matches
     * [HistoryFilter.minMag] against EVERY row in that range regardless of which walk originally
     * fetched it. A quake landing in `[lower, upper)` because [com.yugma.terrawatch.home.HomeViewModel]'s
     * always-running 24h poll cached it, or because a DIFFERENT [HistoryFilter] value's own archive
     * walk happened to pass through the same time range, shows up here too, as long as its magnitude
     * matches. This is "correct but broader than this filter's own walk" — every row shown genuinely
     * exists and genuinely matches the filter, never a false positive — but it does mean [HistoryPager]
     * can spend a real network round trip re-fetching (idempotently — [ingest] no-ops on
     * already-current data) a time span another source already populated. Accepted for v1: detecting
     * "already covered by a different source" before paging would need cross-filter bookkeeping this
     * task's brief never asked for, for a cost that's wasted egress, not incorrect data.
     *
     * **No `LIMIT`, documented tradeoff**: a filter whose own archive walk has gone extremely deep
     * (many hundreds of pages) makes this an unbounded-width range read. Accepted for v1 — the
     * `quake_time` index keeps the read itself cheap, and reaching that depth requires the user to
     * have actually scrolled that far, at which point the query is proportionate to what's already
     * been shown; revisit if real usage ever makes this measurably slow.
     */
    suspend fun pageBetween(lowerInclusive: Long, upperExclusive: Long, minMag: Double? = null): List<Quake> =
        withContext(ioDispatcher) { dao.pageBetween(lowerInclusive, upperExclusive, minMag) }

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

    /**
     * Task 6 (Plan 3): Insights' three read-only aggregates — thin suspend + [ioDispatcher]
     * pass-throughs over [QuakeDao], same shape as [pageBefore]/[byId] above. `InsightsViewModel`
     * (composeApp) is constructor-limited to this repository alone, never [QuakeDao] directly —
     * same "the repository is the one seam a screen ViewModel needs" discipline [HistoryPager]'s
     * own kdoc documents for [pageBefore]/[historyCursor].
     */
    suspend fun quakesPerDay(sinceMillis: Long): List<DayCount> =
        withContext(ioDispatcher) { dao.quakesPerDay(sinceMillis) }

    suspend fun bandDistribution(sinceMillis: Long): List<BandCount> =
        withContext(ioDispatcher) { dao.bandDistribution(sinceMillis) }

    suspend fun strongest(sinceMillis: Long): Quake? =
        withContext(ioDispatcher) { dao.strongest(sinceMillis) }

    /**
     * Commit "since-last-visit summary" (feed-visit-ux): the feed sheet's "N quakes M4.0+ since
     * your last visit" banner count — thin suspend + [ioDispatcher] pass-through over
     * [QuakeStore.newSinceCount], same shape as [pageBetween]/[quakesPerDay]/[strongest] above.
     * `HomeViewModel` only depends on this repository, never [QuakeStore]/[QuakeDao] directly, same
     * "the repository is the one seam a screen ViewModel needs" discipline this class's own
     * [pageBetween] kdoc already states explicitly for History.
     */
    suspend fun newSinceCount(sinceMillis: Long, minMag: Double): Long =
        withContext(ioDispatcher) { dao.newSinceCount(sinceMillis, minMag) }

    /**
     * Task 5 (Plan 4): Insights' density-disclosure caption backfill — a single FDSN `/count` call
     * (never per-day-bucket, never rows) for the SAME window Insights already renders, used only to
     * caption "N cached · M total worldwide" when the local cache looks thin. Thin suspend +
     * [ioDispatcher] pass-through over [UsgsApi.queryCount], same shape as [byId]/[quakesPerDay]
     * above — `InsightsViewModel` never talks to [api] directly, matching this class's own
     * established "the repository is the one seam a screen ViewModel needs" discipline
     * ([pageBefore]'s kdoc states this explicitly for History; the same rule applies here).
     *
     * Returns null on any failure (see [UsgsApi.queryCount]'s own kdoc) — the caller's job is to
     * fall back to a cached value or omit the caption entirely, never to surface an error state for
     * what is a purely cosmetic disclosure.
     */
    suspend fun worldwideCount(startTimeMillis: Long, endTimeMillis: Long): Long? =
        withContext(ioDispatcher) { api.queryCount(startTimeMillis, endTimeMillis) }

    /**
     * [worldwideCount]'s 6h cache, backing the "cached in meta 6h" requirement (Task 5 brief) —
     * dedicated meta-key pair, not a generic KV pass-through, same "a single purpose gets a single
     * named method pair, not an arbitrary key/value surface" discipline [historyCursor]/
     * [setHistoryCursor] already established for this repository's public API.
     */
    suspend fun worldwideCountCache(): WorldwideCountCache? = withContext(ioDispatcher) {
        val count = dao.metaGet(WORLDWIDE_COUNT_KEY)?.toLongOrNull() ?: return@withContext null
        val at = dao.metaGet(WORLDWIDE_COUNT_AT_KEY)?.toLongOrNull() ?: return@withContext null
        WorldwideCountCache(count = count, fetchedAtMillis = at)
    }

    /** Writes both halves of [WorldwideCountCache] as ONE transaction (via [QuakeStore.metaPutAll]
     * — see that method's own kdoc) so a reader never observes a count with no matching timestamp
     * or vice versa. */
    suspend fun setWorldwideCountCache(count: Long, fetchedAtMillis: Long) {
        withContext(ioDispatcher) {
            dao.metaPutAll(WORLDWIDE_COUNT_KEY to count.toString(), WORLDWIDE_COUNT_AT_KEY to fetchedAtMillis.toString())
        }
    }

    /**
     * Task 7 (Plan 3), USER REQUIREMENT: the "near" rule's radius/minMag are now STORE-fed, not
     * [DEFAULT_RULES]'s compile-time 500.0/4.5 — [alertRuleStore], once wired (every real call site
     * always wires it via [AppModule][com.yugma.terrawatch.di.appModule]; only tests that construct
     * this repository without one ever fall back here), is the one source of truth. [DEFAULT_RULES]
     * itself is untouched and stays exactly what it always was: the compile-time fallback for a
     * caller with no store at all (unit tests, mainly) — not a second, competing definition of
     * "near."
     *
     * Read FRESH on every call, not cached-with-invalidation (the brief's own offered fork —
     * "read per-ingest? cache w/ updates invalidation"): [AlertRuleStore]'s reads
     * ([AlertRuleStore.currentRadiusKm]/[AlertRuleStore.currentMinMag]) are plain synchronous local
     * DAO lookups — the exact same cost class as this class's own unconditional
     * `dao.metaGet(FEED_ETAG_KEY)` a few lines up in [refreshFeed], or the `dao.byId`/window-query
     * pair [ingest] below already does per call. A cache would only trade that non-existent
     * performance problem for a real correctness risk of its own: a missed or late-arriving
     * [AlertRuleStore.updates] signal silently serving a stale radius to an in-flight ingest.
     * "World" stays a fixed rule (magnitude ≥6.0, global) — only "near" (radius + a home reference
     * point) is meaningfully store-configurable; there is no store-backed knob for "world" to read.
     */
    suspend fun currentRules(): List<AlertRule> = withContext(ioDispatcher) {
        val store = alertRuleStore ?: return@withContext DEFAULT_RULES
        listOf(
            AlertRule(id = "near", minMag = store.currentMinMag(), radiusKm = store.currentRadiusKm(), center = null),
            AlertRule(id = "world", minMag = 6.0, radiusKm = null, center = null),
        )
    }

    /**
     * [currentRules]'s home-location counterpart.
     *
     * Fix Round 1 (I1): now `suspend` + [ioDispatcher], matching [currentRules] exactly. The prior
     * version of this kdoc claimed "[HomeLocationStore.get] needs no [ioDispatcher] hop of its own
     * beyond whatever the caller (already inside one, at every real call site) provides" — that was
     * false for [startLive]: its collector runs directly on the `CoroutineScope` `HomeViewModel`
     * hands it (`viewModelScope`, i.e. `Dispatchers.Main.immediate`), NOT inside a
     * `withContext(ioDispatcher)` block the way [refreshFeed] wraps its whole body. Kotlin evaluates
     * call arguments before the call itself, so `ingest(it, rules = currentRules(), home =
     * currentHome())` ran `currentRules()`'s suspend hop first, returned to the caller's own
     * context (Main, per `withContext`'s contract), and only THEN called the old plain
     * [currentHome] — meaning the synchronous [HomeLocationStore.get] DAO read executed directly on
     * the main thread for every single incoming live event, not just once at startup. Verified: the
     * two real call sites are [refreshFeed] (already inside its own `withContext(ioDispatcher)`,
     * so this is a harmless nested hop there — `withContext` short-circuits when the target
     * dispatcher is already current) and [startLive] (the one this fix actually matters for); no
     * other call site exists (grepped the whole repo — EVIDENCE INTEGRITY — `currentHome` is
     * `private`, so these two are structurally the only possible callers).
     */
    private suspend fun currentHome(): GeoPoint? = withContext(ioDispatcher) { homeLocationStore?.get() }

    suspend fun ingest(
        incoming: Quake,
        rules: List<AlertRule> = DEFAULT_RULES,
        home: GeoPoint? = null,
        // Task 2 (Plan 4): which write path this call came from — see QuakeStore's own kdoc for the
        // four values and why this stays a plain DB-layer tag ([Quake] itself never carries it).
        // Defaulted to ORIGIN_FEED (not e.g. an unlabeled/empty string) so every pre-existing test
        // call site across this whole module (QuakeRepositoryTest, QuakeRepositoryConcurrencyTest,
        // HomeViewModelTest's freshQuake()-seeded ingests, QuakeSelectionViewModelTest, ...) keeps
        // compiling AND keeps behaving as "feed" — the correct default, since none of those omit it
        // to mean anything else.
        origin: String = QuakeStore.ORIGIN_FEED,
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
                // Task 2 (Plan 4), Fix Round 1 (review finding): origin-flip-on-merge protection —
                // see QuakeStore.pruneOldRows's own kdoc for the full reproduction this closes (an
                // 'archive' row silently downgraded to 'feed'/'live' by a same-event merge, then
                // wrongly deleted by a MUCH later pruneOldRows pass). Protection priority, never
                // downgrade: an existing row's ORIGIN_ARCHIVE/ORIGIN_DEBUG tag always survives a
                // merge, regardless of which origin the CALLER passed in; anything else (feed/live,
                // neither ever protected) lets the caller's own origin win exactly as before.
                //
                // Checks BOTH slots below independently — NOT just `previous`'s own resolved id:
                //  - `previous`: whichever of the THREE elvis branches above actually fired —
                //    previousById (same-id update), the replacesId lookup (cross-id merge), OR the
                //    canonical.id fallback (the orphaned-third-id case `deleteIds`' own comment above
                //    documents: an EMSC epicenter-drift revision, where canonical.id lands on neither
                //    incoming.id nor the matched row's id). All three already funnel through this one
                //    variable, so this slot alone protects whichever one of them actually resolved.
                //  - `result.replacesId` read AGAIN, directly (not reused from `previous`): needed
                //    because `previous` only ever resolves to ONE row — the elvis chain's first
                //    non-null hit — so whenever previousById itself is non-null, `previous` stops
                //    there and never reaches the replacesId branch at all, even though replacesId
                //    can independently point at a SECOND, different stale row that also needs
                //    checking. `deleteIds` just above already has to reason about this identical
                //    "not mutually exclusive" shape (previousById != null AND result.replacesId !=
                //    null can both hold at once) — checking only `previous`'s origin would silently
                //    miss a protected replacesId row in exactly that dual-stale-row case
                //    (QuakeRepositoryTest's "checks the replaced row too" pins this).
                // `setOfNotNull` (Task 3, Plan 4 re-review), not two independent `listOfNotNull`
                // entries: a PLAIN cross-id merge (previousById null, only result.replacesId set)
                // has `previous.id == result.replacesId` — the old code queried `dao.originOf` for
                // that identical id twice over. A set naturally collapses the two id sources down to
                // however many are actually DISTINCT before querying, so the dual-stale-row case
                // (previousById AND replacesId both non-null, genuinely two different ids) still
                // queries both independently, exactly as the paragraph above requires — this is a
                // pure query-count optimization, not a behavior change (membership-testing
                // `existingOrigins` below via `in` was already indifferent to a duplicate entry).
                val existingOrigins = setOfNotNull(previous?.id, result.replacesId).map { dao.originOf(it) }
                val effectiveOrigin = when {
                    QuakeStore.ORIGIN_ARCHIVE in existingOrigins -> QuakeStore.ORIGIN_ARCHIVE
                    QuakeStore.ORIGIN_DEBUG in existingOrigins -> QuakeStore.ORIGIN_DEBUG
                    else -> origin
                }
                // Delete + write as ONE transaction (Task 9 review, Important 2): separate calls are
                // separate commits, so a live recentQuakes() collector observes the transient state in
                // between (an empty list, if a deleted row was the only one in view), and a crash
                // between commits can permanently lose the quake.
                dao.replaceAndDelete(result.canonical, deleteIds, origin = effectiveOrigin)   // NOT upsert() — reconciler already resolved recency; see Task 9 DAO notes
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
        // Task 5 (Plan 4): worldwideCountCache/setWorldwideCountCache's dedicated meta-key pair —
        // see those methods' own kdoc.
        const val WORLDWIDE_COUNT_KEY = "worldwide_count_30d"
        const val WORLDWIDE_COUNT_AT_KEY = "worldwide_count_30d_at"
    }
}
