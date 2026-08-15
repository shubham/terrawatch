package com.yugma.terrawatch.home

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.yugma.terrawatch.model.Quake
import com.yugma.terrawatch.motion.LocalReducedMotion
import com.yugma.terrawatch.ui.components.QuakeCard
import com.yugma.terrawatch.ui.components.SkeletonCard
import com.yugma.terrawatch.ui.theme.TerraColors
import com.yugma.terrawatch.ui.theme.TerraRadii
import kotlinx.coroutines.launch

/**
 * The feed sheet's own content — everything below the grabber handle that
 * [androidx.compose.material3.BottomSheetScaffold] already draws for us via its default
 * `sheetDragHandle` ("grabber auto from M3" per the Task 9 brief). HomeScreen.kt hosts the actual
 * `BottomSheetScaffold` call (it also needs the screen's own `BoxWithConstraints` to compute
 * `sheetPeekHeight` as a fraction of screen height) and passes this composable as its
 * `sheetContent`; this file only draws what's inside the sheet, at whatever height the scaffold
 * gives it — peeking (30% of screen height) or fully expanded (this same content, just with more
 * of the list visible below the fold).
 *
 * Task 12: the "N NEW" chip half of this header is phone-only sheet chrome — the desktop/tablet
 * two-pane right panel (`HomeScreen.kt`'s `TwoPaneLayout`) has no peek/expanded state for a "seen it
 * yet" count to track, so it doesn't reuse this whole composable. It does reuse the other half,
 * [LiveStatusRow] (Fix 2, below), plus [FeedList] — see both composables' own kdocs.
 *
 * Task 3b (user dogfooding report): "count updates but new rows stay hidden above the viewport
 * until the user scrolls up manually." ROOT CAUSE, confirmed by reading (not guessed): [FeedList]'s
 * `LazyColumn` items are keyed by `it.id` and [quakes] arrives already time-descending (newest
 * first — `Quake.sq`'s `recent` query is `ORDER BY timeMillis DESC`, `QuakeRepository.recentQuakes`
 * does no re-sort of its own), so a new arrival is always a PREPEND at index 0. Compose's own
 * key-based scroll-position preservation (`LazyListState` re-anchors `firstVisibleItemIndex` to
 * whichever KEY was on screen, not to a fixed index) then keeps showing that same old item at the
 * same screen position after the prepend — true even when the user was already sitting at index 0,
 * since the OLD index-0 item's key just shifts to a higher index and the anchor follows it there.
 * The pre-existing "N NEW" chip (below) was honestly counting arrivals the whole time; nothing ever
 * told the LazyColumn to actually move.
 *
 * FIX: [listState] is now hoisted here (not left implicit inside [FeedList]) so this composable can
 * both read it (`atTop`) and drive it (`animateScrollToItem`/`scrollToItem`). [previousTopId] is a
 * one-shot-per-arrival baseline: null until the first real sight of the list, so neither a cold
 * start's own already-elevated [newCount] (see HomeViewModelTest's "already reflects quakes ingested
 * by the very first refresh" test — a real, pre-existing ViewModel-level fact, not new behavior)
 * nor the Loading→Content transition is ever mistaken for a live "new arrival" needing a reveal
 * action. [feedVisible] gates the SAME effect on "is the user actually looking at this list right
 * now" (sheet genuinely dragged open, no [DetailSheet][com.yugma.terrawatch.detail.DetailSheet]
 * modal layered on top per that composable's own "layered above everything, feed stays mounted
 * underneath" kdoc) — while false, arrivals still update [topId] tracking is DEFERRED (the `if
 * (feedVisible) previousTopId = topId` below only commits once actually visible again), so exactly
 * one reveal action fires for whatever arrived while hidden, not one per arrival (no animation
 * churn). Peeking (sheet collapsed, [isSheetExpanded] false) is deliberately NOT touched by any of
 * this — [FeedSheetHeader]'s original, unchanged "N NEW" badge is still the only signal there; see
 * that composable's own kdoc for why a scroll-position-driven affordance has nothing meaningful to
 * react to on a sheet nobody has dragged open yet.
 */
@Composable
fun FeedSheet(
    quakes: List<Quake>,
    isLive: Boolean,
    newCount: Int,
    nowMillis: Long,
    distanceKm: (Quake) -> Double?,
    onQuakeClick: (String) -> Unit,
    modifier: Modifier = Modifier,
    isLoading: Boolean = false,
    // Task 3b: PhoneLayout's own `scaffoldState.bottomSheetState.currentValue == SheetValue.Expanded`
    // read — see this file's own kdoc above for why peek vs. expanded matters here. Defaulted so the
    // pre-existing ComponentsTest.feedSheet_emptyContentShowsTheQuietCopy call site (quakes=emptyList,
    // never reaches FeedList/this wiring at all) keeps compiling and passing unchanged.
    isSheetExpanded: Boolean = false,
    // Task 3b: HomeScreen's `selectedQuake != null` — see this file's own kdoc above for the "no
    // chip animation churn while DetailSheet is layered on top" rule this gates.
    isDetailOpen: Boolean = false,
) {
    val listState = rememberLazyListState()
    val reducedMotion = LocalReducedMotion.current
    val scope = rememberCoroutineScope()
    var previousTopId by remember { mutableStateOf<String?>(null) }
    var chipVisible by remember { mutableStateOf(false) }
    val topId = quakes.firstOrNull()?.id
    val feedVisible = isSheetExpanded && !isDetailOpen

    // The arrival-triggered half: fires once per genuine new-item-at-the-front event (topId
    // changing IS that event, given the sort/prepend contract this kdoc documents above), never on
    // a mere recomposition that leaves the front id unchanged.
    LaunchedEffect(topId, feedVisible) {
        val previous = previousTopId
        if (feedVisible && previous != null && topId != previous) {
            val atTop = isAtTopOfFeed(listState.firstVisibleItemIndex, listState.firstVisibleItemScrollOffset)
            when (feedRevealAction(atTop, newCount)) {
                // Guard (task brief): a user actively dragging/flinging must never be fought by a
                // programmatic scroll — defer to the chip instead of racing the gesture.
                FeedRevealAction.AUTO_SCROLL -> if (listState.isScrollInProgress) {
                    chipVisible = true
                } else if (reducedMotion) {
                    listState.scrollToItem(0)
                } else {
                    listState.animateScrollToItem(0)
                }
                FeedRevealAction.SHOW_CHIP -> chipVisible = true
                FeedRevealAction.NONE -> {}
            }
        }
        if (feedVisible) previousTopId = topId
    }
    // The dismiss half: independent of the effect above — this only ever CLEARS chipVisible, never
    // sets it, so it can't race/flicker against the SHOW_CHIP branch above. Covers BOTH "user
    // scrolled to top manually" and "our own auto/tap-triggered scroll just landed at the top."
    LaunchedEffect(listState) {
        snapshotFlow { isAtTopOfFeed(listState.firstVisibleItemIndex, listState.firstVisibleItemScrollOffset) }
            .collect { atTop -> if (atTop) chipVisible = false }
    }

    Column(modifier.fillMaxWidth()) {
        FeedSheetHeader(
            isLive = isLive,
            newCount = newCount,
            isSheetExpanded = isSheetExpanded,
            showRevealChip = chipVisible && newCount > 0,
            onRevealChipClick = {
                chipVisible = false
                scope.launch { if (reducedMotion) listState.scrollToItem(0) else listState.animateScrollToItem(0) }
            },
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        )
        // Task 10 (items b/c): this sheet previously had no Loading concept of its own at all -
        // HomeScreen fed it an empty `quakes` list both while genuinely loading AND while the feed
        // was genuinely, honestly empty, rendering as a blank LazyColumn either way. [isLoading]
        // (new) and quakes.isEmpty() now distinguish the three real states.
        when {
            isLoading -> FeedSkeletonList(modifier = Modifier.weight(1f))
            quakes.isEmpty() -> FeedEmptyState(modifier = Modifier.weight(1f))
            else -> FeedList(
                quakes = quakes,
                nowMillis = nowMillis,
                distanceKm = distanceKm,
                onQuakeClick = onQuakeClick,
                modifier = Modifier.weight(1f),
                state = listState,
            )
        }
    }
}

/** Task 10 (item b): [FeedSheet]'s Loading placeholder - a short column of shimmering
 * [SkeletonCard]s (5, not History's 6: this sheet's peek height shows fewer rows at a glance).
 * Public (not `private`) - [com.yugma.terrawatch.home.TwoPaneLayout] (`HomeScreen.kt`, same
 * package) reuses it verbatim for the desktop/tablet right panel, same "shared, not duplicated"
 * shape [FeedList]/[LiveStatusRow] already established for that panel.
 *
 * Plan 4 Task 4 (a), SDK-36 edge-to-edge sweep: `windowInsetsPadding(WindowInsets.navigationBars)`
 * added before the fixed padding — when this renders inside [FeedSheet]'s phone sheet fully
 * expanded (or [TwoPaneLayout]'s right panel), its own bottom edge can reach the physical screen
 * bottom with nothing else below it to reserve navigation-bar space, unlike History/Insights (see
 * those screens' own kdocs), which always have `AppNav`'s `AppBottomBar` doing that job for them. */
@Composable
fun FeedSkeletonList(modifier: Modifier = Modifier) {
    val reducedMotion = LocalReducedMotion.current
    Column(
        modifier = modifier.fillMaxSize().windowInsetsPadding(WindowInsets.navigationBars).padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        repeat(5) { SkeletonCard(reducedMotion = reducedMotion) }
    }
}

/** Task 10 (item c): the feed's honest "nothing to report" face - shown only once real [Content]
 * data confirms the last-24h window is genuinely empty (never during [FeedSkeletonList]'s Loading
 * window, which would otherwise look identical to a quiet feed for a brief moment). Exact copy per
 * the brief: a calm, non-alarming statement rather than a bare "No results" - this app's whole
 * personality is "a weather app's warmth applied to a scary subject" (spec §4.1), and "no quakes"
 * is good news, not an error. Public for the same [TwoPaneLayout]-reuse reason as
 * [FeedSkeletonList].
 *
 * Plan 4 Task 4 (a): same navigation-bar gap/fix as [FeedSkeletonList]'s own kdoc. */
@Composable
fun FeedEmptyState(modifier: Modifier = Modifier) {
    Box(modifier.fillMaxSize().windowInsetsPadding(WindowInsets.navigationBars).padding(32.dp), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = "Quiet right now — no quakes in the last 24 h",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
                textAlign = TextAlign.Center,
            )
            Text(
                text = "Data updates every minute",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
    }
}

/**
 * Task 12: the plain scrollable list of [QuakeCard]s, extracted out of [FeedSheet] so
 * `HomeScreen.kt`'s desktop/tablet two-pane right panel can reuse the exact same list rendering
 * without [FeedSheet]'s phone-only sheet header. [quakes] is already time-descending (see
 * [HomeViewModel]'s `recentQuakes()`/DAO query) — no re-sort here. [distanceKm] is a per-quake
 * lookup rather than a pre-computed list because the distance depends on the home location, which
 * can resolve (or change) independently of the quake list itself.
 *
 * Plan 4 Task 4 (a), SDK-36 edge-to-edge sweep: [navBarBottomInset] (added to the fixed 16dp
 * `bottom` below, not replacing it) is the same navigation-bar gap [FeedSkeletonList]'s own kdoc
 * documents — this `LazyColumn`'s own `contentPadding` (not a `Modifier.windowInsetsPadding`, since
 * a `LazyColumn`'s scrollable content needs the inset baked into its content padding to keep the
 * LAST item clear of the bar while still letting the list scroll fully behind it, not a fixed
 * always-reserved gap the way a static `Column`/`Box` needs) is where this list's own last row
 * would otherwise sit right at (or under) the physical navigation bar once this renders inside
 * [FeedSheet]'s fully-expanded phone sheet or [TwoPaneLayout]'s right panel.
 *
 * Task 3b: [state] is now an explicit (defaulted) parameter rather than an implicit internal
 * `rememberLazyListState()` — [FeedSheet] hoists its own instance so its reveal wiring can read
 * (`firstVisibleItemIndex`/`isScrollInProgress`) and drive (`animateScrollToItem`) the SAME state
 * this `LazyColumn` actually scrolls, rather than the two silently drifting out of sync. Defaulted
 * so [TwoPaneLayout]'s own call site — no peek/expand state, no reveal wiring of its own, see this
 * file's Task 12 kdoc for why — needs no change at all and keeps its own independent scroll state.
 */
@Composable
fun FeedList(
    quakes: List<Quake>,
    nowMillis: Long,
    distanceKm: (Quake) -> Double?,
    onQuakeClick: (String) -> Unit,
    modifier: Modifier = Modifier,
    state: LazyListState = rememberLazyListState(),
) {
    val navBarBottomInset = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    LazyColumn(
        state = state,
        modifier = modifier.fillMaxWidth(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 16.dp + navBarBottomInset),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(quakes, key = { it.id }) { quake ->
            QuakeCard(
                quake = quake,
                distanceKm = distanceKm(quake),
                nowMillis = nowMillis,
                onClick = { onQuakeClick(quake.id) },
            )
        }
    }
}

// Task 3b: the reveal wiring's own pure decision logic — colocated with FeedList/FeedSheet (its
// only consumers), tested independent of Compose in FeedSheetTest.kt, same "pure fn TDD" convention
// [liveStatusContentDescription]/[HomeScreen]'s `shouldShowStalenessBanner` already establish in
// this codebase.

/** How far (in raw px, matching [androidx.compose.foundation.lazy.LazyListState]'s own units) the
 * list may sit below a bit-for-bit `firstVisibleItemScrollOffset == 0` and still count as "at the
 * top" for [isAtTopOfFeed]'s purposes — see [FeedSheetTest]'s own epsilon test cases for the
 * decision this constant encodes: absorbs sub-pixel fling-deceleration residue without ever being
 * large enough to read as "still scrolled" to the eye. */
internal const val FEED_AT_TOP_EPSILON_PX = 2

/** Epsilon-tolerant "is the feed list genuinely at its top" check — see [FEED_AT_TOP_EPSILON_PX]'s
 * own kdoc for the tolerance itself. */
internal fun isAtTopOfFeed(firstVisibleItemIndex: Int, firstVisibleItemScrollOffset: Int): Boolean =
    firstVisibleItemIndex == 0 && firstVisibleItemScrollOffset <= FEED_AT_TOP_EPSILON_PX

/** What [FeedSheet] should do when [newCount] new quakes have landed at the front of the list since
 * it last checked, given whether the user is currently [atTop]. */
internal enum class FeedRevealAction { AUTO_SCROLL, SHOW_CHIP, NONE }

/**
 * The 3-way truth table: nothing new is always [FeedRevealAction.NONE] regardless of scroll
 * position; new items with the user already [atTop] silently reveal via
 * [FeedRevealAction.AUTO_SCROLL] (the row appearing IS the feedback — no badge needed); new items
 * while scrolled away need [FeedRevealAction.SHOW_CHIP] since nothing else would ever tell the user
 * they exist.
 */
internal fun feedRevealAction(atTop: Boolean, newCount: Int): FeedRevealAction = when {
    newCount <= 0 -> FeedRevealAction.NONE
    atTop -> FeedRevealAction.AUTO_SCROLL
    else -> FeedRevealAction.SHOW_CHIP
}

/** The reveal chip's short, glanceable visible label — deliberately different register from
 * [feedRevealChipContentDescription]'s full TalkBack sentence, same "sighted vs. spoken register"
 * split [core.ui.components.pillContentDescription] already establishes for this app's other pills. */
internal fun feedRevealChipText(newCount: Int): String = "$newCount new quakes ↑"

/** Task 3b (a11y, per brief): the reveal chip's TalkBack sentence — names both the count and the
 * action a tap performs, so a screen-reader user gets the same "there's something new, here's what
 * tapping does" information a sighted user reads off the arrow glyph. */
internal fun feedRevealChipContentDescription(newCount: Int): String = "$newCount new earthquakes, scroll to top"

// Task 3b: [isSheetExpanded]/[showRevealChip]/[onRevealChipClick] are additive — the peeking
// (`!isSheetExpanded`) branch below is BYTE-FOR-BYTE the original Task 9 "N NEW" badge (same text,
// same non-interactive Surface), untouched: a sheet nobody has dragged open yet has no meaningful
// scroll position for a reveal action to react to, so that signal stays exactly as it always was.
// The NEW tappable reveal chip only ever applies to the expanded branch, where [FeedSheet]'s own
// LazyListState-driven wiring (see that composable's kdoc) has something real to act on.
@Composable
private fun FeedSheetHeader(
    isLive: Boolean,
    newCount: Int,
    isSheetExpanded: Boolean,
    showRevealChip: Boolean,
    onRevealChipClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        LiveStatusRow(isLive)
        if (isSheetExpanded) {
            if (showRevealChip) {
                Surface(
                    onClick = onRevealChipClick,
                    shape = RoundedCornerShape(TerraRadii.pill),
                    color = TerraColors.InfoBlue,
                    // mergeDescendants (not clearAndSetSemantics) - same StatusShield.kt precedent
                    // this file's own kdoc points to: clearAndSetSemantics on this OUTER modifier
                    // would discard the click action Surface's own internal `clickable` contributes
                    // (chained after this parameter), silently making the chip untappable via
                    // TalkBack's activate gesture.
                    modifier = Modifier.semantics(mergeDescendants = true) {
                        contentDescription = feedRevealChipContentDescription(newCount)
                    },
                ) {
                    Text(
                        text = feedRevealChipText(newCount),
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        color = Color.White,
                        modifier = Modifier
                            .padding(horizontal = 8.dp, vertical = 2.dp)
                            // Blocks this Text's own implicit "read the literal string" semantics
                            // from ALSO riding along into the Surface's merged node on top of the
                            // contentDescription above - same double-read fix StatusShield.kt's own
                            // AlertContent/MagnitudeBadge precedent applies for the identical reason.
                            .clearAndSetSemantics {},
                    )
                }
            }
        } else if (newCount > 0) {
            Surface(shape = RoundedCornerShape(TerraRadii.pill), color = TerraColors.InfoBlue) {
                Text(
                    text = "$newCount NEW",
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                    color = Color.White,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                )
            }
        }
    }
}

/**
 * Task 12 Fix 2 (review finding — reviewer's Important 2, "desktop panel has no LIVE/OFFLINE
 * signal"): the [LiveDot] + "LIVE"/"OFFLINE" label pairing, extracted out of [FeedSheetHeader] so
 * `HomeScreen.kt`'s desktop/tablet two-pane right panel can show the exact same honest connection
 * signal the phone sheet's header always has — the panel shipped with none at all in the original
 * Task 12 cut. Deliberately excludes the "N NEW" chip [FeedSheetHeader] still adds on its own:
 * `TwoPaneLayout` keeps that counter pinned at zero (see its own Fix 1 note) precisely because an
 * always-visible list has no "unseen since last look" concept, so a chip that could only ever read
 * "0 NEW" would be dead UI, not a smaller version of the phone one.
 */
// Task 10 (item g, a11y): clearAndSetSemantics replaces the default per-child reading (which would
// otherwise expose LiveDot's bare color swatch as unlabeled, focusable noise plus the "LIVE"/
// "OFFLINE" text on its own) with one clean sentence naming what the dot+label pairing actually
// MEANS, rather than making a TalkBack user piece it together from a color they can't see plus a
// terse label. Safe to clear here (unlike StatusShield, which must preserve a click action): this
// row has no onClick of its own to lose.
@Composable
fun LiveStatusRow(isLive: Boolean, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier.clearAndSetSemantics {
            contentDescription = liveStatusContentDescription(isLive)
        },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        LiveDot(isLive)
        Text(
            text = if (isLive) "LIVE" else "OFFLINE",
            style = if (isLive) MaterialTheme.typography.labelMedium else MaterialTheme.typography.labelSmall,
            fontWeight = if (isLive) FontWeight.Bold else FontWeight.Normal,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * Task 10 (item g): [LiveStatusRow]'s TalkBack sentence — extracted to a pure function so it can be
 * pinned directly in `composeApp`'s jvmTest, same "TDD pure a11y-string builders" convention
 * [com.yugma.terrawatch.ui.components.pillContentDescription] establishes in `core:ui`. Parallel
 * "Live connection active"/"Live connection offline" phrasing (rather than a bare "Offline") is a
 * deliberate choice: spec §4.5 asks for labels that "read naturally," and a matched-register pair
 * reads as one sentence with two outcomes rather than a full phrase versus a lone word.
 */
internal fun liveStatusContentDescription(isLive: Boolean): String =
    if (isLive) "Live connection active" else "Live connection offline"

/**
 * Task 10: truthful connection state — [isLive] now reflects
 * [com.yugma.terrawatch.data.QuakeRepository.liveConnected] (the real WebSocket state), so this
 * always renders one of two honest faces instead of the old "STATIC dot, only shown when the
 * (always-true) placeholder said live": a pulsing green dot while the socket is actually open, or
 * a static, translucent ink dot while it isn't (paired with the "OFFLINE" label above). The pulse
 * itself (alpha 0.35 -> 1, reversing, non-stopping) is skipped under [LocalReducedMotion] — a
 * steady fully-on dot substitutes so "live" still reads instantly at a glance without motion.
 *
 * Fix Round 1 (entangled minors):
 * - The pulsing alpha used to be read directly in this composable function's body (a `by`-delegated
 *   `State<Float>` feeding a `Color.copy(alpha = ...)` argument passed to `Modifier.background()`).
 *   That is a composition-phase read: Compose must recompose this whole function on every animation
 *   frame (60fps) just to re-derive that `Color` argument. `Modifier.graphicsLayer { alpha = ... }`
 *   defers the same read to the draw phase instead — only this node's compositing layer redraws
 *   each frame; the composable itself never recomposes for the pulse. (Kept as a plain `State`
 *   reference, not a `by`-delegated local, specifically so nothing in this function's own body
 *   reads `.value` outside the `graphicsLayer` lambda.)
 * - The not-live dot used a hardcoded `Color.Gray` — an ad-hoc, off-palette color this codebase's
 *   own token rule (core:ui's `TerraColors` kdoc: "every screen sources color from here... never
 *   ad-hoc `Color(0x...)` literals") forbids. Replaced with `TerraColors.Ink.copy(alpha = 0.35f)`,
 *   which (unlike the old code's structure) now actually renders translucent: the old version's
 *   outer `.copy(alpha = alpha)` — with `alpha` hardcoded to `1f` on this branch — unconditionally
 *   overwrote any alpha baked into the base color, so the intended translucency could never have
 *   shown up even if the base color itself had carried one.
 */
@Composable
private fun LiveDot(isLive: Boolean, modifier: Modifier = Modifier) {
    val reducedMotion = LocalReducedMotion.current
    val baseColor = if (isLive) TerraColors.Safe else TerraColors.Ink.copy(alpha = 0.35f)
    if (isLive && !reducedMotion) {
        val transition = rememberInfiniteTransition(label = "live-dot-pulse")
        val animatedAlpha = transition.animateFloat(
            initialValue = 0.35f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(
                animation = tween(durationMillis = 900),
                repeatMode = RepeatMode.Reverse,
            ),
            label = "live-dot-alpha",
        )
        Box(
            modifier
                .size(8.dp)
                .graphicsLayer { alpha = animatedAlpha.value }
                .background(color = baseColor, shape = CircleShape),
        )
    } else {
        Box(
            modifier
                .size(8.dp)
                .background(color = baseColor, shape = CircleShape),
        )
    }
}
