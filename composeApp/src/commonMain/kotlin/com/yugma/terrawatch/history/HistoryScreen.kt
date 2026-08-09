package com.yugma.terrawatch.history

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.yugma.terrawatch.common.rememberNowMillisTicker
import com.yugma.terrawatch.data.HistoryFilter
import com.yugma.terrawatch.detail.DetailSheet
import com.yugma.terrawatch.home.QuakeSelectionViewModel
import com.yugma.terrawatch.share.shareQuakeText
import com.yugma.terrawatch.ui.components.QuakeCard
import com.yugma.terrawatch.ui.components.SkeletonCard
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import org.koin.compose.viewmodel.koinViewModel
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

// How close to the end of the currently-laid-out list (in item count, headers included) triggers
// the next page — small enough that a fast fling still has a page ready before the user hits a
// hard stop, without firing on every scroll tick once already near the end (distinctUntilChanged
// below only re-fires the trigger on a false->true transition).
private const val LOAD_MORE_THRESHOLD = 3

private val MAG_CHIPS = listOf("All" to null, "M4.5+" to 4.5, "M6+" to 6.0)

/**
 * Task 5 (Plan 3): the archive browser — filter chips (magnitude + year, independently
 * combinable) over a sticky-month-header [LazyColumn] of [QuakeCard]s, backed by
 * [HistoryViewModel]'s paged [com.yugma.terrawatch.data.HistoryPager] walk.
 *
 * [selectionViewModel] is the SAME Activity-scoped instance Home shares (Task 4's own design —
 * resolved once in `App()`, passed down through `AppNav`), not a second, independent one, so a row
 * tap here and a pin tap on Home open the identical detail sheet. [DetailSheet] is rendered
 * directly in THIS composable (not inherited from `HomeScreen`, which is off-composition entirely
 * while this tab is showing — `AppNav`'s `NavHost` shows exactly one tab route at a time) —
 * required for the device matrix's own "detail sheet opens from a history row" regression check.
 *
 * v1 scope, deliberate: no home-distance on History's cards/sheet (`distanceKm = null`
 * throughout). [HistoryViewModel]'s constructor is pinned to `(repository, pager)` by this task's
 * own interface — no [com.yugma.terrawatch.data.HomeLocationStore] wired in. A later pass could add
 * it without touching that class at all, by threading `homeLocation` down from `AppNav`'s own
 * `homeViewModel` as a plain Composable parameter here, the same way [selectionViewModel] itself is
 * already threaded — noted for whoever picks this up, not implemented now (out of this task's
 * explicit scope).
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun HistoryScreen(
    selectionViewModel: QuakeSelectionViewModel,
    viewModel: HistoryViewModel = koinViewModel(),
) {
    val state by viewModel.state.collectAsState()
    val filter by viewModel.filter.collectAsState()
    val selectedQuake by selectionViewModel.selectedQuake.collectAsState()
    val nowMillis by rememberNowMillisTicker()
    val listState = rememberLazyListState()

    LoadMoreOnScrollEnd(listState = listState, onLoadMore = viewModel::loadMore)

    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Box(Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize()) {
                HistoryFilterChips(filter = filter, onFilterChange = viewModel::setFilter)
                when (val s = state) {
                    HistoryUiState.LoadingFirst -> HistorySkeletonList(modifier = Modifier.weight(1f))
                    is HistoryUiState.Content -> HistoryList(
                        content = s,
                        nowMillis = nowMillis,
                        listState = listState,
                        onQuakeClick = { id -> selectionViewModel.select(id) },
                        onRetry = viewModel::retry,
                        modifier = Modifier.weight(1f),
                    )
                    HistoryUiState.Empty -> HistoryEmptyState(
                        onWidenFilters = { viewModel.setFilter(HistoryFilter()) },
                        modifier = Modifier.weight(1f),
                    )
                    is HistoryUiState.Error -> HistoryErrorState(onRetry = viewModel::retry, modifier = Modifier.weight(1f))
                }
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
 * Infinite scroll's own trigger — fires [onLoadMore] once the last laid-out item comes within
 * [LOAD_MORE_THRESHOLD] of the list's current end. `snapshotFlow` + `distinctUntilChanged` means
 * this only calls [onLoadMore] on the false->true transition, not on every scroll-driven
 * recomposition while already near the end — [HistoryViewModel.loadMore]'s own internal guard
 * (no-op while already loading or ended) makes this belt-and-suspenders safe either way, not the
 * only thing preventing duplicate fetches.
 */
@Composable
private fun LoadMoreOnScrollEnd(listState: LazyListState, onLoadMore: () -> Unit) {
    LaunchedEffect(listState) {
        snapshotFlow {
            val info = listState.layoutInfo
            val lastVisible = info.visibleItemsInfo.lastOrNull()?.index
            lastVisible != null && info.totalItemsCount > 0 && lastVisible >= info.totalItemsCount - LOAD_MORE_THRESHOLD
        }.distinctUntilChanged().collect { nearEnd -> if (nearEnd) onLoadMore() }
    }
}

@Composable
private fun HistoryFilterChips(filter: HistoryFilter, onFilterChange: (HistoryFilter) -> Unit, modifier: Modifier = Modifier) {
    val currentYear = remember { currentYearUtc() }
    Column(modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            MAG_CHIPS.forEach { (label, value) ->
                FilterChip(
                    selected = filter.minMag == value,
                    onClick = { onFilterChange(filter.copy(minMag = value)) },
                    label = { Text(label) },
                )
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp).horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            listOf(currentYear, currentYear - 1).forEach { year ->
                FilterChip(
                    selected = filter.year == year,
                    onClick = { onFilterChange(filter.copy(year = year)) },
                    label = { Text(year.toString()) },
                )
            }
            FilterChip(
                selected = filter.year == null,
                onClick = { onFilterChange(filter.copy(year = null)) },
                label = { Text("All") },
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun HistoryList(
    content: HistoryUiState.Content,
    nowMillis: Long,
    listState: LazyListState,
    onQuakeClick: (String) -> Unit,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyColumn(
        state = listState,
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        content.sections.forEach { section ->
            stickyHeader(key = "header:${section.label}") { MonthHeader(section.label) }
            items(section.quakes, key = { it.id }) { quake ->
                QuakeCard(quake = quake, distanceKm = null, nowMillis = nowMillis, onClick = { onQuakeClick(quake.id) })
            }
        }
        item(key = "footer") { HistoryFooter(content = content, onRetry = onRetry) }
    }
}

@Composable
private fun MonthHeader(label: String, modifier: Modifier = Modifier) {
    Surface(modifier = modifier.fillMaxWidth(), color = MaterialTheme.colorScheme.background) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(vertical = 8.dp),
        )
    }
}

@Composable
private fun HistoryFooter(content: HistoryUiState.Content, onRetry: () -> Unit, modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth().padding(vertical = 16.dp), contentAlignment = Alignment.Center) {
        when {
            content.loadingMore -> CircularProgressIndicator(modifier = Modifier.size(24.dp))
            content.loadMoreFailed -> Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Couldn't load more", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                TextButton(onClick = onRetry) { Text("Retry") }
            }
            content.endReached -> Text(
                "End of archive",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            // Mid-list: nothing to show yet, next page hasn't been asked for (still scrolling
            // toward LOAD_MORE_THRESHOLD) — an empty footer, not a permanent spinner.
            else -> Unit
        }
    }
}

@Composable
private fun HistorySkeletonList(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        repeat(6) { SkeletonCard() }
    }
}

@Composable
private fun HistoryEmptyState(onWidenFilters: () -> Unit, modifier: Modifier = Modifier) {
    Box(modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                "No quakes match these filters",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
                textAlign = TextAlign.Center,
            )
            Text(
                "Try a wider magnitude or year.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 8.dp),
            )
            Button(onClick = onWidenFilters, modifier = Modifier.padding(top = 16.dp)) { Text("Widen filters") }
        }
    }
}

@Composable
private fun HistoryErrorState(onRetry: () -> Unit, modifier: Modifier = Modifier) {
    Box(modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                "Couldn't load the archive",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
                textAlign = TextAlign.Center,
            )
            Text(
                "Check your connection and try again.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 8.dp),
            )
            Button(onClick = onRetry, modifier = Modifier.padding(top = 16.dp)) { Text("Retry") }
        }
    }
}

// UTC, matching HistoryFilter's own year-boundary convention (com.yugma.terrawatch.data's
// yearFloorMillisOrNull/yearCeilingMillisExclusiveOrNull) — so the chip labeled "the current year"
// always agrees with what that year's filter actually bounds, rather than drifting against the
// device's local calendar near a UTC day/year boundary.
@OptIn(ExperimentalTime::class)
private fun currentYearUtc(): Int =
    Clock.System.now().toLocalDateTime(TimeZone.UTC).year
