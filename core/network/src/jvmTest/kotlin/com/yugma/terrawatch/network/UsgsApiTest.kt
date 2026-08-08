package com.yugma.terrawatch.network

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

private fun readFixture(name: String): String =
    Thread.currentThread().contextClassLoader!!.getResource("fixtures/$name")!!.readText()

class UsgsApiTest {
    @Test fun `fresh feed returns quakes and etag`() = runTest {
        val engine = MockEngine { req ->
            assertTrue(req.url.toString().contains("/summary/all_day.geojson"))
            respond(
                readFixture("usgs_all_hour.json"), HttpStatusCode.OK,
                headersOf(HttpHeaders.ETag to listOf("\"abc123\""), HttpHeaders.ContentType to listOf("application/json")),
            )
        }
        val api = UsgsApi(HttpClient(engine))
        val result = api.fetchFeed()
        val fresh = assertIs<FeedResult.Fresh>(result)
        assertTrue(fresh.quakes.isNotEmpty())
        assertEquals("\"abc123\"", fresh.etag)
    }

    @Test fun `etag is sent and 304 maps to NotModified`() = runTest {
        val engine = MockEngine { req ->
            assertEquals("\"abc123\"", req.headers[HttpHeaders.IfNoneMatch])
            respond("", HttpStatusCode.NotModified)
        }
        val api = UsgsApi(HttpClient(engine))
        assertIs<FeedResult.NotModified>(api.fetchFeed(previousEtag = "\"abc123\""))
    }

    @Test fun `server error maps to Failure not exception`() = runTest {
        val engine = MockEngine { respond("boom", HttpStatusCode.InternalServerError) }
        val api = UsgsApi(HttpClient(engine))
        assertIs<FeedResult.Failure>(api.fetchFeed())
    }

    @Test fun `archive query builds fdsn url and parses`() = runTest {
        val engine = MockEngine { req ->
            val u = req.url.toString()
            assertTrue(u.contains("/fdsnws/event/1/query"))
            assertTrue(u.contains("format=geojson"))
            assertTrue(u.contains("limit=200"))
            assertTrue(u.contains("orderby=time"))
            assertTrue(u.contains("endtime="))
            respond(
                readFixture("usgs_all_hour.json"), HttpStatusCode.OK,
                headersOf(HttpHeaders.ContentType, "application/json"),
            )
        }
        val api = UsgsApi(HttpClient(engine))
        assertTrue(api.queryArchive(endTimeMillis = 1_754_600_000_000).isNotEmpty())
    }

    @Test fun `cancellation propagates, not mapped to Failure`() = runTest {
        val engine = MockEngine { delay(10_000); respond("", HttpStatusCode.OK) }
        val api = UsgsApi(HttpClient(engine))
        var completedNormallyWith: FeedResult? = null
        val job = launch {
            // Only reached if fetchFeed() returns a value instead of propagating cancellation —
            // this is the assertion that actually discriminates the bug: job.isCancelled alone
            // is true either way, because the coroutine framework forces a cancelled Job's final
            // state to Cancelled even when the body swallows CancellationException and returns
            // normally (verified empirically against the pre-fix code below).
            completedNormallyWith = api.fetchFeed()
        }
        testScheduler.runCurrent()
        job.cancel()
        testScheduler.advanceUntilIdle()
        assertTrue(job.isCancelled)
        assertEquals(null, completedNormallyWith, "fetchFeed() must not return a value (e.g. Failure) after cancellation — it must propagate CancellationException instead")
    }

    @Test fun `archive non-2xx throws clear http error, not parse garbage`() = runTest {
        val engine = MockEngine { respond("<html>503</html>", HttpStatusCode.ServiceUnavailable) }
        val api = UsgsApi(HttpClient(engine))
        val ex = assertFailsWith<IllegalStateException> { api.queryArchive(endTimeMillis = 1_754_600_000_000) }
        assertTrue(ex.message!!.contains("503"), "got: ${ex.message}")
    }
}
