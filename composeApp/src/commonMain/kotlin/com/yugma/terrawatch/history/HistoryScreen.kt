package com.yugma.terrawatch.history

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.yugma.terrawatch.common.rememberNowMillisTicker
import com.yugma.terrawatch.data.HistoryFilter
import com.yugma.terrawatch.detail.DetailNewsViewModel
import com.yugma.terrawatch.detail.DetailSheet
import com.yugma.terrawatch.filter.MAGNITUDE_FILTER_CHIPS
import com.yugma.terrawatch.home.QuakeSelectionViewModel
import com.yugma.terrawatch.motion.LocalReducedMotion
import com.yugma.terrawatch.share.openUrl
import com.yugma.terrawatch.share.shareQuakeText
import com.yugma.terrawatch.share.sharePackaged
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

// User review items 3+4: the magnitude chip row now sources the SHARED "All/4.0+/5.0+/6.0+"
// vocabulary (MAGNITUDE_FILTER_CHIPS, core to composeApp's own com.yugma.terrawatch.filter package)
// instead of this screen's own former, independent MAG_CHIPS ("All"/"M4.5+"/"M6+") — see that
// file's own kdoc for why one shared definition replaces two independently-drifting ones.
//
// Migration note (checked, not assumed — EVIDENCE INTEGRITY): HistoryFilter/this screen's own chip
// selection has never been persisted anywhere (HistoryViewModel's `_filter` is a plain
// MutableStateFlow(HistoryFilter()), reset to "All" on every fresh HistoryViewModel — grepped for
// any AlertRuleStore/QuakeDao.metaPut-style write of a filter value; none exists), so there is no
// stored user selection to migrate. The one real carry-over: HistoryPager persists its PAGING
// CURSOR per filter value under a `history_cursor_min<X>_yr<Y>` meta key (see that class's own
// `stableKey()`) — a device that had previously paged deep into the old "M4.5+" chip (minMag=4.5,
// not one of this new set's three values) leaves that one cursor row orphaned: harmless (it's
// simply never looked up again, since no chip can produce minMag=4.5 anymore), not a correctness
// issue, and not worth a migration step of its own for a handful of dead key/value rows in a local
// SQLite meta table. The former "M6+" chip's value (6.0) is unchanged in the new set, so any
// cursor progress under that one carries over exactly as before.


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
 *
 * Plan 4 Task 4 (a), SDK-36 edge-to-edge sweep: [HistoryHeader] used to render with no status-bar
 * awareness at all — the very first screen `AppNav.kt`'s phone-layout `Column` places above THIS
 * one (History is a `TAB_ROUTES` member, so nothing else sits between it and the physical top of the
 * screen). `windowInsetsPadding(WindowInsets.statusBars)` on this outer `Column` fixes that; the
 * BOTTOM edge needs no matching fix — `AppNav`'s own `AppBottomBar` (a real `NavigationBar`, whose
 * M3 default already reserves navigation-bar space) always renders below this whole `Column` on the
 * same tab route, so this screen's own content never structurally reaches the physical bottom edge
 * the way a stack-only, chrome-less screen (e.g. Settings) does.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun HistoryScreen(
    selectionViewModel: QuakeSelectionViewModel,
    // Plan 4 Task 5: same Activity-scoped, explicitly-threaded shape as selectionViewModel above -
    // see DetailNewsViewModel's own kdoc. Defaulted purely so a Koin-free test could override it,
    // matching selectionViewModel's own established convention.
    detailNewsViewModel: DetailNewsViewModel = koinViewModel(),
    viewModel: HistoryViewModel = koinViewModel(),
) {
    val state by viewModel.state.collectAsState()
    val filter by viewModel.filter.collectAsState()
    // User review item 3 (history search): see HistorySearchField's own kdoc for the field itself,
    // and this function's own "effectively empty" note below for how [searchQuery] combines with
    // [state] to pick between the two different empty treatments.
    val searchQuery by viewModel.searchQuery.collectAsState()
    val selectedQuake by selectionViewModel.selectedQuake.collectAsState()
    val newsState by detailNewsViewModel.newsState.collectAsState()
    LaunchedEffect(selectedQuake) { detailNewsViewModel.onQuakeSelected(selectedQuake) }
    val nowMillis by rememberNowMillisTicker()
    val listState = rememberLazyListState()

    LoadMoreOnScrollEnd(listState = listState, onLoadMore = viewModel::loadMore)

    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Box(Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.statusBars)) {
                HistoryHeader()
                HistorySearchField(
                    query = searchQuery,
                    onQueryChange = viewModel::setSearchQuery,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                )
                HistoryFilterChips(filter = filter, onFilterChange = viewModel::setFilter)
                // User review item 3: "effectively empty" folds TWO independent sealed shapes
                // ([HistoryUiState.Empty] itself, and a [HistoryUiState.Content] whose `sections`
                // search has narrowed to nothing — see that sealed interface's own kdoc for why the
                // latter is now a legitimate, durable published shape, not just a transient one)
                // into ONE "nothing to show right now" question, then [searchQuery] alone decides
                // which of the two different empty COPIES applies — a search-caused empty gets "No
                // quakes match 'x'" + a clear-search action; every other empty (including a
                // search-caused Empty ONCE THE SEARCH IS LATER CLEARED — [state] can by then still
                // literally be a `Content(sections=emptyList())` left over from that search, not a
                // fresh [HistoryUiState.Empty]) keeps the screen's own original "widen filters" copy.
                // Computed once, ahead of the `when` below, so the two can never each independently
                // (and inconsistently) re-derive it.
                val effectivelyEmpty = when (val s = state) {
                    is HistoryUiState.Content -> s.sections.isEmpty()
                    HistoryUiState.Empty -> true
                    HistoryUiState.LoadingFirst, is HistoryUiState.Error -> false
                }
                if (effectivelyEmpty && searchQuery.isNotBlank()) {
                    HistorySearchEmptyState(
                        query = searchQuery,
                        onClearSearch = { viewModel.setSearchQuery("") },
                        modifier = Modifier.weight(1f),
                    )
                } else {
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
            }
            selectedQuake?.let { quake ->
                DetailSheet(
                    quake = quake,
                    distanceKm = null,
                    nowMillis = nowMillis,
                    onShare = { text -> shareQuakeText(text) },
                    onDismiss = { selectionViewModel.dismissSelection() },
                    onSharePackaged = { pkg, text -> sharePackaged(pkg, text) },
                    newsState = newsState,
                    onNewsArticleClick = { url -> openUrl(url) },
                    onNewsRetry = detailNewsViewModel::retry,
                )
            }
        }
    }
}

/**
 * Task 11 (external review): History had NO screen header at all before this — filter chips sat
 * directly under the status bar with nothing naming the screen, unlike every other tab
 * ([com.yugma.terrawatch.insights.InsightsScreen]'s own `InsightsHeader`, threaded through
 * unchanged from Task 6). [HistoryHeader] matches that exact title treatment (headlineSmall, Bold,
 * onBackground, same 16dp/12dp padding) minus the period-toggle row Insights has and History has no
 * equivalent of. [HISTORY_SUBTITLE] is the REVIEW-CLARITY half of the same finding: title-only,
 * History and Insights didn't visibly say what told them apart — "archive" (raw, browsable, every
 * quake ever) vs. Insights' own "trends" (computed/aggregated) — see
 * [com.yugma.terrawatch.insights.INSIGHTS_SUBTITLE] for the matching half. `internal`, so an
 * instrumented test can render [HistoryHeader] directly with no ViewModel/DI in the loop, same
 * "direct composable content only" convention `ComponentsTest` already documents for itself.
 */
internal const val HISTORY_SUBTITLE = "Browse the full quake archive"

@Composable
internal fun HistoryHeader(modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)) {
        Text(
            text = "History",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onBackground,
        )
        // Small - same quiet register as InsightsHeader's own subtitle (bodySmall/onSurfaceVariant).
        Text(
            text = HISTORY_SUBTITLE,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
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

/**
 * User review item 3 (history search): a persistent, always-visible compact field — not a
 * collapsed-icon-that-expands affordance — matching this app's own established "no icon-library
 * dependency anywhere" convention (`StatusShield.kt`/`nav/NavIcons.kt`/`FeedSheet.kt`'s
 * `VisitSummaryBanner` all document this: every glyph here is either a hand-drawn `Canvas` path or
 * plain text, never `Icons.Default.*`). A collapsed-icon trigger would need a search-glyph icon to
 * tap in the first place — the persistent field needs none, and History already has clear vertical
 * room for it (no map/pins competing for space here the way Home's feed sheet header does — see
 * that screen's own, much more space-constrained filter control for the contrasting choice).
 *
 * The trailing "×" clear affordance reuses the exact same plain-Unicode-glyph idiom
 * [com.yugma.terrawatch.home.FeedSheet]'s own `VisitSummaryBanner` dismiss control already
 * establishes, shown only once there is something to clear.
 */
@Composable
private fun HistorySearchField(query: String, onQueryChange: (String) -> Unit, modifier: Modifier = Modifier) {
    OutlinedTextField(
        value = query,
        onValueChange = onQueryChange,
        modifier = modifier.fillMaxWidth(),
        placeholder = { Text("Search place, e.g. \"Indonesia\"") },
        singleLine = true,
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        trailingIcon = if (query.isNotEmpty()) {
            {
                Box(
                    modifier = Modifier
                        .size(32.dp)
                        .clickable(onClick = { onQueryChange("") })
                        .semantics { contentDescription = "Clear search" },
                    contentAlignment = Alignment.Center,
                ) {
                    Text("×", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        } else {
            null
        },
    )
}

@Composable
private fun HistoryFilterChips(filter: HistoryFilter, onFilterChange: (HistoryFilter) -> Unit, modifier: Modifier = Modifier) {
    val currentYear = remember { currentYearUtc() }
    Column(modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            MAGNITUDE_FILTER_CHIPS.forEach { (label, value) ->
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
    // Task 10 (item b): SkeletonCard's own shimmer now needs the real reduced-motion signal -
    // see that composable's kdoc for why it can't read LocalReducedMotion itself.
    val reducedMotion = LocalReducedMotion.current
    Column(
        modifier = modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        repeat(6) { SkeletonCard(reducedMotion = reducedMotion) }
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

/** User review item 3 (history search): the search-specific empty-result copy the task's own spec
 * calls for verbatim ("No quakes match 'x'") — a sibling of [HistoryEmptyState], not a reuse of it,
 * since the two need different copy AND a different recovery action (clear the search box here,
 * vs. reset the whole filter there) even though they share the same layout shape. */
@Composable
private fun HistorySearchEmptyState(query: String, onClearSearch: () -> Unit, modifier: Modifier = Modifier) {
    Box(modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                "No quakes match \"$query\"",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
                textAlign = TextAlign.Center,
            )
            Text(
                "Try a different place name, or clear the search.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 8.dp),
            )
            Button(onClick = onClearSearch, modifier = Modifier.padding(top = 16.dp)) { Text("Clear search") }
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
