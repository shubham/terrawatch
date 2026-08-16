package com.yugma.terrawatch.news

import com.yugma.terrawatch.model.Quake
import com.yugma.terrawatch.model.Source
import com.yugma.terrawatch.network.NewsArticle

/**
 * Plan 4 Task 5: the "In the news" state machine shared by both news surfaces —
 * [com.yugma.terrawatch.detail.DetailNewsViewModel] (per-selected-quake, DetailSheet's own section)
 * and [com.yugma.terrawatch.insights.InsightsNewsViewModel] (fixed M6+/7-day window, Insights' own
 * card) — same five states, same meaning, even though the two VMs are otherwise independent
 * (different trigger, different window, different magnitude floor).
 *
 * Task 2b (dogfooding fix, task-2b-news-fix-report.md): this used to be only three states, not the
 * plan's usual four ("Loading/Content/Empty/Error" — InsightsUiState's own shape), on the reasoning
 * that [GdeltClient][com.yugma.terrawatch.network.GdeltClient] already collapsed every failure mode
 * (network, non-2xx, malformed body) AND a genuine zero-results response down to a plain empty
 * list, so there was supposedly no distinct "error" a Retry action could act on. That reasoning
 * broke in the field: [GdeltClient.searchEarthquakeNews] now returns
 * [NewsResult][com.yugma.terrawatch.network.NewsResult] specifically so this state machine CAN
 * distinguish "resolved cleanly, genuinely nothing relevant" ([Empty], with the plan's own
 * zero-dependency USGS-event-page fallback link — `docs/superpowers/plans/plan-4-backlog.md`)
 * from "the fetch itself failed" ([Error], compact row + Retry) — a shimmer that resolves to either
 * of those is honest; a shimmer that just vanishes (this feature's actual dogfooding bug) is not.
 * [Hidden] still exists, for the one case that was never the bug: no selected quake, or magnitude
 * below the feature's own floor — the fetch never even starts, so there is genuinely nothing to
 * report and staying fully invisible is correct. [Content] is never constructed with an empty
 * list — that case routes to [Empty] instead — so any renderer switching on this type can safely
 * assume [Content.articles] is non-empty without a defensive check of its own.
 */
sealed interface NewsUiState {
    /** No selected quake, or magnitude below the feature's own floor — the fetch never starts, so
     * the section stays fully invisible. Distinct from [Empty] (a fetch DID run and resolved to
     * zero relevant articles) and from [Error] (a fetch DID run and failed) — see this interface's
     * own kdoc for why only this original case still collapses silently. */
    data object Hidden : NewsUiState

    data object Loading : NewsUiState

    data class Content(val articles: List<NewsArticle>) : NewsUiState

    /** A fetch resolved cleanly with zero relevant articles — renders "No news coverage yet" plus
     * [usgsEventUrl]'s link row (the plan's own zero-dependency fallback) rather than collapsing to
     * [Hidden]: the user asked (implicitly, by viewing an M5.5+/M6+ quake) and deserves an answer,
     * not a shimmer that quietly disappears. [usgsEventUrl] is null only when the underlying quake
     * has no USGS-sourced id at all (an EMSC-only quake) — the caption still shows, just without
     * the link row. */
    data class Empty(val usgsEventUrl: String?) : NewsUiState

    /** The fetch itself failed — network exception, non-2xx, or GDELT's own malformed-query
     * "200 OK + HTML error page" quirk (see
     * [GdeltClient.searchEarthquakeNews][com.yugma.terrawatch.network.GdeltClient.searchEarthquakeNews]'s
     * own kdoc). Distinct from [Empty]: this is "we don't know," not "we checked and there's
     * nothing" — renders a compact "Couldn't load news" row with a Retry action. */
    data object Error : NewsUiState
}

/**
 * The plan's own zero-dependency news fallback (`docs/superpowers/plans/plan-4-backlog.md`:
 * "Fallback if GDELT quality poor: USGS event-page link row") — shared by both
 * [com.yugma.terrawatch.detail.DetailNewsViewModel] and
 * [com.yugma.terrawatch.insights.InsightsNewsViewModel] so [NewsUiState.Empty] means the identical
 * thing from either caller. Reads the raw USGS feature id straight off [Quake.sources] (populated
 * verbatim from the USGS feed itself — see `UsgsFeedParser.kt`'s own `sources = mapOf(Source.USGS
 * to featureId)`), needing no extra network round-trip of its own — null only for an EMSC-only
 * quake with no USGS mirror at all.
 */
internal fun usgsEventUrl(quake: Quake): String? =
    quake.sources[Source.USGS]?.let { "https://earthquake.usgs.gov/earthquakes/eventpage/$it" }
