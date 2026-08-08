package com.yugma.terrawatch.data

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.yugma.terrawatch.database.QuakeDao
import com.yugma.terrawatch.database.TerraWatchDb
import com.yugma.terrawatch.model.MagRevision
import com.yugma.terrawatch.model.Quake
import com.yugma.terrawatch.model.QuakeStatus
import com.yugma.terrawatch.model.Source
import com.yugma.terrawatch.network.EmscLiveSource
import com.yugma.terrawatch.network.UsgsApi
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runTest
import kotlin.coroutines.CoroutineContext
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals

class QuakeRepositoryConcurrencyTest {
    private lateinit var dao: QuakeDao

    @BeforeTest fun setup() {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        TerraWatchDb.Schema.create(driver)
        dao = QuakeDao(TerraWatchDb(driver))
    }

    private fun repo() = QuakeRepository(
        UsgsApi(HttpClient(MockEngine { respond("", HttpStatusCode.NotFound) })),
        EmscLiveSource(HttpClient(MockEngine { respond("", HttpStatusCode.NotFound) })),
        dao, clock = { 2_000_000 },
        ioDispatcher = Dispatchers.Default,   // real parallelism — the point of this test
    )

    private fun q(id: String, source: Source, lat: Double, updated: Long) =
        Quake(id, 1_950_000, lat, 126.5, 10.0, 5.5, "mw", "P", false, null,
            QuakeStatus.AUTOMATIC, mapOf(source to id),
            listOf(MagRevision(5.5, "mw", updated, source)), updated)

    @Test fun `20 concurrent ingests of the same twin pair yield exactly one row`() = runTest {
        val r = repo()
        // Two agencies' variants of one event, ingested concurrently many times.
        val usgs = q("us1", Source.USGS, 7.10, updated = 1_950_000)
        val emsc = q("e1", Source.EMSC, 7.14, updated = 1_960_000)
        (1..10).flatMap { i ->
            listOf(
                async(Dispatchers.Default) { r.ingest(usgs.copy(updatedAtMillis = 1_950_000L + i)) },
                async(Dispatchers.Default) { r.ingest(emsc.copy(updatedAtMillis = 1_960_000L + i)) },
            )
        }.awaitAll()
        assertEquals(1, dao.countAll(), "concurrent ingest must never leave duplicates")
    }

    @Test fun `ingest returns on caller thread but work ran on io dispatcher`() = runTest {
        // Behavioral proxy: repository built with an ioDispatcher that records usage.
        var used = false
        val recording: CoroutineDispatcher = object : CoroutineDispatcher() {
            override fun dispatch(context: CoroutineContext, block: Runnable) {
                used = true
                Dispatchers.Default.dispatch(context, block)
            }
        }
        val r = QuakeRepository(
            UsgsApi(HttpClient(MockEngine { respond("", HttpStatusCode.NotFound) })),
            EmscLiveSource(HttpClient(MockEngine { respond("", HttpStatusCode.NotFound) })),
            dao, clock = { 2_000_000 }, ioDispatcher = recording,
        )
        r.ingest(q("us9", Source.USGS, 7.0, 1_900_000))
        assertEquals(true, used, "ingest must hop to ioDispatcher")
        assertEquals(1, dao.countAll())
    }
}
