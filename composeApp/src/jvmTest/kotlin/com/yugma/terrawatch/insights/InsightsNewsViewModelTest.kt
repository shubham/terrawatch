package com.yugma.terrawatch.insights

import androidx.lifecycle.viewModelScope
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import app.cash.turbine.test
import com.yugma.terrawatch.data.QuakeRepository
import com.yugma.terrawatch.database.QuakeDao
import com.yugma.terrawatch.database.TerraWatchDb
import com.yugma.terrawatch.model.MagRevision
import com.yugma.terrawatch.model.Quake
import com.yugma.terrawatch.model.QuakeStatus
import com.yugma.terrawatch.model.Source
import com.yugma.terrawatch.network.EmscLiveSource
import com.yugma.terrawatch.network.GdeltClient
import com.yugma.terrawatch.network.UsgsApi
import com.yugma.terrawatch.news.NewsUiState
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.job
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

private const val DAY = 86_400_000L

private const val THREE_ARTICLES = """
    {"articles":[
      {"title":"A","url":"https://a.com/1","domain":"a.com","seendate":"20260815T041500Z"},
      {"title":"B","url":"https://b.com/1","domain":"b.com","seendate":"20260815T041500Z"},
      {"title":"C","url":"https://c.com/1","domain":"c.com","seendate":"20260815T041500Z"}
    ]}
"""

class InsightsNewsViewModelTest {
    // Same leak-prevention discipline as InsightsViewModelTest's own tearDown.
    private val createdViewModels = mutableListOf<InsightsNewsViewModel>()

    @AfterTest fun tearDown() {
        Dispatchers.resetMain()
        runBlocking { createdViewModels.forEach { it.viewModelScope.coroutineContext.job.cancelAndJoin() } }
        createdViewModels.clear()
    }

    private fun freshDao(): QuakeDao {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        TerraWatchDb.Schema.create(driver)
        return QuakeDao(TerraWatchDb(driver))
    }

    private fun repository(dao: QuakeDao): QuakeRepository {
        val engine = MockEngine { respond("", HttpStatusCode.NotFound) }
        return QuakeRepository(UsgsApi(HttpClient(engine)), EmscLiveSource(HttpClient(engine)), dao, clock = { 1L })
    }

    // Plan 5 (news kill-switch): `newsEnabled` defaults to `true` HERE (the test helper's own
    // default), deliberately the opposite of the production default (`NewsFeature.ENABLED`, which
    // is `false` — see that object's own kdoc, core:network's GdeltClient.kt) — every pre-existing
    // test below calls `createVm(dao, ...)` unchanged and keeps exercising the real window/floor/
    // fetch/retry logic, since that's what all of them are actually testing. The flag-OFF behavior
    // itself gets its own dedicated tests further down, which pass `newsEnabled = false` explicitly.
    private fun createVm(
        dao: QuakeDao,
        gdeltResponse: String = THREE_ARTICLES,
        gdeltStatus: HttpStatusCode = HttpStatusCode.OK,
        nowMillis: Long = 100 * DAY,
        newsEnabled: Boolean = true,
    ): InsightsNewsViewModel {
        val gdeltClient = GdeltClient(HttpClient(MockEngine { respond(gdeltResponse, gdeltStatus) }))
        return InsightsNewsViewModel(repository(dao), gdeltClient, clock = { nowMillis }, newsEnabled = newsEnabled)
            .also { createdViewModels += it }
    }

    private fun quake(id: String, timeMillis: Long, mag: Double?, place: String = "Test $id") = Quake(
        id = id, timeMillis = timeMillis, lat = 1.0, lon = 2.0, depthKm = 5.0, mag = mag,
        magType = if (mag != null) "mw" else null, place = place, tsunami = false, felt = null,
        status = QuakeStatus.AUTOMATIC, sources = mapOf(Source.USGS to id),
        revisions = if (mag != null) listOf(MagRevision(mag, "mw", timeMillis, Source.USGS)) else emptyList(),
        updatedAtMillis = timeMillis,
    )

    // Same "tolerate Loading being skipped" shape as InsightsViewModelTest's own
    // awaitPastLoading(): unlike DetailNewsViewModel (whose Loading assignment is SYNCHRONOUS,
    // inside onQuakeSelected itself, before the test ever yields), InsightsNewsViewModel's whole
    // recompute() runs on a background collector started at VM-construction time — real dispatcher
    // hops (QuakeDao's own mapToList(Dispatchers.Default), ktor's engine dispatcher) mean the
    // Hidden -> Loading -> Content sequence can fully resolve before this test's own
    // `newsState.test {}` block ever gets to subscribe, in which case turbine's first item is
    // already the settled state, never Loading. Both orderings are correct production behavior;
    // only the FINAL settled state is the actual assertion that matters.
    private suspend fun app.cash.turbine.ReceiveTurbine<NewsUiState>.awaitSettled(): NewsUiState {
        var s = awaitItem()
        while (s is NewsUiState.Loading) s = awaitItem()
        return s
    }

    @Test fun `an empty database settles on Hidden`() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        val vm = createVm(freshDao())
        vm.newsState.test {
            assertEquals(NewsUiState.Hidden, awaitSettled())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test fun `no M6+ quake in the last 7 days stays Hidden, even with plenty of smaller ones`() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        val dao = freshDao()
        val now = 100 * DAY
        dao.upsert(quake("small", timeMillis = now, mag = 5.9))
        val vm = createVm(dao, nowMillis = now)
        vm.newsState.test {
            assertEquals(NewsUiState.Hidden, awaitSettled())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test fun `an M6+ quake within 7 days resolves to Content`() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        val dao = freshDao()
        val now = 100 * DAY
        dao.upsert(quake("big", timeMillis = now, mag = 6.8, place = "Kumamoto, Japan"))
        val vm = createVm(dao, nowMillis = now)
        vm.newsState.test {
            val content = assertIs<NewsUiState.Content>(awaitSettled())
            assertEquals(3, content.articles.size)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test fun `an M6+ quake older than 7 days is out of window, stays Hidden`() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        val dao = freshDao()
        val now = 100 * DAY
        dao.upsert(quake("old-big", timeMillis = now - 10 * DAY, mag = 7.0))
        val vm = createVm(dao, nowMillis = now)
        vm.newsState.test {
            assertEquals(NewsUiState.Hidden, awaitSettled())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test fun `an empty GDELT result for a genuine M6+ candidate resolves to Empty with the USGS fallback link, not Content or a silent Hidden`() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        val dao = freshDao()
        val now = 100 * DAY
        dao.upsert(quake("big", timeMillis = now, mag = 6.5))
        val vm = createVm(dao, gdeltResponse = """{"articles":[]}""", nowMillis = now)
        vm.newsState.test {
            val empty = assertIs<NewsUiState.Empty>(awaitSettled())
            assertEquals("https://earthquake.usgs.gov/earthquakes/eventpage/big", empty.usgsEventUrl)
            cancelAndIgnoreRemainingEvents()
        }
    }

    // Task 2b (dogfooding fix, task-2b-news-fix-report.md): same bug as DetailNewsViewModel's own
    // sibling test — a GDELT failure used to collapse to the identical Hidden a genuine zero-hit
    // query resolves to. Error now exists so Insights' own "In the news" card can render a compact
    // "Couldn't load news" + Retry row instead of silently vanishing.
    @Test fun `a GDELT failure for a genuine M6+ candidate resolves to Error, never a silent Hidden`() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        val dao = freshDao()
        val now = 100 * DAY
        dao.upsert(quake("big", timeMillis = now, mag = 6.5))
        val vm = createVm(dao, gdeltResponse = "boom", gdeltStatus = HttpStatusCode.InternalServerError, nowMillis = now)
        vm.newsState.test {
            assertEquals(NewsUiState.Error, awaitSettled())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test fun `retry re-issues the fetch for the same candidate and can recover to Content`() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        val dao = freshDao()
        val now = 100 * DAY
        dao.upsert(quake("big", timeMillis = now, mag = 6.5))
        var callCount = 0
        val gdeltClient = GdeltClient(
            HttpClient(
                MockEngine {
                    callCount++
                    if (callCount == 1) respond("boom", HttpStatusCode.InternalServerError) else respond(THREE_ARTICLES, HttpStatusCode.OK)
                },
            ),
        )
        val vm = InsightsNewsViewModel(repository(dao), gdeltClient, clock = { now }, newsEnabled = true).also { createdViewModels += it }
        vm.newsState.test {
            assertEquals(NewsUiState.Error, awaitSettled())

            vm.retry()
            val content = assertIs<NewsUiState.Content>(awaitSettled())
            assertEquals(3, content.articles.size)
            cancelAndIgnoreRemainingEvents()
        }
        assertEquals(2, callCount)
    }

    @Test fun `an unrelated smaller quake arriving does not re-fetch the unchanged strongest candidate`() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        val dao = freshDao()
        val now = 100 * DAY
        dao.upsert(quake("big", timeMillis = now, mag = 6.8))
        val vm = createVm(dao, nowMillis = now)
        vm.newsState.test {
            assertIs<NewsUiState.Content>(awaitSettled())

            dao.upsert(quake("small-elsewhere", timeMillis = now, mag = 3.0))

            expectNoEvents()
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test fun `a NEW stronger M6+ candidate replaces the previous one and re-fetches`() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        val dao = freshDao()
        val now = 100 * DAY
        dao.upsert(quake("first", timeMillis = now, mag = 6.2))
        val vm = createVm(dao, nowMillis = now)
        vm.newsState.test {
            assertIs<NewsUiState.Content>(awaitSettled())

            dao.upsert(quake("stronger", timeMillis = now, mag = 7.5))

            assertIs<NewsUiState.Content>(awaitSettled())
            cancelAndIgnoreRemainingEvents()
        }
    }

    // --- Plan 5, news kill-switch (USER DECISION, 2026-08-16): NewsFeature.ENABLED = false in
    // production -- see that object's own kdoc (core:network's GdeltClient.kt) for the full
    // rationale. These tests prove the ViewModel-level guard both when forced off explicitly and,
    // separately, under the REAL shipped default (no override at all) -- same split
    // DetailNewsViewModelTest's own identical section draws.

    @Test fun `flag OFF -- a genuine M6+ candidate within the window still never calls GDELT, stays Hidden`() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        val dao = freshDao()
        val now = 100 * DAY
        dao.upsert(quake("big", timeMillis = now, mag = 7.5))
        val vm = createVm(dao, nowMillis = now, newsEnabled = false)
        vm.newsState.test {
            assertEquals(NewsUiState.Hidden, awaitSettled())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test fun `flag OFF -- retry is also a no-op, never calls GDELT`() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        val dao = freshDao()
        val now = 100 * DAY
        dao.upsert(quake("big", timeMillis = now, mag = 7.5))
        val vm = createVm(dao, nowMillis = now, newsEnabled = false)
        vm.newsState.test {
            assertEquals(NewsUiState.Hidden, awaitSettled())
            vm.retry()
            expectNoEvents()
            cancelAndIgnoreRemainingEvents()
        }
    }

    // The one test in this suite that does NOT force `newsEnabled` either way -- it constructs
    // InsightsNewsViewModel exactly as `AppModule.kt`'s real Koin wiring does (no fourth argument),
    // so this is the single place actually proving today's real shipped default
    // (NewsFeature.ENABLED) is `false`, not just that the ViewModel's guard works when told to.
    @Test fun `production default -- with no override, the real NewsFeature flag blocks every fetch for a genuine M6+ candidate`() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        val dao = freshDao()
        val now = 100 * DAY
        dao.upsert(quake("big", timeMillis = now, mag = 7.5))
        var called = false
        val gdeltClient = GdeltClient(HttpClient(MockEngine { called = true; respond(THREE_ARTICLES, HttpStatusCode.OK) }))
        val vm = InsightsNewsViewModel(repository(dao), gdeltClient, clock = { now }).also { createdViewModels += it }
        vm.newsState.test {
            assertEquals(NewsUiState.Hidden, awaitSettled())
            cancelAndIgnoreRemainingEvents()
        }
        assertTrue(!called, "NewsFeature.ENABLED must be false today -- flip it back only per a real user decision")
    }
}
