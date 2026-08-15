package com.yugma.terrawatch.network

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

private fun readFixture(name: String): String =
    Thread.currentThread().contextClassLoader!!.getResource("fixtures/$name")!!.readText()

/**
 * Plan 4 Task 5. GATE: PASS (spike, task-5-report.md) — GdeltClient exists because the spike found
 * GDELT DOC 2.0 quality genuinely good for major-quake queries. `gdelt_articles.json` is a trimmed,
 * real recording from that spike (Indonesia M7.7, 2026-08-14/15) — the same "recorded fixture, not
 * synthetic" convention `usgs_all_hour.json` already established for [UsgsApi].
 */
class GdeltClientTest {
    @Test fun `parses title, url, domain and seendate from a successful response`() = runTest {
        val engine = MockEngine {
            respond(readFixture("gdelt_articles.json"), HttpStatusCode.OK, headersOf("Content-Type", "application/json"))
        }
        val client = GdeltClient(HttpClient(engine))
        val articles = client.searchEarthquakeNews(place = "68 km NNW of Ende, Indonesia", eventTimeMillis = 1_786_744_701_564)
        assertEquals(3, articles.size)
        val first = articles.first()
        assertEquals("2 dead after powerful 7.7 earthquake strikes off Indonesia", first.title)
        assertEquals("https://abcnews.com/International/2-dead-after-powerful-77-earthquake-strikes-off/story?id=135664681", first.url)
        assertEquals("abcnews.com", first.domain)
        assertEquals(parseGdeltSeenDate("20260815T041500Z"), first.seenAtMillis)
    }

    @Test fun `sends mode=artlist, format=json, sourcelang filter and the stripped place query`() = runTest {
        val engine = MockEngine { req ->
            val url = req.url.toString()
            assertTrue(url.contains("mode=artlist"), "got: $url")
            assertTrue(url.contains("format=json"), "got: $url")
            assertTrue(url.contains("sourcelang%3Aenglish") || url.contains("sourcelang:english"), "got: $url")
            // "68 km NNW of " stripped, "Ende, Indonesia earthquake" remains (URL-encoded).
            assertTrue(url.contains("Ende") && url.contains("Indonesia") && url.contains("earthquake"), "got: $url")
            assertTrue(!url.contains("NNW"), "distance/direction prefix must be stripped: $url")
            respond("""{"articles":[]}""", HttpStatusCode.OK)
        }
        val client = GdeltClient(HttpClient(engine))
        client.searchEarthquakeNews(place = "68 km NNW of Ende, Indonesia", eventTimeMillis = 1_786_744_701_564)
    }

    @Test fun `a 429 rate limit degrades to an empty list, not an exception`() = runTest {
        // The exact failure this spike hit live against GDELT's own rate limiter (task-5-report.md)
        // — news must never break the detail sheet/Insights card it's layered onto.
        val engine = MockEngine { respond("Please limit requests to one every 5 seconds", HttpStatusCode.TooManyRequests) }
        val client = GdeltClient(HttpClient(engine))
        assertEquals(emptyList(), client.searchEarthquakeNews(place = "Colombia", eventTimeMillis = 0))
    }

    @Test fun `a server error degrades to an empty list`() = runTest {
        val engine = MockEngine { respond("boom", HttpStatusCode.InternalServerError) }
        val client = GdeltClient(HttpClient(engine))
        assertEquals(emptyList(), client.searchEarthquakeNews(place = "Colombia", eventTimeMillis = 0))
    }

    @Test fun `a malformed JSON body on 200 degrades to an empty list, not a crash`() = runTest {
        val engine = MockEngine { respond("<html>not json</html>", HttpStatusCode.OK) }
        val client = GdeltClient(HttpClient(engine))
        assertEquals(emptyList(), client.searchEarthquakeNews(place = "Colombia", eventTimeMillis = 0))
    }
}

class GdeltPlaceQueryTest {
    @Test fun `strips the USGS distance and direction prefix`() {
        assertEquals("Ende, Indonesia earthquake", gdeltPlaceQuery("68 km NNW of Ende, Indonesia"))
    }

    @Test fun `strips a single-letter compass direction too`() {
        assertEquals("San Jose del Palmar, Colombia earthquake", gdeltPlaceQuery("5 km S of San Jose del Palmar, Colombia"))
    }

    @Test fun `a place with no distance prefix is used as-is, earthquake appended`() {
        assertEquals("South Sandwich Islands region earthquake", gdeltPlaceQuery("South Sandwich Islands region"))
    }

    @Test fun `does not double up when the place already names an earthquake`() {
        assertEquals(
            "The 2026 Kumamoto Region, Japan Earthquake",
            gdeltPlaceQuery("The 2026 Kumamoto Region, Japan Earthquake"),
        )
    }
}

class GdeltDateTimeTest {
    @Test fun `formatGdeltDateTime produces GDELT's compact yyyyMMddHHmmss shape`() {
        assertEquals("20260815041500", formatGdeltDateTime(parseGdeltSeenDate("20260815T041500Z")!!))
    }

    @Test fun `parseGdeltSeenDate round-trips through formatGdeltDateTime`() {
        val millis = parseGdeltSeenDate("20260815T041500Z")
        assertEquals("20260815041500", formatGdeltDateTime(millis!!))
    }

    @Test fun `parseGdeltSeenDate returns null for a too-short or malformed string`() {
        assertNull(parseGdeltSeenDate("garbage"))
        assertNull(parseGdeltSeenDate(""))
    }
}

class ParseArticlesTest {
    @Test fun `drops an article missing any required field, keeps the rest`() {
        val body = """
            {"articles":[
              {"title":"Has everything","url":"https://a.com/1","domain":"a.com","seendate":"20260815T041500Z"},
              {"title":"Missing url","domain":"b.com","seendate":"20260815T041500Z"},
              {"title":"Missing seendate","url":"https://c.com/1","domain":"c.com"}
            ]}
        """.trimIndent()
        val result = parseArticles(body)
        assertEquals(1, result.size)
        assertEquals("Has everything", result.first().title)
    }

    @Test fun `a missing articles key degrades to an empty list, not an exception`() {
        assertEquals(emptyList(), parseArticles("""{}"""))
    }

    @Test fun `non-JSON body degrades to an empty list, not an exception`() {
        assertEquals(emptyList(), parseArticles("not json at all"))
    }
}
