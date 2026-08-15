package com.yugma.terrawatch.news

import com.yugma.terrawatch.network.NewsArticle

/**
 * Plan 4 Task 5: the "In the news" state machine shared by both news surfaces —
 * [com.yugma.terrawatch.detail.DetailNewsViewModel] (per-selected-quake, DetailSheet's own section)
 * and [com.yugma.terrawatch.insights.InsightsNewsViewModel] (fixed M6+/7-day window, Insights' own
 * card) — same three states, same meaning, even though the two VMs are otherwise independent
 * (different trigger, different window, different magnitude floor).
 *
 * Deliberately only three states, not the plan's usual four ("Loading/Content/Empty/Error" —
 * InsightsUiState's own shape): [GdeltClient][com.yugma.terrawatch.network.GdeltClient] already
 * collapses every failure mode (network, non-2xx, malformed body) AND a genuine zero-results
 * response down to a plain empty list — this feature has no distinct "error" a Retry action could
 * even act on, and a quiet [Hidden] is the correct rendering for "nothing to show" regardless of
 * which of those reasons caused it. [Content] is never constructed with an empty list — that case
 * routes to [Hidden] instead — so any renderer switching on this type can safely assume
 * [Content.articles] is non-empty without a defensive check of its own.
 */
sealed interface NewsUiState {
    /** No selected quake, magnitude below the feature's own floor, or a resolved fetch that came
     * back with zero articles — all three collapse here on purpose, see this interface's own kdoc. */
    data object Hidden : NewsUiState

    data object Loading : NewsUiState

    data class Content(val articles: List<NewsArticle>) : NewsUiState
}
