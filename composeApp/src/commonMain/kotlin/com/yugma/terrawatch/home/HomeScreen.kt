package com.yugma.terrawatch.home

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.yugma.terrawatch.map.QuakeMap
import com.yugma.terrawatch.ui.format.formatRelativeTime
import com.yugma.terrawatch.ui.theme.TerraRadii
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

// Spec's Offline row (docs/superpowers/specs/2026-08-08-terrawatch-design.md): "Glass banner...
// Map desaturates. Cache stays fully browsable" — Task 8 ships the banner text + the "always
// render the map underneath" half; map desaturation and the Retry link are not part of this task's
// brief and are left for whichever task next touches this banner.
private const val STALE_AFTER_MILLIS = 10 * 60 * 1000L

/**
 * The app's centerpiece: a full-bleed [QuakeMap] fed from [viewModel], with a translucent
 * ("glass", per the Calm Guardian spec's floating-overlay rule) staleness/offline banner floating
 * over the top when the data on screen might be lying to the user. The map itself never goes
 * away — see [HomeUiState]'s own kdoc for why there's no Error state to swap it out for.
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
 * (see QuakeMap.android.kt's own fix note). The `when` below now only picks the *overlay* chrome
 * (spinner vs. banner), never the map itself.
 */
@Composable
fun HomeScreen(viewModel: HomeViewModel) {
    val state by viewModel.state.collectAsState()
    Box(Modifier.fillMaxSize()) {
        val content = state as? HomeUiState.Content
        // Mounted once, for good, regardless of state — see the kdoc above. Empty pins pre-Content
        // is fine: MapLibre's own tile/style fetch still overlaps the initial DB read/network
        // refresh instead of waiting behind the spinner.
        QuakeMap(
            pins = content?.pins ?: emptyList(),
            newQuakeId = null, // TODO(Task 10): wire viewModel.newQuakeIds through here.
            onPinTap = {}, // TODO(Task 9): open the quake detail sheet.
            modifier = Modifier.fillMaxSize(),
        )
        when (val s = state) {
            HomeUiState.Loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
            is HomeUiState.Content -> if (s.refreshFailed || isStale(s.lastUpdatedMillis)) {
                StalenessBanner(
                    lastUpdatedMillis = s.lastUpdatedMillis,
                    modifier = Modifier.align(Alignment.TopCenter).padding(top = 16.dp),
                )
            }
        }
    }
}

@OptIn(ExperimentalTime::class)
private fun isStale(lastUpdatedMillis: Long?): Boolean =
    lastUpdatedMillis != null &&
        Clock.System.now().toEpochMilliseconds() - lastUpdatedMillis > STALE_AFTER_MILLIS

@OptIn(ExperimentalTime::class)
@Composable
private fun StalenessBanner(lastUpdatedMillis: Long?, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(TerraRadii.pill),
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.78f),
        tonalElevation = 4.dp,
        shadowElevation = 2.dp,
    ) {
        val text = lastUpdatedMillis?.let {
            "Updated ${formatRelativeTime(it, Clock.System.now().toEpochMilliseconds())}"
        } ?: "Not updated yet"
        Text(
            text = text,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            style = MaterialTheme.typography.labelLarge,
        )
    }
}
