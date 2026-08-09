package com.yugma.terrawatch.database

import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToList
import com.yugma.terrawatch.model.MagRevision
import com.yugma.terrawatch.model.MagnitudeBand
import com.yugma.terrawatch.model.Quake as DomainQuake
import com.yugma.terrawatch.model.QuakeStatus
import com.yugma.terrawatch.model.Source
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.Serializable

@Serializable
private data class RevisionJson(val mag: Double, val magType: String?, val atMillis: Long, val source: String)

/** One [QuakeDao.quakesPerDay] row — `dayBucket` is `timeMillis / 86_400_000` (UTC epoch-day
 * index, matching the SQL projection verbatim), `n` the count of quakes whose `timeMillis` falls
 * in that bucket. Never carries a zero-count bucket itself (SQL `GROUP BY` only ever returns
 * buckets that actually have at least one row) — `com.yugma.terrawatch.insights.fillDayGaps`
 * (composeApp) is what turns this sparse list into a dense, gap-filled one for the chart. */
data class DayCount(val dayBucket: Long, val n: Long)

/** One [QuakeDao.bandDistribution] row. [band] is mapped defensively from the SQL query's raw
 * string column via [bandFromLabel] — an unrecognized label (should never happen: the CASE
 * expression's own branches are the only five strings [MagnitudeBand] has) degrades to
 * [MagnitudeBand.UNKNOWN] rather than throwing, same "never crash on a data-shape surprise"
 * posture [DedupeEngine]/[QuakeRepository] take elsewhere in this codebase. */
data class BandCount(val band: MagnitudeBand, val n: Long)

private fun bandFromLabel(label: String?): MagnitudeBand =
    MagnitudeBand.entries.firstOrNull { it.name == label } ?: MagnitudeBand.UNKNOWN

class QuakeDao(private val db: TerraWatchDb, private val clock: () -> Long = { 0L }) {
    private val json = Json

    fun upsert(quake: DomainQuake) = db.transaction { upsertInternal(quake) }

    fun upsertAll(quakes: List<DomainQuake>) = db.transaction { quakes.forEach { upsertInternal(it) } }

    private fun upsertInternal(incoming: DomainQuake) {
        val existing = byIdInternal(incoming.id)
        val toWrite = when {
            existing == null -> incoming
            incoming.updatedAtMillis <= existing.updatedAtMillis -> return
            else -> incoming.copy(
                sources = existing.sources + incoming.sources,
                revisions = existing.revisions +
                    incoming.revisions.filter { r ->
                        existing.revisions.none { it.mag == r.mag && it.atMillis == r.atMillis && it.source == r.source }
                    },
            )
        }
        db.quakeQueries.insertOrReplace(toWrite.toRow())
    }

    fun byId(id: String): DomainQuake? = byIdInternal(id)

    private fun byIdInternal(id: String): DomainQuake? =
        db.quakeQueries.byId(id).executeAsOneOrNull()?.toDomain()

    fun recent(sinceMillis: Long): Flow<List<DomainQuake>> =
        db.quakeQueries.recent(sinceMillis).asFlow().mapToList(Dispatchers.Default)
            .map { rows -> rows.map { it.toDomain() } }

    fun pageBefore(timeMillis: Long, limit: Int, minMag: Double?): List<DomainQuake> =
        db.quakeQueries.pageBefore(timeMillis, minMag, limit.toLong()).executeAsList().map { it.toDomain() }

    /**
     * Task 5 fix round 1 (Plan 3, review Critical): `[lowerInclusive, upperExclusive)` range read,
     * no LIMIT — see [com.yugma.terrawatch.data.QuakeRepository.pageBetween]'s own kdoc for why
     * History's display query needs a range instead of a count-based page.
     */
    fun pageBetween(lowerInclusive: Long, upperExclusive: Long, minMag: Double?): List<DomainQuake> =
        db.quakeQueries.pageBetween(lowerInclusive, upperExclusive, minMag).executeAsList().map { it.toDomain() }

    fun countAll(): Long = db.quakeQueries.countAll().executeAsOne()

    fun lastFetchedAtMillis(): Long? = db.quakeQueries.lastFetchedAt().executeAsOneOrNull()?.MAX

    /**
     * Task 6 (Plan 3): Insights' "quakes per day" bar chart source — one [DayCount] per calendar
     * day (UTC epoch-day bucket) that has at least one quake with `timeMillis >= [sinceMillis]`,
     * ascending by bucket. Sparse by construction (a zero-quake day contributes no row at all,
     * per SQL `GROUP BY` semantics) — see [DayCount]'s own kdoc for who fills the gaps.
     */
    fun quakesPerDay(sinceMillis: Long): List<DayCount> =
        db.quakeQueries.quakesPerDay(sinceMillis).executeAsList().map { DayCount(it.dayBucket, it.n) }

    /**
     * Task 6 (Plan 3): Insights' "by magnitude" distribution — one [BandCount] per [MagnitudeBand]
     * that has at least one quake with `timeMillis >= [sinceMillis]` (also sparse, same reasoning
     * as [quakesPerDay] — a band with zero matches in-window contributes no row).
     */
    fun bandDistribution(sinceMillis: Long): List<BandCount> =
        db.quakeQueries.bandDistribution(sinceMillis).executeAsList().map { BandCount(bandFromLabel(it.band), it.n) }

    /**
     * Task 6 (Plan 3): Insights' "strongest this period" card — the single highest-magnitude quake
     * with `timeMillis >= [sinceMillis]`, or null when the window has no quake with a known
     * magnitude (empty window entirely, or every row in it has `mag IS NULL`).
     *
     * Maps via [Strongest.toDomain], NOT [Quake.toDomain] — SQLDelight generates a distinct
     * `Strongest` row class (not the table's own `Quake` row type) for this query, because the
     * `AND mag IS NOT NULL` predicate lets it narrow `mag`'s column type to non-null `Double`
     * (vs. `Quake.mag: Double?`); the two mapping extensions below share [rowToDomain] rather
     * than duplicating the field-by-field construction twice.
     */
    fun strongest(sinceMillis: Long): DomainQuake? =
        db.quakeQueries.strongest(sinceMillis).executeAsOneOrNull()?.toDomain()

    fun delete(id: String) = db.quakeQueries.delete(id)

    /** Fix Round 1 (I2): bulk-purges every row whose id starts with [prefix] — used to sweep up
     * debug-injected quakes (HomeViewModel.injectDebugQuake's "debug-"-prefixed ids) in one
     * statement instead of tracking individual ids to delete. */
    fun deleteByIdPrefix(prefix: String) = db.quakeQueries.deleteByIdPrefix(prefix)

    fun metaGet(key: String): String? = db.quakeQueries.meta_get(key).executeAsOneOrNull()

    fun metaPut(key: String, value: String) { db.quakeQueries.meta_put(key, value) }

    /**
     * Unconditional write for DedupeEngine-reconciled rows. The reconciler has already
     * resolved recency (updatedAtMillis = max of both sides) and merged sources/revisions,
     * so the upsert() recency gate must NOT run — it would silently drop a merge whenever
     * the surviving updatedAtMillis equals the stored one (late-arriving agency twin with
     * a lagging timestamp). Idempotent for self-merges: reconcile() of an already-stored
     * row reproduces the stored row byte-for-byte.
     */
    fun replace(quake: DomainQuake) = replaceAndDelete(quake, deleteIds = emptyList())

    /**
     * Atomically delete every id in [deleteIds] and write [quake], as ONE transaction — one
     * emission of the final state for any live collector.
     *
     * ingest()'s merge-write path needs both the removal of superseded/orphaned row(s) and the
     * write of the reconciled canonical to land together: doing them as separate calls (delete()
     * then replace()) is separate transactions, and SQLDelight fires a table-changed notification
     * per commit — so a live collector on recent()/[Flow] observes the transient state in between
     * (e.g. a brief empty list when a deleted row was the only one in view). It also opens a
     * crash window where a delete commits but the write never happens, permanently losing the
     * quake. Wrapping every statement in one transaction means SQLDelight defers its invalidation
     * notification until commit, so collectors see exactly one emission of the final state, and a
     * crash before commit leaves the original rows untouched.
     *
     * Takes a list, not a single nullable id: a reconciled canonical's id can legitimately
     * differ from BOTH the id superseded via DedupeEngine's `replacesId` AND the incoming
     * quake's own id at the same time (e.g. a USGS-sourced quake whose own `id` — taken from
     * the feed's `ids` alias list — differs from its `sources[USGS]` value — taken from the
     * feed's top-level feature id; see Task 9 review round 3). Both stale rows must go.
     */
    fun replaceAndDelete(quake: DomainQuake, deleteIds: List<String> = emptyList()) = db.transaction {
        deleteIds.forEach { db.quakeQueries.delete(it) }
        db.quakeQueries.insertOrReplace(quake.toRow())
    }

    private fun DomainQuake.toRow() = Quake(
        id = id, timeMillis = timeMillis, lat = lat, lon = lon, depthKm = depthKm,
        mag = mag, magType = magType, place = place, tsunami = if (tsunami) 1 else 0,
        felt = felt?.toLong(), status = status.name,
        sourcesJson = json.encodeToString(
            MapSerializer(String.serializer(), String.serializer()),
            sources.mapKeys { it.key.name }),
        revisionsJson = json.encodeToString(
            ListSerializer(RevisionJson.serializer()),
            revisions.map { RevisionJson(it.mag, it.magType, it.atMillis, it.source.name) }),
        updatedAtMillis = updatedAtMillis,
        fetchedAtMillis = clock(),
    )

    /**
     * The one real mapping from a generated SQLDelight row's raw columns to [DomainQuake] — both
     * [Quake.toDomain] (the table's own row shape) and [Strongest.toDomain] (Task 6, Plan 3 — a
     * query-specific narrowed shape, see [strongest]'s own kdoc) delegate here instead of each
     * repeating this field-by-field construction independently, which would risk the two silently
     * drifting apart on some future field addition.
     */
    private fun rowToDomain(
        id: String, timeMillis: Long, lat: Double, lon: Double, depthKm: Double?,
        mag: Double?, magType: String?, place: String, tsunami: Long, felt: Long?,
        status: String, sourcesJson: String, revisionsJson: String, updatedAtMillis: Long,
    ) = DomainQuake(
        id = id, timeMillis = timeMillis, lat = lat, lon = lon, depthKm = depthKm,
        mag = mag, magType = magType, place = place, tsunami = tsunami == 1L,
        felt = felt?.toInt(), status = QuakeStatus.valueOf(status),
        sources = json.decodeFromString(
            MapSerializer(String.serializer(), String.serializer()), sourcesJson)
            .mapKeys { Source.valueOf(it.key) },
        revisions = json.decodeFromString(ListSerializer(RevisionJson.serializer()), revisionsJson)
            .map { MagRevision(it.mag, it.magType, it.atMillis, Source.valueOf(it.source)) },
        updatedAtMillis = updatedAtMillis,
    )

    private fun Quake.toDomain() = rowToDomain(
        id, timeMillis, lat, lon, depthKm, mag, magType, place, tsunami, felt,
        status, sourcesJson, revisionsJson, updatedAtMillis,
    )

    private fun Strongest.toDomain() = rowToDomain(
        id, timeMillis, lat, lon, depthKm, mag, magType, place, tsunami, felt,
        status, sourcesJson, revisionsJson, updatedAtMillis,
    )
}
