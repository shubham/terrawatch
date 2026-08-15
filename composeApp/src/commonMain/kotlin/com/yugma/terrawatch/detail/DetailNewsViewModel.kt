package com.yugma.terrawatch.detail

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.yugma.terrawatch.model.Quake
import com.yugma.terrawatch.network.GdeltClient
import com.yugma.terrawatch.network.NewsResult
import com.yugma.terrawatch.news.NewsUiState
import com.yugma.terrawatch.news.usgsEventUrl
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/** DetailSheet's own "In the news" magnitude floor (Plan 4 Task 5 brief: "mag>=5.5") — distinct
 * from, and lower than, [com.yugma.terrawatch.insights.InsightsNewsViewModel]'s M6+ floor: the
 * detail sheet is already showing ONE specific, already-selected quake (the user is looking right
 * at it), where Insights' card is proactively surfacing "is anything big enough to have news
 * coverage" with no prior user intent — a higher bar there is deliberate, not an oversight. */
private const val DETAIL_NEWS_MIN_MAG = 5.5

/**
 * Plan 4 Task 5: DetailSheet's "In the news" section state machine — a magnitude-gated GDELT
 * lookup keyed on whichever quake [com.yugma.terrawatch.home.QuakeSelectionViewModel] currently has
 * selected.
 *
 * A separate, sibling ViewModel rather than a new responsibility folded into
 * [com.yugma.terrawatch.home.QuakeSelectionViewModel] itself: that class's own existing
 * `(repository, savedStateHandle)` constructor and its 5-case test suite
 * (`QuakeSelectionViewModelTest`) are unrelated to network access — widening it with a third,
 * network-touching dependency purely to serve an ADDITIVE, best-effort feature would risk that
 * class's own tight, already-reviewed scope for no real gain, when a sibling class wired the same
 * way (resolved once in `App()`, threaded through `AppNav` exactly like `QuakeSelectionViewModel`
 * itself — same Activity-scoped, shared-across-Home/History/Insights shape) does the job with zero
 * risk to it.
 *
 * [onQuakeSelected] is called from each of Home/History/Insights' own
 * `LaunchedEffect(selectedQuake)`, right alongside where they already read
 * `QuakeSelectionViewModel.selectedQuake` — `DetailSheet` itself stays fully "dumb" (its own kdoc's
 * word): it only ever renders whichever [NewsUiState] its caller hands it down as a plain
 * parameter, with no lookup of its own.
 *
 * Task 2b (dogfooding fix, task-2b-news-fix-report.md): [retry] re-issues the fetch for whichever
 * quake [NewsUiState.Error] is currently showing for — [pendingQuake] remembers it purely so a
 * Retry tap doesn't need the caller to re-supply the same [Quake] it already handed to
 * [onQuakeSelected].
 */
class DetailNewsViewModel(private val gdeltClient: GdeltClient) : ViewModel() {
    private val _newsState = MutableStateFlow<NewsUiState>(NewsUiState.Hidden)
    val newsState: StateFlow<NewsUiState> = _newsState

    // Cancellable, same "only the most recent call can win" shape
    // QuakeSelectionViewModel.select's own selectJob already established for the sibling selection
    // concern — a quick double-tap between two mag>=5.5 quakes must not let the FIRST quake's
    // slower-resolving fetch overwrite the second quake's already-landed result. Centralized in
    // [fetch] (Task 2b) so [retry] gets the identical cancel-before-replace discipline for free.
    private var job: Job? = null
    private var lastQuakeId: String? = null
    private var pendingQuake: Quake? = null

    /**
     * Idempotent re-entry: a recomposition that hands back the SAME quake id (e.g. an unrelated
     * state change causing a re-render while the sheet stays open on the same selection) is a
     * no-op — it must not re-flash [NewsUiState.Loading] or re-fetch identical news. A DIFFERENT id
     * (including a transition to/from `null`) always cancels whatever fetch was in flight first.
     */
    fun onQuakeSelected(quake: Quake?) {
        if (quake?.id == lastQuakeId) return
        lastQuakeId = quake?.id
        pendingQuake = quake
        if (quake == null || (quake.mag ?: 0.0) < DETAIL_NEWS_MIN_MAG) {
            job?.cancel()
            _newsState.value = NewsUiState.Hidden
            return
        }
        fetch(quake)
    }

    /** Task 2b: [NewsUiState.Error]'s Retry action — re-issues the exact same fetch [pendingQuake]
     * (the quake [onQuakeSelected] most recently resolved past the magnitude floor for) already
     * describes. A no-op if nothing is pending (defensive only — the Retry row only ever renders
     * from [NewsUiState.Error], which is unreachable without a [pendingQuake] already set). */
    fun retry() {
        pendingQuake?.let(::fetch)
    }

    private fun fetch(quake: Quake) {
        job?.cancel()
        _newsState.value = NewsUiState.Loading
        job = viewModelScope.launch {
            _newsState.value = when (val result = gdeltClient.searchEarthquakeNews(place = quake.place, eventTimeMillis = quake.timeMillis)) {
                // Empty results resolve to Empty (with the zero-dep USGS fallback link), never an
                // empty Content — see NewsUiState's own kdoc for why Content is never constructed
                // empty. A Failure resolves to Error, never silently back to Hidden (Task 2b).
                is NewsResult.Success -> if (result.articles.isEmpty()) {
                    NewsUiState.Empty(usgsEventUrl(quake))
                } else {
                    NewsUiState.Content(result.articles)
                }
                NewsResult.Failure -> NewsUiState.Error
            }
        }
    }
}
