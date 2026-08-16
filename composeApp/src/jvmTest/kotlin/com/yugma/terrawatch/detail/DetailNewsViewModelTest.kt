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
import kotlin.time.Duration.Companion.seconds

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
    //
    // Flake-hardening pass (2026-08-16, sweeping the terrawatch flaky-test playbook -- see
    // HomeViewModelTest's own kdoc for the original Task-13/commit-5e9e922 precedent this ports):
    // unlike InsightsNewsViewModelTest/QuakeSelectionViewModelTest, NOT every test here needs the
    // timeout margin -- `_newsState.value = NewsUiState.Loading` is written SYNCHRONOUSLY inside
    // fetch(), before the GDELT call is even launched (see fetch()'s own body), so a test that never
    // clears the magnitude floor / has the kill-switch off never reaches a real dispatcher hop at
    // all, and stays untouched. Every test that DOES reach a real GdeltClient.searchEarthquakeNews
    // call now carries `timeout = 30.seconds` on its `vm.newsState.test { ... }` -- ktor's engine
    // dispatch is a genuine hop off Main even against MockEngine, the same class of starved-runner
    // exposure commit 5e9e922 documents for HomeViewModelTest's poll-loop test.
    private val createdViewModels = mutableListOf<DetailNewsViewModel>()

    @AfterTest fun tearDown() {
        Dispatchers.resetMain()
        runBlocking { createdViewModels.forEach { it.viewModelScope.coroutineContext.job.cancelAndJoin() } }
        createdViewModels.clear()
    }

    // Plan 5 (news kill-switch): `newsEnabled` defaults to `true` HERE (the test helper's own
    // default), deliberately the opposite of the production default (`NewsFeature.ENABLED`, which
    // is `false` — see that object's own kdoc) — every pre-existing test below calls
    // `createVm(engine)` unchanged and keeps exercising the real fetch/floor/retry logic, since
    // that's what all of them are actually testing. The flag-OFF behavior itself gets its own
    // dedicated tests further down, which pass `newsEnabled = false` explicitly.
    private fun createVm(engine: MockEngine, newsEnabled: Boolean = true): DetailNewsViewModel =
        DetailNewsViewModel(GdeltClient(HttpClient(engine)), newsEnabled = newsEnabled).also { createdViewModels += it }

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

    // Flake hardening (2026-08-16): a real GDELT fetch resolves past Loading here, and GdeltClient
    // gives ktor's own engine dispatcher a genuine hop off Main -- same starved-CI-runner exposure
    // commit 5e9e922 documents for HomeViewModelTest's poll-loop test. timeout = 30.seconds on every
    // test in this file below that actually reaches a fetch (see this class's own kdoc).
    @Test fun `exactly 5,5 clears the floor (inclusive) and flashes Loading then Content`() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        val vm = createVm(MockEngine { respond(THREE_ARTICLES, HttpStatusCode.OK) })
        vm.newsState.test(timeout = 30.seconds) {
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
        vm.newsState.test(timeout = 30.seconds) {
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
        vm.newsState.test(timeout = 30.seconds) {
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
        vm.newsState.test(timeout = 30.seconds) {
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
        vm.newsState.test(timeout = 30.seconds) {
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
        vm.newsState.test(timeout = 30.seconds) {
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

    // --- Plan 5, news kill-switch (USER DECISION, 2026-08-16): NewsFeature.ENABLED = false in
    // production -- see that object's own kdoc (core:network's GdeltClient.kt) for the full
    // rationale. These tests prove the ViewModel-level guard both when forced off explicitly and,
    // separately, under the REAL shipped default (no override at all).

    @Test fun `flag OFF -- a quake well above the magnitude floor still never calls GDELT, state stays Hidden`() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        var called = false
        val vm = createVm(MockEngine { called = true; respond(THREE_ARTICLES, HttpStatusCode.OK) }, newsEnabled = false)
        vm.newsState.test {
            assertEquals(NewsUiState.Hidden, awaitItem())
            vm.onQuakeSelected(quake(mag = 7.5))
            expectNoEvents()
            cancelAndIgnoreRemainingEvents()
        }
        assertTrue(!called, "the flag being off must block every fetch, regardless of magnitude")
    }

    @Test fun `flag OFF -- selecting, then null, then selecting again all stay Hidden with no fetch`() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        var called = false
        val vm = createVm(MockEngine { called = true; respond(THREE_ARTICLES, HttpStatusCode.OK) }, newsEnabled = false)
        vm.newsState.test {
            assertEquals(NewsUiState.Hidden, awaitItem())
            vm.onQuakeSelected(quake(id = "us1", mag = 6.5))
            expectNoEvents()
            vm.onQuakeSelected(null)
            expectNoEvents()
            vm.onQuakeSelected(quake(id = "us2", mag = 7.0))
            expectNoEvents()
            cancelAndIgnoreRemainingEvents()
        }
        assertTrue(!called, "no selection sequence may trigger a fetch while the flag is off")
    }

    @Test fun `flag OFF -- retry is also a no-op, never calls GDELT`() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        var called = false
        val vm = createVm(MockEngine { called = true; respond(THREE_ARTICLES, HttpStatusCode.OK) }, newsEnabled = false)
        vm.newsState.test {
            assertEquals(NewsUiState.Hidden, awaitItem())
            vm.onQuakeSelected(quake(mag = 7.5))
            vm.retry()
            expectNoEvents()
            cancelAndIgnoreRemainingEvents()
        }
        assertTrue(!called, "retry() must also never call GDELT while the flag is off")
    }

    // The one test in this suite that does NOT force `newsEnabled` either way -- it constructs
    // DetailNewsViewModel exactly as `AppModule.kt`'s real Koin wiring does (gdeltClient only),
    // so this is the single place actually proving today's real shipped default
    // (NewsFeature.ENABLED) is `false`, not just that the ViewModel's guard works when told to.
    @Test fun `production default -- with no override, the real NewsFeature flag blocks every fetch`() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        var called = false
        val vm = DetailNewsViewModel(GdeltClient(HttpClient(MockEngine { called = true; respond(THREE_ARTICLES, HttpStatusCode.OK) })))
            .also { createdViewModels += it }
        vm.newsState.test {
            assertEquals(NewsUiState.Hidden, awaitItem())
            vm.onQuakeSelected(quake(mag = 7.5))
            expectNoEvents()
            cancelAndIgnoreRemainingEvents()
        }
        assertTrue(!called, "NewsFeature.ENABLED must be false today -- flip it back only per a real user decision")
    }
}
