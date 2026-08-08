package com.yugma.terrawatch.database

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.yugma.terrawatch.model.MagRevision
import com.yugma.terrawatch.model.Quake
import com.yugma.terrawatch.model.QuakeStatus
import com.yugma.terrawatch.model.Source
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.BeforeTest

class QuakeDaoTest {
    private lateinit var dao: QuakeDao

    @BeforeTest fun setup() {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        TerraWatchDb.Schema.create(driver)
        dao = QuakeDao(TerraWatchDb(driver))
    }

    private fun quake(
        id: String = "us1", updated: Long = 1000, mag: Double? = 5.0,
        sources: Map<Source, String> = mapOf(Source.USGS to id),
        revisions: List<MagRevision> = listOf(MagRevision(5.0, "mb", 1000, Source.USGS)),
    ) = Quake(id, 900, 7.1, 126.5, 10.0, mag, "mb", "Somewhere", false, null,
        QuakeStatus.AUTOMATIC, sources, revisions, updated)

    @Test fun `insert then read back`() {
        dao.upsert(quake())
        val q = assertNotNull(dao.byId("us1"))
        assertEquals(5.0, q.mag)
        assertEquals(1, dao.countAll())
    }

    @Test fun `stale update is ignored`() {
        dao.upsert(quake(updated = 2000, mag = 6.1))
        dao.upsert(quake(updated = 1000, mag = 5.0))
        assertEquals(6.1, assertNotNull(dao.byId("us1")).mag)
    }

    @Test fun `newer update merges sources and appends distinct revisions`() {
        dao.upsert(quake(updated = 1000))
        dao.upsert(quake(
            updated = 2000, mag = 6.1,
            sources = mapOf(Source.EMSC to "e1"),
            revisions = listOf(MagRevision(6.1, "mw", 2000, Source.EMSC)),
        ))
        val q = assertNotNull(dao.byId("us1"))
        assertEquals(6.1, q.mag)
        assertEquals(setOf(Source.USGS, Source.EMSC), q.sources.keys)
        assertEquals(2, q.revisions.size)
    }

    @Test fun `duplicate revision entries are not appended twice`() {
        dao.upsert(quake(updated = 1000))
        dao.upsert(quake(updated = 2000, revisions = listOf(MagRevision(5.0, "mb", 1000, Source.USGS))))
        assertEquals(1, assertNotNull(dao.byId("us1")).revisions.size)
    }

    @Test fun `pageBefore filters by magnitude and pages by time`() {
        dao.upsertAll(listOf(
            quake(id = "a", updated = 1).copy(timeMillis = 100, mag = 2.0),
            quake(id = "b", updated = 1).copy(timeMillis = 200, mag = 5.0),
            quake(id = "c", updated = 1).copy(timeMillis = 300, mag = 6.5),
        ))
        val page = dao.pageBefore(timeMillis = 400, limit = 10, minMag = 4.5)
        assertEquals(listOf("c", "b"), page.map { it.id })
    }

    @Test fun `delete removes the row`() {
        dao.upsert(quake())
        dao.delete("us1")
        assertEquals(null, dao.byId("us1"))
        assertEquals(0, dao.countAll())
    }

    @Test fun `metaGet returns null for an unknown key`() {
        assertEquals(null, dao.metaGet("feed_etag"))
    }

    @Test fun `metaPut then metaGet roundtrips and overwrites`() {
        dao.metaPut("feed_etag", "\"e1\"")
        assertEquals("\"e1\"", dao.metaGet("feed_etag"))
        dao.metaPut("feed_etag", "\"e2\"")
        assertEquals("\"e2\"", dao.metaGet("feed_etag"))
    }

    @Test fun `replace bypasses the upsert recency gate that silently drops equal-timestamp merges`() {
        // Identical starting condition on two rows: stored updatedAt=2000, sources={USGS}.
        dao.upsert(quake(id = "us1", updated = 2000))
        dao.upsert(quake(id = "us2", updated = 2000))

        // replace() with the SAME updatedAt (2000) but a different sources map must land —
        // this is the DedupeEngine-reconciled-canonical write path.
        dao.replace(quake(id = "us1", updated = 2000, sources = mapOf(Source.EMSC to "e1")))
        assertEquals(setOf(Source.EMSC), assertNotNull(dao.byId("us1")).sources.keys)

        // upsert() with the identical input is silently dropped by the recency gate
        // (incoming.updatedAtMillis <= existing.updatedAtMillis) — the exact bug replace()
        // exists to bypass for reconciled rows (Task 7 review: a late-arriving agency twin
        // with a lagging/equal timestamp must still contribute its merged fields).
        dao.upsert(quake(id = "us2", updated = 2000, sources = mapOf(Source.EMSC to "e1")))
        assertEquals(setOf(Source.USGS), assertNotNull(dao.byId("us2")).sources.keys)
    }
}
