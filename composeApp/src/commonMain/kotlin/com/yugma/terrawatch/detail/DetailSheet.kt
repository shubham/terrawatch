package com.yugma.terrawatch.detail

import androidx.compose.foundation.clickable
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
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.yugma.terrawatch.model.Quake
import com.yugma.terrawatch.model.Source
import com.yugma.terrawatch.model.magnitudeBand
import com.yugma.terrawatch.motion.LocalReducedMotion
import com.yugma.terrawatch.network.NewsArticle
import com.yugma.terrawatch.news.NewsUiState
import com.yugma.terrawatch.share.isPackageInstalled
import com.yugma.terrawatch.ui.components.BadgeSize
import com.yugma.terrawatch.ui.components.MagnitudeBadge
import com.yugma.terrawatch.ui.components.RevisionBadge
import com.yugma.terrawatch.ui.components.SkeletonCard
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
import com.yugma.terrawatch.ui.theme.tabularFigures
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
 *
 * Plan 4 Task 4b (share targets): [onSharePackaged] and the quick-share row it backs are additive,
 * defaulted params - every pre-existing call site (3 production screens + `ComponentsTest`'s own
 * instrumented render) keeps compiling and behaving identically without supplying them.
 * [visibleShareTargets] (computed once via `remember`, not re-queried every recomposition) decides
 * which of [ShareTarget]'s 3 apps get a button at all - an absent app is fully OMITTED, never
 * rendered disabled, per the brief's own "hidden (not grayed)" wording. Reuses [buildShareText] -
 * the SAME string [onShare]'s chooser button sends is what [onSharePackaged] hands each
 * packaged-app tap, computed once per sheet rather than separately per button.
 *
 * Plan 4 Task 5 (news): [newsState] is likewise additive/defaulted - [NewsUiState.Hidden] renders
 * nothing at all, matching "no-results -> section hidden" exactly (see that sealed interface's own
 * kdoc for why [NewsUiState.Content] is never constructed empty in the first place).
 * [onNewsArticleClick] receives a tapped article's raw `url`, mirroring [onShare]'s "hand the
 * caller the built value, not just a tap signal" shape - the caller wires it to
 * `com.yugma.terrawatch.share.openUrl`, keeping the platform `ACTION_VIEW` call at the screen call
 * site exactly like `onShare`'s platform call already stays there, not in here.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DetailSheet(
    quake: Quake,
    distanceKm: Double?,
    nowMillis: Long,
    onShare: (String) -> Unit,
    onDismiss: () -> Unit,
    onSharePackaged: (packageName: String, text: String) -> Unit = { _, _ -> },
    newsState: NewsUiState = NewsUiState.Hidden,
    onNewsArticleClick: (url: String) -> Unit = {},
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
    val shareText = buildShareText(quake, nowMillis)
    // `remember` (no key): isPackageInstalled's PackageManager query only needs to run once per
    // sheet-open, not once per recomposition - this composable is added/removed from composition
    // wholesale each time the caller's `selectedQuake` flips to/from non-null (see this composable's
    // own "does no lookups of its own" kdoc paragraph above), so a fresh sheet-open always re-runs
    // this exactly once, and installed-app state cannot plausibly change while one stays open.
    val visibleTargets = remember { visibleShareTargets(::isPackageInstalled) }
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
            if (newsState != NewsUiState.Hidden) {
                Spacer(Modifier.height(12.dp))
                NewsSection(
                    newsState = newsState,
                    nowMillis = nowMillis,
                    onArticleClick = onNewsArticleClick,
                    reducedMotion = LocalReducedMotion.current,
                )
            }
            if (visibleTargets.isNotEmpty()) {
                Spacer(Modifier.height(12.dp))
                QuickShareRow(targets = visibleTargets, onClick = { target -> onSharePackaged(target.packageName, shareText) })
            }
            Spacer(Modifier.height(16.dp))
            ActionRow(
                onShare = { onShare(shareText) },
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
                // Task 10 (item a): threads the real reduced-motion signal into core:ui's
                // RevisionBadge - see that composable's own kdoc for why it can't read
                // LocalReducedMotion itself (module boundary: core:ui can't depend on composeApp).
                RevisionBadge(text = note, reducedMotion = LocalReducedMotion.current)
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
                // Task 10 (item f): one of the brief's named "stat values" (depth/distance/felt).
                style = MaterialTheme.typography.titleMedium.tabularFigures(),
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

/**
 * Plan 4 Task 4b: the 3 quick-share targets named by the brief - a fixed, ordered list (WhatsApp,
 * X, Threads), each a package-targeted `ACTION_SEND` rather than the generic chooser [onShare]
 * already opens. Package names are pinned exactly as specified, not guessed:
 * `com.whatsapp`/`com.twitter.android`/`com.instagram.barcelona` (Threads still ships under
 * Instagram's own former package id).
 */
enum class ShareTarget(val label: String, val packageName: String) {
    WHATSAPP("WhatsApp", "com.whatsapp"),
    X("X", "com.twitter.android"),
    THREADS("Threads", "com.instagram.barcelona"),
}

/**
 * The one pure, TDD'd piece of the quick-share row: which of [ShareTarget.entries] should get a
 * button at all, given an installed-app check. Takes [isInstalled] as a plain function parameter
 * (rather than calling [com.yugma.terrawatch.share.isPackageInstalled] directly) purely so this can
 * be unit-tested against a fake checker with no platform/PackageManager involved - [DetailSheet]'s
 * own real call site always passes the real expect/actual function reference.
 *
 * Absent apps are OMITTED from the result entirely (never included-but-flagged), matching the
 * brief's own "absent app → button hidden (not grayed)" instruction directly at this layer -
 * `QuickShareRow` below has no separate disabled-state branch to get wrong because there is
 * nothing left for it to render for a missing app.
 */
internal fun visibleShareTargets(isInstalled: (String) -> Boolean): List<ShareTarget> =
    ShareTarget.entries.filter { isInstalled(it.packageName) }

/** Equal-width buttons, one per [targets] entry - a Row of 1-3 [OutlinedButton]s rather than a
 * fixed 3-slot layout, since [targets] is already pre-filtered to only the installed apps (see
 * [visibleShareTargets]) and this composable itself has no notion of "the other 2 are absent." */
@Composable
private fun QuickShareRow(targets: List<ShareTarget>, onClick: (ShareTarget) -> Unit, modifier: Modifier = Modifier) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = modifier.fillMaxWidth()) {
        targets.forEach { target ->
            OutlinedButton(onClick = { onClick(target) }, modifier = Modifier.weight(1f)) {
                Text(target.label)
            }
        }
    }
}

/** Small, muted section label - the same quiet register [com.yugma.terrawatch.insights.InsightsScreen]'s
 * own `CardEyebrow` already established for this app's card headers (labelSmall/onSurfaceVariant/
 * letter-spaced), duplicated here rather than exported across modules for one three-word label. */
@Composable
private fun SectionEyebrow(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        letterSpacing = 1.sp,
        modifier = modifier,
    )
}

/**
 * Plan 4 Task 5: DetailSheet's "In the news" section - [NewsUiState.Hidden] is handled by the
 * caller (see [DetailSheet]'s own `if (newsState != NewsUiState.Hidden)` guard, so this function is
 * never even called for that case); [NewsUiState.Loading] reuses [SkeletonCard] verbatim ("loading
 * shimmer reuse" per the brief - the same shimmer every other Loading state in this app already
 * uses, not a bespoke headline-shaped placeholder); [NewsUiState.Content] renders up to 3 headlines
 * in one card, each tappable straight to [onArticleClick].
 */
@Composable
private fun NewsSection(
    newsState: NewsUiState,
    nowMillis: Long,
    onArticleClick: (String) -> Unit,
    reducedMotion: Boolean,
    modifier: Modifier = Modifier,
) {
    Column(modifier.fillMaxWidth()) {
        SectionEyebrow("IN THE NEWS")
        Spacer(Modifier.height(8.dp))
        when (newsState) {
            NewsUiState.Hidden -> Unit // caller already guards this case; defensive no-op.
            NewsUiState.Loading -> Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                repeat(3) { SkeletonCard(reducedMotion = reducedMotion) }
            }
            is NewsUiState.Content -> Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(TerraRadii.card),
                color = MaterialTheme.colorScheme.surface,
                tonalElevation = 1.dp,
            ) {
                Column {
                    newsState.articles.forEachIndexed { index, article ->
                        NewsRow(article = article, nowMillis = nowMillis, onClick = { onArticleClick(article.url) })
                        if (index != newsState.articles.lastIndex) {
                            HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.2f))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun NewsRow(article: NewsArticle, nowMillis: Long, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 10.dp),
    ) {
        Text(
            text = article.title,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.height(2.dp))
        Text(
            text = "${article.domain} · ${formatRelativeTime(article.seenAtMillis, nowMillis)}",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
