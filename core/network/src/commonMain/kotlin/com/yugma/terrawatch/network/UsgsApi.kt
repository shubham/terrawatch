package com.yugma.terrawatch.network

import com.yugma.terrawatch.model.Quake
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.isSuccess
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

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

    // Throws on network/HTTP/parse failure by design — callers (QuakeRepository.loadArchivePage)
    // wrap. Contrast fetchFeed, which returns FeedResult because polling must never crash.
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
}
