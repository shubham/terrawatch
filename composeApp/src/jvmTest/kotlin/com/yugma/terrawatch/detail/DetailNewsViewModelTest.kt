package com.yugma.terrawatch.detail

import androidx.lifecycle.viewModelScope
import app.cash.turbine.test
import com.yugma.terrawatch.model.MagRevision
import com.yugma.terrawatch.model.Quake
import com.yugma.terrawatch.model.QuakeStatus
import com.yugma.terrawatch.model.Source
import com.yugma.terrawatch.network.GdeltClient
import com.yugma.terrawatch.news.NewsUiState
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
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

private const val THREE_ARTICLES = """
    {"articles":[
      {"title":"A","url":"https://a.com/1","domain":"a.com","seendate":"20260815T041500Z"},
      {"title":"B","url":"https://b.com/1","domain":"b.com","seendate":"20260815T041500Z"},
      {"title":"C","url":"https://c.com/1","domain":"c.com","seendate":"20260815T041500Z"}
    ]}
"""

class DetailNewsViewModelTest {
    // Same leak-prevention discipline as QuakeSelectionViewModelTest/InsightsViewModelTest's own
    // tearDown -- onQuakeSelected's viewModelScope.launch{} coroutine must not survive its test.
    private val createdViewModels = mutableListOf<DetailNewsViewModel>()

    @AfterTest fun tearDown() {
        Dispatchers.resetMain()
        runBlocking { createdViewModels.forEach { it.viewModelScope.coroutineContext.job.cancelAndJoin() } }
        createdViewModels.clear()
    }

    private fun createVm(engine: MockEngine): DetailNewsViewModel =
        DetailNewsViewModel(GdeltClient(HttpClient(engine))).also { createdViewModels += it }

    private fun quake(id: String = "us1", mag: Double?, place: String = "Test Place") = Quake(
        id = id, timeMillis = 1_000_000L, lat = 1.0, lon = 2.0, depthKm = 5.0, mag = mag,
        magType = if (mag != null) "mw" else null, place = place, tsunami = false, felt = null,
        status = QuakeStatus.AUTOMATIC, sources = mapOf(Source.USGS to id),
        revisions = if (mag != null) listOf(MagRevision(mag, "mw", 1_000_000L, Source.USGS)) else emptyList(),
        updatedAtMillis = 1_000_000L,
    )

    @Test fun `starts Hidden before any selection`() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        val vm = createVm(MockEngine { respond("", HttpStatusCode.NotFound) })
        assertEquals(NewsUiState.Hidden, vm.newsState.value)
    }

    @Test fun `a quake below the magnitude floor stays Hidden and never calls GDELT`() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        var called = false
        val vm = createVm(MockEngine { called = true; respond(THREE_ARTICLES, HttpStatusCode.OK) })
        vm.newsState.test {
            assertEquals(NewsUiState.Hidden, awaitItem())
            vm.onQuakeSelected(quake(mag = 5.4))
            expectNoEvents()
            cancelAndIgnoreRemainingEvents()
        }
        assertTrue(!called, "a sub-5.5 quake must never trigger a GDELT fetch")
    }

    @Test fun `exactly 5,5 clears the floor (inclusive) and flashes Loading then Content`() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        val vm = createVm(MockEngine { respond(THREE_ARTICLES, HttpStatusCode.OK) })
        vm.newsState.test {
            assertEquals(NewsUiState.Hidden, awaitItem())
            vm.onQuakeSelected(quake(mag = 5.5))
            assertEquals(NewsUiState.Loading, awaitItem())
            val content = assertIs<NewsUiState.Content>(awaitItem())
            assertEquals(3, content.articles.size)
            assertEquals("A", content.articles.first().title)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test fun `a null mag is treated as below the floor, not a crash`() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        val vm = createVm(MockEngine { respond(THREE_ARTICLES, HttpStatusCode.OK) })
        vm.newsState.test {
            assertEquals(NewsUiState.Hidden, awaitItem())
            vm.onQuakeSelected(quake(mag = null))
            expectNoEvents()
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test fun `empty GDELT results resolve to Empty with the USGS fallback link, never an empty Content or a silent Hidden`() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        val vm = createVm(MockEngine { respond("""{"articles":[]}""", HttpStatusCode.OK, headersOf("Content-Type", "application/json")) })
        vm.newsState.test {
            assertEquals(NewsUiState.Hidden, awaitItem())
            vm.onQuakeSelected(quake(id = "us1", mag = 6.0))
            assertEquals(NewsUiState.Loading, awaitItem())
            val empty = assertIs<NewsUiState.Empty>(awaitItem())
            assertEquals("https://earthquake.usgs.gov/earthquakes/eventpage/us1", empty.usgsEventUrl)
            cancelAndIgnoreRemainingEvents()
        }
    }

    // Task 2b (dogfooding fix, task-2b-news-fix-report.md): this is the exact bug — a GDELT failure
    // (429, malformed body, GDELT's own 200+HTML illegal-character quirk, ...) used to collapse to
    // the SAME Hidden an honest zero-hit query does, so the "In the news" section's shimmer just
    // vanished with no way to tell "nothing to show" from "couldn't check." Error now exists so the
    // UI can render a compact "Couldn't load news" + Retry row instead.
    @Test fun `a GDELT failure resolves to Error, never a silent Hidden`() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        val vm = createVm(MockEngine { respond("boom", HttpStatusCode.InternalServerError) })
        vm.newsState.test {
            assertEquals(NewsUiState.Hidden, awaitItem())
            vm.onQuakeSelected(quake(mag = 6.0))
            assertEquals(NewsUiState.Loading, awaitItem())
            assertEquals(NewsUiState.Error, awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test fun `retry re-issues the fetch for the same quake and can recover to Content`() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        var callCount = 0
        val vm = createVm(
            MockEngine {
                callCount++
                if (callCount == 1) respond("boom", HttpStatusCode.InternalServerError) else respond(THREE_ARTICLES, HttpStatusCode.OK)
            },
        )
        vm.newsState.test {
            assertEquals(NewsUiState.Hidden, awaitItem())
            vm.onQuakeSelected(quake(mag = 6.0))
            assertEquals(NewsUiState.Loading, awaitItem())
            assertEquals(NewsUiState.Error, awaitItem())

            vm.retry()
            assertEquals(NewsUiState.Loading, awaitItem())
            val content = assertIs<NewsUiState.Content>(awaitItem())
            assertEquals(3, content.articles.size)
            cancelAndIgnoreRemainingEvents()
        }
        assertEquals(2, callCount)
    }

    @Test fun `retry before any selection is a no-op`() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        var called = false
        val vm = createVm(MockEngine { called = true; respond(THREE_ARTICLES, HttpStatusCode.OK) })
        vm.newsState.test {
            assertEquals(NewsUiState.Hidden, awaitItem())
            vm.retry()
            expectNoEvents()
            cancelAndIgnoreRemainingEvents()
        }
        assertTrue(!called, "retry() with no pending quake must never call GDELT")
    }

    @Test fun `selecting null after a real quake clears back to Hidden`() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        val vm = createVm(MockEngine { respond(THREE_ARTICLES, HttpStatusCode.OK) })
        vm.newsState.test {
            assertEquals(NewsUiState.Hidden, awaitItem())
            vm.onQuakeSelected(quake(mag = 6.0))
            assertEquals(NewsUiState.Loading, awaitItem())
            assertIs<NewsUiState.Content>(awaitItem())
            vm.onQuakeSelected(null)
            assertEquals(NewsUiState.Hidden, awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test fun `re-selecting the identical quake id is a no-op, no re-flash`() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        var callCount = 0
        val vm = createVm(MockEngine { callCount++; respond(THREE_ARTICLES, HttpStatusCode.OK) })
        vm.newsState.test {
            assertEquals(NewsUiState.Hidden, awaitItem())
            val q = quake(id = "us1", mag = 6.0)
            vm.onQuakeSelected(q)
            assertEquals(NewsUiState.Loading, awaitItem())
            assertIs<NewsUiState.Content>(awaitItem())
            vm.onQuakeSelected(q.copy()) // same id, structurally equal re-selection
            expectNoEvents()
            cancelAndIgnoreRemainingEvents()
        }
        assertEquals(1, callCount, "an identical re-selection must not trigger a second GDELT fetch")
    }
}
