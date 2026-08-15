package com.yugma.terrawatch.network

import com.yugma.terrawatch.model.Quake
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.isSuccess
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

// Shared across queryCount below — a fresh Json{} per call is wasted allocation for a config that
// never varies.
private val lenientJson = Json { ignoreUnknownKeys = true }

sealed interface FeedResult {
    data class Fresh(val quakes: List<Quake>, val etag: String?) : FeedResult
    data object NotModified : FeedResult
    data class Failure(val cause: Throwable) : FeedResult
}

class UsgsApi(
    private val http: HttpClient,
    private val baseFeedUrl: String = "https://earthquake.usgs.gov",
) {
    suspend fun fetchFeed(feed: String = "all_day", previousEtag: String? = null): FeedResult = try {
        val resp = http.get("$baseFeedUrl/earthquakes/feed/v1.0/summary/$feed.geojson") {
            previousEtag?.let { header(HttpHeaders.IfNoneMatch, it) }
        }
        when {
            resp.status == HttpStatusCode.NotModified -> FeedResult.NotModified
            resp.status.isSuccess() ->
                FeedResult.Fresh(UsgsFeedParser.parse(resp.bodyAsText()), resp.headers[HttpHeaders.ETag])
            else -> FeedResult.Failure(IllegalStateException("HTTP ${resp.status.value}"))
        }
    } catch (ce: kotlinx.coroutines.CancellationException) {
        throw ce
    } catch (t: Throwable) {
        FeedResult.Failure(t)
    }

    // Throws on network/HTTP/parse failure by design — the History feature's caller wraps.
    // Contrast fetchFeed, which returns FeedResult because polling must never crash.
    @OptIn(ExperimentalTime::class)
    suspend fun queryArchive(
        endTimeMillis: Long,
        limit: Int = 200,
        minMagnitude: Double? = null,
    ): List<Quake> {
        val endIso = Instant.fromEpochMilliseconds(endTimeMillis).toString()
        val url = buildString {
            append("$baseFeedUrl/fdsnws/event/1/query?format=geojson&orderby=time&limit=$limit&endtime=$endIso")
            minMagnitude?.let { append("&minmagnitude=$it") }
        }
        val resp = http.get(url)
        check(resp.status.isSuccess()) { "FDSN archive query failed: HTTP ${resp.status.value}" }
        return UsgsFeedParser.parse(resp.bodyAsText())
    }

    /**
     * Plan 4 Task 5 (Insights density backfill): FDSN's own `/count` endpoint — a single scalar
     * count, never rows (confirmed live: `?format=geojson&starttime=...&endtime=...` returns e.g.
     * `{"count":11082,"maxAllowed":20000}`, not a feature collection) — used only to caption
     * Insights' 30-day chart with "N cached · M total worldwide" when the local cache looks thin.
     * Deliberately no `minMagnitude` filter (unlike [queryArchive]'s optional one): the caption's
     * own honesty depends on comparing like-for-like against this app's local cache, which never
     * applies a magnitude floor to what it ingests either.
     *
     * Returns null (never throws — [CancellationException] still propagates) on any failure —
     * network, non-2xx, or a malformed body — matching [GdeltClient]'s "must never break the
     * screen it captions" contract, not [queryArchive]'s throw-by-design one: this is a purely
     * cosmetic caption, never the caller's primary data.
     */
    @OptIn(ExperimentalTime::class)
    suspend fun queryCount(startTimeMillis: Long, endTimeMillis: Long, minMagnitude: Double? = null): Long? = try {
        val startIso = Instant.fromEpochMilliseconds(startTimeMillis).toString()
        val endIso = Instant.fromEpochMilliseconds(endTimeMillis).toString()
        val url = buildString {
            append("$baseFeedUrl/fdsnws/event/1/count?format=geojson&starttime=$startIso&endtime=$endIso")
            minMagnitude?.let { append("&minmagnitude=$it") }
        }
        val resp = http.get(url)
        if (!resp.status.isSuccess()) {
            null
        } else {
            // ignoreUnknownKeys: the real response also carries `maxAllowed` (see this function's
            // own kdoc) — FdsnCount only declares `count`, so the bare Json.Default decoder would
            // otherwise throw on that extra field (caught below, silently degrading to null — this
            // fix was caught by UsgsApiTest's own `queryCount parses the scalar count field` test
            // failing red against exactly that bug before this line existed).
            lenientJson.decodeFromString(FdsnCount.serializer(), resp.bodyAsText()).count
        }
    } catch (ce: kotlinx.coroutines.CancellationException) {
        throw ce
    } catch (t: Throwable) {
        null
    }
}

@Serializable
internal data class FdsnCount(val count: Long)
