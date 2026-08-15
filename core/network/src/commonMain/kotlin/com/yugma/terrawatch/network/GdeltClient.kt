package com.yugma.terrawatch.network

import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import kotlinx.coroutines.CancellationException
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

/**
 * Plan 4 Task 5: one GDELT DOC 2.0 API article — parsed down to exactly the four fields the
 * DetailSheet/Insights "In the news" surfaces show (title, domain, relative time via
 * [seenAtMillis]) plus [url] for the tap-through `ACTION_VIEW`. GDELT's own raw article record
 * carries more (`url_mobile`, `socialimage`, `language`, `sourcecountry` — see the spike's recorded
 * fixture) — none of it is part of this app's contract, so [parseArticles] drops it rather than
 * growing this type to mirror a third-party response shape verbatim.
 */
data class NewsArticle(
    val title: String,
    val url: String,
    val domain: String,
    val seenAtMillis: Long,
)

/**
 * Task 2b (dogfooding fix, task-2b-news-fix-report.md): [GdeltClient.searchEarthquakeNews] used to
 * collapse "the query resolved cleanly but found nothing" and "the request itself failed" down to
 * the exact same [emptyList] — [NewsUiState][com.yugma.terrawatch.news.NewsUiState] had no way to
 * tell them apart, so BOTH rendered as a shimmer that quietly disappeared. [Success.articles] MAY
 * be empty (a genuine zero-hit query is still a success) — nothing here hides that case the way
 * [NewsArticle] parsing already does; the presentation layer ([NewsUiState.Empty] vs
 * [NewsUiState.Error]) decides what an empty list means. Same "distinct sealed cases for a resolved
 * miss vs. an outright failure" shape [UsgsApi]'s own [FeedResult] already established
 * ([FeedResult.NotModified] vs [FeedResult.Failure]) — [Failure] carries no `cause` (unlike
 * [FeedResult.Failure]'s own) because nothing downstream ever needs to distinguish network/HTTP/
 * malformed-body failures from one another; every one of them renders the identical "Couldn't load
 * news" + Retry row.
 */
sealed interface NewsResult {
    data class Success(val articles: List<NewsArticle>) : NewsResult
    data object Failure : NewsResult
}

@Serializable
internal data class GdeltResponse(val articles: List<GdeltArticleJson> = emptyList())

@Serializable
internal data class GdeltArticleJson(
    val title: String? = null,
    val url: String? = null,
    val domain: String? = null,
    val seendate: String? = null,
)

// Shared across parseArticles below — a fresh Json{} per call is wasted allocation for a config
// that never varies (same fix as UsgsApi.queryCount's own lenientJson).
private val lenientJson = Json { ignoreUnknownKeys = true }

/**
 * Plan 4 Task 5: GDELT DOC 2.0 API client (`https://api.gdeltproject.org/api/v2/doc/doc`, free, no
 * key). Spike (2026-08-15, ≤45min, see task-5-report.md) queried 3 real recent M6+ quakes (Ende
 * Indonesia M7.7, western Colombia M7.4, Kumamoto Japan M6.8) — 2 of 3 cleared before GDELT's own
 * aggressive rate limit ("one request every 5 seconds", empirically stricter than that under this
 * environment's shared corporate egress IP — the third query 429'd on every retry across ~70s of
 * spaced attempts) returned highly relevant, sourcelang:english-filtered, real-domain results
 * (abcnews.com/baltimoresun.com/smh.com.au/nypost.com/yahoo.com among others) — GATE: **PASS**.
 *
 * Task 2b (dogfooding fix, 2026-08-16, task-2b-news-fix-report.md): that spike's own hand-tried
 * queries never actually exercised the shipped [gdeltPlaceQuery] output live — live re-testing
 * against a fresh real M6.9 quake (Pematangsiantar, Indonesia) proved GDELT's DOC API flatly
 * rejects a literal comma in `query` with **HTTP 200 + an HTML body** ("One or more of your
 * keywords contained an illegal character..."), not a non-2xx — and a raw USGS `place` string is
 * ALWAYS "City, Region" shaped, so [gdeltPlaceQuery] was building an illegal query for essentially
 * every quake, every time, not as some rare edge case. [gdeltPlaceQuery] now strips that (and the
 * dash GDELT's own error text names as equally illegal) before ever reaching GDELT. [searchEarthquakeNews]
 * still never *throws* for network/HTTP/malformed-body failures — that discipline continues this
 * codebase's own "the map/feed never goes away" posture ([UsgsApi.fetchFeed]'s `FeedResult` sealed
 * type) — but it no longer collapses "resolved with zero relevant articles" and "the request itself
 * failed" into the identical value the way a bare `emptyList()` used to: seeing that HTML-error-page
 * body land on a 200 was exactly what proved a plain success/non-2xx check alone isn't enough
 * defense against GDELT's own quirks, so [NewsResult] carries the distinction the rest of this
 * feature's UI now needs to stop rendering both as the same silently-vanishing shimmer.
 */
class GdeltClient(
    private val http: HttpClient,
    private val baseUrl: String = "https://api.gdeltproject.org/api/v2/doc/doc",
) {
    /**
     * [place] is a raw [com.yugma.terrawatch.model.Quake.place] string (e.g. "68 km NNW of Ende,
     * Indonesia") — [gdeltPlaceQuery] strips the USGS distance/direction prefix and appends
     * "earthquake" (skipped when the place string already names one, e.g. USGS's own
     * "The 2026 Kumamoto Region, Japan Earthquake" titles). The search window is
     * [eventTimeMillis]..[eventTimeMillis]+72h (backlog item 3's own "event time +72h" window) —
     * wide enough to catch a quake's first news cycle without drifting into unrelated later
     * coverage of the same region.
     *
     * Returns [NewsResult.Failure] — never throws, never a bare empty list — for a non-2xx status,
     * a network/parse exception, OR a response that merely LOOKS like a failure ([looksLikeJson]'s
     * own content-type/leading-brace sniff, guarding against GDELT's "200 OK + HTML error page"
     * malformed-query quirk this task's own live curl reproduction hit — see this class's kdoc).
     * [NewsResult.Success] carries whatever [parseArticles] found, including an empty list for a
     * genuine zero-hit query — that distinction is exactly what [NewsResult] exists to preserve.
     */
    @OptIn(ExperimentalTime::class)
    suspend fun searchEarthquakeNews(
        place: String,
        eventTimeMillis: Long,
        maxRecords: Int = 3,
    ): NewsResult = try {
        val resp = http.get(baseUrl) {
            parameter("query", "${gdeltPlaceQuery(place)} sourcelang:english")
            parameter("mode", "artlist")
            parameter("format", "json")
            parameter("startdatetime", formatGdeltDateTime(eventTimeMillis))
            parameter("enddatetime", formatGdeltDateTime(eventTimeMillis + WINDOW_MILLIS))
            parameter("maxrecords", maxRecords)
        }
        if (!resp.status.isSuccess()) {
            NewsResult.Failure
        } else {
            // Fix Round 1 (Review 1, MINOR-1): stripped ONCE, upstream of BOTH consumers below --
            // a leading U+FEFF surviving into [parseArticles] is just as much of a live bug as it
            // reaching [looksLikeJson]'s own sniff: kotlinx.serialization's `Json.decodeFromString`
            // doesn't tolerate a leading BOM before `{` either (confirmed live via this fix's own RED
            // test -- fixing looksLikeJson alone produced a `NewsResult.Success` that had silently
            // dropped every article, which is not a real fix, just a relabeled one). Stripping here
            // means [looksLikeJson]'s own BOM-aware trim below is now belt-and-suspenders (kept
            // anyway -- harmless, and correct in isolation if that function is ever called directly
            // with a not-yet-stripped body), not the only line of defense.
            val body = resp.bodyAsText().removePrefix("\uFEFF")
            if (!looksLikeJson(resp, body)) NewsResult.Failure else NewsResult.Success(parseArticles(body))
        }
    } catch (ce: CancellationException) {
        throw ce
    } catch (t: Throwable) {
        NewsResult.Failure
    }

    private companion object {
        const val WINDOW_MILLIS = 72 * 60 * 60 * 1000L
    }
}

/**
 * Task 2b's explicit defense against GDELT's own "malformed query still comes back 200 OK" quirk —
 * live-reproduced (task-2b-news-fix-report.md): a query GDELT rejects returns
 * `Content-Type: text/html` and a body reading "One or more of your keywords contained an illegal
 * character...", not a non-2xx status [searchEarthquakeNews]'s own success check would already
 * catch. Checked ahead of, and independently from, [parseArticles]'s own internal try/catch (which
 * stays exactly as defensive as before for its own direct callers/tests) — this is what lets
 * [searchEarthquakeNews] report that case as [NewsResult.Failure] rather than a bare "zero articles"
 * [NewsResult.Success], which a body-shape-blind `Json.decodeFromString` failure alone can't do.
 * Two independent signals, either sufficient on its own: an explicit `text/html` content-type, or a
 * body whose first non-whitespace character isn't JSON's own leading `{`.
 *
 * Fix Round 1 (Review 1, MINOR-1): `body.trimStart()` (no predicate) uses `Char.isWhitespace()`,
 * which deliberately excludes U+FEFF (the UTF-8 byte-order-mark) -- documented JDK/Kotlin behavior,
 * not an oversight of this call site specifically. A response arriving with a leading BOM (plausible
 * behind this project's own recorded corporate-proxy environment, which can alter response framing)
 * would otherwise leave the BOM in front of JSON's own leading `{`, fail this sniff, and misclassify
 * a genuinely successful response as [NewsResult.Failure]. The trim predicate below strips U+FEFF
 * alongside ordinary whitespace so a leading BOM can never cause that false negative.
 */
internal fun looksLikeJson(resp: HttpResponse, body: String): Boolean {
    if (resp.contentType()?.match(ContentType.Text.Html) == true) return false
    return body.trimStart { it.isWhitespace() || it == '\uFEFF' }.startsWith("{")
}

// Strips a leading USGS "<N> km <compass> of " distance/direction prefix (e.g. "68 km NNW of ")
// case-insensitively — the compass group is 1-3 letters from {N,S,E,W} (covers every real USGS
// direction abbreviation: N/S/E/W/NE/NW/SE/SW/NNW/NNE/etc.).
private val DISTANCE_PREFIX = Regex("""^\d+(\.\d+)?\s*km\s+[NSEW]{1,3}\s+of\s+""", RegexOption.IGNORE_CASE)

// Task 2b (task-2b-news-fix-report.md): live-reproduced 2026-08-16 — GDELT's DOC API rejects a
// literal comma in `query` outright, responding HTTP 200 with an HTML body ("One or more of your
// keywords contained an illegal character..."). That same live response names the dash as equally
// illegal unless quoted ("place it in quotes like \"f-16\""). A raw USGS place string is near-
// universally "City, Region" shaped (and occasionally hyphenated, e.g. "Port-au-Prince, Haiti").
//
// Fix Round 1 (Review 1, MINOR-2): comma and dash were the only two characters GDELT's own live
// error text ever named — USGS place strings can also carry an apostrophe (Xi'an, Hawai'i, Cote
// d'Ivoire), a common query-syntax special character in search APIs generally, but never itself
// live-verified against GDELT either way (same "assumed illegal, not confirmed" evidentiary status
// dash had before its own live A/B — see [sanitizeForGdelt]'s own kdoc). Rather than growing a
// hand-picked punctuation blocklist one character at a time — an ever-lengthening pile of
// individually-unverified assumptions — [sanitizeForGdelt] switched to the inverse: an ALLOWLIST of
// Unicode letters/digits/whitespace for the place portion of the query. Comma/dash (live-verified
// above) are subsumed by that same allowlist (neither is a letter/digit/space), so nothing
// regresses; apostrophe and any OTHER not-yet-discovered illegal punctuation (semicolons, slashes,
// parens, ...) are now handled the same uniform way, without needing a live A/B against every single
// one before shipping a fix for it. Both stay stripped to a space (not deleted outright) — GDELT's
// own suggested escape for dash (quoting) would demand an exact substring match in that literal
// order, which international coverage rarely echoes verbatim, where the live A/B already proved a
// plain space-separated keyword list ("earthquake Indonesia", no comma) returns real, relevant
// results.
private val EXTRA_WHITESPACE = Regex("""\s+""")

/**
 * Fix Round 1 (Review 1, MINOR-2): the place-token sanitizer — every character that is NEITHER a
 * Unicode letter/digit NOR whitespace becomes a space (comma, dash, apostrophe, and anything else
 * GDELT might reject that hasn't been individually catalogued). [Char.isLetterOrDigit]/
 * [Char.isWhitespace] are common Kotlin stdlib, Unicode-aware on every target (JVM/Android/wasmJs
 * alike) — deliberately NOT a `Regex` Unicode-property-escape (`\p{L}`) for this, which would tie
 * correctness to whichever regex engine backs `Regex` on each individual Kotlin target.
 *
 * Diacritics (é, ō, ñ, São, ...) are Unicode LETTERS, so [Char.isLetterOrDigit] keeps them untouched
 * — GDELT's own error text never named them illegal, and this app's own place strings (interna-
 * tional quakes) legitimately contain them; over-stripping them would be a new, self-inflicted
 * regression this fix must not introduce (see GdeltClientTest's own diacritic-preserving cases).
 */
private fun sanitizeForGdelt(text: String): String =
    text.map { if (it.isLetterOrDigit() || it.isWhitespace()) it else ' ' }.joinToString("")

/**
 * Plan 4 Task 5: the pure half of [GdeltClient.searchEarthquakeNews]'s query-building — TDD'd
 * directly (GdeltClientTest), no HTTP/MockEngine needed. Strips USGS's own distance/direction
 * prefix (a literal place name reads better in a news search than "68 km NNW of Ende, Indonesia
 * earthquake"), sanitizes GDELT-illegal punctuation ([sanitizeForGdelt] — Task 2b + Fix Round 1, see
 * that function's own kdoc), then appends "earthquake" UNLESS [place] already names one
 * (case-insensitive check — USGS titles named events, e.g. "The 2026 Kumamoto Region, Japan
 * Earthquake", already contain the word; appending a second one would just read oddly, not search
 * any better).
 */
internal fun gdeltPlaceQuery(place: String): String {
    val stripped = DISTANCE_PREFIX.replace(place, "").trim().ifBlank { place.trim() }
    val sanitized = sanitizeForGdelt(stripped).replace(EXTRA_WHITESPACE, " ").trim()
    return if (sanitized.contains("earthquake", ignoreCase = true)) sanitized else "$sanitized earthquake"
}

/**
 * `yyyyMMddHHmmss`, GDELT's own `startdatetime`/`enddatetime` query-param format (UTC, no
 * separators — distinct from [UsgsApi]'s ISO-8601-with-separators FDSN format, both derived from
 * the same [kotlin.time.Instant] machinery per-call since there's no shared formatter between the
 * two unrelated third-party APIs).
 */
@OptIn(ExperimentalTime::class)
internal fun formatGdeltDateTime(millis: Long): String {
    val dt = Instant.fromEpochMilliseconds(millis).toLocalDateTime(TimeZone.UTC)
    fun two(n: Int) = if (n < 10) "0$n" else "$n"
    return "${dt.year}${two(dt.month.ordinal + 1)}${two(dt.day)}${two(dt.hour)}${two(dt.minute)}${two(dt.second)}"
}

/**
 * Parses GDELT's own `seendate` shape, e.g. "20260815T041500Z" -> epoch millis, by inserting
 * ISO-8601 separators and delegating to [Instant.parse] rather than hand-rolling a second date
 * epoch computation next to [formatGdeltDateTime]'s. Null on anything shorter than the expected 16
 * chars or on a parse failure — [parseArticles] drops (not defaults) an article whose timestamp
 * can't be trusted, rather than showing a silently-wrong relative time.
 */
@OptIn(ExperimentalTime::class)
internal fun parseGdeltSeenDate(raw: String): Long? {
    if (raw.length < 16) return null
    return try {
        val iso = "${raw.substring(0, 4)}-${raw.substring(4, 6)}-${raw.substring(6, 8)}T" +
            "${raw.substring(9, 11)}:${raw.substring(11, 13)}:${raw.substring(13, 15)}Z"
        Instant.parse(iso).toEpochMilliseconds()
    } catch (t: Throwable) {
        null
    }
}

/**
 * Decodes a raw GDELT DOC API response body into [NewsArticle]s. Defensive on three independent
 * axes, matching [GdeltClient]'s own "news must never break detail" contract: malformed/non-JSON
 * body (`Json.decodeFromString` throws -> caught, empty list), a missing `articles` key (defaulted
 * to `emptyList()` on [GdeltResponse] itself — GDELT's own zero-result shape is unconfirmed against
 * a live example since every spike query returned real results, so this defaults defensively rather
 * than assuming), and a single article missing any of title/url/domain/a parseable seendate
 * ([mapNotNull] drops just that one article rather than failing the whole batch).
 */
internal fun parseArticles(body: String): List<NewsArticle> = try {
    lenientJson.decodeFromString(GdeltResponse.serializer(), body)
        .articles.mapNotNull { a ->
            val title = a.title ?: return@mapNotNull null
            val url = a.url ?: return@mapNotNull null
            val domain = a.domain ?: return@mapNotNull null
            val seenAt = a.seendate?.let(::parseGdeltSeenDate) ?: return@mapNotNull null
            NewsArticle(title = title, url = url, domain = domain, seenAtMillis = seenAt)
        }
} catch (t: Throwable) {
    emptyList()
}
