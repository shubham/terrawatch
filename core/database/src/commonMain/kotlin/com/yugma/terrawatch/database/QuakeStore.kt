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
 *    field, and no READ method on this interface ever surfaces one back out; see `Quake.sq`'s own
 *    schema-comment for the full ruling.
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
     * later DedupeEngine merge — an [ORIGIN_ARCHIVE] row that later gets merged with a same-event
     * [ORIGIN_LIVE]/[ORIGIN_FEED] arrival adopts the merge's origin, not its original one. In
     * practice this is rare and low-stakes: [com.yugma.terrawatch.data.DedupeEngine]'s own match
     * window is ±90 SECONDS of real event time, so only two variants of the exact same real-world
     * quake can ever merge, regardless of when each was fetched — an archive-backfilled event and a
     * live/feed arrival for that SAME event necessarily share a recent `timeMillis`, so the merged
     * row is nowhere near this function's 30-day cutoff by the time the origin-flip could matter.
     */
    fun pruneOldRows(cutoffMillis: Long)

    companion object {
        const val ORIGIN_FEED = "feed"
        const val ORIGIN_ARCHIVE = "archive"
        const val ORIGIN_LIVE = "live"
        const val ORIGIN_DEBUG = "debug"
    }
}
