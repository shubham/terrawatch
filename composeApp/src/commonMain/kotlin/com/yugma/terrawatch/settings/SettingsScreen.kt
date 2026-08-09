package com.yugma.terrawatch.settings

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.yugma.terrawatch.data.ThemeSetting
import com.yugma.terrawatch.location.CityPickerDialog
import com.yugma.terrawatch.location.LocationRequester
import com.yugma.terrawatch.location.canRequestLocation
import com.yugma.terrawatch.model.GeoPoint
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

private const val APP_VERSION = "0.3.0"

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
 */
@Composable
fun SettingsScreen(
    onBack: () -> Unit = {},
    viewModel: SettingsViewModel = koinViewModel(),
) {
    val nearbyRadiusKm by viewModel.nearbyRadiusKm.collectAsState()
    val minMag by viewModel.minMag.collectAsState()
    val theme by viewModel.theme.collectAsState()
    val homeLocation by viewModel.homeLocation.collectAsState()
    var showCityPicker by remember { mutableStateOf(false) }
    val locationRequester = koinInject<LocationRequester>()

    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
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
                }
                SettingsCard {
                    SettingsSectionLabel("PLACE")
                    PlaceRow(
                        homeLocation = homeLocation,
                        canUseMyLocation = canRequestLocation(),
                        onChangeClick = { showCityPicker = true },
                        onUseMyLocationClick = { locationRequester.request() },
                    )
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
    }
}

@Composable
private fun SettingsHeader(onBack: () -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onBack) {
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
 * The live label updates on every recomposition — [radiusKm] is already a Compose `State` read
 * (via [SettingsScreen]'s own `collectAsState()`), so this re-runs exactly when the stored value
 * itself changes, no separate local drag-state cache needed ([AlertRuleStore]'s writes are
 * synchronous local DB writes, fast enough that binding the slider directly to the persisted value
 * introduces no perceptible lag).
 */
@Composable
private fun RadiusSlider(radiusKm: Double, onRadiusChange: (Double) -> Unit, modifier: Modifier = Modifier) {
    val index = closestRadiusStepIndex(radiusKm)
    Column(modifier.fillMaxWidth()) {
        Text(
            text = "Nearby means within ${formatCount(RADIUS_STEPS_KM[index].roundToInt().toLong())} km",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Slider(
            value = index.toFloat(),
            onValueChange = { newValue ->
                val snappedIndex = newValue.roundToInt().coerceIn(RADIUS_STEPS_KM.indices)
                onRadiusChange(RADIUS_STEPS_KM[snappedIndex])
            },
            valueRange = 0f..(RADIUS_STEPS_KM.size - 1).toFloat(),
            // 5 stops = start + 3 intermediate + end.
            steps = RADIUS_STEPS_KM.size - 2,
        )
    }
}

@Composable
private fun MinMagSlider(minMag: Double, onMinMagChange: (Double) -> Unit, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth()) {
        Text(
            text = "Alerts for magnitude ${formatMagnitude(minMag)}+ nearby",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Slider(
            value = minMag.toFloat(),
            onValueChange = { onMinMagChange(snapToHalfMagnitude(it.toDouble())) },
            valueRange = 3.0f..6.0f,
            // 3.0..6.0 in steps of 0.5 = 7 stops = start + 5 intermediate + end.
            steps = 5,
        )
    }
}

/**
 * The saved-place row (Task 7 brief: "shows current home... store only has GeoPoint: show
 * '%.2f, %.2f'"). Reuses [formatCoordinates] (already TDD'd, already KMP-safe — no
 * `String.format`, unavailable on wasmJs — see that function's own kdoc) rather than hand-rolling a
 * second two-decimal formatter: it renders the identical precision the brief asks for, with a
 * nicer N/E/S/W hemisphere convention instead of a bare signed pair — the same function
 * `DetailSheet`'s own "Coordinates" row already uses for a quake's location.
 */
@Composable
private fun PlaceRow(
    homeLocation: GeoPoint?,
    canUseMyLocation: Boolean,
    onChangeClick: () -> Unit,
    onUseMyLocationClick: () -> Unit,
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
        // LocationAskDialog's identical "Use my location" gating, so this button never renders
        // somewhere it couldn't possibly do anything.
        if (canUseMyLocation) {
            TextButton(onClick = onUseMyLocationClick, modifier = Modifier.padding(top = 4.dp)) {
                Text("Use my location")
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
