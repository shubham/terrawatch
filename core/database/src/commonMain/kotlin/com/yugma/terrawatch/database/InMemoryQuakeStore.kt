package com.yugma.terrawatch.database

import com.yugma.terrawatch.model.FavoriteAlertType
import com.yugma.terrawatch.model.FavoritePlace
import com.yugma.terrawatch.model.GeoPoint
import com.yugma.terrawatch.model.Quake as DomainQuake
import com.yugma.terrawatch.model.magnitudeBand
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update

/**
 * Task 9 (Plan 3) storage-decision-spike fallback: a [QuakeStore] backed by plain in-memory
 * collections instead of a real `SqlDriver`. See [QuakeStore]'s own kdoc for the spike this class
 * came out of — SQLDelight's web-worker driver needs `generateAsync=true`, which (confirmed by
 * actually flipping the flag and recompiling `core:database`, not just reasoned about) turns every
 * mutating call on the shared generated `TerraWatchDb` interface `suspend`, rippling past this
 * module into plain Compose `onClick`/`onFinish` lambdas in `composeApp` with no coroutine scope in
 * hand. Rejected as too invasive for this task's 30-minute decision gate; this class is the
 * sanctioned fallback so wasmJs gets a real, working [QuakeStore] today.
 *
 * Lives in `commonMain`, not a wasmJs-only source set, matching the plan's own offered fork
 * ("wasm-only source set or commonMain"): nothing about this class's logic is platform-specific, so
 * keeping it common makes it trivially unit-testable from `jvmTest`
 * ([InMemoryQuakeStoreTest] — the same jvmTest source set [QuakeDaoTest] itself lives in) with no
 * extra test-source-set plumbing, and leaves it available to any future target/test that wants a
 * `SqlDriver`-free [QuakeStore] without another rewrite.
 *
 * **No persistence across a reload** — a plain in-memory [Map], gone the moment the browser tab
 * closes or refreshes. An accepted, documented gap: a web reload starts this store empty every time,
 * same as every other piece of this app's in-tab state, until web persistence is revisited for real
 * (Plan 4+: `localStorage`/IndexedDB, or reconsidering the async SQLDelight driver this class's own
 * kdoc documents rejecting FOR THIS TASK, not forever).
 *
 * **Concurrency**: single-writer-in-practice, not separately locked here —
 * [com.yugma.terrawatch.data.QuakeRepository]'s own `ingestMutex` already serializes every real
 * write path (poll, live WebSocket, archive backfill) before it ever reaches a [QuakeStore], the same
 * protection [QuakeDao] itself leans on rather than adding its own. [quakes] is a [MutableStateFlow]
 * regardless (not a plain `var`), updated via the lock-free [MutableStateFlow.update]: that is what
 * makes [recent] genuinely reactive — a [Flow] derived via [Flow.map] over a hot [MutableStateFlow]
 * re-emits to every collector on each mutation, no polling required — and costs nothing extra on the
 * single-threaded wasmJs target this class actually ships to.
 */
class InMemoryQuakeStore(private val clock: () -> Long = { 0L }) : QuakeStore {
    private val quakes = MutableStateFlow<Map<String, DomainQuake>>(emptyMap())

    // Parallel to `quakes`, not folded into one Map<String, Pair<DomainQuake, Long>>: keeping the
    // domain map's value type exactly DomainQuake (no wrapper) is what lets every read method below
    // stay a plain `.values` filter/map with no unwrapping step. Pruned in lockstep with `quakes` in
    // both write paths ([replaceAndDelete], [deleteByIdPrefix]) so [lastFetchedAtMillis] mirrors
    // QuakeDao's real `MAX(fetchedAtMillis) FROM quake` faithfully — the max over rows that STILL
    // EXIST, not a monotonic high-water mark that would keep reporting a since-purged row's
    // timestamp forever.
    private val fetchedAt = mutableMapOf<String, Long>()

    private val meta = mutableMapOf<String, String>()

    // Task 2 (Plan 4), origin tagging: parallel to `quakes`/`fetchedAt` above, same reasoning —
    // keeps DomainQuake itself free of an `origin` field (controller decision, see QuakeStore's own
    // kdoc) while still letting pruneOldRows below tell feed/live rows apart from archive/debug
    // ones. Pruned in lockstep with `quakes`/`fetchedAt` in both write paths (replaceAndDelete,
    // deleteByIdPrefix) for the identical "no stale bookkeeping for a row that no longer exists"
    // reason fetchedAt's own kdoc gives.
    private val originById = mutableMapOf<String, String>()

    override fun byId(id: String): DomainQuake? = quakes.value[id]

    override fun recent(sinceMillis: Long): Flow<List<DomainQuake>> =
        quakes.map { current -> current.values.byRecency { it.timeMillis >= sinceMillis } }

    override fun pageBefore(timeMillis: Long, limit: Int, minMag: Double?): List<DomainQuake> =
        quakes.value.values
            .byRecency { it.timeMillis < timeMillis && matchesMinMag(it, minMag) }
            .take(limit)

    // No default here — same "overriding functions can't redeclare a default" restriction
    // QuakeDao.pageBetween's own comment documents; QuakeStore.pageBetween's interface-level
    // default is what lets a QuakeStore-typed caller (QuakeRepository) keep omitting it.
    override fun pageBetween(lowerInclusive: Long, upperExclusive: Long, minMag: Double?, placeQuery: String?): List<DomainQuake> =
        quakes.value.values.byRecency {
            it.timeMillis >= lowerInclusive && it.timeMillis < upperExclusive &&
                matchesMinMag(it, minMag) && matchesPlaceQuery(it, placeQuery)
        }

    override fun lastFetchedAtMillis(): Long? = fetchedAt.values.maxOrNull()

    override fun quakesPerDay(sinceMillis: Long): List<DayCount> =
        quakes.value.values
            .filter { it.timeMillis >= sinceMillis }
            .groupingBy { it.timeMillis / 86_400_000L }
            .eachCount()
            .map { (bucket, n) -> DayCount(bucket, n.toLong()) }
            .sortedBy { it.dayBucket }

    // No explicit ordering, matching the SQL query's own lack of an ORDER BY on this aggregate
    // (QuakeDao.bandDistribution's .sq source has none either) — every real caller (InsightsViewModel)
    // already re-derives a fixed display order of its own rather than trusting either backend's.
    override fun bandDistribution(sinceMillis: Long): List<BandCount> =
        quakes.value.values
            .filter { it.timeMillis >= sinceMillis }
            .groupingBy { magnitudeBand(it.mag) }
            .eachCount()
            .map { (band, n) -> BandCount(band, n.toLong()) }

    override fun strongest(sinceMillis: Long): DomainQuake? =
        quakes.value.values
            .filter { it.timeMillis >= sinceMillis && it.mag != null }
            .maxWithOrNull(compareBy<DomainQuake> { it.mag }.thenBy { it.timeMillis })

    override fun deleteByIdPrefix(prefix: String) {
        quakes.update { current -> current.filterKeys { !it.startsWith(prefix) } }
        fetchedAt.keys.removeAll { it.startsWith(prefix) }
        originById.keys.removeAll { it.startsWith(prefix) }
    }

    override fun metaGet(key: String): String? = meta[key]

    override fun metaPut(key: String, value: String) {
        meta[key] = value
    }

    /** Task 2 (Plan 4), Fix Round 1 (review finding): see [QuakeStore.originOf]'s own kdoc. */
    override fun originOf(id: String): String? = originById[id]

    // Task 2 (Plan 4), M1 torn-write fix. Fix Round 1 (review finding): the ORIGINAL version of
    // this note pointed at "this class's own kdoc" for why no guard is needed here — meaning the
    // class-level Concurrency section's QuakeRepository.ingestMutex reasoning above. That's the
    // WRONG class and the wrong path for THIS method: ingestMutex only ever serializes writes to
    // `quakes` (via QuakeRepository.ingest's replace/replaceAndDelete calls) — it has nothing to do
    // with `meta`, and neither of metaPutAll's real callers ever touches QuakeRepository or
    // acquires that mutex at all. Task 3 (Plan 4) re-review: the prior version of THIS note
    // overclaimed the caller list too (named HomeLocationStore.set, ThemeStore, AlertRuleStore, AND
    // OnboardingStore — grepped the whole repo to check, EVIDENCE INTEGRITY: only HomeLocationStore.
    // set ever called this at the time). Corrected, and now genuinely two real callers as of this
    // task: HomeLocationStore.set (home_lat/home_lon, this method's original motivating case) and
    // `com.yugma.terrawatch.alerts.AlertDigestWorker` (androidMain — its own atomic `alert_last_run`
    // + `alert_notified_ids` pair, same torn-write concern as HomeLocationStore's own, a fresh
    // instance of the identical M1-shaped hazard this method exists to close). ThemeStore/
    // AlertRuleStore/OnboardingStore each still only ever call the single-key `metaPut` — they have
    // no multi-key write to make atomic. The actual reason a plain loop is safe here: this class
    // only ever ships to the single-threaded wasmJs target (see class kdoc), and the loop body
    // itself (`meta[key] = value`, a plain synchronous Map write) has NO suspension point — nothing
    // else can run on that one JS thread until this whole forEach returns, so no reader can ever
    // observe a torn intermediate state, with or without a lock. [QuakeDao]'s real
    // `db.transaction {}` (a genuine multi-threaded, concurrent-reader environment) is what
    // actually needs, and gets, its own transactional guard — see that method's own kdoc.
    override fun metaPutAll(vararg pairs: Pair<String, String>) {
        pairs.forEach { (key, value) -> meta[key] = value }
    }

    override fun replace(quake: DomainQuake, origin: String) = replaceAndDelete(quake, deleteIds = emptyList(), origin = origin)

    // Atomic from any collector's point of view, same guarantee QuakeDao.replaceAndDelete's own
    // kdoc documents for the real db.transaction {} — deleteIds removal and the incoming write both
    // land inside the ONE `quakes.update {}` CAS step, so a recent()/Flow collector never observes
    // an in-between state (e.g. a transient empty list when a deleted row was the only one in view).
    override fun replaceAndDelete(quake: DomainQuake, deleteIds: List<String>, origin: String) {
        quakes.update { current -> (current - deleteIds.toSet()) + (quake.id to quake) }
        deleteIds.forEach { fetchedAt.remove(it); originById.remove(it) }
        fetchedAt[quake.id] = clock()
        originById[quake.id] = origin
    }

    /**
     * Task 2 (Plan 4): retention — mirrors [QuakeDao.pruneOldRows] exactly (same cutoff semantics,
     * same 'feed'/'live'-only exemption set); see that function's kdoc / [QuakeStore.pruneOldRows]'s
     * interface kdoc for the full ruling. A row with no recorded origin at all (should not happen
     * outside a test that pokes [quakes] directly, since every real write path goes through
     * [replaceAndDelete]) defaults to [QuakeStore.ORIGIN_FEED] — the same "never crash on a
     * data-shape surprise, degrade to the least-surprising default" posture [bandFromLabel] takes
     * above, not a silent data-loss risk: an unrecognized/missing origin is prunable, same as a
     * genuine feed row, rather than accidentally becoming immortal.
     */
    override fun pruneOldRows(cutoffMillis: Long) {
        val expired = quakes.value.values
            .filter { it.timeMillis < cutoffMillis && (originById[it.id] ?: QuakeStore.ORIGIN_FEED) in PRUNABLE_ORIGINS }
            .map { it.id }
            .toSet()
        if (expired.isEmpty()) return
        quakes.update { current -> current - expired }
        expired.forEach { fetchedAt.remove(it); originById.remove(it) }
    }

    /**
     * Task 3 (Plan 4): mirrors [QuakeDao.newSince] exactly (same contract, other [QuakeStore]
     * implementation) -- see [QuakeStore.newSince]'s own interface kdoc for the full ruling. A
     * missing origin (should not happen outside a test poking [quakes] directly -- see
     * [pruneOldRows]'s own kdoc for the identical defensive default) resolves to [QuakeStore.
     * ORIGIN_FEED], i.e. eligible, matching [pruneOldRows]'s own "degrade to the least-surprising
     * default" posture rather than silently excluding an origin-less row from ever alerting.
     *
     * Fix Round 1 (I1): cursor moved from `timeMillis` to `fetchedAt[id]` (this store's own
     * write-clock map, parallel to [quakes] -- see that field's own kdoc), mirroring
     * [QuakeDao.newSince]'s identical `timeMillis` -> `fetchedAtMillis` correction (see that
     * method's own kdoc for the publication-lag/revision cases a `timeMillis` cursor silently
     * missed). A missing `fetchedAt` entry (should not happen outside a test poking [quakes]
     * directly -- the one real write path, [replaceAndDelete], always populates both together)
     * defaults to `0L`, i.e. EXCLUDED by any realistic positive [sinceMillis] -- the OPPOSITE
     * direction from [originById]'s own "missing degrades to eligible" default just above,
     * deliberately: this field gates whether a row can ever reach a notification at all, so an
     * impossible/test-only data shape should degrade toward silence, never toward a spurious
     * alert.
     */
    override fun newSince(sinceMillis: Long): List<DomainQuake> =
        quakes.value.values.byRecency {
            (fetchedAt[it.id] ?: 0L) > sinceMillis && (originById[it.id] ?: QuakeStore.ORIGIN_FEED) in ALERT_ELIGIBLE_ORIGINS
        }

    /**
     * Commit "since-last-visit summary": mirrors [QuakeDao.newSinceCount] exactly (same eligible
     * origins as [newSince] above, same "missing origin/fetchedAt defaults" reasoning that
     * method's own kdoc already documents) — see [QuakeStore.newSinceCount]'s own interface kdoc
     * for the full ruling. `mag == null` never satisfies `matchesMinMag` (that helper's own `mag !=
     * null && mag >= minMag` check), matching the SQL query's three-valued-logic behavior on a NULL
     * column exactly.
     */
    override fun newSinceCount(sinceMillis: Long, minMag: Double): Long =
        quakes.value.values.count {
            (fetchedAt[it.id] ?: 0L) > sinceMillis &&
                (originById[it.id] ?: QuakeStore.ORIGIN_FEED) in ALERT_ELIGIBLE_ORIGINS &&
                matchesMinMag(it, minMag)
        }.toLong()

    // Task 2 (Plan 5): favorite_place's in-memory mirror -- a MutableStateFlow (not a plain
    // MutableList), same "Flow.map over a hot StateFlow re-emits to every collector on each
    // mutation, no polling required" reasoning `quakes` above already documents for the identical
    // shape. `nextFavoritePlaceId` is a plain incrementing counter standing in for SQLite's own
    // AUTOINCREMENT -- this store only ever ships to the single-threaded wasmJs target (see this
    // class's own Concurrency section above), so a bare `var` needs no atomic/locked increment.
    private val favoritePlacesState = MutableStateFlow<List<FavoritePlace>>(emptyList())
    private var nextFavoritePlaceId = 1L

    override fun favoritePlaces(): Flow<List<FavoritePlace>> = favoritePlacesState

    override fun insertFavoritePlace(label: String, point: GeoPoint, alertType: FavoriteAlertType) {
        val id = nextFavoritePlaceId++
        favoritePlacesState.update { current -> current + FavoritePlace(id, label, point, alertType) }
    }

    override fun deleteFavoritePlace(id: Long) {
        favoritePlacesState.update { current -> current.filter { it.id != id } }
    }

    override fun updateFavoritePlaceAlertType(id: Long, alertType: FavoriteAlertType) {
        favoritePlacesState.update { current ->
            current.map { if (it.id == id) it.copy(alertType = alertType) else it }
        }
    }

    // `val mag = quake.mag` first, not `quake.mag` inline twice: Quake.mag is a property declared
    // in a DIFFERENT module (core:model) from this one (core:database) — Kotlin refuses to
    // smart-cast a cross-module property read even after a `!= null` check right next to it ("Smart
    // cast to 'Double' is impossible, because 'mag' is a public API property declared in different
    // module" — hit this for real compiling this file, not a theoretical concern). A local `val`
    // copy is always smart-castable regardless of where its TYPE is declared, since the compiler is
    // tracking control flow on the local binding, not the cross-module property.
    private fun matchesMinMag(quake: DomainQuake, minMag: Double?): Boolean {
        val mag = quake.mag
        return minMag == null || (mag != null && mag >= minMag)
    }

    /** User review items 3+4 (history search): mirrors `Quake.sq`'s own `pageBetween` LIKE
     * predicate for the common case — a null/blank [placeQuery] matches everything (SQL's
     * `:placeQuery IS NULL` branch), otherwise a case-insensitive substring match against
     * [DomainQuake.place] ([String.contains]'s own `ignoreCase = true`, the in-memory equivalent of
     * SQLite's default ASCII-case-insensitive `LIKE` — see that query's own kdoc).
     *
     * Round-3 review NIT (I-1), correcting this kdoc's own prior "mirrors exactly" overclaim: this
     * does NOT actually match `Quake.sq`'s `LIKE '%' || :placeQuery || '%'` for every input — SQL
     * `LIKE` treats a literal `%`/`_` typed by the user as WILDCARDS (any run of characters / any
     * single character), while [String.contains] here treats them as plain literal characters with
     * no special meaning at all. The two implementations therefore diverge only when the typed query
     * itself contains a literal `%`/`_` — e.g. searching `"90%"` matches a place containing that
     * exact substring here, but on the SQL/Android path matches any place with "90" followed by
     * anything. `Quake.sq`'s own kdoc already accepts not escaping those characters as a deliberate
     * v1 gap on ITS side; this class (wasmJs's production [QuakeStore], per this file's own class
     * kdoc) never shared that gap in the first place — [String.contains] has no wildcard concept to
     * begin with, so there was nothing to escape here either way. Narrow in practice (real place
     * names essentially never contain `%`/`_`) and left as a doc-only correction, no behavior
     * change: this app's only device-verified target is Android (this class's own real usage is
     * wasmJs, out of this fix round's Android-only verification scope). */
    private fun matchesPlaceQuery(quake: DomainQuake, placeQuery: String?): Boolean =
        placeQuery == null || quake.place.contains(placeQuery, ignoreCase = true)

    private fun Collection<DomainQuake>.byRecency(predicate: (DomainQuake) -> Boolean): List<DomainQuake> =
        filter(predicate).sortedByDescending { it.timeMillis }

    private companion object {
        val PRUNABLE_ORIGINS = setOf(QuakeStore.ORIGIN_FEED, QuakeStore.ORIGIN_LIVE)

        // Task 3 (Plan 4): same two literal values as PRUNABLE_ORIGINS above, kept as its OWN named
        // constant rather than reused -- see QuakeStore.newSince's own kdoc for why: pruning and
        // alert-eligibility are independent policies that currently happen to agree, not one
        // concern wearing two names.
        val ALERT_ELIGIBLE_ORIGINS = setOf(QuakeStore.ORIGIN_FEED, QuakeStore.ORIGIN_LIVE)
    }
}
