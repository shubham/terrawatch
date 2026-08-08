package com.yugma.terrawatch.database

import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToList
import com.yugma.terrawatch.model.MagRevision
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

class QuakeDao(private val db: TerraWatchDb) {
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

    fun countAll(): Long = db.quakeQueries.countAll().executeAsOne()

    fun delete(id: String) = db.quakeQueries.delete(id)

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
        fetchedAtMillis = updatedAtMillis,
    )

    private fun Quake.toDomain() = DomainQuake(
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
}
