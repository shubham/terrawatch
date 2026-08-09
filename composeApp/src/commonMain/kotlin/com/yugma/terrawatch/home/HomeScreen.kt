package com.yugma.terrawatch.home

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.BottomSheetScaffold
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberBottomSheetScaffoldState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.yugma.terrawatch.data.PillStatus
import com.yugma.terrawatch.data.pillStatus
import com.yugma.terrawatch.map.QuakeMap
import com.yugma.terrawatch.model.GeoPoint
import com.yugma.terrawatch.model.haversineKm
import com.yugma.terrawatch.ui.components.StatusShield
import com.yugma.terrawatch.ui.format.formatRelativeTime
import com.yugma.terrawatch.ui.theme.TerraRadii
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.collectLatest
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
 * poking). `QuakeMap` is now called from exactly ONE call site, unconditionally, for the entire
 * lifetime of this composable; only its `pins` argument changes across recompositions as [state]
 * changes, which is the incremental-update path `rememberGeoJsonSource`'s `setData` is built for
 * (see QuakeMap.android.kt's own fix note). The `when` below only picks the *overlay* chrome
 * (spinner vs. pill+banner), never the map itself.
 *
 * Task 9 wraps the whole thing in a [BottomSheetScaffold] rather than pushing the map/pill/banner
 * content up above a persistent sheet: `content` here still gets the full screen (the sheet floats
 * on top, peeking from the bottom) — deliberately, so the map stays edge-to-edge under the peeking
 * sheet exactly like the approved mockup (map-home-layout.html, option 1).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(viewModel: HomeViewModel) {
    val state by viewModel.state.collectAsState()
    val homeLocation by viewModel.homeLocation.collectAsState()
    val newSinceExpand by viewModel.newSinceExpand.collectAsState()
    // Fix Round 2 (review finding): feeds both isStale() and the banner's formatRelativeTime()
    // call below, so the staleness verdict and the displayed age both actually advance every 30s
    // instead of freezing at whatever they were when `state` last changed — see
    // TICKER_INTERVAL_MILLIS and rememberNowMillisTicker() below. Task 9 also feeds this into
    // pillStatus() below — the pill's own age math (e.g. the ALERT face's relative-time text)
    // needs to keep advancing for exactly the same reason.
    val nowMillis by rememberNowMillisTicker()
    val content = state as? HomeUiState.Content
    // Task 10: the pin-drop animation's trigger — see rememberExpiringNewQuakeId's own kdoc for
    // why this needs its own expiry rather than passing viewModel.newQuakeIds straight through.
    val newQuakeId by rememberExpiringNewQuakeId(viewModel.newQuakeIds)

    BoxWithConstraints(Modifier.fillMaxSize()) {
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
                    onQuakeClick = {}, // TODO(Task 11): open the quake detail sheet.
                )
            },
        ) {
            // Mounted once, for good, regardless of state — see the kdoc above. Empty pins
            // pre-Content is fine: MapLibre's own tile/style fetch still overlaps the initial DB
            // read/network refresh instead of waiting behind the spinner. The PaddingValues this
            // lambda receives (M3's own convention for "leave room for the peeking sheet") is
            // deliberately unused — the map is meant to run full-bleed under the sheet.
            Box(Modifier.fillMaxSize()) {
                QuakeMap(
                    pins = content?.pins.orEmpty(),
                    newQuakeId = newQuakeId,
                    onPinTap = {}, // TODO(Task 11): open the quake detail sheet.
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
                                onClick = {
                                    // Task 9 scope: every variant is a no-op tap for now — see the
                                    // brief's explicit "this task: no-op with TODO" call for
                                    // ASK_LOCATION, and Task 11/Plan 3 for the other two.
                                    when (pill.kind) {
                                        PillStatus.Kind.ASK_LOCATION -> {} // TODO(Plan 3 settings): re-ask permission / open settings.
                                        PillStatus.Kind.ALERT -> {} // TODO(Task 11): open the detail sheet for pill.quake.
                                        PillStatus.Kind.CALM -> {} // Nothing to show.
                                    }
                                },
                                modifier = Modifier.fillMaxWidth(),
                            )
                            // Banner moves below the pill when both are showing (Task 9 brief:
                            // "above banner if both — banner moves below pill").
                            if (s.refreshFailed || isStale(s.lastUpdatedMillis, nowMillis)) {
                                Spacer(Modifier.height(8.dp))
                                StalenessBanner(lastUpdatedMillis = s.lastUpdatedMillis, nowMillis = nowMillis)
                            }
                        }
                    }
                }
            }
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

@Composable
private fun StalenessBanner(lastUpdatedMillis: Long?, nowMillis: Long, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(TerraRadii.pill),
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.78f),
        tonalElevation = 4.dp,
        shadowElevation = 2.dp,
    ) {
        val text = lastUpdatedMillis?.let {
            "Updated ${formatRelativeTime(it, nowMillis)}"
        } ?: "Not updated yet"
        Text(
            text = text,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            style = MaterialTheme.typography.labelLarge,
        )
    }
}
