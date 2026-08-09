package com.yugma.terrawatch.data

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.yugma.terrawatch.database.QuakeDao
import com.yugma.terrawatch.database.TerraWatchDb
import com.yugma.terrawatch.network.EmscLiveSource
import com.yugma.terrawatch.network.UsgsApi
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

/**
 * TDD, same "MockEngine + a real QuakeRepository over an in-memory JDBC driver" pattern as
 * QuakeRepositoryTest/HomeViewModelTest — QuakeRepository is a concrete class with no interface to
 * fake against, so "fake repository" in this task's brief means exactly this shape.
 */
class HistoryPagerTest {
    private lateinit var dao: QuakeDao

    @BeforeTest fun setup() {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        TerraWatchDb.Schema.create(driver)
        dao = QuakeDao(TerraWatchDb(driver))
    }

    private fun repository(engine: MockEngine) = QuakeRepository(
        UsgsApi(HttpClient(engine)),
        EmscLiveSource(HttpClient(engine)),
        dao,
        clock = { 2_000_000L },
    )

    private fun featureJson(id: String, timeMillis: Long, mag: Double = 5.0) = """
        {
          "type": "Feature",
          "id": "$id",
          "properties": {"mag": $mag, "place": "Test $id", "time": $timeMillis, "updated": $timeMillis, "magType": "mw", "status": "automatic", "tsunami": 0},
          "geometry": {"type": "Point", "coordinates": [10.0, 20.0, 10.0]}
        }
    """.trimIndent()

    private fun featureCollection(vararg features: String) =
        """{"type":"FeatureCollection","features":[${features.joinToString(",")}]}"""

    private fun jsonHeaders() = headersOf(HttpHeaders.ContentType to listOf("application/json"))

    // ---- Pure year-boundary helpers, checked against reference epoch values computed two
    // INDEPENDENT ways outside Kotlin entirely (BSD `date -j -f ... +%s` AND python's
    // datetime.timestamp()), not derived from kotlinx-datetime itself — a bug in this file's own
    // use of that library would still be caught by these. ----

    @Test fun `yearFloorMillisOrNull is Jan 1 00-00-00 UTC of the filter year`() {
        assertEquals(1_735_689_600_000L, HistoryFilter(year = 2025).yearFloorMillisOrNull()) // 2025-01-01T00:00:00Z
    }

    @Test fun `yearCeilingMillisExclusiveOrNull is Jan 1 00-00-00 UTC of the FOLLOWING year`() {
        assertEquals(1_767_225_600_000L, HistoryFilter(year = 2025).yearCeilingMillisExclusiveOrNull()) // 2026-01-01T00:00:00Z
    }

    @Test fun `year bounds are null when no year filter is set`() {
        assertNull(HistoryFilter().yearFloorMillisOrNull())
        assertNull(HistoryFilter().yearCeilingMillisExclusiveOrNull())
    }

    // ---- loaded / failed / end ----

    @Test fun `loadNext returns Loaded with the fetched count`() = runTest {
        val engine = MockEngine { respond(featureCollection(featureJson("a", 1_000_000)), HttpStatusCode.OK, jsonHeaders()) }
        val pager = HistoryPager(repository(engine), clock = { 2_000_000L })
        assertEquals(PageResult.Loaded(1), pager.loadNext(HistoryFilter()))
    }

    @Test fun `loadNext returns Failed when the archive query fails`() = runTest {
        val engine = MockEngine { respond("boom", HttpStatusCode.InternalServerError) }
        val pager = HistoryPager(repository(engine), clock = { 2_000_000L })
        val result = assertIs<PageResult.Failed>(pager.loadNext(HistoryFilter()))
        assertIs<IllegalStateException>(result.cause)
    }

    @Test fun `loadNext returns End when the page comes back empty`() = runTest {
        val engine = MockEngine { respond(featureCollection(), HttpStatusCode.OK, jsonHeaders()) }
        val pager = HistoryPager(repository(engine), clock = { 2_000_000L })
        assertEquals(PageResult.End, pager.loadNext(HistoryFilter()))
    }

    // ---- cursor advance ----

    @OptIn(ExperimentalTime::class)
    @Test fun `loadNext advances the cursor to the oldest loaded timeMillis for the next call`() = runTest {
        var callCount = 0
        var secondCallEndtime: String? = null
        val engine = MockEngine { req ->
            callCount++
            if (callCount == 1) {
                respond(
                    featureCollection(featureJson("newest", 5_000_000), featureJson("oldest", 1_000_000)),
                    HttpStatusCode.OK, jsonHeaders(),
                )
            } else {
                secondCallEndtime = req.url.parameters["endtime"]
                respond(featureCollection(), HttpStatusCode.OK, jsonHeaders())
            }
        }
        val pager = HistoryPager(repository(engine), clock = { 9_000_000L })
        pager.loadNext(HistoryFilter())
        pager.loadNext(HistoryFilter())
        assertEquals(Instant.fromEpochMilliseconds(1_000_000L).toString(), secondCallEndtime)
    }

    // ---- filter isolation ----

    @OptIn(ExperimentalTime::class)
    @Test fun `loadNext keeps independent cursors per filter`() = runTest {
        val seenEndtimes = mutableListOf<String?>()
        val engine = MockEngine { req ->
            seenEndtimes += req.url.parameters["endtime"]
            respond(featureCollection(featureJson("a", 3_000_000)), HttpStatusCode.OK, jsonHeaders())
        }
        val pager = HistoryPager(repository(engine), clock = { 9_000_000L })
        val filterA = HistoryFilter(minMag = 6.0)
        val filterB = HistoryFilter(minMag = 4.5)

        pager.loadNext(filterA) // filterA's cursor advances to 3_000_000 afterward
        pager.loadNext(filterA) // filterA's 2nd call should ask before 3_000_000
        pager.loadNext(filterB) // filterB's 1st call must still start fresh from "now" — filterA never touched it

        assertEquals(Instant.fromEpochMilliseconds(9_000_000L).toString(), seenEndtimes[0])
        assertEquals(Instant.fromEpochMilliseconds(3_000_000L).toString(), seenEndtimes[1])
        assertEquals(Instant.fromEpochMilliseconds(9_000_000L).toString(), seenEndtimes[2])
    }

    // ---- persistence roundtrip ----

    @OptIn(ExperimentalTime::class)
    @Test fun `a fresh HistoryPager instance resumes from the persisted cursor`() = runTest {
        val firstEngine = MockEngine { respond(featureCollection(featureJson("a", 1_500_000)), HttpStatusCode.OK, jsonHeaders()) }
        val firstPager = HistoryPager(repository(firstEngine), clock = { 9_000_000L })
        firstPager.loadNext(HistoryFilter()) // advances + persists the cursor to 1_500_000 via the shared `dao`

        var secondCallEndtime: String? = null
        val secondEngine = MockEngine { req ->
            secondCallEndtime = req.url.parameters["endtime"]
            respond(featureCollection(), HttpStatusCode.OK, jsonHeaders())
        }
        // A second, independent HistoryPager (own empty in-memory cursor Map) over a SECOND,
        // independent QuakeRepository/HttpClient — but the SAME underlying `dao` — simulating a
        // fresh app session (new ViewModel graph) reopening History against the same on-disk cache.
        // If persistence weren't real, this call would fall back to "now" (9_000_000) instead.
        val secondRepository = QuakeRepository(
            UsgsApi(HttpClient(secondEngine)), EmscLiveSource(HttpClient(secondEngine)), dao, clock = { 9_000_000L },
        )
        val secondPager = HistoryPager(secondRepository, clock = { 9_000_000L })
        secondPager.loadNext(HistoryFilter())

        assertEquals(Instant.fromEpochMilliseconds(1_500_000L).toString(), secondCallEndtime)
    }

    // ---- year filter: initial cursor + floor stop ----

    @OptIn(ExperimentalTime::class)
    @Test fun `a year filter seeds its first cursor at that year's Dec 31 23-59-59-999 UTC`() = runTest {
        var seenEndtime: String? = null
        val engine = MockEngine { req ->
            seenEndtime = req.url.parameters["endtime"]
            respond(featureCollection(), HttpStatusCode.OK, jsonHeaders())
        }
        val pager = HistoryPager(repository(engine), clock = { 9_000_000_000_000L })
        val filter = HistoryFilter(year = 2025)
        pager.loadNext(filter)
        val expectedCursor = filter.yearCeilingMillisExclusiveOrNull()!! - 1
        assertEquals(Instant.fromEpochMilliseconds(expectedCursor).toString(), seenEndtime)
    }

    @Test fun `a year filter stops paging once the cursor has crossed below Jan 1 UTC, without another network call`() = runTest {
        var callCount = 0
        // Dated one day before the 2020 floor -- a real-world "the whole page spilled into the
        // prior year" case (a rare, sparse magnitude filter can do this on its very first page),
        // not a fabricated one -- see HistoryPager's own kdoc.
        val beforeFloor = HistoryFilter(year = 2020).yearFloorMillisOrNull()!! - 86_400_000L
        val engine = MockEngine {
            callCount++
            respond(featureCollection(featureJson("spill", beforeFloor)), HttpStatusCode.OK, jsonHeaders())
        }
        val pager = HistoryPager(repository(engine), clock = { 9_000_000_000_000L })
        val filter = HistoryFilter(year = 2020)

        assertEquals(PageResult.Loaded(1), pager.loadNext(filter))
        assertEquals(1, callCount)

        // Cursor is now `beforeFloor`, already < the 2020 floor -- must short-circuit to End
        // without a second network call.
        assertEquals(PageResult.End, pager.loadNext(filter))
        assertEquals(1, callCount, "must not have made a second network call once the floor was already crossed")
    }
}
