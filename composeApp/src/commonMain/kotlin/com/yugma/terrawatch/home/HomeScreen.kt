package com.yugma.terrawatch.home

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.BottomSheetScaffold
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SheetValue
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.adaptive.currentWindowAdaptiveInfo
import androidx.compose.material3.rememberBottomSheetScaffoldState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.yugma.terrawatch.common.rememberNowMillisTicker
import com.yugma.terrawatch.data.PillStatus
import com.yugma.terrawatch.data.pillStatus
import com.yugma.terrawatch.detail.DetailNewsViewModel
import com.yugma.terrawatch.detail.DetailSheet
import com.yugma.terrawatch.location.LocationAskDialog
import com.yugma.terrawatch.location.LocationAskUiState
import com.yugma.terrawatch.location.LocationRequester
import com.yugma.terrawatch.location.reduceLocationPermissionState
import com.yugma.terrawatch.location.rememberLocationCondition
import com.yugma.terrawatch.map.QuakeMap
import com.yugma.terrawatch.model.FavoritePlace
import com.yugma.terrawatch.model.GeoPoint
import com.yugma.terrawatch.model.haversineKm
import com.yugma.terrawatch.motion.LocalReducedMotion
import com.yugma.terrawatch.share.openUrl
import com.yugma.terrawatch.share.shareQuakeText
import com.yugma.terrawatch.share.sharePackaged
import com.yugma.terrawatch.ui.components.StatusShield
import com.yugma.terrawatch.ui.format.formatRelativeTime
import com.yugma.terrawatch.ui.theme.TerraRadii
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.collectLatest
import org.koin.compose.koinInject
import org.koin.compose.viewmodel.koinViewModel

// Spec's Offline row (docs/superpowers/specs/2026-08-08-terrawatch-design.md): "Glass banner...
// Map desaturates. Cache stays fully browsable" — Task 8 ships the banner text + the "always
// render the map underneath" half; map desaturation and the Retry link are not part of this task's
// brief and are left for whichever task next touches this banner.
private const val STALE_AFTER_MILLIS = 10 * 60 * 1000L

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
// the paired breakpoint this is designed against (panel + a still-usable map pane needs the
// headroom that breakpoint leaves — 840dp as of Plan 4 Task 4 (c)'s material3-adaptive
// EXPANDED-width cutover, was a raw 900dp before it).
private val TWO_PANE_RIGHT_PANEL_WIDTH = 360.dp

// Task 1 (Plan 5), USER REQUIREMENT: the my-location FAB. 48dp matches Android's own documented
// minimum touch-target size (the brief's own explicit a11y ask) — deliberately sized as the
// Surface's own footprint, not just the glyph inside it (contrast SettingsGearChip a few lines
// down, whose 24dp glyph + 10dp padding footprint — 44dp — predates this task and is left
// untouched, out of this task's scope). 16dp is this screen's own established floating-overlay
// margin (the pill/gear chip's own windowInsetsPadding(...).padding(16.dp) two blocks below).
private val MY_LOCATION_FAB_SIZE = 48.dp
private val MY_LOCATION_FAB_MARGIN = 16.dp

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
 * Task 12 section): routes to one of two whole-screen chrome arrangements via [layoutMode] —
 * [PhoneLayout] (unchanged from Task 9/10/11 behavior) below the expanded-width breakpoint,
 * [TwoPaneLayout] (map + a fixed-width list/pill panel, both always visible, no peek/expand sheet)
 * at or above it. Plan 4 Task 4 (c): that breakpoint decision itself now comes from
 * `currentWindowAdaptiveInfo().windowSizeClass` (840dp, material3-adaptive's own EXPANDED lower
 * bound — see `layoutMode`'s own kdoc), computed once above this [BoxWithConstraints] rather than
 * measured BY it — the `BoxWithConstraints` below stays only because [PhoneLayout] still needs
 * [maxHeight] for its sheet-peek fraction, a completely separate concern from which of the two
 * layouts gets chosen. [DetailSheet] is a THIRD, independent layer on top of whichever of the two is
 * active — its own on-demand [ModalBottomSheet][androidx.compose.material3.ModalBottomSheet] is
 * unaffected by which layout is showing underneath it.
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
/**
 * Task 13: two `testTag`s `NavRoundTripTest` (androidInstrumentedTest) pins directly — same
 * "internal, so a test can pin it" convention as `HistoryScreen.HISTORY_SUBTITLE`. [HOME_MAP_CONTAINER_TAG]
 * marks the `Box` that wraps [com.yugma.terrawatch.map.QuakeMap] in BOTH [PhoneLayout] and
 * [TwoPaneLayout] — this is the literal node THE white-screen regression (Task 4's own kdoc) is
 * about: it existing after a Home->History->Insights->Settings->Home round trip is the actual
 * assertion, not a proxy for it. [SETTINGS_GEAR_TAG] marks the gear chip that round trip's own
 * Settings leg taps through, since `AppNav`'s real Settings route has no tab-bar entry of its own.
 */
internal const val HOME_MAP_CONTAINER_TAG = "home-map-container"
internal const val SETTINGS_GEAR_TAG = "settings-gear"

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    viewModel: HomeViewModel,
    selectionViewModel: QuakeSelectionViewModel = koinViewModel(),
    // Plan 4 Task 5: same "defaulted so a Koin-free test can override it" shape as
    // selectionViewModel just above - AppNav's real call site always threads through the SAME
    // Activity-scoped instance History/Insights share (see DetailNewsViewModel's own kdoc).
    detailNewsViewModel: DetailNewsViewModel = koinViewModel(),
    onSettingsClick: () -> Unit = {},
) {
    val state by viewModel.state.collectAsState()
    val homeLocation by viewModel.homeLocation.collectAsState()
    val newSinceExpand by viewModel.newSinceExpand.collectAsState()
    // Task 1 (Plan 5), USER REQUIREMENT: the cold-start camera-centering signal + the my-location
    // FAB's own recenter signal — both plain HomeViewModel StateFlows, collected once here (same
    // "collect once at this level, thread the resolved value down" convention every other
    // ViewModel-fed value on this screen already follows) and threaded into both PhoneLayout/
    // TwoPaneLayout below, whose QuakeMap call sites actually apply them.
    val startupCameraTarget by viewModel.startupCameraTarget.collectAsState()
    val recenterTarget by viewModel.recenterTarget.collectAsState()
    // Task 2 (Plan 5): the quick-switch chip row (Home + favorites) and its own session-only pill
    // override — see PlaceQuickSwitchChips' own kdoc for the chips themselves, and
    // HomeViewModel.focusTarget's kdoc for the "session swap = ViewModel state, not persisted"
    // ruling this pair implements. pillFocusPoint (below) is what both PhoneLayout/TwoPaneLayout's
    // pillStatus() calls read INSTEAD of homeLocation directly — QuakeMap's own homeLocation/ring
    // stays bound to the real, persisted home unconditionally (the ring's whole meaning is "home's
    // radius"; only the pill's verdict is meant to preview a different place for the session).
    val favorites by viewModel.favorites.collectAsState()
    val focusTarget by viewModel.focusTarget.collectAsState()
    val pillFocusPoint = focusTarget ?: homeLocation
    // Task 1 (Plan 5): the FAB's own live visibility gate — "visible only when permission granted"
    // (the brief's own words), reactive to a grant/revoke made in system Settings while this app
    // was merely paused (rememberLocationCondition's own ON_RESUME re-check — same helper
    // SettingsScreen's UseMyLocationAction already uses for the identical live-permission need).
    // koinInject() here (not through HomeViewModel's constructor) matches this app's own
    // established "resolve a platform requester directly at the composable that needs LIVE
    // permission state" convention (see AppModule.kt's own kdoc note on LocationRequester/
    // NotificationPermissionRequester) — HomeViewModel's own [locationRequester] dependency is a
    // separate, ONE-SHOT check (the cold-start decision), not a reason to duplicate this one.
    val locationRequester = koinInject<LocationRequester>()
    val locationCondition = rememberLocationCondition(locationRequester)
    val locationPermissionGranted = reduceLocationPermissionState(locationCondition) == LocationAskUiState.GRANTED
    // Task 1 (Plan 5): the FAB's "Location unavailable" snackbar — a hot, one-shot event
    // (HomeViewModel.locationUnavailableEvents), so this LaunchedEffect's only job is showing it
    // when it fires, never replaying it on an unrelated recomposition.
    val snackbarHostState = remember { SnackbarHostState() }
    LaunchedEffect(viewModel) {
        // Plain collect, not collectLatest: each tap's event deserves its own shown-to-completion
        // snackbar rather than a newer tap silently cancelling an already-showing one early (unlike
        // rememberExpiringNewQuakeId's own collectLatest a few lines down, which specifically needs
        // to CANCEL a stale expiry delay — there is no equivalent in-flight work here to supersede).
        viewModel.locationUnavailableEvents.collect { snackbarHostState.showSnackbar("Location unavailable") }
    }
    // Task 7 (Plan 3), USER REQUIREMENT: the user-settable "nearby" radius/min-magnitude — fed into
    // both pillStatus() (the pill's own verdict) and QuakeMap()'s home-radius ring below, in both
    // PhoneLayout and TwoPaneLayout, so a Settings slider change reaches whichever layout is
    // currently showing without needing to leave/return to the Home tab.
    val nearbyRadiusKm by viewModel.nearbyRadiusKm.collectAsState()
    val minMag by viewModel.minMag.collectAsState()
    // Task 11: drives the detail sheet below — non-null shows it, null (the initial value, and
    // whatever QuakeSelectionViewModel.dismissSelection()/an unresolved select() settles back to)
    // hides it. Task 3 (Plan 3): moved from `viewModel` to `selectionViewModel` — see this
    // function's own kdoc.
    val selectedQuake by selectionViewModel.selectedQuake.collectAsState()
    // Plan 4 Task 5: keeps DetailNewsViewModel's own idea of "which quake" in sync with the
    // shared selectionViewModel's - see that class's own kdoc for the idempotent-re-entry guard
    // that makes this safe to call on every recomposition, not just on a genuine change.
    val newsState by detailNewsViewModel.newsState.collectAsState()
    LaunchedEffect(selectedQuake) { detailNewsViewModel.onQuakeSelected(selectedQuake) }
    // Fix Round 2 (review finding): feeds both isStale() and the banner's formatRelativeTime()
    // call below, so the staleness verdict and the displayed age both actually advance every 30s
    // instead of freezing at whatever they were when `state` last changed — see
    // com.yugma.terrawatch.common.rememberNowMillisTicker's own kdoc (Task 5, Plan 3: extracted out
    // of this file into a shared helper the moment History needed the identical ticker too). Task 9
    // also feeds this into pillStatus() below — the pill's own age math (e.g. the ALERT face's
    // relative-time text) needs to keep advancing for exactly the same reason.
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
    // Plan 4 Task 4 (c): ONE shared source of truth with AppNav.kt's own identical call — see
    // layoutMode()'s own kdoc (home/LayoutMode.kt) for the "900-980dp dead zone" bug this closes.
    // Read OUTSIDE the BoxWithConstraints below (unlike the old maxWidth-based version, which HAD
    // to be read from inside it): currentWindowAdaptiveInfo() reports the window's actual size
    // regardless of where in the tree it's called, so this line's placement is a style choice, not
    // a correctness requirement the way the deleted maxWidth read was.
    val windowSizeClass = currentWindowAdaptiveInfo().windowSizeClass

    BoxWithConstraints(Modifier.fillMaxSize()) {
        if (layoutMode(windowSizeClass) == LayoutMode.TWO_PANE) {
            TwoPaneLayout(
                state = state,
                homeLocation = homeLocation,
                pillFocusPoint = pillFocusPoint,
                nowMillis = nowMillis,
                newQuakeId = newQuakeId,
                nearbyRadiusKm = nearbyRadiusKm,
                minMag = minMag,
                viewModel = viewModel,
                selectionViewModel = selectionViewModel,
                onAskLocation = { showLocationAsk = true },
                startupCameraTarget = startupCameraTarget,
                recenterTarget = recenterTarget,
                locationPermissionGranted = locationPermissionGranted,
                favorites = favorites,
                focusTarget = focusTarget,
            )
        } else {
            PhoneLayout(
                state = state,
                homeLocation = homeLocation,
                pillFocusPoint = pillFocusPoint,
                newSinceExpand = newSinceExpand,
                nowMillis = nowMillis,
                newQuakeId = newQuakeId,
                nearbyRadiusKm = nearbyRadiusKm,
                minMag = minMag,
                maxHeight = maxHeight,
                viewModel = viewModel,
                selectionViewModel = selectionViewModel,
                onAskLocation = { showLocationAsk = true },
                startupCameraTarget = startupCameraTarget,
                recenterTarget = recenterTarget,
                locationPermissionGranted = locationPermissionGranted,
                favorites = favorites,
                focusTarget = focusTarget,
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
                .padding(16.dp)
                .testTag(SETTINGS_GEAR_TAG),
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
                onSharePackaged = { pkg, text -> sharePackaged(pkg, text) },
                newsState = newsState,
                onNewsArticleClick = { url -> openUrl(url) },
            )
        }
        // Task 2 (Plan 3): a THIRD independent overlay layer, same "stacks on top of whichever
        // layout/DetailSheet is active" shape as the detail sheet just above.
        if (showLocationAsk) {
            LocationAskDialog(onDismiss = { showLocationAsk = false })
        }
        // Task 1 (Plan 5): the my-location FAB's "Location unavailable" snackbar — a FOURTH
        // independent overlay layer, same shape as the three above. Lives at this HomeScreen level
        // (not inside PhoneLayout/TwoPaneLayout) so ONE SnackbarHostState/LaunchedEffect pair
        // serves whichever layout is active, rather than duplicating the collection wiring per
        // layout the way the FAB itself (genuinely different bottom-clearance per layout) can't
        // avoid doing.
        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 16.dp),
        )
    }
}

/**
 * Phone layout ([LayoutMode.PHONE], width below material3-adaptive's 840dp EXPANDED lower bound —
 * see `layoutMode`'s own kdoc) — the Task 9/10/11 design, unchanged in behavior by Task 12 or by
 * Plan 4 Task 4 (c)'s breakpoint-source cutover: full-bleed map with a draggable [FeedSheet]
 * anchored to the bottom and the status pill/staleness banner floating over the map's top edge.
 * Extracted out of [HomeScreen] only so that function can route between this and [TwoPaneLayout] —
 * [maxHeight] is passed in because `BoxWithConstraintsScope.maxHeight` is only available inside that
 * scope, not inside a separately-declared composable (unlike the layout CHOICE itself, [maxHeight]
 * genuinely does still need [HomeScreen]'s own `BoxWithConstraints`).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PhoneLayout(
    state: HomeUiState,
    homeLocation: GeoPoint?,
    // Task 2 (Plan 5): the pill's own reference point — HomeScreen's `focusTarget ?: homeLocation`;
    // see this file's own kdoc note at that computation for why [homeLocation] itself (fed to
    // QuakeMap's ring below, unchanged) stays separate from this.
    pillFocusPoint: GeoPoint?,
    newSinceExpand: Int,
    nowMillis: Long,
    newQuakeId: String?,
    nearbyRadiusKm: Double,
    minMag: Double,
    maxHeight: Dp,
    viewModel: HomeViewModel,
    selectionViewModel: QuakeSelectionViewModel,
    onAskLocation: () -> Unit,
    startupCameraTarget: GeoPoint?,
    recenterTarget: GeoPoint?,
    locationPermissionGranted: Boolean,
    // Task 2 (Plan 5): the quick-switch chip row's own data — see PlaceQuickSwitchChips' own kdoc.
    favorites: List<FavoritePlace>,
    focusTarget: GeoPoint?,
) {
    val content = state as? HomeUiState.Content
    // Task 10 (item e): the banner's freshness-only verdict — see shouldShowStalenessBanner's own
    // kdoc for the full rule (LIVE row below owns the connection signal separately). Computed once
    // per recomposition rather than inlined into the `if` below, so the banner's guard and any
    // future second consumer can never read two different answers.
    val offline = content?.let {
        shouldShowStalenessBanner(it.refreshFailed, isStale(it.lastUpdatedMillis, nowMillis), it.isLive)
    } ?: false
    val scaffoldState = rememberBottomSheetScaffoldState()
    // Task 1 (Plan 5): hoisted out of the `sheetPeekHeight = ...` parameter below so the
    // my-location FAB (further down this function) can clear the SAME peek height the scaffold
    // itself uses — "above sheet peek" (the brief's own words) means literally this value.
    val sheetPeekHeight = maxHeight * SHEET_PEEK_FRACTION
    // Task 9: the sheet's "N NEW" chip clears the moment the user actually drags the sheet
    // open — SheetValue.Expanded is M3's own name for "fully open" (as opposed to peeking).
    LaunchedEffect(scaffoldState) {
        snapshotFlow { scaffoldState.bottomSheetState.currentValue }
            .collect { value -> if (value == SheetValue.Expanded) viewModel.markSheetExpanded() }
    }
    BottomSheetScaffold(
        modifier = Modifier.fillMaxSize(),
        scaffoldState = scaffoldState,
        sheetPeekHeight = sheetPeekHeight,
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
                // Task 10 (item b): the sheet previously showed neither a spinner nor a skeleton
                // during Loading, just an empty list (content was null pre-Content, see FeedSheet's
                // own kdoc) — this closes that gap with the same shimmer skeleton every other
                // Loading state in this plan now uses.
                isLoading = state is HomeUiState.Loading,
            )
        },
    ) {
        // Mounted once, for good, regardless of state — see HomeScreen's kdoc. Empty pins
        // pre-Content is fine: MapLibre's own tile/style fetch still overlaps the initial DB
        // read/network refresh instead of waiting behind the spinner. The PaddingValues this
        // lambda receives (M3's own convention for "leave room for the peeking sheet") is
        // deliberately unused — the map is meant to run full-bleed under the sheet.
        Box(Modifier.fillMaxSize().testTag(HOME_MAP_CONTAINER_TAG)) {
            QuakeMap(
                pins = content?.pins.orEmpty(),
                newQuakeId = newQuakeId,
                onPinTap = { id -> selectionViewModel.select(id) },
                modifier = Modifier.fillMaxSize(),
                onDebugLongPress = { lat, lon -> viewModel.injectDebugQuake(lat, lon) },
                homeLocation = homeLocation,
                radiusKm = nearbyRadiusKm,
                startupCameraTarget = startupCameraTarget,
                onStartupCameraApplied = viewModel::consumeStartupCameraTarget,
                recenterTarget = recenterTarget,
                onRecenterApplied = viewModel::consumeRecenterTarget,
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
                    // Task 7 (Plan 3), USER REQUIREMENT: radiusKm/minMag are now the
                    // user-settable, store-fed values (HomeViewModel.nearbyRadiusKm/minMag) rather
                    // than pillStatus()'s own default parameters (Fix Round 1, entangled minor:
                    // radiusKm's default itself now reads AlertRuleStore.DEFAULT_RADIUS_KM, not an
                    // independent hardcoded 500.0 - see PillStatus.kt).
                    // Task 2 (Plan 5): pillFocusPoint, not homeLocation directly — a quick-switch
                    // chip tap swaps this to a favorite's point for the session (see this file's own
                    // kdoc note at HomeScreen's pillFocusPoint computation).
                    val pill = pillStatus(s.quakes, pillFocusPoint, nowMillis, radiusKm = nearbyRadiusKm, minMag = minMag)
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
                            radiusKm = nearbyRadiusKm,
                            reducedMotion = LocalReducedMotion.current,
                        )
                        // Task 10 (item e), LIVE/staleness vocabulary rule: banner = data freshness
                        // only when stale/failed; LIVE row = connection only. Banner moves below the
                        // pill when both are showing (Task 9 brief: "above banner if both — banner
                        // moves below pill").
                        if (offline) {
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
            // Task 1 (Plan 5), USER REQUIREMENT (dogfooding feedback item 2): the my-location FAB —
            // bottom-END, cleared above the sheet's own peek height (see [sheetPeekHeight]'s own
            // kdoc a few lines up). "Above ... the ad slot" (this task's own brief) needed no extra
            // clearance once actually checked: AppNav.kt's own Column places the ad slot/bottom bar
            // as SIBLINGS below this whole screen's composition (AppNavHost only ever gets
            // `Modifier.weight(1f)`, the ad slot/bar claim their own row height outside it) — this
            // Box's `fillMaxSize()` already stops exactly at that boundary, so a FAB aligned
            // BottomEnd inside it can never physically reach into the ad-slot/bar's own space
            // regardless of this screen's own padding.
            if (locationPermissionGranted) {
                MyLocationFab(
                    onClick = viewModel::recenterToCurrentLocation,
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(bottom = sheetPeekHeight + MY_LOCATION_FAB_MARGIN, end = MY_LOCATION_FAB_MARGIN),
                )
            }
            // Task 2 (Plan 5), USER REQUIREMENT: the Home quick-switch chip row — "above sheet
            // peek" (this task's own brief, same literal clearance [MyLocationFab] already uses),
            // BottomStart so it can never collide with the FAB's own BottomEnd corner (the brief's
            // other explicit ask: "keep chips out of the way of MyLocationFab") — its own end padding
            // additionally reserves that corner's full footprint, not just the alignment side, so an
            // unusually long scrolled-to-the-end chip can't visually run into the FAB either.
            PlaceQuickSwitchChips(
                favorites = favorites,
                focusTarget = focusTarget,
                onSelectHome = viewModel::focusHome,
                onSelectFavorite = { point -> viewModel.focusFavorite(point) },
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(
                        bottom = sheetPeekHeight + MY_LOCATION_FAB_MARGIN,
                        start = MY_LOCATION_FAB_MARGIN,
                        end = MY_LOCATION_FAB_SIZE + MY_LOCATION_FAB_MARGIN * 2,
                    ),
            )
        }
    }
}

/**
 * Desktop/tablet two-pane layout ([LayoutMode.TWO_PANE], width at/above material3-adaptive's 840dp
 * EXPANDED lower bound — see `layoutMode`'s own kdoc) — the Task 12 spike decision's
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
    // Task 2 (Plan 5): see PhoneLayout's own identical parameter kdoc.
    pillFocusPoint: GeoPoint?,
    nowMillis: Long,
    newQuakeId: String?,
    nearbyRadiusKm: Double,
    minMag: Double,
    viewModel: HomeViewModel,
    selectionViewModel: QuakeSelectionViewModel,
    onAskLocation: () -> Unit,
    startupCameraTarget: GeoPoint?,
    recenterTarget: GeoPoint?,
    locationPermissionGranted: Boolean,
    favorites: List<FavoritePlace>,
    focusTarget: GeoPoint?,
) {
    val content = state as? HomeUiState.Content
    // Task 10 (item e): same banner freshness-only verdict PhoneLayout computes - see
    // shouldShowStalenessBanner's own kdoc.
    val offline = content?.let {
        shouldShowStalenessBanner(it.refreshFailed, isStale(it.lastUpdatedMillis, nowMillis), it.isLive)
    } ?: false
    // Fix 1 (see kdoc above): re-fires on every `state` emission, i.e. every genuinely new arrival
    // (HomeViewModel.insertedQuakeIds is exactly what both bumps newSinceExpand AND changes the
    // quakes/pins lists `state` carries), so the counter never has a chance to accumulate visibly.
    LaunchedEffect(state) { viewModel.markSheetExpanded() }
    Row(Modifier.fillMaxSize()) {
        Box(Modifier.weight(1f).fillMaxHeight().testTag(HOME_MAP_CONTAINER_TAG)) {
            QuakeMap(
                pins = content?.pins.orEmpty(),
                newQuakeId = newQuakeId,
                onPinTap = { id -> selectionViewModel.select(id) },
                modifier = Modifier.fillMaxSize(),
                onDebugLongPress = { lat, lon -> viewModel.injectDebugQuake(lat, lon) },
                homeLocation = homeLocation,
                radiusKm = nearbyRadiusKm,
                startupCameraTarget = startupCameraTarget,
                onStartupCameraApplied = viewModel::consumeStartupCameraTarget,
                recenterTarget = recenterTarget,
                onRecenterApplied = viewModel::consumeRecenterTarget,
            )
            if (state is HomeUiState.Loading) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            }
            // Task 1 (Plan 5), USER REQUIREMENT: same FAB as PhoneLayout, simpler clearance — no
            // peek/expand sheet exists in this layout (see this function's own kdoc), so a plain
            // margin is enough; there is no ad slot in TWO_PANE either (AppNav.kt's own SCOPE NOTE,
            // that Row branch never reserves one).
            if (locationPermissionGranted) {
                MyLocationFab(
                    onClick = viewModel::recenterToCurrentLocation,
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(MY_LOCATION_FAB_MARGIN),
                )
            }
            // Task 2 (Plan 5): same chip row as PhoneLayout, simpler clearance — no sheet-peek
            // concept in this layout (see this function's own kdoc), a plain bottom margin plus the
            // FAB's own end-side reservation is enough.
            PlaceQuickSwitchChips(
                favorites = favorites,
                focusTarget = focusTarget,
                onSelectHome = viewModel::focusHome,
                onSelectFavorite = { point -> viewModel.focusFavorite(point) },
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(
                        bottom = MY_LOCATION_FAB_MARGIN,
                        start = MY_LOCATION_FAB_MARGIN,
                        end = MY_LOCATION_FAB_SIZE + MY_LOCATION_FAB_MARGIN * 2,
                    ),
            )
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
                // Task 2 (Plan 5): pillFocusPoint, not homeLocation directly — see PhoneLayout's own
                // identical comment at its pillStatus() call.
                val pill = pillStatus(state.quakes, pillFocusPoint, nowMillis, radiusKm = nearbyRadiusKm, minMag = minMag)
                Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
                    StatusShield(
                        status = pill,
                        nowMillis = nowMillis,
                        onClick = { onPillClick(pill, selectionViewModel, onAskLocation) },
                        modifier = Modifier.fillMaxWidth().padding(end = GEAR_CHIP_CLEARANCE),
                        radiusKm = nearbyRadiusKm,
                        reducedMotion = LocalReducedMotion.current,
                    )
                    // Task 10 (item e), LIVE/staleness vocabulary rule: banner = data freshness
                    // only when stale/failed; LIVE row = connection only.
                    if (offline) {
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
            // Task 10 (items b/c): the phone sheet's FeedSheet now distinguishes Loading (skeleton)
            // / empty (calm copy) / Content (the real list) - this panel reuses the same two
            // composables so the desktop/tablet layout doesn't stay a permanently-blank list during
            // either a first load or a genuinely quiet 24h window.
            when {
                state is HomeUiState.Loading -> FeedSkeletonList(modifier = Modifier.fillMaxWidth().weight(1f))
                content != null && content.quakes.isEmpty() -> FeedEmptyState(modifier = Modifier.fillMaxWidth().weight(1f))
                else -> FeedList(
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

internal fun isStale(lastUpdatedMillis: Long?, nowMillis: Long): Boolean =
    lastUpdatedMillis != null && nowMillis - lastUpdatedMillis > STALE_AFTER_MILLIS

/**
 * Task 10 (item e): the LIVE/staleness vocabulary rule, made real. **Banner = data freshness only,
 * shown when stale or failed. LIVE row = connection only** ([FeedSheet]'s `LiveStatusRow`/`LiveDot`
 * own that signal exclusively) — the two must never contradict each other on screen.
 *
 * Before this fix, both [PhoneLayout] and [TwoPaneLayout] gated their [StalenessBanner] on plain
 * `refreshFailed || isStale(...)`, with no reference to [isLive] at all — so a perfectly healthy,
 * actively-connected feed that simply hadn't seen a NEW quake in over [STALE_AFTER_MILLIS] (a quiet
 * period, not a broken one) would show "You're offline"/"Updated N min ago" chrome directly beside
 * a pulsing green LIVE dot, the exact contradictory-chrome failure mode this rule exists to
 * prevent. [refreshFailed] alone still always wins regardless of [isLive] — a failed refresh is
 * worth surfacing even while the socket happens to still be open (the poll loop and the WebSocket
 * are independent connections; one can fail while the other stays up).
 *
 * Pure `Boolean`-in/`Boolean`-out on purpose — [isStale] is a caller-computed argument here rather
 * than this function re-deriving it from `lastUpdatedMillis`/`nowMillis` itself, so the full
 * decision is a plain 3-input truth table with no time/clock concern folded in (both call sites
 * below compute `isStale(...)` explicitly, right next to this call). `internal` so
 * `HomeScreenBannerTest` can pin every row of that table directly.
 */
internal fun shouldShowStalenessBanner(
    refreshFailed: Boolean,
    isStale: Boolean,
    isLive: Boolean,
): Boolean = refreshFailed || (isStale && !isLive)

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
 * above. Doc fix (Task 5, Plan 3 review): this is a 5th glass-styled surface, not literally one of
 * the spec Global Constraints' 4-item glass allow-list (pill/banner/nav/sheet-header) — the prior
 * wording here ("per the glass allow-list") implied this chip was already covered by that list,
 * which isn't quite true. Extending the glass treatment to a new element the list doesn't name was
 * a controller judgment call (visual consistency with the pill this chip sits beside), not
 * something the allow-list pre-approved — flagged here rather than implied as already covered.
 * `contentDescription` is set explicitly rather than left for Task 10's broader a11y sweep to
 * backfill: an entirely unlabeled tappable icon is an easy-to-avoid gap this task would otherwise
 * be introducing fresh, not a pre-existing one Task 10 is already scheduled to fix.
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

/**
 * Task 1 (Plan 5), USER REQUIREMENT (dogfooding feedback item 2, "a button to recenter on me"):
 * the my-location FAB — a circular glass surface, same "translucent [Surface] + tonal/shadow
 * elevation" treatment [SettingsGearChip] above already establishes for a floating control over
 * the map, rather than a differently-styled M3 [androidx.compose.material3.FloatingActionButton]
 * that would introduce a second, inconsistent floating-control visual language onto this same
 * screen.
 *
 * ICON DECISION (this task's own brief asked to "verify icon exists in our compose-icons set, else
 * material icon core" before reaching for `Icons.Filled.MyLocation`): this project has NO
 * material-icons dependency of any kind today (`composeApp/build.gradle.kts`'s `commonMain`
 * dependencies list only `compose.runtime`/`compose.foundation`/`compose.material3`/`compose.ui` —
 * grepped, not assumed). Checked both real candidates directly against their resolved artifacts
 * (`unzip -l` on the actual jars/aars in `~/.gradle/caches`, the same "verify against the real
 * artifact" discipline this codebase already applies to maplibre-compose — see QuakeMap.android.kt
 * 's own kdoc): `androidx.compose.material:material-icons-core` (the small ~50-icon curated set)
 * does NOT contain `MyLocation` in any style; only `material-icons-extended` (thousands of icons)
 * has `filled/MyLocationKt.class`. Pulling in the FULL extended set for exactly one icon would be
 * the first icon-library dependency this codebase has ever taken on — and this project has made
 * that call three times already, always the other way (`SettingsGlyph` above, `nav/NavIcons.kt`,
 * core:ui's `StatusShield` glyphs — each one explicitly citing "no icon-library dependency in this
 * project" as the reason). [MyLocationGlyph] below continues that established precedent: a
 * hand-drawn "target/crosshair" glyph (ring + center dot + four short outward ticks — the same
 * silhouette `Icons.Filled.MyLocation` itself uses), at zero new dependency cost.
 *
 * [MY_LOCATION_FAB_SIZE] (48dp) is the Surface's OWN size — the full tappable footprint meets the
 * brief's explicit a11y ask, not just the glyph inside it. `contentDescription` is the brief's own
 * literal copy ("Go to my location").
 */
@Composable
private fun MyLocationFab(onClick: () -> Unit, modifier: Modifier = Modifier) {
    Surface(
        onClick = onClick,
        modifier = modifier
            .size(MY_LOCATION_FAB_SIZE)
            .semantics { contentDescription = "Go to my location" },
        shape = RoundedCornerShape(TerraRadii.pill),
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.78f),
        tonalElevation = 4.dp,
        shadowElevation = 2.dp,
    ) {
        Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
            MyLocationGlyph(tint = MaterialTheme.colorScheme.onSurface, modifier = Modifier.size(24.dp))
        }
    }
}

/**
 * A "target/crosshair" my-location glyph — a stroked ring, a filled center dot, and four short
 * ticks just outside the ring at N/S/E/W — drawn on [Canvas], same "no icon-library dependency"
 * reasoning as [SettingsGlyph] above (see [MyLocationFab]'s own kdoc for the full icon-vs-hand-draw
 * investigation this glyph is the outcome of). Proportions assume a square [modifier] (the one real
 * call site above sizes this 24dp x 24dp); `size.minDimension` (not a bare `size.width`, unlike
 * [SettingsGlyph]'s own row-based layout, which genuinely needs width and height separately) keeps
 * every radius/length correct even if a future caller ever passes a non-square size.
 */
@Composable
private fun MyLocationGlyph(tint: Color, modifier: Modifier = Modifier) {
    Canvas(modifier = modifier) {
        val d = size.minDimension
        val center = Offset(size.width / 2f, size.height / 2f)
        val strokeWidth = d * 0.08f
        val ringRadius = d * 0.28f
        val dotRadius = d * 0.12f
        val tickGap = d * 0.06f // gap between the ring's own edge and each tick's inner end
        val tickLength = d * 0.14f
        val tickInner = ringRadius + tickGap
        val tickOuter = tickInner + tickLength

        // Four ticks (N/S/E/W), each a short line just outside the ring, not touching it.
        drawLine(tint, Offset(center.x, center.y - tickOuter), Offset(center.x, center.y - tickInner), strokeWidth, StrokeCap.Round)
        drawLine(tint, Offset(center.x, center.y + tickInner), Offset(center.x, center.y + tickOuter), strokeWidth, StrokeCap.Round)
        drawLine(tint, Offset(center.x - tickOuter, center.y), Offset(center.x - tickInner, center.y), strokeWidth, StrokeCap.Round)
        drawLine(tint, Offset(center.x + tickInner, center.y), Offset(center.x + tickOuter, center.y), strokeWidth, StrokeCap.Round)

        drawCircle(color = tint, radius = ringRadius, center = center, style = Stroke(width = strokeWidth))
        drawCircle(color = tint, radius = dotRadius, center = center)
    }
}

/**
 * Task 2 (Plan 5), USER REQUIREMENT: the Home quick-switch chip row — "Home" plus one [FilterChip]
 * per favorite, horizontally scrollable (same idiom `HistoryScreen.HistoryFilterChips`/
 * `InsightsScreen`'s own period selector already establish for a small set of mutually-exclusive
 * options — a `Row` + `horizontalScroll` + [FilterChip], not a custom segmented control, matching
 * this app's existing control idiom rather than inventing a new one). Tapping "Home" calls
 * [onSelectHome] ([HomeViewModel.focusHome]); tapping a favorite calls [onSelectFavorite] with its
 * own [com.yugma.terrawatch.model.GeoPoint] ([HomeViewModel.focusFavorite]) — both fly the camera
 * AND swap the pill's session-only reference point, per [focusTarget]'s own kdoc
 * ([HomeViewModel.focusTarget]).
 *
 * Rendered ONLY when [favorites] is non-empty — a lone "Home" chip with nothing else to switch to
 * is pure clutter over the map for the common zero-favorites case (every user before this task ever
 * ships, and every user who simply never adds one), not a real quick-switch affordance yet.
 *
 * [selected] is derived structurally, not by [GeoPoint] equality: "Home" is selected exactly when
 * [focusTarget] is `null` (see that field's own kdoc — `null` IS "home is focused", not "unknown");
 * a favorite chip is selected when [focusTarget] equals ITS OWN point. Two favorites that happen to
 * share identical coordinates (an unlikely but possible manual entry) would both read as selected
 * together in that edge case — accepted, since [FavoritePlace.point] (not a numeric id) is the only
 * signal [HomeViewModel.focusTarget] itself carries, by deliberate design (see that field's kdoc for
 * why: the pill only ever needs a point to compare against, not which favorite row produced it).
 */
@Composable
private fun PlaceQuickSwitchChips(
    favorites: List<FavoritePlace>,
    focusTarget: GeoPoint?,
    onSelectHome: () -> Unit,
    onSelectFavorite: (GeoPoint) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (favorites.isEmpty()) return
    Row(
        modifier = modifier.horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        FilterChip(selected = focusTarget == null, onClick = onSelectHome, label = { Text("Home") })
        favorites.forEach { favorite ->
            FilterChip(
                selected = focusTarget == favorite.point,
                onClick = { onSelectFavorite(favorite.point) },
                label = { Text(favorite.label) },
            )
        }
    }
}
