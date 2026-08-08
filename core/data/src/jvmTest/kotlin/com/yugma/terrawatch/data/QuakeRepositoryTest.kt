package com.yugma.terrawatch.data

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import app.cash.turbine.test
import com.yugma.terrawatch.database.QuakeDao
import com.yugma.terrawatch.database.TerraWatchDb
import com.yugma.terrawatch.model.MagRevision
import com.yugma.terrawatch.model.Quake
import com.yugma.terrawatch.model.QuakeStatus
import com.yugma.terrawatch.model.Source
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import com.yugma.terrawatch.network.EmscLiveSource
import com.yugma.terrawatch.network.UsgsApi
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.BeforeTest

class QuakeRepositoryTest {
    private lateinit var dao: QuakeDao

    @BeforeTest fun setup() {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        TerraWatchDb.Schema.create(driver)
        dao = QuakeDao(TerraWatchDb(driver))
    }

    private fun quake(id: String, source: Source, mag: Double, t: Long, updated: Long = t) =
        Quake(id, t, 7.1, 126.5, 10.0, mag, "mw", "P", false, null, QuakeStatus.AUTOMATIC,
            mapOf(source to id), listOf(MagRevision(mag, "mw", updated, source)), updated)

    @Test fun `ingest stores new quake and emits on recent flow`() = runTest {
        val r = QuakeRepository(
            UsgsApi(HttpClient(MockEngine { respond("", HttpStatusCode.NotFound) })),
            EmscLiveSource(HttpClient(MockEngine { respond("", HttpStatusCode.NotFound) })),
            dao, clock = { 2_000_000 })
        r.ingest(quake("us1", Source.USGS, 5.5, t = 1_950_000))
        r.recentQuakes(windowMs = 100_000).test {
            assertEquals(listOf("us1"), awaitItem().map { it.id })
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test fun `emsc twin merges into stored usgs row`() = runTest {
        val r = QuakeRepository(
            UsgsApi(HttpClient(MockEngine { respond("", HttpStatusCode.NotFound) })),
            EmscLiveSource(HttpClient(MockEngine { respond("", HttpStatusCode.NotFound) })),
            dao, clock = { 2_000_000 })
        r.ingest(quake("us1", Source.USGS, 5.5, t = 1_950_000, updated = 1_950_000))
        r.ingest(quake("e1", Source.EMSC, 5.7, t = 1_960_000, updated = 1_960_000))
        assertEquals(1, dao.countAll())
        val stored = dao.byId("us1")!!
        assertEquals("e1", stored.sources[Source.EMSC])
    }

    @Test fun `alert fires once when threshold crossed by revision`() = runTest {
        val r = QuakeRepository(
            UsgsApi(HttpClient(MockEngine { respond("", HttpStatusCode.NotFound) })),
            EmscLiveSource(HttpClient(MockEngine { respond("", HttpStatusCode.NotFound) })),
            dao, clock = { 2_000_000 })
        r.alertEvents.test {
            r.ingest(quake("us1", Source.USGS, 5.8, t = 1_900_000, updated = 1_900_000), home = null)
            r.ingest(quake("us1", Source.USGS, 6.1, t = 1_900_000, updated = 1_910_000), home = null)
            assertEquals("world", awaitItem().matchedRuleId)
            expectNoEvents()
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test fun `refreshFeed persists etag and second call sends it`() = runTest {
        var sawIfNoneMatch: String? = null
        val engine = MockEngine { req ->
            sawIfNoneMatch = req.headers[HttpHeaders.IfNoneMatch]
            if (sawIfNoneMatch == null)
                respond(
                    """{"features":[]}""", HttpStatusCode.OK,
                    headersOf(HttpHeaders.ETag to listOf("\"e1\""), HttpHeaders.ContentType to listOf("application/json")),
                )
            else respond("", HttpStatusCode.NotModified)
        }
        val r = QuakeRepository(UsgsApi(HttpClient(engine)),
            EmscLiveSource(HttpClient(engine)), dao, clock = { 2_000_000 })
        assertEquals(RefreshStatus.UPDATED, r.refreshFeed())
        assertEquals(RefreshStatus.NOT_MODIFIED, r.refreshFeed())
        assertEquals("\"e1\"", sawIfNoneMatch)
    }

    // Task 7 review carry-over (system seam, empirically proven): ingest() MUST write the
    // reconciled canonical via replace(), never upsert() — the surviving updatedAtMillis here
    // (max(2_000_000, 1_500_000) = 2_000_000) equals the already-stored row's updatedAtMillis,
    // which is exactly the input shape that makes the DAO's upsert() recency gate silently
    // drop the write. If ingest() ever regresses to dao.upsert(), this test fails.
    @Test fun `lagging-timestamp emsc twin still contributes tsunami and sources`() = runTest {
        val r = QuakeRepository(
            UsgsApi(HttpClient(MockEngine { respond("", HttpStatusCode.NotFound) })),
            EmscLiveSource(HttpClient(MockEngine { respond("", HttpStatusCode.NotFound) })),
            dao, clock = { 2_000_000 })
        r.ingest(quake("us1", Source.USGS, 5.5, t = 1_950_000, updated = 2_000_000))
        r.ingest(quake("e1", Source.EMSC, 5.5, t = 1_960_000, updated = 1_500_000).copy(tsunami = true))
        val stored = dao.byId("us1")!!
        assertEquals(true, stored.tsunami)                 // OR survived the write
        assertEquals("e1", stored.sources[Source.EMSC])    // union survived the write
        assertEquals(1, dao.countAll())
    }
}
