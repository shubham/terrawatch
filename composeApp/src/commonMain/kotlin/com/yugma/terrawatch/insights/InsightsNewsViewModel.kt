package com.yugma.terrawatch.insights

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.yugma.terrawatch.data.QuakeRepository
import com.yugma.terrawatch.network.GdeltClient
import com.yugma.terrawatch.news.NewsUiState
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
 */
class InsightsNewsViewModel(
    private val repository: QuakeRepository,
    private val gdeltClient: GdeltClient,
    private val clock: () -> Long,
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

    init {
        viewModelScope.launch {
            repository.recentQuakes().conflate().collect { recompute() }
        }
    }

    private suspend fun recompute() {
        val sinceMillis = clock() - INSIGHTS_NEWS_WINDOW_DAYS * DAY_MILLIS
        val candidate = repository.strongest(sinceMillis)?.takeIf { (it.mag ?: 0.0) >= INSIGHTS_NEWS_MIN_MAG }
        if (candidate == null) {
            lastQuakeId = null
            _newsState.value = NewsUiState.Hidden
            return
        }
        if (candidate.id == lastQuakeId) return // unchanged 7d/M6+ strongest — nothing to re-fetch
        lastQuakeId = candidate.id
        _newsState.value = NewsUiState.Loading
        val articles = gdeltClient.searchEarthquakeNews(place = candidate.place, eventTimeMillis = candidate.timeMillis)
        // Empty results collapse to Hidden, not an empty Content — see NewsUiState's own kdoc.
        _newsState.value = if (articles.isEmpty()) NewsUiState.Hidden else NewsUiState.Content(articles)
    }
}
