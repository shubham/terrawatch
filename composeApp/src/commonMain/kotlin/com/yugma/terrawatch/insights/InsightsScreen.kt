package com.yugma.terrawatch.insights

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
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
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
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

@Composable
private fun InsightsHeader(period: InsightsPeriod, onPeriodChange: (InsightsPeriod) -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = "Insights",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onBackground,
        )
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
                labels = dayCountLabels(content.dayCounts.size, nowMillis),
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

/** The bar chart's start/end baseline dates - derived independently from [bucketCount]/[nowMillis]
 * rather than threaded through [InsightsUiState.Content] (whose own shape is pinned by the plan
 * brief to exactly `dayCounts`/`bands`/`strongest`/`periodLabel`), but guaranteed to land on the
 * SAME bucket range [InsightsViewModel.computeContent] used to build [dayCounts] in the first
 * place: both this function and that one derive `sinceBucket` as `nowBucket - (bucketCount - 1)`
 * from the identical `nowMillis`/[DAY_MILLIS] - the one shared constant - so the labels can never
 * drift a day off from the bars they caption. */
private fun dayCountLabels(bucketCount: Int, nowMillis: Long): Pair<String, String> {
    if (bucketCount <= 0) return "" to ""
    val nowBucket = nowMillis / DAY_MILLIS
    val sinceBucket = nowBucket - (bucketCount - 1)
    return formatShortDate(sinceBucket * DAY_MILLIS) to formatShortDate(nowBucket * DAY_MILLIS)
}

/** Loading placeholder - three [SkeletonCard]s, one per card region. Per the brief's own "same
 * shape as Task 5's" instruction taken literally: History's `HistorySkeletonList` already reuses
 * this exact composable verbatim for its own Loading state, and Task 10 ("design catch-up bundle")
 * is the task that owns upgrading skeletons in general (shimmer, bespoke per-region shapes) - this
 * task only needs a minimal, consistent placeholder to exist, not a bespoke chart-shaped one. */
@Composable
private fun InsightsSkeleton(modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth().padding(top = 4.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        repeat(3) { SkeletonCard() }
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
