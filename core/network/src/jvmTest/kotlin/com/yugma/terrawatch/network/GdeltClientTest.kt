package com.yugma.terrawatch.network

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

private fun readFixture(name: String): String =
    Thread.currentThread().contextClassLoader!!.getResource("fixtures/$name")!!.readText()

/**
 * Plan 4 Task 5. GATE: PASS (spike, task-5-report.md) — GdeltClient exists because the spike found
 * GDELT DOC 2.0 quality genuinely good for major-quake queries. `gdelt_articles.json` is a trimmed,
 * real recording from that spike (Indonesia M7.7, 2026-08-14/15) — the same "recorded fixture, not
 * synthetic" convention `usgs_all_hour.json` already established for [UsgsApi].
 *
 * Task 2b (task-2b-news-fix-report.md): [searchEarthquakeNews] now returns [NewsResult], not a bare
 * `List<NewsArticle>` — every case below that used to assert an `emptyList()` for a FAILURE (429,
 * 500, malformed body) now asserts [NewsResult.Failure] instead; a genuine zero-hit *success* is
 * its own separately-tested case ([NewsResult.Success] with an empty list), which is exactly the
 * distinction a bare empty list could never make.
 */
class GdeltClientTest {
    @Test fun `parses title, url, domain and seendate from a successful response`() = runTest {
        val engine = MockEngine {
            respond(readFixture("gdelt_articles.json"), HttpStatusCode.OK, headersOf("Content-Type", "application/json"))
        }
        val client = GdeltClient(HttpClient(engine))
        val result = client.searchEarthquakeNews(place = "68 km NNW of Ende, Indonesia", eventTimeMillis = 1_786_744_701_564)
        val success = assertIs<NewsResult.Success>(result)
        assertEquals(3, success.articles.size)
        val first = success.articles.first()
        assertEquals("2 dead after powerful 7.7 earthquake strikes off Indonesia", first.title)
        assertEquals("https://abcnews.com/International/2-dead-after-powerful-77-earthquake-strikes-off/story?id=135664681", first.url)
        assertEquals("abcnews.com", first.domain)
        assertEquals(parseGdeltSeenDate("20260815T041500Z"), first.seenAtMillis)
    }

    @Test fun `sends mode=artlist, format=json, sourcelang filter and the sanitized place query`() = runTest {
        val engine = MockEngine { req ->
            val url = req.url.toString()
            assertTrue(url.contains("mode=artlist"), "got: $url")
            assertTrue(url.contains("format=json"), "got: $url")
            assertTrue(url.contains("sourcelang%3Aenglish") || url.contains("sourcelang:english"), "got: $url")
            // "68 km NNW of " stripped, "Ende Indonesia earthquake" remains (URL-encoded) — Task 2b:
            // the comma between "Ende" and "Indonesia" is GONE, not just percent-encoded, since GDELT
            // itself rejects a literal comma (see GdeltClient's own kdoc for the live proof).
            assertTrue(url.contains("Ende") && url.contains("Indonesia") && url.contains("earthquake"), "got: $url")
            assertTrue(!url.contains("NNW"), "distance/direction prefix must be stripped: $url")
            assertTrue(!url.contains(",") && !url.contains("%2C"), "GDELT rejects a literal comma with a 200+HTML error page: $url")
            respond("""{"articles":[]}""", HttpStatusCode.OK, headersOf("Content-Type", "application/json"))
        }
        val client = GdeltClient(HttpClient(engine))
        client.searchEarthquakeNews(place = "68 km NNW of Ende, Indonesia", eventTimeMillis = 1_786_744_701_564)
    }

    @Test fun `builds the exact canonical query URL for a known place and time`() = runTest {
        var capturedUrl = ""
        val engine = MockEngine { req ->
            capturedUrl = req.url.toString()
            respond("""{"articles":[]}""", HttpStatusCode.OK, headersOf("Content-Type", "application/json"))
        }
        val client = GdeltClient(HttpClient(engine), baseUrl = "https://api.gdeltproject.org/api/v2/doc/doc")
        // 1_786_791_291_796 == 2026-08-15T10:54:51Z (the live-reproduced M6.9 Pematangsiantar quake,
        // task-2b-news-fix-report.md); +72h == 2026-08-18T10:54:51Z.
        client.searchEarthquakeNews(place = "15 km NNW of Pematangsiantar, Indonesia", eventTimeMillis = 1_786_791_291_796)
        val expected = "https://api.gdeltproject.org/api/v2/doc/doc" +
            "?query=Pematangsiantar+Indonesia+earthquake+sourcelang%3Aenglish" +
            "&mode=artlist&format=json&startdatetime=20260815105451&enddatetime=20260818105451&maxrecords=3"
        assertEquals(expected, capturedUrl)
    }

    @Test fun `a 429 rate limit resolves to Failure, not an exception`() = runTest {
        // The exact failure this spike hit live against GDELT's own rate limiter (task-5-report.md,
        // reconfirmed live in task-2b-news-fix-report.md) — news must never break the detail
        // sheet/Insights card it's layered onto.
        val engine = MockEngine { respond("Please limit requests to one every 5 seconds", HttpStatusCode.TooManyRequests) }
        val client = GdeltClient(HttpClient(engine))
        assertEquals(NewsResult.Failure, client.searchEarthquakeNews(place = "Colombia", eventTimeMillis = 0))
    }

    @Test fun `a server error resolves to Failure`() = runTest {
        val engine = MockEngine { respond("boom", HttpStatusCode.InternalServerError) }
        val client = GdeltClient(HttpClient(engine))
        assertEquals(NewsResult.Failure, client.searchEarthquakeNews(place = "Colombia", eventTimeMillis = 0))
    }

    @Test fun `a malformed JSON body on 200 resolves to Failure, not a crash`() = runTest {
        val engine = MockEngine { respond("<html>not json</html>", HttpStatusCode.OK) }
        val client = GdeltClient(HttpClient(engine))
        assertEquals(NewsResult.Failure, client.searchEarthquakeNews(place = "Colombia", eventTimeMillis = 0))
    }

    @Test fun `GDELT's own 200+HTML illegal-character error page resolves to Failure, not a false empty Success`() = runTest {
        // Live-reproduced verbatim (task-2b-news-fix-report.md): a query GDELT rejects still comes
        // back HTTP 200, Content-Type text/html, this exact body text.
        val engine = MockEngine {
            respond(
                """One or more of your keywords contained an illegal character. To use a dash in a word, place it in quotes like "f-16".""",
                HttpStatusCode.OK,
                headersOf("Content-Type", "text/html; charset=utf-8"),
            )
        }
        val client = GdeltClient(HttpClient(engine))
        assertEquals(NewsResult.Failure, client.searchEarthquakeNews(place = "Colombia", eventTimeMillis = 0))
    }

    @Test fun `a genuine zero-hit query is a Success with an empty list, not a Failure`() = runTest {
        // The distinction the whole Task 2b fix exists to preserve: this must NOT equal the Failure
        // case above even though both once collapsed to the identical emptyList().
        val engine = MockEngine {
            respond("""{"articles":[]}""", HttpStatusCode.OK, headersOf("Content-Type", "application/json"))
        }
        val client = GdeltClient(HttpClient(engine))
        val result = client.searchEarthquakeNews(place = "Colombia", eventTimeMillis = 0)
        assertEquals(NewsResult.Success(emptyList()), result)
    }

    // Fix Round 1 (Review 1, MINOR-1): a leading U+FEFF (UTF-8 BOM) survives `String.trimStart()`
    // (Char.isWhitespace() deliberately excludes it -- documented JDK behavior) -- so a genuinely
    // successful, BOM-prefixed JSON response used to fail `looksLikeJson`'s leading-brace sniff and
    // get misclassified as Failure ("Couldn't load news" for a fetch that actually had real
    // articles). This environment sits behind a corporate proxy that can alter response framing
    // (this project's own recorded Zscaler TLS notes), making a stray BOM plausible, not exotic.
    @Test fun `a BOM-prefixed JSON body still resolves to Success, not a false Failure`() = runTest {
        val engine = MockEngine {
            respond(
                "﻿" + """{"articles":[{"title":"t","url":"https://a.com","domain":"a.com","seendate":"20260815T041500Z"}]}""",
                HttpStatusCode.OK,
                headersOf("Content-Type", "application/json"),
            )
        }
        val client = GdeltClient(HttpClient(engine))
        val result = client.searchEarthquakeNews(place = "Colombia", eventTimeMillis = 0)
        val success = assertIs<NewsResult.Success>(result, "a BOM before the leading '{' must not be misread as a malformed/HTML failure")
        assertEquals(1, success.articles.size)
    }
}

class GdeltPlaceQueryTest {
    @Test fun `strips the USGS distance and direction prefix`() {
        assertEquals("Ende Indonesia earthquake", gdeltPlaceQuery("68 km NNW of Ende, Indonesia"))
    }

    @Test fun `strips a single-letter compass direction too`() {
        assertEquals("San Jose del Palmar Colombia earthquake", gdeltPlaceQuery("5 km S of San Jose del Palmar, Colombia"))
    }

    @Test fun `a place with no distance prefix is used as-is, earthquake appended`() {
        assertEquals("South Sandwich Islands region earthquake", gdeltPlaceQuery("South Sandwich Islands region"))
    }

    @Test fun `does not double up when the place already names an earthquake`() {
        assertEquals(
            "The 2026 Kumamoto Region Japan Earthquake",
            gdeltPlaceQuery("The 2026 Kumamoto Region, Japan Earthquake"),
        )
    }

    // Task 2b (task-2b-news-fix-report.md): live-reproduced — GDELT's DOC API returns HTTP 200 with
    // an HTML "illegal character" error page for a literal comma in `query`, silently breaking every
    // USGS place string ("City, Region" shaped) that reached it unsanitized.
    @Test fun `strips a comma - GDELT rejects it as an illegal character`() {
        assertEquals("Ende Indonesia earthquake", gdeltPlaceQuery("Ende, Indonesia"))
    }

    // GDELT's own live error text names the dash as equally illegal unless quoted.
    @Test fun `strips a dash too - GDELT's own error text names it as illegal unless quoted`() {
        assertEquals("Port au Prince Haiti earthquake", gdeltPlaceQuery("Port-au-Prince, Haiti"))
    }

    @Test fun `collapses whitespace left behind by stripped punctuation`() {
        assertEquals("Ende Indonesia earthquake", gdeltPlaceQuery("  Ende,   Indonesia  "))
    }

    // Fix Round 1 (Review 1, MINOR-2): comma and dash are the only two characters GDELT's own live
    // error text named as illegal -- apostrophe (Xi'an, Hawai'i, Cote d'Ivoire) was never verified
    // live either way, but is a common query-syntax special character in search APIs generally, so
    // this is disclosed the same way dash was before its own live A/B: assumed illegal, not
    // confirmed. Per GDELT-semantics safety, the sanitizer strips to alphanumerics+spaces for the
    // place tokens rather than growing a hand-picked blocklist one punctuation mark at a time.
    @Test fun `strips an apostrophe -- assumed illegal per general search-API convention, not live-verified`() {
        assertEquals("Xi an China earthquake", gdeltPlaceQuery("Xi'an, China"))
    }

    @Test fun `strips an apostrophe with no following space too`() {
        assertEquals("Hawai i earthquake", gdeltPlaceQuery("Hawai'i"))
    }

    @Test fun `strips every apostrophe in a multi-apostrophe place name`() {
        assertEquals("Cote d Ivoire earthquake", gdeltPlaceQuery("Cote d'Ivoire"))
    }

    // Diacritics were never named illegal by GDELT and are NOT search-API punctuation -- this proves
    // the alphanumerics+spaces allowlist doesn't over-strip real (non-ASCII) place names while it's
    // busy stripping comma/dash/apostrophe.
    @Test fun `preserves diacritics -- never named illegal, must not be over-stripped by the allowlist`() {
        assertEquals("São Paulo Brazil earthquake", gdeltPlaceQuery("15 km ESE of São Paulo, Brazil"))
    }

    @Test fun `preserves diacritics in a place with no distance prefix or illegal chars`() {
        assertEquals("Réunion Island earthquake", gdeltPlaceQuery("Réunion Island"))
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
