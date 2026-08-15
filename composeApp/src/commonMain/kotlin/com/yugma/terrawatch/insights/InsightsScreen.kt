package com.yugma.terrawatch.insights

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.yugma.terrawatch.common.rememberNowMillisTicker
import com.yugma.terrawatch.detail.DetailNewsViewModel
import com.yugma.terrawatch.detail.DetailSheet
import com.yugma.terrawatch.home.QuakeSelectionViewModel
import com.yugma.terrawatch.model.Quake
import com.yugma.terrawatch.motion.LocalReducedMotion
import com.yugma.terrawatch.network.NewsArticle
import com.yugma.terrawatch.news.NewsUiState
import com.yugma.terrawatch.share.openUrl
import com.yugma.terrawatch.share.shareQuakeText
import com.yugma.terrawatch.share.sharePackaged
import com.yugma.terrawatch.ui.charts.BarChart
import com.yugma.terrawatch.ui.charts.DistributionBars
import com.yugma.terrawatch.ui.components.QuakeCard
import com.yugma.terrawatch.ui.components.SkeletonCard
import com.yugma.terrawatch.ui.format.formatCount
import com.yugma.terrawatch.ui.format.formatRelativeTime
import com.yugma.terrawatch.ui.format.formatShortDate
import com.yugma.terrawatch.ui.theme.TerraRadii
import org.koin.compose.viewmodel.koinViewModel

/**
 * Task 6 (Plan 3): the Insights tab - a 7d/30d toggle over three cards (quakes/day bars, magnitude
 * distribution, strongest-this-period), all computed from the local cache alone
 * ([InsightsViewModel] never calls the network - see that class's own kdoc). This is the "offline
 * mode still renders" proof screen the plan brief calls out explicitly: airplane mode changes
 * nothing here, since there was never a network call to lose.
 *
 * Same detail-sheet wiring as `HistoryScreen`/`HomeScreen`: [selectionViewModel] is the SAME
 * Activity-scoped instance every tab shares (resolved once in `App()`, threaded down through
 * `AppNav`), so tapping the STRONGEST card opens the identical detail sheet a map pin or a
 * History row would.
 *
 * Plan 4 Task 4 (a), SDK-36 edge-to-edge sweep: same gap/fix as `HistoryScreen`'s own kdoc —
 * [InsightsHeader] had no status-bar awareness at all (Insights, like History, is a `TAB_ROUTES`
 * member with nothing else above it); `windowInsetsPadding(WindowInsets.statusBars)` on this outer
 * `Column` fixes it. No bottom fix needed for the identical reason — `AppNav`'s own `AppBottomBar`
 * always renders below this whole `Column` and already reserves navigation-bar space itself.
 */
@Composable
fun InsightsScreen(
    selectionViewModel: QuakeSelectionViewModel,
    // Plan 4 Task 5: same Activity-scoped, explicitly-threaded shape as selectionViewModel above -
    // see DetailNewsViewModel's own kdoc. Backs the detail sheet's OWN "In the news" section, NOT
    // this screen's separate "In the news" CARD (that's newsViewModel below).
    detailNewsViewModel: DetailNewsViewModel = koinViewModel(),
    // Plan 4 Task 5: Insights' own "In the news" card (M6+/7d, period-independent) - a SEPARATE
    // ViewModel from [viewModel] on purpose, see InsightsNewsViewModel's own kdoc for why it isn't
    // folded into InsightsViewModel. Resolved via this screen's own second defaulted
    // `= koinViewModel()` param, same shape [viewModel] itself already establishes - nothing else
    // in this graph needs to share this one instance.
    newsViewModel: InsightsNewsViewModel = koinViewModel(),
    viewModel: InsightsViewModel = koinViewModel(),
) {
    val state by viewModel.state.collectAsState()
    val period by viewModel.period.collectAsState()
    val selectedQuake by selectionViewModel.selectedQuake.collectAsState()
    val detailNewsState by detailNewsViewModel.newsState.collectAsState()
    val newsCardState by newsViewModel.newsState.collectAsState()
    LaunchedEffect(selectedQuake) { detailNewsViewModel.onQuakeSelected(selectedQuake) }
    val nowMillis by rememberNowMillisTicker()
    // Plan 4 Task 5: null except "30-day period AND InsightsViewModel actually populated
    // Content.worldwideCount" - see densityCaption's own kdoc. Computed here (not inside the
    // `when` below) so it can reach InsightsHeader, which renders ABOVE that `when` block.
    val densityCaption = (state as? InsightsUiState.Content)?.let {
        densityCaption(period = period, cachedCount = it.dayCounts.sum(), worldwideCount = it.worldwideCount)
    }

    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Box(Modifier.fillMaxSize()) {
            Column(
                Modifier
                    .fillMaxSize()
                    .windowInsetsPadding(WindowInsets.statusBars)
                    .verticalScroll(rememberScrollState()),
            ) {
                InsightsHeader(period = period, onPeriodChange = viewModel::setPeriod, densityCaption = densityCaption)
                when (val s = state) {
                    InsightsUiState.Loading -> InsightsSkeleton(modifier = Modifier.padding(horizontal = 16.dp))
                    is InsightsUiState.Content -> InsightsContent(
                        content = s,
                        nowMillis = nowMillis,
                        onStrongestClick = { id -> selectionViewModel.select(id) },
                        modifier = Modifier.padding(horizontal = 16.dp),
                    )
                    InsightsUiState.Empty -> InsightsEmptyState()
                    is InsightsUiState.Error -> InsightsErrorState(onRetry = viewModel::retry)
                }
                // Plan 4 Task 5: rendered OUTSIDE the `when` above and gated on its own
                // [newsCardState] alone, deliberately - this card's own 7-day/M6+ window is
                // completely independent of [state]/[period] (a quiet 7d/30d chart period and a
                // genuine M6+ news story in the last week are unrelated facts; this card must be
                // able to show even while the 3 core cards read Empty, and must stay silent even
                // while they read Content).
                if (newsCardState != NewsUiState.Hidden) {
                    Spacer(Modifier.height(12.dp))
                    NewsCard(
                        newsState = newsCardState,
                        nowMillis = nowMillis,
                        onArticleClick = { url -> openUrl(url) },
                        reducedMotion = LocalReducedMotion.current,
                        modifier = Modifier.padding(horizontal = 16.dp),
                    )
                }
                Spacer(Modifier.height(24.dp)) // bottom breathing room under the scroll content
            }
            selectedQuake?.let { quake ->
                DetailSheet(
                    quake = quake,
                    distanceKm = null,
                    nowMillis = nowMillis,
                    onShare = { text -> shareQuakeText(text) },
                    onDismiss = { selectionViewModel.dismissSelection() },
                    onSharePackaged = { pkg, text -> sharePackaged(pkg, text) },
                    newsState = detailNewsState,
                    onNewsArticleClick = { url -> openUrl(url) },
                )
            }
        }
    }
}

/**
 * Task 11 (external review, REVIEW-CLARITY): [INSIGHTS_SUBTITLE] was added so Insights and
 * [com.yugma.terrawatch.history.HistoryScreen]'s own [com.yugma.terrawatch.history.HISTORY_SUBTITLE]
 * read as two distinct promises at a glance (the review's own complaint was that, title-only, the
 * two tabs didn't visibly say what told them apart) — "trends" (computed/aggregated) vs. "archive"
 * (raw/browsable), matching what each screen actually shows below its header. `internal`, not
 * `private` (this file's own established "so a test can pin it" convention — see [dayCountLabels]
 * above), so [InsightsHeader] itself can be rendered directly, no DI, in an instrumented test.
 */
internal const val INSIGHTS_SUBTITLE = "Trends from recent activity"

@Composable
internal fun InsightsHeader(
    period: InsightsPeriod,
    onPeriodChange: (InsightsPeriod) -> Unit,
    // Plan 4 Task 5: defaulted to null so ComponentsTest's own existing direct render
    // (`InsightsHeader(period = ..., onPeriodChange = {})`) keeps compiling and rendering
    // identically - see densityCaption's own kdoc for when this is ever non-null.
    densityCaption: String? = null,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column {
            Text(
                text = "Insights",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onBackground,
            )
            // Task 11: small, matches the mockup's own title treatment (the mockup itself has no
            // literal subtitle line - this is the review's own ask - so "small" is read as "the
            // same quiet register CalmContent's own subtitle already uses elsewhere in this app,"
            // i.e. bodySmall/onSurfaceVariant, not a second competing headline.
            Text(
                text = INSIGHTS_SUBTITLE,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            // Plan 4 Task 5: the density-disclosure caption - "subtitle GAINS the caption" (the
            // brief's own wording) means appended below the existing subtitle, never replacing it.
            if (densityCaption != null) {
                Text(
                    text = densityCaption,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
        }
        // Two FilterChips, not an ExperimentalMaterial3Api SegmentedButton (the brief's own
        // explicitly offered alternative) - matches HistoryScreen's already-established chip
        // pattern (mag/year filter rows) with zero new OptIn surface, close enough to the mockup's
        // own rounded-pill toggle look.
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            InsightsPeriod.entries.forEach { p ->
                FilterChip(selected = period == p, onClick = { onPeriodChange(p) }, label = { Text(p.shortLabel) })
            }
        }
    }
}

@Composable
private fun InsightsContent(
    content: InsightsUiState.Content,
    nowMillis: Long,
    onStrongestClick: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        InsightsCard {
            CardEyebrow(text = "QUAKES PER DAY", trailing = "${formatCount(content.dayCounts.sum())} total")
            BarChart(
                values = content.dayCounts,
                labels = dayCountLabels(content.dayCounts.size, content.nowBucketAtCompute),
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            )
        }
        InsightsCard {
            CardEyebrow(text = "BY MAGNITUDE", trailing = content.periodLabel)
            DistributionBars(bands = content.bands, modifier = Modifier.fillMaxWidth().padding(top = 10.dp))
        }
        InsightsCard {
            CardEyebrow(text = "STRONGEST", trailing = content.periodLabel)
            StrongestRow(quake = content.strongest, nowMillis = nowMillis, onClick = onStrongestClick)
        }
    }
}

@Composable
private fun StrongestRow(quake: Quake?, nowMillis: Long, onClick: (String) -> Unit, modifier: Modifier = Modifier) {
    if (quake != null) {
        QuakeCard(
            quake = quake,
            distanceKm = null,
            nowMillis = nowMillis,
            onClick = { onClick(quake.id) },
            modifier = modifier.padding(top = 8.dp),
        )
    } else {
        Text(
            text = "No quake with a known magnitude in this period",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = modifier.padding(top = 8.dp),
        )
    }
}

/** Every insight card: a white/[MaterialTheme.colorScheme.surface] rounded [TerraRadii.card]
 * surface with fixed internal padding - the one shared shape all three cards use, so a future
 * fourth card gets it for free. [modifier] defaults to [Modifier.fillMaxWidth] (unchanged behavior
 * for [InsightsContent]'s own 3 call sites, none of which pass one) - Plan 4 Task 5's [NewsCard] is
 * that predicted fourth card, and the one that actually needs a caller-supplied modifier (its own
 * call site in `InsightsScreen` sits OUTSIDE [InsightsContent]'s shared horizontal-padding
 * `Column`, so it has to carry that padding itself). */
@Composable
private fun InsightsCard(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(TerraRadii.card),
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 1.dp,
    ) {
        Column(Modifier.padding(14.dp), content = content)
    }
}

@Composable
private fun CardEyebrow(text: String, trailing: String? = null, modifier: Modifier = Modifier) {
    Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            letterSpacing = 1.sp,
        )
        if (trailing != null) {
            Text(text = trailing, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/**
 * Plan 4 Task 5: Insights' own "In the news" card - the "future fourth card" [InsightsCard]'s own
 * kdoc predicted, reusing that exact shared shape. [NewsUiState.Hidden] is handled by the caller
 * (`InsightsScreen`'s own `if (newsCardState != NewsUiState.Hidden)` guard - this composable is
 * never even called for that case); [NewsUiState.Loading] reuses [SkeletonCard] ("loading shimmer
 * reuse" per the brief, same shimmer every other Loading state in this app already uses).
 */
@Composable
private fun NewsCard(
    newsState: NewsUiState,
    nowMillis: Long,
    onArticleClick: (String) -> Unit,
    reducedMotion: Boolean,
    modifier: Modifier = Modifier,
) {
    InsightsCard(modifier = modifier) {
        CardEyebrow(text = "IN THE NEWS")
        when (newsState) {
            NewsUiState.Hidden -> Unit // caller already guards this case; defensive no-op.
            NewsUiState.Loading -> Column(
                modifier = Modifier.padding(top = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                repeat(3) { SkeletonCard(reducedMotion = reducedMotion) }
            }
            is NewsUiState.Content -> Column(Modifier.padding(top = 4.dp)) {
                newsState.articles.forEachIndexed { index, article ->
                    NewsHeadlineRow(article = article, nowMillis = nowMillis, onClick = { onArticleClick(article.url) })
                    if (index != newsState.articles.lastIndex) {
                        HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.2f))
                    }
                }
            }
        }
    }
}

@Composable
private fun NewsHeadlineRow(article: NewsArticle, nowMillis: Long, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 8.dp)) {
        Text(
            text = article.title,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.height(2.dp))
        Text(
            text = "${article.domain} · ${formatRelativeTime(article.seenAtMillis, nowMillis)}",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * The bar chart's start/end baseline dates.
 *
 * Fix round (review I1 - this function's own PRIOR version had a real, since-fixed bug, documented
 * here rather than erased): it used to take a live `nowMillis` (this screen's own 30s-ticking
 * clock) and independently re-derive "today's bucket" from it at RENDER time, on the claim that
 * this would always land on the same bucket `InsightsViewModel.computeContent` used at COMPUTE
 * time since both divided by the same [DAY_MILLIS]. That claim was false whenever a UTC midnight
 * passes between a recompute and a later render with no recompute in between - genuinely possible
 * here, since [InsightsViewModel] deliberately does NOT recompute on a timer, only on a period flip
 * or a `recentQuakes` invalidation tick (see that class's own kdoc) - the live ticker would then
 * report a bucket one day ahead of whatever [content][InsightsUiState.Content] actually contains,
 * silently mislabeling the chart's end date.
 *
 * Fixed by taking [nowBucketAtCompute] - [InsightsUiState.Content.nowBucketAtCompute], the EXACT
 * bucket `computeContent` used to build `dayCounts` - directly, rather than re-deriving anything
 * from a clock of its own. The TRUE guarantee, now: these labels can never drift from the bars
 * they caption, because there is no second derivation left to disagree with the first - both
 * ultimately read the same stored `Long`. `internal`, not `private` (mirrors `DetailSheet.
 * buildShareText`'s "so a test can pin it" convention) - see `InsightsScreenTest`.
 */
internal fun dayCountLabels(bucketCount: Int, nowBucketAtCompute: Long): Pair<String, String> {
    if (bucketCount <= 0) return "" to ""
    val sinceBucket = nowBucketAtCompute - (bucketCount - 1)
    return formatShortDate(sinceBucket * DAY_MILLIS) to formatShortDate(nowBucketAtCompute * DAY_MILLIS)
}

/**
 * Plan 4 Task 5: the Insights density-disclosure caption — null unless [worldwideCount] is
 * non-null (i.e. [InsightsViewModel.worldwideCountIfThin] actually fetched or served a cached FDSN
 * total, which only ever happens for [InsightsPeriod.THIRTY_DAYS] with a thin local cache — see
 * that function's own kdoc for the exact gate). Pure and `internal` (this file's own established
 * "so a test can pin it" convention — see [dayCountLabels]) rather than living on
 * [InsightsViewModel]: turning numbers into user-facing copy is this screen's job, not the
 * ViewModel's, matching this codebase's existing "ViewModel emits data, screen formats it" split
 * (`InsightsContent`'s own `"${formatCount(...)} total"` trailing text is built the same way, at
 * render time, not pre-formatted by the ViewModel).
 *
 * Deliberately does NOT re-check `cachedCount < 100` itself — that gate already happened once, in
 * [InsightsViewModel], to decide whether to populate [worldwideCount] at all; re-deriving it here
 * from [cachedCount] alone would risk silently disagreeing with the ViewModel's own gate on some
 * future edit to either side. This function's only job is "given a [worldwideCount] the ViewModel
 * already decided to disclose, does the CURRENT [period] want to show it" (yes only for
 * THIRTY_DAYS, matching the brief's own scoping — the caption never appears on the 7-day view).
 */
internal fun densityCaption(period: InsightsPeriod, cachedCount: Long, worldwideCount: Long?): String? {
    if (period != InsightsPeriod.THIRTY_DAYS || worldwideCount == null) return null
    return "Charts show ${formatCount(cachedCount)} cached quakes · ${formatCount(worldwideCount)} total worldwide (USGS)"
}

/** Loading placeholder - three [SkeletonCard]s, one per card region. Per the brief's own "same
 * shape as Task 5's" instruction taken literally: History's `HistorySkeletonList` already reuses
 * this exact composable verbatim for its own Loading state, and Task 10 ("design catch-up bundle")
 * is the task that owns upgrading skeletons in general (shimmer, bespoke per-region shapes) - this
 * task only needs a minimal, consistent placeholder to exist, not a bespoke chart-shaped one. */
@Composable
private fun InsightsSkeleton(modifier: Modifier = Modifier) {
    // Task 10 (item b): real reduced-motion signal for SkeletonCard's shimmer - see that
    // composable's own kdoc for why it takes a plain parameter instead of reading
    // LocalReducedMotion itself.
    val reducedMotion = LocalReducedMotion.current
    Column(modifier.fillMaxWidth().padding(top = 4.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        repeat(3) { SkeletonCard(reducedMotion = reducedMotion) }
    }
}

@Composable
private fun InsightsEmptyState(modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = "Nothing to show yet",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
                textAlign = TextAlign.Center,
            )
            Text(
                text = "Data builds up as quakes arrive.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 8.dp),
            )
        }
    }
}

@Composable
private fun InsightsErrorState(onRetry: () -> Unit, modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = "Couldn't compute insights",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
                textAlign = TextAlign.Center,
            )
            Text(
                text = "This is a local, offline calculation - try again.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 8.dp),
            )
            Button(onClick = onRetry, modifier = Modifier.padding(top = 16.dp)) { Text("Retry") }
        }
    }
}
