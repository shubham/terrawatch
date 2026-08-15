package com.yugma.terrawatch.insights

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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.yugma.terrawatch.common.rememberNowMillisTicker
import com.yugma.terrawatch.detail.DetailSheet
import com.yugma.terrawatch.home.QuakeSelectionViewModel
import com.yugma.terrawatch.model.Quake
import com.yugma.terrawatch.motion.LocalReducedMotion
import com.yugma.terrawatch.share.shareQuakeText
import com.yugma.terrawatch.ui.charts.BarChart
import com.yugma.terrawatch.ui.charts.DistributionBars
import com.yugma.terrawatch.ui.components.QuakeCard
import com.yugma.terrawatch.ui.components.SkeletonCard
import com.yugma.terrawatch.ui.format.formatCount
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
    viewModel: InsightsViewModel = koinViewModel(),
) {
    val state by viewModel.state.collectAsState()
    val period by viewModel.period.collectAsState()
    val selectedQuake by selectionViewModel.selectedQuake.collectAsState()
    val nowMillis by rememberNowMillisTicker()

    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Box(Modifier.fillMaxSize()) {
            Column(
                Modifier
                    .fillMaxSize()
                    .windowInsetsPadding(WindowInsets.statusBars)
                    .verticalScroll(rememberScrollState()),
            ) {
                InsightsHeader(period = period, onPeriodChange = viewModel::setPeriod)
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
                Spacer(Modifier.height(24.dp)) // bottom breathing room under the scroll content
            }
            selectedQuake?.let { quake ->
                DetailSheet(
                    quake = quake,
                    distanceKm = null,
                    nowMillis = nowMillis,
                    onShare = { text -> shareQuakeText(text) },
                    onDismiss = { selectionViewModel.dismissSelection() },
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
internal fun InsightsHeader(period: InsightsPeriod, onPeriodChange: (InsightsPeriod) -> Unit, modifier: Modifier = Modifier) {
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
 * fourth card gets it for free. */
@Composable
private fun InsightsCard(content: @Composable ColumnScope.() -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
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
