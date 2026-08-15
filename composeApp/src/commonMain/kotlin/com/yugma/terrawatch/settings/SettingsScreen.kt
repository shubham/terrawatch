package com.yugma.terrawatch.settings

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.FilterChip
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.yugma.terrawatch.alerts.AlertDigestScheduler
import com.yugma.terrawatch.data.ThemeSetting
import com.yugma.terrawatch.location.CityPickerDialog
import com.yugma.terrawatch.location.LocationAskUiState
import com.yugma.terrawatch.location.LocationRequester
import com.yugma.terrawatch.location.canRequestLocation
import com.yugma.terrawatch.location.reduceLocationPermissionState
import com.yugma.terrawatch.location.rememberLocationCondition
import com.yugma.terrawatch.model.FavoriteAlertType
import com.yugma.terrawatch.model.FavoritePlace
import com.yugma.terrawatch.model.GeoPoint
import com.yugma.terrawatch.notifications.NotificationAlertsUiState
import com.yugma.terrawatch.notifications.NotificationPermissionRequester
import com.yugma.terrawatch.notifications.reduceNotificationPermissionState
import com.yugma.terrawatch.notifications.rememberNotificationCondition
import com.yugma.terrawatch.ui.format.formatCoordinates
import com.yugma.terrawatch.ui.format.formatCount
import com.yugma.terrawatch.ui.format.formatMagnitude
import com.yugma.terrawatch.ui.theme.TerraRadii
import kotlin.math.abs
import kotlin.math.round
import kotlin.math.roundToInt
import org.koin.compose.koinInject
import org.koin.compose.viewmodel.koinViewModel

/** The five fixed radius stops the "nearby" slider snaps to (plan's own spec) — NOT evenly
 * spaced (50/150/250/500 apart), so the slider is driven by INDEX into this list rather than by
 * [androidx.compose.material3.Slider]'s own evenly-distributed `steps` parameter, which only knows
 * how to divide a range into equal-width increments. */
private val RADIUS_STEPS_KM = listOf(50.0, 100.0, 250.0, 500.0, 1000.0)

/** The nearest [RADIUS_STEPS_KM] index to an arbitrary stored [radiusKm] — a stored value that
 * doesn't land exactly on one of the five stops (shouldn't normally happen once this screen is the
 * only writer, but a defensively-corrupt/hand-edited meta row is always possible, same posture
 * [AlertRuleStore]'s own corrupt-value fallback takes) still resolves to SOME valid slider
 * position rather than crashing or under/overflowing the index range. `internal`, not `private` —
 * mirrors `InsightsScreen.dayCountLabels`' "so a test can pin it" convention. */
internal fun closestRadiusStepIndex(radiusKm: Double): Int =
    RADIUS_STEPS_KM.indices.minBy { index -> abs(RADIUS_STEPS_KM[index] - radiusKm) }

/** Snaps an arbitrary magnitude to the nearest 0.5 — a defensive safety net around
 * [androidx.compose.material3.Slider]'s own `steps`-driven snapping (which should already deliver
 * exact 0.5 increments for a 3.0..6.0 range) rather than a load-bearing computation of its own. */
internal fun snapToHalfMagnitude(value: Double): Double = round(value * 2.0) / 2.0

/**
 * Task 3 (Plan 4): the ALERTS section's own on/off row text — "On" requires BOTH permission
 * ([NotificationAlertsUiState.ENABLED] — granted, or auto-granted pre-33) AND [enqueued] (a REAL
 * `AlertDigestScheduler.isEnqueued()` query, not merely inferred from permission alone — see that
 * method's own kdoc for why this row wants to be honest about the worker's actual scheduled state,
 * not just what permission theoretically allows).
 */
internal fun alertsRowStatusText(uiState: NotificationAlertsUiState, enqueued: Boolean): String =
    if (uiState == NotificationAlertsUiState.ENABLED && enqueued) "On" else "Off"

/**
 * The explainer line shown under the ALERTS row whenever it reads "Off" — `null` when [uiState] is
 * [NotificationAlertsUiState.ENABLED] (nothing to explain). Deliberately the SAME copy for
 * [NotificationAlertsUiState.CAN_ASK] and [NotificationAlertsUiState.NEEDS_SETTINGS] alike — see
 * [AlertsPermissionRow]'s own kdoc for the controller ruling this reflects: Settings' row always
 * routes to system Settings, never a re-triggered in-app OS dialog (that in-app ask-with-rationale
 * flow is onboarding step 3's job specifically, not this row's).
 */
internal fun alertsRowExplainer(uiState: NotificationAlertsUiState): String? =
    if (uiState == NotificationAlertsUiState.ENABLED) {
        null
    } else {
        "Notifications are off — earthquake digests can't be delivered. Enable them in system Settings."
    }

// Task 12 (Plan 3), release hygiene subset: hardcoded, not read from any BuildConfig-equivalent —
// composeApp is a KMP commonMain source set (this file compiles for android/jvm/wasmJs alike), and
// Android's generated BuildConfig class is androidMain-only, unreachable from here without an
// expect/actual seam this task's own dispatch judged out of scope for a "subset" pass (Plan 4:
// revisit if/when a real per-platform build-info surface is worth adding for more than one string).
// KEEP IN SYNC BY HAND with composeApp/build.gradle.kts's `versionName` — the two are independently
// literal today. SettingsScreenTest pins this constant; bump BOTH this and versionName together
// (no BuildConfig in KMP commonMain).
internal const val APP_VERSION = "0.9.0"

/** Task 13: `testTag` for [SettingsHeader]'s back chevron — `NavRoundTripTest`
 * (androidInstrumentedTest) taps this to complete the Home->History->Insights->Settings->Home leg
 * back to Home. Same "internal, so a test can pin it" convention as [APP_VERSION]'s neighbors
 * elsewhere in this codebase. */
internal const val SETTINGS_BACK_TAG = "settings-back"

/** Plan 4 Task 6: `testTag` for the PLUS section's "TerraWatch Plus" row — same "internal, so a
 * test/device-verification pass can pin it" convention as [SETTINGS_BACK_TAG] just above. */
internal const val SETTINGS_PLUS_ROW_TAG = "settings-plus-row"

/** Task 2 (Plan 5): `testTag` for the Places section's "Add place" row — same "internal, so a
 * device-verification pass can pin it" convention as [SETTINGS_PLUS_ROW_TAG] just above. Per-favorite
 * remove buttons use [favoriteRemoveTag] instead (their own id-keyed tag) since there can be more
 * than one, unlike this single fixed row. */
internal const val SETTINGS_ADD_PLACE_TAG = "settings-add-place"

/**
 * Task 7 (Plan 3): the Settings screen — replaces `AppNav.kt`'s `PlaceholderScreen("Settings —
 * Task 7")`. Four sections per the plan brief: ALERTS (the flagship user-settable "nearby" radius
 * slider + a min-magnitude slider, both backed by [AlertRuleStore][com.yugma.terrawatch.data.AlertRuleStore]),
 * PLACE (the saved home location, reusing [CityPickerDialog] from Task 2 rather than a second
 * picker), THEME (System/Light/Dusk, backed by [com.yugma.terrawatch.data.ThemeStore] — `App()`
 * collects it directly, see that composable's own kdoc), ABOUT (static version/attribution text).
 *
 * [onBack] pops this stack-only route off the nav graph (`AppNav.kt`'s real call site wires
 * `navController::popBackStack`) — defaulted to a no-op so this composable stays callable without a
 * NavController in scope, same "defaulted no-op, not a required param" shape `HomeScreen`'s own
 * `onSettingsClick` uses for the identical reason.
 *
 * Plan 4 Task 4 (a), SDK-36 edge-to-edge sweep: unlike Home/Onboarding (already inset-aware before
 * this task), Settings is a stack-only, chrome-less full screen — no `AppBottomBar`/`NavigationRail`
 * sits above or below it the way it does for the HOME/HISTORY/INSIGHTS tabs (see `AppNav.kt`'s own
 * `TAB_ROUTES`), so nothing else was reserving system-bar space on either edge. `windowInsetsPadding(
 * WindowInsets.systemBars)` on this outermost `Column` (before `.verticalScroll`, so the space is
 * reserved first and the now-smaller remaining area is what scrolls) fixes BOTH the header's
 * back-chevron/title (top, under the status bar) and the final bottom `Spacer` (under the
 * navigation bar) in one change, rather than patching each edge separately.
 *
 * Plan 4 Task 6: [onPlusClick] backs the new PLUS section's "TerraWatch Plus" row — a defaulted
 * no-op, same "so this composable stays callable without a NavController in scope" shape [onBack]
 * already uses; `AppNav.kt`'s real call site overrides it to `navController.navigate(Routes.PAYWALL)`.
 */
@Composable
fun SettingsScreen(
    onBack: () -> Unit = {},
    onPlusClick: () -> Unit = {},
    viewModel: SettingsViewModel = koinViewModel(),
) {
    val nearbyRadiusKm by viewModel.nearbyRadiusKm.collectAsState()
    val minMag by viewModel.minMag.collectAsState()
    val theme by viewModel.theme.collectAsState()
    val homeLocation by viewModel.homeLocation.collectAsState()
    val isPlusActive by viewModel.isPlusActive.collectAsState()
    // Task 2 (Plan 5): the Places section's own favorites list.
    val favorites by viewModel.favorites.collectAsState()
    var showCityPicker by remember { mutableStateOf(false) }
    // Task 2 (Plan 5): a SEPARATE flag from showCityPicker above — this one opens CityPickerDialog
    // in its "add favorite" reuse mode (onCityPicked non-null), never home's own "Change" mode; the
    // two must stay independent so tapping "Add place" can never accidentally overwrite home, and
    // vice versa.
    var showAddFavoritePicker by remember { mutableStateOf(false) }

    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.systemBars)
                .verticalScroll(rememberScrollState()),
        ) {
            SettingsHeader(onBack = onBack)
            Column(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                SettingsCard {
                    SettingsSectionLabel("ALERTS")
                    RadiusSlider(radiusKm = nearbyRadiusKm, onRadiusChange = viewModel::setNearbyRadius)
                    Spacer(Modifier.height(16.dp))
                    MinMagSlider(minMag = minMag, onMinMagChange = viewModel::setMinMag)
                    Spacer(Modifier.height(16.dp))
                    AlertsPermissionRow()
                }
                SettingsCard {
                    // Task 2 (Plan 5): "PLACE" -> "PLACES" — this section now covers home AND every
                    // favorite beyond it, not home alone.
                    SettingsSectionLabel("PLACES")
                    PlaceRow(
                        homeLocation = homeLocation,
                        onChangeClick = { showCityPicker = true },
                    )
                    favorites.forEach { favorite ->
                        Spacer(Modifier.height(12.dp))
                        FavoriteRow(
                            favorite = favorite,
                            onAlertTypeChange = { type -> viewModel.setFavoriteAlertType(favorite.id, type) },
                            onRemove = { viewModel.removeFavorite(favorite.id) },
                        )
                    }
                    Spacer(Modifier.height(12.dp))
                    AddPlaceRow(
                        onClick = {
                            // Task 2 (Plan 5), FIRST REAL PLUS GATE: checked at the moment of the
                            // tap, against THIS instant's own favorites count — see
                            // SettingsViewModel.canAddFavorite's own kdoc. Blocked -> the paywall
                            // (the existing Plan 4 Task 6 Plus row/paywall wiring, reused verbatim
                            // via the SAME onPlusClick callback the PLUS row below already calls),
                            // never the picker.
                            if (viewModel.canAddFavorite()) showAddFavoritePicker = true else onPlusClick()
                        },
                    )
                }
                SettingsCard {
                    SettingsSectionLabel("PLUS")
                    PlusRow(isPlusActive = isPlusActive, onClick = onPlusClick)
                }
                SettingsCard {
                    SettingsSectionLabel("THEME")
                    ThemeOptions(current = theme, onSelect = viewModel::setTheme)
                }
                SettingsCard {
                    SettingsSectionLabel("ABOUT")
                    AboutContent()
                }
                Spacer(Modifier.height(24.dp)) // bottom breathing room under the scroll content
            }
        }
        if (showCityPicker) {
            CityPickerDialog(onDismiss = { showCityPicker = false })
        }
        if (showAddFavoritePicker) {
            CityPickerDialog(
                onDismiss = { showAddFavoritePicker = false },
                onCityPicked = { city -> viewModel.addFavorite(city.name, city.point) },
            )
        }
    }
}

@Composable
private fun SettingsHeader(onBack: () -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onBack, modifier = Modifier.testTag(SETTINGS_BACK_TAG)) {
            BackChevronGlyph(
                tint = MaterialTheme.colorScheme.onBackground,
                modifier = Modifier.width(24.dp).height(24.dp),
            )
        }
        Text(
            text = "Settings",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onBackground,
        )
    }
}

/** A plain "‹" chevron, hand-drawn on [Canvas] — same "no icon-library dependency in this project"
 * reasoning as `nav/NavIcons.kt`'s tab icons and `HomeScreen.kt`'s own `SettingsGlyph`. */
@Composable
private fun BackChevronGlyph(tint: Color, modifier: Modifier = Modifier) {
    Canvas(modifier = modifier) {
        val path = Path().apply {
            moveTo(size.width * 0.62f, size.height * 0.2f)
            lineTo(size.width * 0.32f, size.height * 0.5f)
            lineTo(size.width * 0.62f, size.height * 0.8f)
        }
        drawPath(
            path = path,
            color = tint,
            style = Stroke(width = size.width * 0.12f, cap = StrokeCap.Round, join = StrokeJoin.Round),
        )
    }
}

/** Every settings section: the same white/[MaterialTheme.colorScheme.surface] rounded
 * [TerraRadii.card] surface `InsightsScreen`'s own `InsightsCard` uses — one shared card shape
 * across every screen in this app, not a bespoke one per screen. */
@Composable
private fun SettingsCard(content: @Composable ColumnScope.() -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(TerraRadii.card),
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 1.dp,
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp), content = content)
    }
}

@Composable
private fun SettingsSectionLabel(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        letterSpacing = 1.sp,
        modifier = modifier.padding(bottom = 4.dp),
    )
}

/**
 * THE flagship control (Task 7 brief, USER REQUIREMENT): 50/100/250/500/1000 km, index-driven (see
 * [closestRadiusStepIndex]'s own kdoc for why) rather than [Slider]'s native evenly-spaced `steps`.
 * LOCAL DRAG-STATE: [pendingRadiusIndex] caches the user's in-flight slider position during a drag;
 * [onValueChange] updates it (live label reflects the drag), and [onValueChangeFinished] persists
 * the final value to the ViewModel — guaranteeing ONE write per completed gesture, not per drag tick.
 * The persisted [radiusKm] flow from the store reflects back to recompose the slider and label,
 * same pattern [MinMagSlider] now uses for minMag.
 */
@Composable
private fun RadiusSlider(radiusKm: Double, onRadiusChange: (Double) -> Unit, modifier: Modifier = Modifier) {
    var pendingRadiusIndex by remember { mutableStateOf(closestRadiusStepIndex(radiusKm)) }
    Column(modifier.fillMaxWidth()) {
        Text(
            text = "Nearby means within ${formatCount(RADIUS_STEPS_KM[pendingRadiusIndex].roundToInt().toLong())} km",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Slider(
            value = pendingRadiusIndex.toFloat(),
            onValueChange = { newValue ->
                pendingRadiusIndex = newValue.roundToInt().coerceIn(RADIUS_STEPS_KM.indices)
            },
            onValueChangeFinished = {
                onRadiusChange(RADIUS_STEPS_KM[pendingRadiusIndex])
            },
            valueRange = 0f..(RADIUS_STEPS_KM.size - 1).toFloat(),
            // 5 stops = start + 3 intermediate + end.
            steps = RADIUS_STEPS_KM.size - 2,
        )
    }
}

@Composable
private fun MinMagSlider(minMag: Double, onMinMagChange: (Double) -> Unit, modifier: Modifier = Modifier) {
    var pendingMinMag by remember { mutableStateOf(minMag) }
    Column(modifier.fillMaxWidth()) {
        Text(
            text = "Alerts for magnitude ${formatMagnitude(pendingMinMag)}+ nearby",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Slider(
            value = pendingMinMag.toFloat(),
            onValueChange = { pendingMinMag = snapToHalfMagnitude(it.toDouble()) },
            onValueChangeFinished = {
                onMinMagChange(pendingMinMag)
            },
            valueRange = 3.0f..6.0f,
            // 3.0..6.0 in steps of 0.5 = 7 stops = start + 5 intermediate + end.
            steps = 5,
        )
    }
}

/**
 * Task 3 (Plan 4): the ALERTS section's permission/worker-state row — "On"/"Off"
 * ([alertsRowStatusText]) plus, when off, an explainer and a Settings deep-link
 * ([alertsRowExplainer]). [rememberNotificationCondition] is what keeps this live across a visit
 * to system Settings and back (see that function's own kdoc); [AlertDigestScheduler.isEnqueued] is
 * re-queried every time [condition] itself changes (a permission flip is the one thing that could
 * plausibly change whether the worker is enqueued too, e.g. `MainActivity`'s own grant-callback
 * enqueue call landing right after this row last read `enqueued`).
 *
 * Controller ruling: unlike onboarding step 3 (`OnboardingScreen.kt`'s own `NotificationsAskStep`),
 * this row NEVER re-triggers the in-app OS ask dialog — both [NotificationAlertsUiState.CAN_ASK]
 * and [NotificationAlertsUiState.NEEDS_SETTINGS] resolve to the identical "explain + Settings
 * deep-link" shape ([alertsRowExplainer]'s own kdoc), matching this task's own dispatch text
 * literally ("denied -> row explains + Settings deep-link"). Onboarding is the one place a fresh
 * in-context ask fits Android's own permission-UX conventions; a return visit to Settings reads
 * better as "go fix it in system Settings" than a re-triggered dialog.
 *
 * The debug-only long-press (this task's own device-verification hook): [AlertDigestScheduler.
 * isDebugTriggerAvailable] gates [AlertDigestScheduler.triggerNow] internally (mirrors `QuakeMap.
 * android.kt`'s own `isDebuggableBuild`-gated long-press quake-inject hook) — this row's own
 * `combinedClickable` calls it unconditionally on a long-press; a release build's own `false`
 * answer makes that call a harmless no-op, so no separate "is this UI element visible" gate is
 * needed on top.
 *
 * Fix Round 1 (minor): `indication = null` — the row's `onClick = {}` is a required parameter
 * (`combinedClickable` needs SOME `onClick` to also accept `onLongClick`), not a real tap
 * affordance; left at its default indication, every tap on this row showed a ripple that implied
 * the row itself was actionable when nothing happens on a plain tap. A remembered
 * [MutableInteractionSource] plus `indication = null` keeps the long-press detection intact while
 * removing that misleading visual.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun AlertsPermissionRow(modifier: Modifier = Modifier) {
    val requester = koinInject<NotificationPermissionRequester>()
    val scheduler = koinInject<AlertDigestScheduler>()
    val condition = rememberNotificationCondition(requester)
    val uiState = reduceNotificationPermissionState(condition)
    var enqueued by remember { mutableStateOf(false) }
    LaunchedEffect(condition) { enqueued = scheduler.isEnqueued() }

    Column(
        modifier
            .fillMaxWidth()
            .combinedClickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = {},
                onLongClick = { if (scheduler.isDebugTriggerAvailable()) scheduler.triggerNow() },
            ),
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "Alerts",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = alertsRowStatusText(uiState, enqueued),
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
        alertsRowExplainer(uiState)?.let { explainer ->
            Spacer(Modifier.height(4.dp))
            Text(
                text = explainer,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            TextButton(onClick = { requester.openSettings() }, modifier = Modifier.padding(top = 4.dp)) {
                Text("Open Settings")
            }
        }
    }
}

/**
 * The saved-place row (Task 7 brief: "shows current home... store only has GeoPoint: show
 * '%.2f, %.2f'"). Reuses [formatCoordinates] (already TDD'd, already KMP-safe — no
 * `String.format`, unavailable on wasmJs — see that function's own kdoc) rather than hand-rolling a
 * second two-decimal formatter: it renders the identical precision the brief asks for, with a
 * nicer N/E/S/W hemisphere convention instead of a bare signed pair — the same function
 * `DetailSheet`'s own "Coordinates" row already uses for a quake's location.
 *
 * Plan 4 Task 4 (d): the "Use my location" button itself moved into [UseMyLocationAction] — a
 * self-contained sub-composable (own [koinInject], mirrors [AlertsPermissionRow]'s identical shape)
 * instead of a plain threaded `onUseMyLocationClick` callback, so it can carry its own
 * rationale/permanently-denied state without widening this function's parameter list.
 */
@Composable
private fun PlaceRow(
    homeLocation: GeoPoint?,
    onChangeClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = homeLocation?.let { formatCoordinates(it.lat, it.lon) } ?: "Not set",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = onChangeClick) { Text("Change") }
        }
        // canRequestLocation() is android-only (see LocationRequester.kt's own kdoc) — matches
        // LocationAskDialog's identical "Use my location" gating, so this row never renders
        // somewhere it couldn't possibly do anything.
        if (canRequestLocation()) {
            UseMyLocationAction(modifier = Modifier.padding(top = 4.dp))
        }
    }
}

/**
 * Task 2 (Plan 5): one favorite in the Places section — label, a 3-state alert-type control, and a
 * remove action. [FilterChip] x3 (All/Major only/Off), not a custom segmented control — matches this
 * app's existing "a small set of mutually-exclusive options is a horizontally-scrollable row of
 * [FilterChip]s" idiom (`HistoryScreen.HistoryFilterChips`'s own magnitude/year chips,
 * `InsightsScreen`'s period selector, and this same screen's own `HomeScreen.PlaceQuickSwitchChips`
 * for the identical reason), rather than introducing a new control shape for this one row.
 */
@Composable
private fun FavoriteRow(
    favorite: FavoritePlace,
    onAlertTypeChange: (FavoriteAlertType) -> Unit,
    onRemove: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = favorite.label,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f),
            )
            TextButton(
                onClick = onRemove,
                modifier = Modifier.testTag(favoriteRemoveTag(favorite.id)),
            ) { Text("Remove") }
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 4.dp)
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            FavoriteAlertType.entries.forEach { type ->
                FilterChip(
                    selected = favorite.alertType == type,
                    onClick = { onAlertTypeChange(type) },
                    label = { Text(type.displayName()) },
                )
            }
        }
    }
}

private fun FavoriteAlertType.displayName(): String = when (this) {
    FavoriteAlertType.ALL -> "All"
    FavoriteAlertType.MAJOR_ONLY -> "Major only"
    FavoriteAlertType.OFF -> "Off"
}

/** `internal` so a future device-verification pass can pin a specific favorite's own remove button
 * without depending on row order/label text — same "so a test can pin it" convention every other
 * `testTag` constant in this file already establishes. */
internal fun favoriteRemoveTag(id: Long): String = "settings-favorite-remove-$id"

/**
 * Task 2 (Plan 5): the Places section's own "Add place" action — [onClick] is the gate-check-then-
 * route decision (`SettingsScreen`'s own call site: canAddFavorite() -> open the picker, else ->
 * the paywall), never decided here — this row is purely presentational, same "dumb row, smart
 * caller" split every other action row on this screen (`PlusRow`, `PlaceRow`'s own "Change" button)
 * already follows.
 */
@Composable
private fun AddPlaceRow(onClick: () -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .testTag(SETTINGS_ADD_PLACE_TAG),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = "Add place",
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary,
        )
    }
}

/**
 * Task 6 (Plan 4): the "TerraWatch Plus" row — [onClick] pushes `AppNav.kt`'s new `Routes.PAYWALL`
 * stub (see `PaywallScreen`'s own kdoc for why it's a stub, not a real `purchases-kmp-ui` paywall,
 * this task). Status text mirrors `PlaceRow`'s own "value + chevron-like affordance" shape —
 * "Active"/"Free" rather than a bare label, so this row is honest about current state at a glance,
 * same as `AlertsPermissionRow`'s own "On"/"Off" trailing text.
 */
@Composable
private fun PlusRow(isPlusActive: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .testTag(SETTINGS_PLUS_ROW_TAG),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = "TerraWatch Plus",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = if (isPlusActive) "Active" else "Free",
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

/**
 * Plan 4 Task 4 (d): the "Use my location" half of [PlaceRow] — self-contained (own [koinInject],
 * mirrors [AlertsPermissionRow]'s identical shape) so it can carry its own rationale state without
 * threading permission plumbing through [PlaceRow]'s otherwise-plain parameter list.
 *
 * Deliberately asymmetric with onboarding's own `LocationStep`
 * ([com.yugma.terrawatch.onboarding.OnboardingScreen]) for [LocationAskUiState.GRANTED]: onboarding
 * shows a disabled "Location enabled" confirmation there (a one-time setup step, never meaningfully
 * revisited in the ordinary flow), where this row keeps the SAME plain, always-tappable
 * "Use my location" button a granted user already had before this task — tapping it re-resolves a
 * fresh fix (useful after physically moving), and re-invoking an already-granted permission request
 * is a harmless, silent no-op-then-immediate-callback on the OS side, not a redundant prompt.
 * [LocationAskUiState.CAN_ASK]/[LocationAskUiState.NEEDS_SETTINGS] mirror onboarding's identical
 * rationale-then-ask / explain-then-deep-link shapes — per this task's own dispatch ("wire into
 * both ask sites"), Settings gets the SAME rationale path notifications' own Settings row
 * deliberately does NOT (see [AlertsPermissionRow]'s own kdoc for that different, notification-
 * specific controller ruling — this task's brief draws the line differently for location).
 */
@Composable
private fun UseMyLocationAction(modifier: Modifier = Modifier) {
    val locationRequester = koinInject<LocationRequester>()
    val condition = rememberLocationCondition(locationRequester)
    var showRationale by remember { mutableStateOf(false) }

    Column(modifier) {
        when (reduceLocationPermissionState(condition)) {
            LocationAskUiState.GRANTED -> {
                TextButton(onClick = { locationRequester.request() }) {
                    Text("Use my location")
                }
            }
            LocationAskUiState.CAN_ASK -> {
                if (showRationale) {
                    Text(
                        text = "TerraWatch only uses your location to compare it against nearby " +
                            "earthquakes — it never leaves this device.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    TextButton(onClick = { showRationale = false; locationRequester.request() }) {
                        Text("Continue")
                    }
                } else {
                    TextButton(
                        onClick = {
                            if (locationRequester.shouldShowRationale()) {
                                showRationale = true
                            } else {
                                locationRequester.request()
                            }
                        },
                    ) {
                        Text("Use my location")
                    }
                }
            }
            LocationAskUiState.NEEDS_SETTINGS -> {
                Text(
                    text = "Location is off — enable it in system Settings.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                TextButton(onClick = { locationRequester.openSettings() }) {
                    Text("Open Settings")
                }
            }
        }
    }
}

@Composable
private fun ThemeOptions(current: ThemeSetting, onSelect: (ThemeSetting) -> Unit, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth()) {
        ThemeSetting.entries.forEach { setting ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onSelect(setting) }
                    .padding(vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RadioButton(selected = current == setting, onClick = { onSelect(setting) })
                Text(
                    text = setting.displayName(),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.padding(start = 4.dp),
                )
            }
        }
    }
}

private fun ThemeSetting.displayName(): String = when (this) {
    ThemeSetting.SYSTEM -> "System"
    ThemeSetting.LIGHT -> "Light"
    ThemeSetting.DUSK -> "Dusk"
}

@Composable
private fun AboutContent(modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        val bodyColor = MaterialTheme.colorScheme.onSurfaceVariant
        Text("Version $APP_VERSION", style = MaterialTheme.typography.bodyMedium, color = bodyColor)
        Text("Data sources: USGS · EMSC", style = MaterialTheme.typography.bodyMedium, color = bodyColor)
        Text("© OpenStreetMap contributors", style = MaterialTheme.typography.bodyMedium, color = bodyColor)
        Text("Map data © OpenFreeMap", style = MaterialTheme.typography.bodyMedium, color = bodyColor)
    }
}
