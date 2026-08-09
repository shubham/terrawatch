package com.yugma.terrawatch.detail

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.yugma.terrawatch.model.Quake
import com.yugma.terrawatch.model.Source
import com.yugma.terrawatch.model.magnitudeBand
import com.yugma.terrawatch.ui.components.BadgeSize
import com.yugma.terrawatch.ui.components.MagnitudeBadge
import com.yugma.terrawatch.ui.components.RevisionBadge
import com.yugma.terrawatch.ui.components.StatRow
import com.yugma.terrawatch.ui.components.TsunamiBanner
import com.yugma.terrawatch.ui.format.formatCoordinates
import com.yugma.terrawatch.ui.format.formatDepthKm
import com.yugma.terrawatch.ui.format.formatDistanceKm
import com.yugma.terrawatch.ui.format.formatMagnitude
import com.yugma.terrawatch.ui.format.formatRelativeTime
import com.yugma.terrawatch.ui.format.revisionNote
import com.yugma.terrawatch.ui.theme.TerraColors
import com.yugma.terrawatch.ui.theme.TerraRadii
import kotlinx.coroutines.launch

/**
 * Task 11: the quake detail sheet - opened over the map (per the design spec's §3.3, "dimmed map,
 * pin stays visible") when a pin, a feed-sheet [com.yugma.terrawatch.ui.components.QuakeCard], or
 * the status pill's alert face is tapped. A plain [ModalBottomSheet] (M3's own scrim + swipe/scrim
 * tap to dismiss) rather than nesting inside [com.yugma.terrawatch.home.HomeScreen]'s existing
 * `BottomSheetScaffold` - that scaffold's sheet is the persistent feed list (Task 9); this is a
 * second, independent, on-demand sheet layered on top of everything, exactly matching the mockup's
 * "detail expands over the map, feed sheet unaffected" treatment.
 *
 * Deliberately dumb about *how* it got its data: [quake]/[distanceKm]/[nowMillis] are all plain
 * values the caller (`HomeScreen`) already has in hand (from
 * `com.yugma.terrawatch.home.QuakeSelectionViewModel.selectedQuake` [Task 3, Plan 3 — split out of
 * `HomeViewModel`] / `homeLocation` / the screen's own now-ticker) - this composable does no
 * lookups of its own, so it stays trivially previewable/testable in isolation and carries no
 * platform-specific code.
 *
 * [onShare] receives the fully-built share string (not just a "share was tapped" signal) because
 * building that string needs exactly the inputs this composable already has ([quake] +
 * [nowMillis]) - the caller only has to forward the string to the platform action
 * ([com.yugma.terrawatch.share.shareQuakeText]), keeping that platform call itself at the
 * `HomeScreen` call site (same layering as `onDebugLongPress`/`onPinTap`) rather than reaching for
 * a platform API from inside this shared-UI file.
 *
 * Mockup deviation (deliberate): the approved mockup's bottom stat list includes a "Shaking
 * intensity (MMI)" row - there is no such field anywhere in the [Quake] model (USGS/EMSC shake-map
 * intensity was never ingested), so that row is replaced with a conditional "Felt" row instead
 * (shown only when [Quake.felt] is non-null), and the mockup's "Save"/"Directions" buttons are
 * dropped (no saved-places/maps-deeplink feature exists yet) - Share + Dismiss only, per this
 * task's own brief.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DetailSheet(
    quake: Quake,
    distanceKm: Double?,
    nowMillis: Long,
    onShare: (String) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // Fix Round 1 (review finding): a bare `onClick = onDismiss` on the Dismiss button used to
    // invoke onDismissRequest directly, skipping M3's own hide animation entirely - the sheet
    // snapped shut instantly instead of sliding down. Swipe-to-dismiss and scrim-tap never had
    // this problem: ModalBottomSheet's internal handling for those two gestures already runs
    // `sheetState.hide()` itself and only calls onDismissRequest once that animation completes.
    // Driving the same sheetState explicitly from the button - hide() first, onDismiss() chained
    // via invokeOnCompletion - gives the button-tap path the identical animated close instead of
    // a third, unanimated one.
    //
    // Fix Round 2: guard the onDismiss callback with !sheetState.isVisible to catch edge case
    // where hide() is cancelled by a racing interaction (user swipes or taps the scrim *while* the
    // Dismiss button click is queued), preventing a spurious second dismiss callback. M3's own
    // scrim and swipe internal paths use the same guard pattern.
    val sheetState = rememberModalBottomSheetState()
    val scope = rememberCoroutineScope()
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState, modifier = modifier) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .padding(bottom = 24.dp),
        ) {
            MagnitudeHero(quake = quake, nowMillis = nowMillis)
            Spacer(Modifier.height(16.dp))
            StatTrio(quake = quake, distanceKm = distanceKm)
            Spacer(Modifier.height(12.dp))
            TsunamiBanner(tsunami = quake.tsunami)
            Spacer(Modifier.height(12.dp))
            DetailStatList(quake = quake)
            Spacer(Modifier.height(16.dp))
            ActionRow(
                onShare = { onShare(buildShareText(quake, nowMillis)) },
                onDismiss = { scope.launch { sheetState.hide() }.invokeOnCompletion { if (!sheetState.isVisible) onDismiss() } },
            )
        }
    }
}

/** Big band-colored [MagnitudeBadge] + place + relative time + (conditionally) [RevisionBadge].
 * "Absolute-ish time" per the brief keeps to [formatRelativeTime] alone (its own "MMM d" fallback
 * past a week already reads as an absolute date) rather than building separate local-timezone
 * date/HH:mm infrastructure the brief explicitly calls out of scope. */
@Composable
private fun MagnitudeHero(quake: Quake, nowMillis: Long) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(13.dp)) {
        MagnitudeBadge(mag = quake.mag, band = magnitudeBand(quake.mag), size = BadgeSize.Large)
        Column {
            Text(
                text = quake.place,
                style = MaterialTheme.typography.titleLarge, // TerraTypography already bolds this role.
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = formatRelativeTime(quake.timeMillis, nowMillis),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            val note = revisionNote(quake.revisions, nowMillis)
            if (note != null) {
                Spacer(Modifier.height(4.dp))
                RevisionBadge(text = note)
            }
        }
    }
}

/** Three equal cards: depth / distance-from-you / felt-report count - each already unit-suffixed
 * by its own formatter (e.g. "10.0 km"), so the label underneath stays a plain noun rather than
 * repeating the unit. Falls back to an em dash, never "null"/blank, when a value is unknown
 * (depth is realistically always present; distance needs a resolved home location; felt is only
 * ever reported for well-observed quakes). */
@Composable
private fun StatTrio(quake: Quake, distanceKm: Double?) {
    Row(horizontalArrangement = Arrangement.spacedBy(7.dp), modifier = Modifier.fillMaxWidth()) {
        StatTrioCard(value = formatDepthKm(quake.depthKm), label = "DEPTH", modifier = Modifier.weight(1f))
        StatTrioCard(
            value = distanceKm?.let { formatDistanceKm(it) } ?: "—",
            label = "AWAY",
            modifier = Modifier.weight(1f),
        )
        StatTrioCard(
            value = quake.felt?.toString() ?: "—",
            label = "FELT IT",
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun StatTrioCard(value: String, label: String, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(TerraRadii.card),
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 1.dp,
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(vertical = 9.dp, horizontal = 6.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = value,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** Coordinates + source always show; Felt only when [Quake.felt] is non-null (the trio above
 * already shows an em-dash placeholder for the common "unknown" case - this list only repeats
 * Felt when there is a genuine, spelled-out number worth restating as "N reports"). */
@Composable
private fun DetailStatList(quake: Quake) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(TerraRadii.card),
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 1.dp,
    ) {
        Column {
            StatRow(label = "Coordinates", value = formatCoordinates(quake.lat, quake.lon))
            HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.2f))
            StatRow(label = "Source", value = sourceLine(quake.sources))
            val felt = quake.felt
            if (felt != null) {
                HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.2f))
                StatRow(label = "Felt", value = "$felt reports")
            }
        }
    }
}

@Composable
private fun ActionRow(onShare: () -> Unit, onDismiss: () -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
        Button(
            onClick = onShare,
            modifier = Modifier.weight(1f),
            // Fixed Ink/Canvas per the brief ("filled, Ink"), not MaterialTheme.colorScheme.primary -
            // primary flips to Canvas-on-Ink in dark mode (see TerraTheme.kt), but this button is
            // meant to read as a constant brand action regardless of theme, same as magnitude colors.
            colors = ButtonDefaults.buttonColors(containerColor = TerraColors.Ink, contentColor = TerraColors.Canvas),
        ) {
            Text("Share")
        }
        OutlinedButton(onClick = onDismiss, modifier = Modifier.weight(1f)) {
            Text("Dismiss")
        }
    }
}

/** "USGS · confirmed by EMSC" when both agencies have reported this quake; the single agency's
 * name otherwise. [Quake.sources] is never empty in practice (every quake originates from at least
 * one feed), but an empty map still degrades to a plain, honest label rather than blank text. */
private fun sourceLine(sources: Map<Source, String>): String = when {
    Source.USGS in sources && Source.EMSC in sources -> "USGS · confirmed by EMSC"
    Source.USGS in sources -> "USGS"
    Source.EMSC in sources -> "EMSC"
    else -> "Unreported"
}

/**
 * Task 11 share text. Fix Round 1 (review finding): this kdoc used to claim the shipped text was
 * "exact wording dictated by the brief" - it isn't, quite. The brief's own dictated example was
 * "M 6.1 — Mindanao, Philippines. Depth 10 km. via TerraWatch"; what ships here deliberately
 * enriches that skeleton with a relative-time clause ("2 h ago.") inserted between the depth and
 * the "via TerraWatch" sign-off - e.g. "M 6.1 — Mindanao, Philippines. Depth 10.0 km. 2 h ago. via
 * TerraWatch" - since a share message with no timestamp at all is less useful than one that says
 * how fresh the quake is. This documents the actual, shipped format, not the brief's bare example.
 * Built entirely from formatters already unit-tested elsewhere
 * ([formatMagnitude]/[formatDepthKm]/[formatRelativeTime]), so the only residual risk is the
 * concatenation itself - `internal` (not `private`) so a test can pin the exact string.
 */
internal fun buildShareText(quake: Quake, nowMillis: Long): String =
    "M ${formatMagnitude(quake.mag)} — ${quake.place}. " +
        "Depth ${formatDepthKm(quake.depthKm)}. " +
        "${formatRelativeTime(quake.timeMillis, nowMillis)}. via TerraWatch"
