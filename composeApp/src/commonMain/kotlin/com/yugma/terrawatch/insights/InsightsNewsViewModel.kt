package com.yugma.terrawatch.insights

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.yugma.terrawatch.data.QuakeRepository
import com.yugma.terrawatch.model.Quake
import com.yugma.terrawatch.network.GdeltClient
import com.yugma.terrawatch.network.NewsFeature
import com.yugma.terrawatch.network.NewsResult
import com.yugma.terrawatch.news.NewsUiState
import com.yugma.terrawatch.news.usgsEventUrl
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.launch

// Insights' own news floor: M6+ in the last 7 days, per the plan brief ("In the news" Insights
// card — M6+ last 7d) — a fixed window, independent of InsightsViewModel's own user-toggled
// 7d/30d period (the two "7"s are coincidentally the same NUMBER, not the same STATE; this card
// never changes when the user flips to the 30-day chart view).
private const val INSIGHTS_NEWS_MIN_MAG = 6.0
private const val INSIGHTS_NEWS_WINDOW_DAYS = 7L

/**
 * Plan 4 Task 5: Insights' "In the news" card state machine — independent of, and deliberately NOT
 * folded into, [InsightsViewModel] itself. [InsightsViewModel]'s own kdoc is explicit that it is
 * "offline-pure ... zero network calls anywhere in this class" and is this app's literal proof
 * screen that airplane mode changes nothing there — adding a GDELT lookup directly into that class
 * would quietly break a real, load-bearing, already-tested architectural guarantee for one
 * additive, best-effort card. A sibling `ViewModel`, resolved via `InsightsScreen`'s own second
 * `koinViewModel()` default parameter (same "screen resolves more than one ViewModel" shape
 * `HomeScreen` already has with `viewModel`+`selectionViewModel`), keeps that guarantee intact:
 * airplane mode still renders the three core cards exactly as before, and only this card's own
 * content is ever affected by connectivity.
 *
 * "Strongest M6+ in the last 7 days" is recomputed off [QuakeRepository.recentQuakes]'s own
 * 24h-default invalidation ping — the SAME "discard the payload, treat any emission as `something
 * changed, recompute`" pattern [InsightsViewModel]'s own `recentQuakes().drop(1).conflate()`
 * collector already established (see that class's kdoc) — deliberately WITHOUT `.drop(1)` here:
 * this class has no sibling collector already covering the very first load the way
 * [InsightsViewModel]'s period-collector covers its own initial compute, so the first, subscribe-time
 * "current state" emission IS this class's initial compute, not a redundant one to skip.
 *
 * [lastQuakeId] dedupes against re-fetching/re-flashing [NewsUiState.Loading] when an unrelated
 * quake arrives elsewhere and re-triggers the invalidation ping while the 7-day window's OWN
 * strongest M6+ candidate hasn't actually changed — both a UX nicety (no flicker on unrelated
 * arrivals) and, per this spike's own finding (task-5-report.md), a real reduction in how often this
 * app calls GDELT's rate-limited endpoint for a result it already has.
 *
 * Plan 5 (news kill-switch): [newsEnabled] gates [recompute] and [retry] ahead of everything else
 * either already checks — see [NewsFeature]'s own kdoc for the full "why" and "how to re-enable."
 * Defaulted to [NewsFeature.ENABLED] (production's own `AppModule.kt` wiring doesn't pass this
 * parameter, so it always gets the real compile-time flag) rather than a required constructor
 * parameter, so tests can override it to `true` and keep exercising the real window/floor/fetch/
 * retry logic below even while the flag is OFF in production — see
 * `InsightsNewsViewModelTest`'s own `createVm` helper.
 */
class InsightsNewsViewModel(
    private val repository: QuakeRepository,
    private val gdeltClient: GdeltClient,
    private val clock: () -> Long,
    private val newsEnabled: Boolean = NewsFeature.ENABLED,
) : ViewModel() {
    // Starts at Loading, NOT Hidden — matching InsightsViewModel's own `_state` default (that
    // class's kdoc: initial value is InsightsUiState.Loading, never treated as a settled state).
    // Hidden is ALSO a legitimate settled state here (genuinely "no M6+ candidate this week"), so
    // starting there would be ambiguous with "haven't computed yet" — a real bug this test suite
    // caught: `init`'s collector always runs a real computation immediately, so Loading honestly
    // describes that transient window instead of risking a flash of a possibly-wrong Hidden.
    private val _newsState = MutableStateFlow<NewsUiState>(NewsUiState.Loading)
    val newsState: StateFlow<NewsUiState> = _newsState

    private var lastQuakeId: String? = null

    // Task 2b (dogfooding fix, task-2b-news-fix-report.md): remembers whichever candidate the most
    // recent fetch ran for, purely so [retry] can re-issue it without the caller re-supplying it —
    // same shape DetailNewsViewModel's own pendingQuake just established for its sibling concern.
    private var pendingCandidate: Quake? = null

    init {
        viewModelScope.launch {
            repository.recentQuakes().conflate().collect { recompute() }
        }
    }

    /**
     * Plan 5: [newsEnabled] is checked FIRST, ahead of the window/floor logic below — disabled
     * means Hidden unconditionally, without ever calling [QuakeRepository.strongest] (no point
     * running that query for an answer this method won't act on) and, just as importantly, without
     * [fetch] ever being reached, so [gdeltClient] never sees a call.
     */
    private suspend fun recompute() {
        if (!newsEnabled) {
            lastQuakeId = null
            pendingCandidate = null
            _newsState.value = NewsUiState.Hidden
            return
        }
        val sinceMillis = clock() - INSIGHTS_NEWS_WINDOW_DAYS * DAY_MILLIS
        val candidate = repository.strongest(sinceMillis)?.takeIf { (it.mag ?: 0.0) >= INSIGHTS_NEWS_MIN_MAG }
        if (candidate == null) {
            lastQuakeId = null
            pendingCandidate = null
            _newsState.value = NewsUiState.Hidden
            return
        }
        if (candidate.id == lastQuakeId) return // unchanged 7d/M6+ strongest — nothing to re-fetch
        lastQuakeId = candidate.id
        fetch(candidate)
    }

    /** Task 2b: [NewsUiState.Error]'s Retry action — re-issues the fetch for [pendingCandidate], the
     * same M6+/7d candidate [recompute] most recently resolved. A no-op if nothing is pending
     * (defensive only — see [DetailNewsViewModel.retry]'s identical reasoning). Launched explicitly
     * (unlike [recompute], which already runs inside [init]'s own collector coroutine) since a user
     * tap arrives from outside that flow.
     *
     * Plan 5: also a no-op when [newsEnabled] is off, same belt-and-suspenders reasoning as
     * [DetailNewsViewModel.retry]'s identical guard — [recompute]'s own guard already keeps
     * [pendingCandidate] permanently `null` on that path. */
    fun retry() {
        if (!newsEnabled) return
        pendingCandidate?.let { candidate -> viewModelScope.launch { fetch(candidate) } }
    }

    private suspend fun fetch(candidate: Quake) {
        pendingCandidate = candidate
        _newsState.value = NewsUiState.Loading
        _newsState.value = when (val result = gdeltClient.searchEarthquakeNews(place = candidate.place, eventTimeMillis = candidate.timeMillis)) {
            // Empty results resolve to Empty (with the zero-dep USGS fallback link), never an empty
            // Content — see NewsUiState's own kdoc. A Failure resolves to Error, never silently
            // back to Hidden (Task 2b).
            is NewsResult.Success -> if (result.articles.isEmpty()) {
                NewsUiState.Empty(usgsEventUrl(candidate))
            } else {
                NewsUiState.Content(result.articles)
            }
            NewsResult.Failure -> NewsUiState.Error
        }
    }
}
