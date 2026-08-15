package com.yugma.terrawatch.database

import com.yugma.terrawatch.model.Quake as DomainQuake
import kotlinx.coroutines.flow.Flow

/**
 * Task 9 (Plan 3), web-enablement storage-decision spike: the read/write surface
 * [com.yugma.terrawatch.data.QuakeRepository] and the four meta-table-backed stores
 * ([com.yugma.terrawatch.data.HomeLocationStore], [com.yugma.terrawatch.data.ThemeStore],
 * [com.yugma.terrawatch.data.AlertRuleStore], [com.yugma.terrawatch.data.OnboardingStore]) actually
 * call on [QuakeDao] — grepped across the whole repo, not guessed (EVIDENCE INTEGRITY): every one of
 * these 13 methods has a real production call site outside this module's own test suite.
 * [QuakeDao]'s own `upsert`/`upsertAll`/`countAll`/`delete(id)` do NOT — only `QuakeDaoTest` calls
 * them, to seed rows directly for its own assertions — and are deliberately left OFF this interface;
 * they stay `QuakeDao`-only methods. (~13, not the plan's own approximate "~12" — a recount at
 * extraction time; see task-9-report.md's spike section.)
 *
 * Exists so [InMemoryQuakeStore] can stand in for [QuakeDao] wherever a real `SqlDriver` isn't
 * available yet — currently wasmJs (see [InMemoryQuakeStore]'s own kdoc for the spike that led here:
 * SQLDelight's web-worker driver needs `generateAsync=true`, which turns every mutating call on the
 * SHARED generated `TerraWatchDb` interface `suspend` — confirmed empirically, not just reasoned
 * about, by flipping that flag and recompiling this exact module: `insertOrReplace`/`delete`/
 * `deleteByIdPrefix`/`meta_put`/`transaction` all failed to compile with "Suspend function ... can
 * only be called from a coroutine or another suspend function" — and that ripples past this module
 * entirely, into plain `Compose` `onClick`/`onFinish` lambdas in `composeApp` that call
 * `OnboardingStore.setOnboarded()`/`ThemeStore.setTheme()`/`AlertRuleStore.setNearbyRadius()` today
 * with no coroutine scope in hand; rejected as too invasive for this task's 30-minute decision gate).
 *
 * [QuakeDao] implements this unchanged — mechanical `: QuakeStore` + `override` on exactly these 13
 * methods, no behavior change on the jvm/android paths, confirmed by the full jvmTest suite staying
 * green unmodified (287 pre-existing tests, zero edits). Every real caller (`QuakeRepository`, the
 * four meta stores above) now takes this interface instead of the concrete class, so
 * `AppModule.kt`'s `appModule()` can hand it either a real [QuakeDao] (android/jvm) or an
 * [InMemoryQuakeStore] (wasmJs) with zero other code caring which.
 *
 * Task 2 (Plan 4) grew this interface to 15 methods, closing three carried Plan 3 exit-condition
 * items:
 *  - **F1 retention**: [pruneOldRows] — see its own kdoc.
 *  - **M1 torn write**: [metaPutAll] — a transactional multi-key write, see its own kdoc; the fix
 *    for [com.yugma.terrawatch.data.HomeLocationStore.set]'s non-atomic home_lat/home_lon pair.
 *  - **origin tagging** (the mechanism [pruneOldRows] itself relies on): [replace]/
 *    [replaceAndDelete] both gain a defaulted `origin` parameter (see the [Companion] constants).
 *    Deliberately DB-layer-only, per controller decision — [DomainQuake] itself gains no `origin`
 *    field, and no OTHER read method on this interface ever surfaces one back out (updated below —
 *    Fix Round 1 adds exactly one narrow exception); see `Quake.sq`'s own schema-comment for the
 *    full ruling.
 *
 * Fix Round 1 (review finding) grew this to 16: [originOf] is a deliberate, narrow crack in the
 * "no read method surfaces origin" rule stated above — added purely so
 * [com.yugma.terrawatch.data.QuakeRepository.ingest]'s own merge-write path can decide whether a
 * row it's about to overwrite is protected ([ORIGIN_ARCHIVE]/[ORIGIN_DEBUG]) before choosing what
 * origin to write next; see [pruneOldRows]'s own kdoc for the bug this closes. Still never reaches
 * [DomainQuake] or any UI-facing read path — the one caller is [QuakeRepository]'s internal
 * bookkeeping, not a new public capability for this data to leak through.
 *
 * Task 3 (Plan 4) grows this to 17: [newSince] — `AlertDigestWorker`'s (androidMain) own delta
 * query, the read-side twin of [pruneOldRows]'s origin filter. See its own kdoc.
 */
interface QuakeStore {
    fun byId(id: String): DomainQuake?

    fun recent(sinceMillis: Long): Flow<List<DomainQuake>>

    fun pageBefore(timeMillis: Long, limit: Int, minMag: Double?): List<DomainQuake>

    fun pageBetween(lowerInclusive: Long, upperExclusive: Long, minMag: Double?): List<DomainQuake>

    fun lastFetchedAtMillis(): Long?

    fun quakesPerDay(sinceMillis: Long): List<DayCount>

    fun bandDistribution(sinceMillis: Long): List<BandCount>

    fun strongest(sinceMillis: Long): DomainQuake?

    fun deleteByIdPrefix(prefix: String)

    fun metaGet(key: String): String?

    fun metaPut(key: String, value: String)

    /**
     * Task 2 (Plan 4), Fix Round 1 (review finding): the read side of the origin-flip-on-merge
     * protection — returns the `origin` currently stored for [id], or `null` when no row exists
     * there. [com.yugma.terrawatch.data.QuakeRepository.ingest]'s merge-write path calls this on
     * every row a merge is about to supersede (the row already at the incoming quake's own id, and
     * — separately — the row a cross-id merge's `replacesId` points at, since a single ingest can
     * supersede BOTH at once) to decide [ORIGIN_ARCHIVE]/[ORIGIN_DEBUG] protection: see
     * [pruneOldRows]'s own kdoc for the full reproduction this closes. Deliberately returns the raw
     * origin string, not a Boolean "is this protected" — the protection POLICY (which origins count
     * as protected, and what wins when two superseded rows disagree) belongs in the repository, the
     * one place that already knows which rows are in play for a given ingest call; this method's
     * only job is the DB-layer lookup.
     */
    fun originOf(id: String): String?

    /**
     * Task 2 (Plan 4), M1 torn-write fix: writes every (key, value) pair in [pairs] as ONE
     * transaction — see [com.yugma.terrawatch.data.HomeLocationStore.set]'s own kdoc for the bug
     * this closes (a `get()` racing between two separate [metaPut] calls could read a torn point,
     * new lat paired with stale lon). [QuakeDao]'s implementation wraps every pair in a single
     * `Transacter.transaction {}`, deferring SQLDelight's own invalidation notification until
     * commit — a listener on any query reading the `meta` table sees exactly ONE notification for
     * the whole call, not one per pair (QuakeDaoTest proves this the same way
     * `replaceAndDelete is atomic` already proves [replaceAndDelete]'s own transaction boundary,
     * via a notification-count listener rather than guessing at internal implementation).
     */
    fun metaPutAll(vararg pairs: Pair<String, String>)

    /**
     * Task 2 (Plan 4): the write path for every DEDUPE-RECONCILED single-row replace — unchanged
     * from before this task except for the new [origin] parameter (defaulted to [ORIGIN_FEED]),
     * which every real caller now passes explicitly: [com.yugma.terrawatch.data.QuakeRepository.
     * ingestDebugBypassingDedupe] passes [ORIGIN_DEBUG]; every other production caller goes through
     * [replaceAndDelete] instead (this function is itself `replaceAndDelete(quake, emptyList(),
     * origin)` under the hood — see [QuakeDao.replace]/[InMemoryQuakeStore.replace]).
     */
    fun replace(quake: DomainQuake, origin: String = ORIGIN_FEED)

    /**
     * Task 2 (Plan 4): unchanged from before this task except for the new [origin] parameter
     * (defaulted to [ORIGIN_FEED]) — [com.yugma.terrawatch.data.QuakeRepository.ingest] threads its
     * own `origin` parameter straight through here on every call: [ORIGIN_FEED] from `refreshFeed`,
     * [ORIGIN_LIVE] from `startLive`'s collector, [ORIGIN_ARCHIVE] from `loadArchivePage`. See this
     * interface's own kdoc for why the value never round-trips back out through a read method.
     */
    fun replaceAndDelete(quake: DomainQuake, deleteIds: List<String> = emptyList(), origin: String = ORIGIN_FEED)

    /**
     * Task 2 (Plan 4), F1 retention ruling (plan-3-exit-conditions.md carried item): deletes every
     * row whose `timeMillis` is strictly before [cutoffMillis] AND whose `origin` is [ORIGIN_FEED]
     * or [ORIGIN_LIVE] — [ORIGIN_ARCHIVE] rows are exempt (History's own `loadArchivePage` kdoc:
     * "cached pages browse offline" implicitly wants old rows kept indefinitely) and so are
     * [ORIGIN_DEBUG] rows (already swept, unconditionally, by `deleteByIdPrefix`/
     * `purgeDebugQuakes` — exempting them here too is redundant-but-harmless, not load-bearing).
     * Called unconditionally from `HomeViewModel.init` alongside `purgeDebugQuakes`, with
     * `cutoffMillis = now - 30 days`.
     *
     * Origin-tagging caveat, documented honestly rather than silently accepted: a row's `origin`
     * reflects whichever [replace]/[replaceAndDelete] call MOST RECENTLY wrote it, including a
     * later DedupeEngine merge.
     *
     * Task 2 (Plan 4), Fix Round 1 (review finding): the ORIGINAL version of this note argued the
     * flip was "rare and low-stakes" because [com.yugma.terrawatch.data.DedupeEngine]'s own match
     * window is ±90 SECONDS of the quake's own reported `timeMillis` — true, but the wrong
     * mechanism to reason from, and it understated the real risk. That ±90s window bounds how
     * close two merge candidates' EVENT times must be; it says nothing about how soon after
     * ingestion a merge can actually occur, because DedupeEngine only ever compares the stored
     * row's `timeMillis` against the incoming one, never "how long ago was this row written." A
     * late-arriving EMSC "update" revision for the exact same event can match and re-merge a row on
     * ANY day after its first ingestion — the merge is unbounded in wall-clock terms even though
     * it's tightly bounded in event-time terms. So the tag this function reads is simply whatever
     * the LAST merge happened to freeze it to, decided completely independently of when that merge
     * fell relative to the 30-day window this function later judges it by: an [ORIGIN_ARCHIVE] row
     * could sit untouched for 29 days, get silently re-tagged [ORIGIN_FEED] by a same-event merge
     * on day 29, and be deleted by THIS function on day 30 — a row the user had actually browsed via
     * History, gone, in violation of that screen's own "cached pages browse offline" contract.
     *
     * Mitigated, not just documented, as of Fix Round 1:
     * [com.yugma.terrawatch.data.QuakeRepository.ingest]'s merge-write path now reads [originOf] on
     * every row a merge is about to supersede — both the row already stored at the incoming
     * quake's own id, and (separately, since one ingest call can supersede both at once — see that
     * function's own `deleteIds` comment) the row a cross-id merge's `replacesId` points at — and
     * keeps [ORIGIN_ARCHIVE]/[ORIGIN_DEBUG] whenever either already carries it, regardless of which
     * origin the CALLER passed in. A merge can still freely move a row between [ORIGIN_FEED] and
     * [ORIGIN_LIVE] (neither is protected, and this function prunes both identically anyway), but
     * an archived/debug row can no longer be silently downgraded into a prunable one.
     */
    fun pruneOldRows(cutoffMillis: Long)

    /**
     * Task 3 (Plan 4): `AlertDigestWorker`'s (androidMain, `composeApp`) own "what's new since my
     * last run" query — every row whose `timeMillis` is strictly after [sinceMillis] AND whose
     * `origin` is [ORIGIN_FEED] or [ORIGIN_LIVE], newest first. The worker reads its own persisted
     * `alert_last_run` meta value as [sinceMillis], evaluates [com.yugma.terrawatch.data.
     * AlertRuleEngine] against exactly the rows this returns, then advances that meta value.
     *
     * [ORIGIN_ARCHIVE]/[ORIGIN_DEBUG] rows are excluded even when they satisfy the time bound —
     * this is the SAME F5 guard `QuakeRepository.loadArchivePage`'s own `rules = emptyList()`
     * already enforces for the live in-session ingest path (plan-3-exit-conditions.md carried
     * item: "a user deep-scrolling History past old M6+ quakes will notification-storm on events
     * years old"), applied here to the digest worker's SEPARATE, worker-side re-evaluation instead
     * of the ingest-time one — a backfilled archive row must never be able to trigger a background
     * notification either, and a debug-injected row must never leak into real alerting. Mirrors
     * [pruneOldRows]'s own eligible-origin set exactly (by coincidence of policy, not by shared
     * code — the two are independent concerns that currently happen to agree on which origins
     * count; a future origin added to one set should not be assumed to belong in the other without
     * its own decision).
     *
     * Strict `>`, not `>=` — a row exactly AT [sinceMillis] was already considered by whichever
     * run first recorded that cutoff (mirrors [pruneOldRows]'s own strict `<` on the opposite side
     * of an identical boundary-value question).
     */
    fun newSince(sinceMillis: Long): List<DomainQuake>

    companion object {
        const val ORIGIN_FEED = "feed"
        const val ORIGIN_ARCHIVE = "archive"
        const val ORIGIN_LIVE = "live"
        const val ORIGIN_DEBUG = "debug"
    }
}
