package com.yugma.terrawatch.home

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.BottomSheetScaffold
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberBottomSheetScaffoldState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.yugma.terrawatch.data.PillStatus
import com.yugma.terrawatch.data.pillStatus
import com.yugma.terrawatch.detail.DetailSheet
import com.yugma.terrawatch.location.LocationAskDialog
import com.yugma.terrawatch.map.QuakeMap
import com.yugma.terrawatch.model.GeoPoint
import com.yugma.terrawatch.model.haversineKm
import com.yugma.terrawatch.share.shareQuakeText
import com.yugma.terrawatch.ui.components.StatusShield
import com.yugma.terrawatch.ui.format.formatRelativeTime
import com.yugma.terrawatch.ui.theme.TerraRadii
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.collectLatest
import org.koin.compose.viewmodel.koinViewModel
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

// Spec's Offline row (docs/superpowers/specs/2026-08-08-terrawatch-design.md): "Glass banner...
// Map desaturates. Cache stays fully browsable" — Task 8 ships the banner text + the "always
// render the map underneath" half; map desaturation and the Retry link are not part of this task's
// brief and are left for whichever task next touches this banner.
private const val STALE_AFTER_MILLIS = 10 * 60 * 1000L

// Fix Round 2 (review finding): how often the ticker below re-reads wall-clock time. Without it,
// isStale()'s verdict and the banner's "N min ago" text were computed once per recomposition of
// the Content branch and never again — Compose has no State read to invalidate on there, so a
// screen left open would freeze at whatever staleness/age it happened to show the moment `state`
// last changed, however much real time then passed with zero new data.
private const val TICKER_INTERVAL_MILLIS = 30_000L

// Task 9: M3's BottomSheetScaffold gives 2-3 fixed detents, not an arbitrary one — "peek +
// expanded" is the accepted compromise (see the Task 9 brief's FeedSheet interface note). Peek
// height is a fraction of the *screen's* height (via BoxWithConstraints below), not a fixed dp
// value, so it stays proportionate across phone sizes.
private const val SHEET_PEEK_FRACTION = 0.3f

// Task 10: how long a newly-arrived quake's id stays "highlighted" as QuakeMap's `newQuakeId` —
// long enough for the pin-drop pop + ring animation (~1.2s total, see QuakeMap.android.kt) to
// fully play out, short enough that it can't still be "new" by the time a user who glances away
// looks back.
//
// Fix Round 1 (entangled minor, "ring 2 truncation"): was 1_500L. QuakeMap.android.kt's
// LaunchedEffect(newQuakeId) is keyed on this same value — the moment it reverts to null, that
// effect RESTARTS (a LaunchedEffect key change cancels the running coroutine before relaunching),
// cutting off whatever animation was still in flight. The animation's own timeline isn't just the
// ~1.2s of ring2's stagger+duration (300ms + 900ms): QuakeMap.android.kt's polling loop for the
// matching pin to land in `pins` can itself take up to NEW_PIN_LOOKUP_MAX_FRAMES (~0.75s at 60fps)
// BEFORE the animation even starts, since `pins` and `newQuakeIds` are independently-collected
// upstreams with no ordering guarantee between them (see that file's own LaunchedEffect comment).
// Worst case, lookup-poll + ring2's full timeline could already approach or exceed 1.5s, visibly
// truncating ring 2's fade mid-flight. 2_500L gives a comfortable margin over that worst case
// (~0.75s poll + 1.2s animation ≈ 2.0s) without meaningfully changing when a glanced-away user
// stops seeing something flagged "new."
private const val NEW_QUAKE_HIGHLIGHT_EXPIRY_MILLIS = 2_500L

// Task 4 (Plan 3), device-verified fix (not a design guess): the settings gear chip
// (SettingsGearChip below) floats at Alignment.TopEnd with the SAME windowInsetsPadding(
// statusBars) + 16dp margin the pill/banner column below also starts from — device screenshot
// task4-onboarding-then-home.png (pre-fix capture, see task-4-report.md) showed the chip
// rendering directly on top of the pill's own right edge, because the pill's `fillMaxWidth()`
// modifier claims the FULL row the chip also occupies. This reserves exactly the chip's own
// footprint (16dp outer margin + 2*10dp inner padding + 24dp glyph = 60dp, +12dp breathing room)
// on the pill's end side ONLY -- not the banner below it, which by the time it renders is already
// below the chip's vertical extent and never collided with it. Both PhoneLayout's and
// TwoPaneLayout's own StatusShield call sites apply this.
private val GEAR_CHIP_CLEARANCE = 72.dp

// Task 12: the desktop/tablet two-pane right panel's fixed width — see layoutMode()'s own kdoc for
// the paired 900dp breakpoint this is designed against (panel + a still-usable map pane needs the
// headroom that breakpoint leaves).
private val TWO_PANE_RIGHT_PANEL_WIDTH = 360.dp

/**
 * The app's centerpiece: a full-bleed [QuakeMap] fed from [viewModel], with a translucent
 * ("glass", per the Calm Guardian spec's floating-overlay rule) status pill + staleness/offline
 * banner floating over the top, and a draggable [FeedSheet] anchored to the bottom (Task 9). The
 * map itself never goes away — see [HomeUiState]'s own kdoc for why there's no Error state to
 * swap it out for.
 *
 * BUG FIX (post-Task-8-device-verify, controller-diagnosed): this used to call `QuakeMap` from
 * *inside* both branches of `when (state)` — one call site per branch. Compose treats those as two
 * distinct call sites, so the Loading -> Content transition removed the Loading branch's QuakeMap
 * from composition and mounted a brand-new one for Content: on Android, that tears down and
 * recreates maplibre-compose's underlying AndroidView/GL surface, which silently re-inits to a
 * blank white surface (no crash, no log — exactly the unexplained blank-render device symptom from
 * the original device-verify pass, root-caused by inspection rather than by more on-device
 * poking). `QuakeMap` is now called from exactly ONE call site per layout branch, unconditionally,
 * for the entire lifetime of that branch's composition; only its `pins` argument changes across
 * recompositions as [state] changes, which is the incremental-update path `rememberGeoJsonSource`'s
 * `setData` is built for (see QuakeMap.android.kt's own fix note).
 *
 * Task 9 wraps the phone layout in a [BottomSheetScaffold] rather than pushing the map/pill/banner
 * content up above a persistent sheet: `content` there still gets the full screen (the sheet floats
 * on top, peeking from the bottom) — deliberately, so the map stays edge-to-edge under the peeking
 * sheet exactly like the approved mockup (map-home-layout.html, option 1).
 *
 * Task 12 (spike decision — see `docs/superpowers/plans/2026-08-08-terrawatch-plan-2-ui-shell.md`'s
 * Task 12 section): this single [BoxWithConstraints] now measures itself once and routes to one of
 * two whole-screen chrome arrangements via [layoutMode] — [PhoneLayout] (unchanged from Task 9/10/11
 * behavior) below 900dp, [TwoPaneLayout] (map + a fixed-width list/pill panel, both always visible,
 * no peek/expand sheet) at or above it. [DetailSheet] is a THIRD, independent layer on top of
 * whichever of the two is active — its own on-demand [ModalBottomSheet][androidx.compose.material3.ModalBottomSheet]
 * is unaffected by which layout is showing underneath it.
 *
 * Task 3 (Plan 3): [selectionViewModel] used to be [HomeViewModel]'s own `selectedQuake`/`select`/
 * `dismissSelection` — split into [QuakeSelectionViewModel] because HomeViewModel was serving
 * map+pill+sheet+detail+two-pane at once (`plan-3-entry-conditions.md` #3). Defaulted to
 * `koinViewModel()` rather than resolved as a body-local `val` (contrast [viewModel], which this
 * function's one real caller — `App()` — already resolves via `koinViewModel<HomeViewModel>()`
 * before calling here): a default parameter expression is *also* just a `koinViewModel()` call
 * "alongside" [viewModel] as asked, but additionally keeps this whole composable callable without
 * a running Koin instance by passing an explicit override — which
 * `androidInstrumentedTest/HomeFlowTest.kt` does deliberately (see that file's own kdoc for why it
 * avoids `startKoin {}` entirely) and which Task 4's "selection VM shared at nav-graph scope"
 * plan explicitly needs too (the NavHost will pass its own nav-graph-scoped instance in here
 * instead of letting this default fire).
 *
 * Task 4 (Plan 3): [onSettingsClick] backs the gear chip floating top-right of the whole screen
 * (both layouts) — a defaulted no-op, not a required param, so `HomeFlowTest`/`ComponentsTest`
 * keep compiling unchanged against their existing 2-arg call sites. `AppNav`'s real call site
 * overrides it to `navController.navigate(Routes.SETTINGS)`.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    viewModel: HomeViewModel,
    selectionViewModel: QuakeSelectionViewModel = koinViewModel(),
    onSettingsClick: () -> Unit = {},
) {
    val state by viewModel.state.collectAsState()
    val homeLocation by viewModel.homeLocation.collectAsState()
    val newSinceExpand by viewModel.newSinceExpand.collectAsState()
    // Task 11: drives the detail sheet below — non-null shows it, null (the initial value, and
    // whatever QuakeSelectionViewModel.dismissSelection()/an unresolved select() settles back to)
    // hides it. Task 3 (Plan 3): moved from `viewModel` to `selectionViewModel` — see this
    // function's own kdoc.
    val selectedQuake by selectionViewModel.selectedQuake.collectAsState()
    // Fix Round 2 (review finding): feeds both isStale() and the banner's formatRelativeTime()
    // call below, so the staleness verdict and the displayed age both actually advance every 30s
    // instead of freezing at whatever they were when `state` last changed — see
    // TICKER_INTERVAL_MILLIS and rememberNowMillisTicker() below. Task 9 also feeds this into
    // pillStatus() below — the pill's own age math (e.g. the ALERT face's relative-time text)
    // needs to keep advancing for exactly the same reason.
    val nowMillis by rememberNowMillisTicker()
    // Task 10: the pin-drop animation's trigger — see rememberExpiringNewQuakeId's own kdoc for
    // why this needs its own expiry rather than passing viewModel.newQuakeIds straight through.
    val newQuakeId by rememberExpiringNewQuakeId(viewModel.newQuakeIds)
    // Task 2 (Plan 3): the ASK-pill's tap target — see onPillClick below. Scoped to this composable
    // (not HomeViewModel) because it's pure transient UI state with no persistence/business-logic
    // half of its own, same "UI-only state lives in the composable" split [selectedQuake] draws
    // against the sheet-visibility question below (that one IS VM state, because it also carries
    // which quake to render — this one carries nothing beyond "is the ask dialog showing").
    var showLocationAsk by remember { mutableStateOf(false) }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        if (layoutMode(maxWidth.value.toInt()) == LayoutMode.TWO_PANE) {
            TwoPaneLayout(
                state = state,
                homeLocation = homeLocation,
                nowMillis = nowMillis,
                newQuakeId = newQuakeId,
                viewModel = viewModel,
                selectionViewModel = selectionViewModel,
                onAskLocation = { showLocationAsk = true },
            )
        } else {
            PhoneLayout(
                state = state,
                homeLocation = homeLocation,
                newSinceExpand = newSinceExpand,
                nowMillis = nowMillis,
                newQuakeId = newQuakeId,
                maxHeight = maxHeight,
                viewModel = viewModel,
                selectionViewModel = selectionViewModel,
                onAskLocation = { showLocationAsk = true },
            )
        }
        // Task 4 (Plan 3): the settings entry point — a glass chip floating top-right, above
        // whichever layout is active (same "always on top, regardless of PHONE/TWO_PANE" shape as
        // the detail sheet/location-ask overlays below). Device-verified fix: this sits at the
        // exact same vertical band as the pill below (both start at windowInsetsPadding(statusBars)
        // + 16dp), so PhoneLayout/TwoPaneLayout's own StatusShield call sites reserve
        // GEAR_CHIP_CLEARANCE on their end side — see that constant's own kdoc for the device
        // screenshot that caught the un-reserved overlap.
        SettingsGearChip(
            onClick = onSettingsClick,
            modifier = Modifier
                .align(Alignment.TopEnd)
                .windowInsetsPadding(WindowInsets.statusBars)
                .padding(16.dp),
        )
        // Task 11: the quake detail sheet — a second, independent, on-demand ModalBottomSheet
        // layered above everything else in this BoxWithConstraints (whichever of PhoneLayout's
        // BottomSheetScaffold or TwoPaneLayout's Row is active), matching the mockup's "detail
        // expands over the map, feed sheet unaffected" treatment. distanceKm is computed fresh
        // here rather than threaded through from FeedList's per-row closure — same
        // haversineKm(home, quakePoint) call FeedList already makes per row, just for the one
        // selected quake.
        //
        // Task 12 review, controller decision (Finding 3): TWO_PANE reuses this same modal sheet
        // rather than the brief's literal "detail replaces list in panel" — modal reuse wins on
        // consistency + zero new surface for a target the plan doesn't judge; in-panel
        // master-detail is deferred to a Plan 3 desktop pass. See task-12-report.md's Fix Round 1.
        selectedQuake?.let { quake ->
            DetailSheet(
                quake = quake,
                distanceKm = homeLocation?.let { home -> haversineKm(home, GeoPoint(quake.lat, quake.lon)) },
                nowMillis = nowMillis,
                onShare = { text -> shareQuakeText(text) },
                onDismiss = { selectionViewModel.dismissSelection() },
            )
        }
        // Task 2 (Plan 3): a THIRD independent overlay layer, same "stacks on top of whichever
        // layout/DetailSheet is active" shape as the detail sheet just above.
        if (showLocationAsk) {
            LocationAskDialog(onDismiss = { showLocationAsk = false })
        }
    }
}

/**
 * Phone layout ([LayoutMode.PHONE], < 900dp) — the Task 9/10/11 design, unchanged in behavior by
 * Task 12: full-bleed map with a draggable [FeedSheet] anchored to the bottom and the status pill/
 * staleness banner floating over the map's top edge. Extracted out of [HomeScreen] only so that
 * function can route between this and [TwoPaneLayout] on a single `BoxWithConstraints`
 * measurement — [maxHeight] is passed in because `BoxWithConstraintsScope.maxHeight` is only
 * available inside that scope, not inside a separately-declared composable.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PhoneLayout(
    state: HomeUiState,
    homeLocation: GeoPoint?,
    newSinceExpand: Int,
    nowMillis: Long,
    newQuakeId: String?,
    maxHeight: Dp,
    viewModel: HomeViewModel,
    selectionViewModel: QuakeSelectionViewModel,
    onAskLocation: () -> Unit,
) {
    val content = state as? HomeUiState.Content
    val scaffoldState = rememberBottomSheetScaffoldState()
    // Task 9: the sheet's "N NEW" chip clears the moment the user actually drags the sheet
    // open — SheetValue.Expanded is M3's own name for "fully open" (as opposed to peeking).
    LaunchedEffect(scaffoldState) {
        snapshotFlow { scaffoldState.bottomSheetState.currentValue }
            .collect { value -> if (value == SheetValue.Expanded) viewModel.markSheetExpanded() }
    }
    BottomSheetScaffold(
        modifier = Modifier.fillMaxSize(),
        scaffoldState = scaffoldState,
        sheetPeekHeight = maxHeight * SHEET_PEEK_FRACTION,
        sheetShape = RoundedCornerShape(topStart = TerraRadii.sheet, topEnd = TerraRadii.sheet),
        sheetContainerColor = MaterialTheme.colorScheme.surface,
        sheetContent = {
            FeedSheet(
                quakes = content?.quakes.orEmpty(),
                isLive = content?.isLive ?: false,
                newCount = newSinceExpand,
                nowMillis = nowMillis,
                distanceKm = { quake ->
                    homeLocation?.let { haversineKm(it, GeoPoint(quake.lat, quake.lon)) }
                },
                onQuakeClick = { id -> selectionViewModel.select(id) },
            )
        },
    ) {
        // Mounted once, for good, regardless of state — see HomeScreen's kdoc. Empty pins
        // pre-Content is fine: MapLibre's own tile/style fetch still overlaps the initial DB
        // read/network refresh instead of waiting behind the spinner. The PaddingValues this
        // lambda receives (M3's own convention for "leave room for the peeking sheet") is
        // deliberately unused — the map is meant to run full-bleed under the sheet.
        Box(Modifier.fillMaxSize()) {
            QuakeMap(
                pins = content?.pins.orEmpty(),
                newQuakeId = newQuakeId,
                onPinTap = { id -> selectionViewModel.select(id) },
                modifier = Modifier.fillMaxSize(),
                onDebugLongPress = { lat, lon -> viewModel.injectDebugQuake(lat, lon) },
            )
            when (val s = state) {
                HomeUiState.Loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
                is HomeUiState.Content -> {
                    // Cheap, pure call — recomputed every recomposition rather than
                    // `remember`-ed (see the Task 9 brief: "keep it a pure call in
                    // composition, cheap"); quakes/home/nowMillis are all already Compose
                    // State reads, so this only re-runs when one of them actually changes.
                    val pill = pillStatus(s.quakes, homeLocation, nowMillis)
                    Column(
                        modifier = Modifier
                            .align(Alignment.TopCenter)
                            .fillMaxWidth()
                            .windowInsetsPadding(WindowInsets.statusBars)
                            .padding(horizontal = 16.dp, vertical = 16.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        StatusShield(
                            status = pill,
                            nowMillis = nowMillis,
                            onClick = { onPillClick(pill, selectionViewModel, onAskLocation) },
                            modifier = Modifier.fillMaxWidth().padding(end = GEAR_CHIP_CLEARANCE),
                        )
                        // Banner moves below the pill when both are showing (Task 9 brief:
                        // "above banner if both — banner moves below pill").
                        if (s.refreshFailed || isStale(s.lastUpdatedMillis, nowMillis)) {
                            Spacer(Modifier.height(8.dp))
                            StalenessBanner(
                                lastUpdatedMillis = s.lastUpdatedMillis,
                                nowMillis = nowMillis,
                                // Task 1 (Plan 3): Retry only when there's an actual failure to
                                // retry — staleness alone (a healthy feed that simply hasn't had
                                // anything new to report in a while) isn't one.
                                onRetry = if (s.refreshFailed) viewModel::retryNow else null,
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * Desktop/tablet two-pane layout ([LayoutMode.TWO_PANE], >= 900dp) — the Task 12 spike decision's
 * other half (see `FallbackMapPane.kt`'s kdoc for the maplibre-compose constraint that makes jvm/
 * wasmJs's [QuakeMap] actual a static pane rather than a live tile map there). The map/fallback
 * fills whatever width remains after a fixed [TWO_PANE_RIGHT_PANEL_WIDTH] right panel, which holds
 * the status pill, a compact live/offline row, and the full quake list stacked vertically, all
 * always fully visible — there is no phone-style peek/expand sheet here, so this panel reuses
 * [FeedList] directly (not [FeedSheet], which adds the peek-only "N NEW" header — see that
 * composable's own Task 12 kdoc note) and renders [StatusShield] itself rather than floating it
 * over the map.
 *
 * Task 12 review, Fix 1 (Important — "`newSinceExpand` leaks across layout flip"): this panel's
 * list has no peek/expanded state, so nothing here ever called [HomeViewModel.markSheetExpanded]
 * the way [PhoneLayout]'s scaffold-driven `LaunchedEffect` does — but `HomeViewModel`'s
 * `newSinceExpand` counter itself is layout-agnostic and keeps incrementing regardless, so it would
 * silently accumulate for the entire time a user stayed on this panel. Left alone, flipping back to
 * [PhoneLayout] later would show a stale, inflated "N NEW" chip counting arrivals the user had
 * fully visible the whole time. The `LaunchedEffect(state)` below re-clears the counter on every
 * single content update for as long as this composable stays on screen, keeping it pinned at zero
 * — the simplest shape that stays correct across an arbitrary number of arrivals, not just the
 * first one (a one-shot `LaunchedEffect(Unit)` would only clear whatever had accumulated before
 * this panel first composed, then let it drift right back up).
 */
@Composable
private fun TwoPaneLayout(
    state: HomeUiState,
    homeLocation: GeoPoint?,
    nowMillis: Long,
    newQuakeId: String?,
    viewModel: HomeViewModel,
    selectionViewModel: QuakeSelectionViewModel,
    onAskLocation: () -> Unit,
) {
    val content = state as? HomeUiState.Content
    // Fix 1 (see kdoc above): re-fires on every `state` emission, i.e. every genuinely new arrival
    // (HomeViewModel.insertedQuakeIds is exactly what both bumps newSinceExpand AND changes the
    // quakes/pins lists `state` carries), so the counter never has a chance to accumulate visibly.
    LaunchedEffect(state) { viewModel.markSheetExpanded() }
    Row(Modifier.fillMaxSize()) {
        Box(Modifier.weight(1f).fillMaxHeight()) {
            QuakeMap(
                pins = content?.pins.orEmpty(),
                newQuakeId = newQuakeId,
                onPinTap = { id -> selectionViewModel.select(id) },
                modifier = Modifier.fillMaxSize(),
                onDebugLongPress = { lat, lon -> viewModel.injectDebugQuake(lat, lon) },
            )
            if (state is HomeUiState.Loading) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            }
        }
        Column(
            modifier = Modifier
                .width(TWO_PANE_RIGHT_PANEL_WIDTH)
                .fillMaxHeight()
                .windowInsetsPadding(WindowInsets.statusBars),
        ) {
            // Same "only render once there's real Content" rule PhoneLayout's pill uses — an
            // empty/Loading pill would have to either lie ("all calm") or invent a fourth,
            // not-yet-loaded PillStatus.Kind, neither of which this task's brief asked for.
            if (state is HomeUiState.Content) {
                val pill = pillStatus(state.quakes, homeLocation, nowMillis)
                Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
                    StatusShield(
                        status = pill,
                        nowMillis = nowMillis,
                        onClick = { onPillClick(pill, selectionViewModel, onAskLocation) },
                        modifier = Modifier.fillMaxWidth().padding(end = GEAR_CHIP_CLEARANCE),
                    )
                    if (state.refreshFailed || isStale(state.lastUpdatedMillis, nowMillis)) {
                        Spacer(Modifier.height(8.dp))
                        StalenessBanner(
                            lastUpdatedMillis = state.lastUpdatedMillis,
                            nowMillis = nowMillis,
                            onRetry = if (state.refreshFailed) viewModel::retryNow else null,
                        )
                    }
                }
            }
            // Task 12 review, Fix 2 (Important — "desktop panel has no LIVE/OFFLINE signal"): this
            // panel had no connection-state indicator at all before this fix, unlike the phone
            // sheet's header. Reuses FeedSheetHeader's own LiveDot+label pairing (extracted as
            // LiveStatusRow) rather than duplicating it — no "N NEW" chip here, see LiveStatusRow's
            // own kdoc for why. Shown unconditionally (not gated on `is Content` like the pill
            // above): defaults to content?.isLive ?: false, so it honestly reads "OFFLINE" during
            // Loading rather than disappearing — the same always-rendered convention FeedSheet's
            // header already uses on the phone side.
            LiveStatusRow(
                isLive = content?.isLive ?: false,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )
            FeedList(
                quakes = content?.quakes.orEmpty(),
                nowMillis = nowMillis,
                distanceKm = { quake ->
                    homeLocation?.let { haversineKm(it, GeoPoint(quake.lat, quake.lon)) }
                },
                onQuakeClick = { id -> selectionViewModel.select(id) },
                modifier = Modifier.fillMaxWidth().weight(1f),
            )
        }
    }
}

/**
 * Fix Round 2 (review finding): a plain wall-clock read inside a `@Composable` body is not itself
 * a Compose `State` — recomposition happens when a `State` *value* changes, not merely because
 * time passed, so `isStale`/the banner text used to be computed once and never revisited.
 * `produceState` runs its block in a coroutine scoped to the caller's composition lifetime; looping
 * forever and re-assigning `value` every [TICKER_INTERVAL_MILLIS] is what actually triggers
 * recomposition of whatever reads this ticker.
 */
@Composable
private fun rememberNowMillisTicker(): State<Long> =
    produceState(initialValue = currentTimeMillis()) {
        while (true) {
            delay(TICKER_INTERVAL_MILLIS)
            value = currentTimeMillis()
        }
    }

/**
 * Task 10: turns the hot, fire-and-forget [newQuakeIds] SharedFlow into a `State<String?>` that
 * QuakeMap's `newQuakeId` param can key its pin-drop animation off of — holding each arrival for
 * [NEW_QUAKE_HIGHLIGHT_EXPIRY_MILLIS] (Fix Round 1: 2.5s, was 1.5s — see that constant's own kdoc
 * for the truncation bug that raised it) before reverting to null. `collectLatest` (not `collect`)
 * is the "collects latest with an expiry" behavior the brief calls for: if a second quake arrives
 * before the first one's expiry delay finishes, that delay is cancelled outright and the value
 * jumps straight to the new id — QuakeMap's own `LaunchedEffect(newQuakeId)` (keyed on this value)
 * then restarts for the new arrival rather than ever seeing a spurious null in between.
 */
@Composable
private fun rememberExpiringNewQuakeId(newQuakeIds: SharedFlow<String>): State<String?> {
    val state = remember { mutableStateOf<String?>(null) }
    LaunchedEffect(newQuakeIds) {
        newQuakeIds.collectLatest { id ->
            state.value = id
            delay(NEW_QUAKE_HIGHLIGHT_EXPIRY_MILLIS)
            state.value = null
        }
    }
    return state
}

@OptIn(ExperimentalTime::class)
private fun currentTimeMillis(): Long = Clock.System.now().toEpochMilliseconds()

private fun isStale(lastUpdatedMillis: Long?, nowMillis: Long): Boolean =
    lastUpdatedMillis != null && nowMillis - lastUpdatedMillis > STALE_AFTER_MILLIS

/**
 * Task 12: the pill's three-way tap behavior, shared between [PhoneLayout] and [TwoPaneLayout] now
 * that both host their own [StatusShield] call site — extracted so the two layouts' `onClick`
 * lambdas can't quietly drift out of sync with each other the way two independent copies of this
 * `when` could.
 *
 * Task 2 (Plan 3): the [PillStatus.Kind.ASK_LOCATION] TODO this used to carry dies here —
 * [onAskLocation] opens [com.yugma.terrawatch.location.LocationAskDialog] (wired from HomeScreen's
 * own `showLocationAsk` state, since a plain function like this one can't itself hold Compose
 * state or show a dialog).
 *
 * Task 3 (Plan 3): takes [QuakeSelectionViewModel] instead of [HomeViewModel] now — the ALERT
 * branch's `select` call is the only reason this function ever needed a ViewModel reference at all.
 */
private fun onPillClick(pill: PillStatus, selectionViewModel: QuakeSelectionViewModel, onAskLocation: () -> Unit) {
    when (pill.kind) {
        PillStatus.Kind.ASK_LOCATION -> onAskLocation()
        PillStatus.Kind.ALERT -> pill.quake?.let { selectionViewModel.select(it.id) }
        PillStatus.Kind.CALM -> {} // Nothing to show.
    }
}

/**
 * Task 1 (Plan 3): [onRetry] wires the design spec's "Offline ... + Retry link" affordance
 * (`docs/superpowers/specs/2026-08-08-terrawatch-design.md` 4.4) — null (the default) keeps this
 * banner exactly as it was pre-Task-1 (a plain, un-actionable status line) for any future caller
 * that shows it without a retry story; HomeScreen's own call sites below always pass one when
 * `refreshFailed` is true. A trailing [TextButton], not a filled [androidx.compose.material3.Button]
 * — this is a "link" per the spec, not a primary action, and a filled button would be a second
 * glass-adjacent surface competing with the pill for visual weight. Reads
 * [MaterialTheme.colorScheme] `primary` for its label (M3's own [TextButton] default content
 * color) rather than a hardcoded color, so it stays theme-token-honest across light/dark like
 * every other control in this app.
 */
@Composable
private fun StalenessBanner(
    lastUpdatedMillis: Long?,
    nowMillis: Long,
    onRetry: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(TerraRadii.pill),
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.78f),
        tonalElevation = 4.dp,
        shadowElevation = 2.dp,
    ) {
        Row(
            modifier = Modifier.padding(
                start = 16.dp,
                top = 8.dp,
                bottom = 8.dp,
                end = if (onRetry != null) 4.dp else 16.dp,
            ),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val text = lastUpdatedMillis?.let {
                "Updated ${formatRelativeTime(it, nowMillis)}"
            } ?: "Not updated yet"
            Text(text = text, style = MaterialTheme.typography.labelLarge)
            if (onRetry != null) {
                TextButton(onClick = onRetry) {
                    Text("Retry")
                }
            }
        }
    }
}

/**
 * Task 4 (Plan 3): the gear chip that opens Settings (`AppNav`'s `Routes.SETTINGS`) — same glass
 * treatment (translucent [Surface] + tonal/shadow elevation) as [StatusShield]/[StalenessBanner]
 * above, per the glass allow-list. `contentDescription` is set explicitly rather than left for
 * Task 10's broader a11y sweep to backfill: an entirely unlabeled tappable icon is an easy-to-avoid
 * gap this task would otherwise be introducing fresh, not a pre-existing one Task 10 is already
 * scheduled to fix.
 */
@Composable
private fun SettingsGearChip(onClick: () -> Unit, modifier: Modifier = Modifier) {
    Surface(
        onClick = onClick,
        modifier = modifier.semantics { contentDescription = "Settings" },
        shape = RoundedCornerShape(TerraRadii.pill),
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.78f),
        tonalElevation = 4.dp,
        shadowElevation = 2.dp,
    ) {
        SettingsGlyph(
            tint = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(10.dp).size(24.dp),
        )
    }
}

/**
 * A "sliders/equalizer" settings glyph (three horizontal tracks, each with one offset knob) drawn
 * on [Canvas] — same "no icon-library dependency in this project" reasoning as `nav/NavIcons.kt`'s
 * tab icons and core:ui's `StatusShield` glyphs. Picked over a literal gear/cog silhouette: a
 * recognizable gear needs teeth around its rim, which is a much fussier path to hand-draw reliably
 * at 24dp than three lines + three circles, and the sliders/equalizer glyph is just as widely
 * recognized for "Settings" in production apps.
 */
@Composable
private fun SettingsGlyph(tint: Color, modifier: Modifier = Modifier) {
    // Read here, not inside the Canvas draw lambda below: DrawScope is not a @Composable context,
    // so MaterialTheme.colorScheme can't be read from inside it directly.
    val knobFill = MaterialTheme.colorScheme.surface
    Canvas(modifier = modifier) {
        val w = size.width
        val trackWidth = w * 0.09f
        val knobRadius = w * 0.15f
        val rows = listOf(size.height * 0.24f to w * 0.32f, size.height * 0.5f to w * 0.65f, size.height * 0.76f to w * 0.44f)
        rows.forEach { (y, knobX) ->
            drawLine(
                color = tint,
                start = Offset(w * 0.12f, y),
                end = Offset(w * 0.88f, y),
                strokeWidth = trackWidth,
                cap = StrokeCap.Round,
            )
            drawCircle(color = knobFill, radius = knobRadius, center = Offset(knobX, y))
            drawCircle(
                color = tint,
                radius = knobRadius,
                center = Offset(knobX, y),
                style = Stroke(width = w * 0.05f),
            )
        }
    }
}
