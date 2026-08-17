package com.yugma.terrawatch.detail

import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.yugma.terrawatch.model.Quake
import com.yugma.terrawatch.model.Source
import com.yugma.terrawatch.model.magnitudeBand
import com.yugma.terrawatch.motion.LocalReducedMotion
import com.yugma.terrawatch.network.NewsArticle
import com.yugma.terrawatch.news.NewsUiState
import com.yugma.terrawatch.share.appIcon
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
import com.yugma.terrawatch.ui.theme.TerraRadii
import com.yugma.terrawatch.ui.theme.tabularFigures

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
 * dropped (no saved-places/maps-deeplink feature exists yet) - Share only, per this task's own
 * brief (see [ShareButton]'s own kdoc for why the sheet no longer also ships a custom Dismiss
 * button here).
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
 * nothing at all, matching "no selection / below the magnitude floor -> section hidden" exactly
 * (see that sealed interface's own kdoc for why [NewsUiState.Content] is never constructed empty in
 * the first place). [onNewsArticleClick] receives a tapped article's raw `url`, mirroring
 * [onShare]'s "hand the caller the built value, not just a tap signal" shape - the caller wires it
 * to `com.yugma.terrawatch.share.openUrl`, keeping the platform `ACTION_VIEW` call at the screen
 * call site exactly like `onShare`'s platform call already stays there, not in here. The SAME
 * callback backs [NewsUiState.Empty]'s "More on USGS" link row (Task 2b) - it's still just a raw
 * URL tap-through, no new platform call needed at any call site.
 *
 * Task 2b (dogfooding fix, task-2b-news-fix-report.md): [onNewsRetry] is [NewsUiState.Error]'s
 * Retry action - additive/defaulted (a no-op) for the identical "every pre-existing call site keeps
 * compiling" reason [onSharePackaged] documents just above, wired by every real caller to
 * `DetailNewsViewModel::retry`.
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
    onNewsRetry: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    // UI polish findings (docs/superpowers/plans/2026-08-16-ui-polish-findings.md, Part 2): this
    // sheet used to also ship a custom "Dismiss" OutlinedButton here, driven by a manual
    // `sheetState.hide()` + `invokeOnCompletion` dance (Fix Round 1/2) so a button-tap got the same
    // animated close scrim-tap/swipe-to-dismiss already got for free. That whole custom path is
    // gone now, not just visually - reading the real M3 1.8.2 `ModalBottomSheet.kt` source (this
    // project's actual resolved dependency, not the marketing docs) confirms TWO independent,
    // already-wired, TalkBack-actionable dismiss affordances exist with zero app code: the drag
    // handle's wrapper carries a real, named `dismiss()` accessibility action
    // (`Modifier.semantics(mergeDescendants = true) { dismiss(dismissActionLabel) { ... } }`), and
    // the scrim independently carries its own `contentDescription` + `onClick`. A fourth,
    // app-owned dismiss button was pure accessibility redundancy, not an added capability - and it
    // was visually a dead ringer for the WhatsApp quick-share button next to it (identical
    // `OutlinedButton` styling, one row apart), which the findings doc identifies as the likely
    // real source of the "extra dismiss button... feels weird" dogfooding complaint. Scrim-tap,
    // swipe-down, and the drag handle's own TalkBack action remain fully intact - all
    // library-guaranteed, none of them app code this change gives up.
    // review-round-3 (Samsung dogfooding: "sheet not fully visible" + "Share button overlapping the
    // nav bar" + "takes 2 back presses to close"): all three trace to ONE missing config, confirmed
    // by reading this project's actual resolved androidx.compose.material3:material3:1.8.2
    // ModalBottomSheet.kt/SheetDefaults.kt sources (gradle cache, not guessed from docs):
    //
    // - `rememberModalBottomSheetState()`'s default `skipPartiallyExpanded = false` gives this sheet
    //   a PartiallyExpanded anchor whenever its measured content is taller than half the screen
    //   (`ModalBottomSheetContent`'s own `draggableAnchors`: `if (sheetSize.height > fullHeight / 2
    //   && !skipPartiallyExpanded) PartiallyExpanded at fullHeight / 2f`) — true on essentially any
    //   phone screen once news/quick-share are showing (a real device screenshot already documented
    //   this content "reaching the literal bottom edge" — see this composable's own `verticalScroll`
    //   kdoc below).
    // - `SheetState.show()` (called unconditionally on first composition by `ModalBottomSheet`
    //   itself) PREFERS that PartiallyExpanded anchor over Expanded whenever one exists (`val
    //   targetValue = when { hasPartiallyExpandedState -> PartiallyExpanded; else -> Expanded }`) —
    //   so this sheet always opened parked at the halfway line, not full height. Content below that
    //   line is measured/laid out at full height regardless (drag only changes the sheet's Y
    //   *offset*, not its size), so the back half of it renders below the display's physical bottom
    //   edge entirely: "not fully visible" is literal here, not a figure of speech. Wherever that
    //   half-height cutoff happened to land (device/content-dependent) could bisect content right at
    //   the screen's bottom edge — exactly where a 3-button nav bar overlays app content in
    //   edge-to-edge mode — which reads as "Share is behind the nav bar" even though the real
    //   Expanded state already reserves correct nav-bar clearance (M3's own default
    //   `contentWindowInsets = { BottomSheetDefaults.windowInsets }` = `WindowInsets.safeDrawing
    //   .only(Bottom)`, applied via `windowInsetsPadding(...)` on the sheet's own content column in
    //   `ModalBottomSheetContent` — verified in source; nothing wrong with it, it just never got
    //   reached before the user manually dragged past the halfway resting point).
    // - Separately, `ModalBottomSheetDialog`'s back-press handler (`ModalBottomSheet.kt`) special-
    //   cases exactly this: `if (currentValue == Expanded && hasPartiallyExpandedState)
    //   partialExpand() else { hide(); onDismissRequest() }` — the FIRST back press from Expanded
    //   only collapses to PartiallyExpanded and never calls this composable's own [onDismiss]; only
    //   the SECOND press (now not Expanded) actually hides and dismisses. That is exactly the
    //   reported "takes 2 back presses."
    //
    // `skipPartiallyExpanded = true` removes the PartiallyExpanded anchor outright (the
    // `!skipPartiallyExpanded` guard above then never lets it exist), fixing all three symptoms as
    // one config change rather than three patches: `show()` has no PartiallyExpanded anchor left to
    // prefer, so the sheet always opens straight to Expanded (full content immediately visible, no
    // manual drag-up needed); with no PartiallyExpanded state, `hasPartiallyExpandedState` is always
    // false, so EVERY back press takes the `hide()` branch — one press, from anywhere, fully closes
    // it, matching Material's own guidance and the user's stated expectation. The existing
    // `verticalScroll` below still cleanly handles content taller than the display in the Expanded
    // state — this doesn't remove or fight that, it only removes the resting state that was hiding
    // part of it. Deliberately NOT a hand-rolled `BackHandler`/manual dismiss dance: that would just
    // reimplement (and risk drifting from) what this one library flag already gives for free —
    // `ModalBottomSheet.android.kt`'s own back-callback wiring flows through this same `sheetState`
    // unchanged. Device-verified on OnePlus 9R (98bc1cd8); Samsung (the reporting device) unavailable
    // for direct re-verification — see docs/qa/review-round-3/RESULTS.md's residual-risk note.
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val shareText = buildShareText(quake, nowMillis)
    // `remember` (no key): isPackageInstalled's PackageManager query only needs to run once per
    // sheet-open, not once per recomposition - this composable is added/removed from composition
    // wholesale each time the caller's `selectedQuake` flips to/from non-null (see this composable's
    // own "does no lookups of its own" kdoc paragraph above), so a fresh sheet-open always re-runs
    // this exactly once, and installed-app state cannot plausibly change while one stays open.
    val visibleTargets = remember { visibleShareTargets(::isPackageInstalled) }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState, modifier = modifier) {
        // UI polish findings, Part 2 (reachability bug, found in passing): this root Column had no
        // scroll modifier at all - a real device screenshot
        // (docs/qa/plan-5-device-matrix/store/detail-sheet-clean-news-error-state.png) shows content
        // already reaching the literal bottom edge of a 2400px screen while the sheet is fully
        // expanded, meaning Share/the quick-share row could become physically unreachable (by touch
        // or by TalkBack linear navigation) on a shorter device or any quake with a few more
        // populated fields. `verticalScroll` cooperates with `ModalBottomSheet`'s own drag-to-dismiss
        // nested-scroll handling - the standard M3 pattern (scrolled-to-top + continued downward drag
        // hands off to the sheet's own swipe-to-dismiss, same as any M3 bottom sheet with scrollable
        // content) - so the handle still drags the whole sheet while this content scrolls
        // independently once it's taller than the sheet's available height. Tradeoff, noted
        // honestly: this cooperation is the well-established M3 contract, not something re-verified
        // against this project's own real `ModalBottomSheet.kt` source this pass, and the actual
        // drag-vs-scroll feel (not just the code-level wiring) is device-verification-pending, same
        // as every other UI change in this pass.
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
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
                    onRetry = onNewsRetry,
                    reducedMotion = LocalReducedMotion.current,
                )
            }
            if (visibleTargets.isNotEmpty()) {
                Spacer(Modifier.height(12.dp))
                QuickShareRow(targets = visibleTargets, onClick = { target -> onSharePackaged(target.packageName, shareText) })
            }
            Spacer(Modifier.height(16.dp))
            ShareButton(onShare = { onShare(shareText) })
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

/**
 * The sheet's one primary action row. Used to be "Share" (filled, fixed Ink/Canvas) + a redundant
 * "Dismiss" `OutlinedButton` side by side (see [DetailSheet]'s own kdoc for why the latter is gone).
 * With Dismiss removed, Share is the sheet's only primary-row button - full width, not
 * `Modifier.weight(1f)` in a two-item Row it no longer shares.
 *
 * UI polish findings, Part 1 row 7: the fixed `TerraColors.Ink`/`TerraColors.Canvas` pair this
 * button used to hardcode measured **1.00:1** non-text contrast against the dark-theme sheet
 * background (`Ink` fill on `DuskCard` - functionally invisible as a shape in dark mode; confirmed
 * both by WCAG math and by direct visual read of a real device screenshot). The original reasoning
 * for hardcoding the pair rather than using `colorScheme.primary` was to keep Share reading as "a
 * constant brand action regardless of theme" - reasonable in isolation, but it produced a real,
 * measured contrast failure that a per-theme role does not: `MaterialTheme.colorScheme.primary`/
 * `onPrimary` already resolve to the *exact same* Ink-fill/Canvas-text pair in LIGHT theme (byte
 * identical - zero visual change there) and correctly flip to Canvas-fill/Ink-text in DARK theme
 * (see `TerraTheme.kt`), which measures **~15:1** against `DuskCard` instead of 1.00:1. Token choice
 * over a manual border/elevation treatment, per the findings doc's own recommendation.
 */
@Composable
private fun ShareButton(onShare: () -> Unit, modifier: Modifier = Modifier) {
    Button(
        onClick = onShare,
        modifier = modifier.fillMaxWidth(),
        colors = ButtonDefaults.buttonColors(
            containerColor = MaterialTheme.colorScheme.primary,
            contentColor = MaterialTheme.colorScheme.onPrimary,
        ),
    ) {
        Text("Share")
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

/**
 * UI polish findings, Part 2: [QuickShareRow]'s per-target TalkBack label, e.g. "Share via
 * WhatsApp" - needed once the row is demoted to icon-only chips with no visible text label of
 * their own to fall back on. `internal`, not `private`, so a jvmTest can pin this exact string
 * shape without spinning up Compose - same "TDD what's pure, even if trivial" convention this
 * codebase already applies to [com.yugma.terrawatch.onboarding.defaultRuleSummary] and
 * [com.yugma.terrawatch.ui.components.magnitudeContentDescription].
 */
internal fun shareTargetContentDescription(target: ShareTarget): String = "Share via ${target.label}"

/**
 * UI polish findings, Part 2: [QuickShareRow]'s per-target visible glyph. This app has an
 * established "no icon-font/icon-library dependency" posture ([com.yugma.terrawatch.ui.components.
 * StatusShield]'s hand-drawn `CheckGlyph`/`LocationPinGlyph`, `nav/NavIcons.kt`'s tab icons) - a
 * bold single-letter monogram is the simplest, dependency-free way to give WhatsApp/X/Threads a
 * distinct-per-target glyph without drawing 3 trademarked brand marks by hand or adding an icon
 * library for 3 icons. `target.label.take(1)` already gives a correct, non-colliding letter for
 * all 3 current entries ('W'/'X'/'T') with no separate per-target glyph table to keep in sync.
 */
internal fun shareTargetMonogram(target: ShareTarget): String = target.label.take(1)

/**
 * UI polish findings, Part 2 (doc's own recommendation, element-by-element): demoted from 3
 * full-width `OutlinedButton`s (identical visual weight to the sheet's old "Dismiss" button - the
 * finding's own likely cause of "feels weird") to compact, icon-only 48dp `FilledTonalIconButton`s,
 * so the sheet's grammar reads as "one primary Share action + small shortcut icons," never "a row
 * of look-alike buttons." `Modifier.size(48.dp)` is spec's own literal touch-target floor, matching
 * [com.yugma.terrawatch.ui.components.StatusShield]'s identical `MIN_TOUCH_TARGET` convention.
 * [visibleShareTargets]'s existing installed-app-check/omit-not-grey logic is untouched - this is a
 * visual-treatment change only, not a logic change (Plan 4 Task 4b's pure fns are reused as-is).
 *
 * `Modifier.semantics(mergeDescendants = true) { contentDescription = ... }` on the button +
 * `Modifier.clearAndSetSemantics {}` on the inner monogram `Text` is the exact double-read fix this
 * codebase already establishes for a clickable container with a custom sentence and a decorative
 * child (`StatusShield.AlertContent`'s `MagnitudeBadge` clear, `FeedSheet.kt`'s reveal-chip `Text`
 * clear) - without it, TalkBack would announce the bare monogram letter ("W") in addition to (or
 * instead of) the real "Share via WhatsApp" sentence.
 *
 * feat/feed-visit-ux, "real share app icons" (user: "use the icons of the app, not the
 * abbreviations"): each button now renders [target]'s real installed-app icon
 * ([com.yugma.terrawatch.share.appIcon], android-only - see that function's own common kdoc) at
 * 28dp inside the unchanged 48dp touch target, falling back to the pre-existing letter monogram
 * only if the icon genuinely can't be loaded (see [appIcon]'s own null contract - this is NOT the
 * "app not installed" case, [visibleTargets] upstream already excludes that entirely). The fallback
 * itself is a bare `icon ?: monogram` null-check - looked for a non-trivial pure decision to
 * extract and TDD the way [visibleShareTargets]/[shareTargetMonogram] already are, and there isn't
 * one here: unlike THOSE two (a real filter, a real per-target letter mapping), "did loading
 * succeed" has exactly one bit of real information and one branch, already fully expressed by the
 * null-check itself - wrapping it in a same-shaped named function would test the wrapper, not add
 * coverage. `remember(target)` (same "PackageManager query only needs to run once" reasoning
 * [visibleTargets]'s own `remember` above this function already uses) - not `remember(Unit)` across
 * the whole `forEach`, since each target's icon lookup is independent.
 */
@Composable
private fun QuickShareRow(targets: List<ShareTarget>, onClick: (ShareTarget) -> Unit, modifier: Modifier = Modifier) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = modifier.fillMaxWidth()) {
        targets.forEach { target ->
            val icon = remember(target) { appIcon(target.packageName) }
            FilledTonalIconButton(
                onClick = { onClick(target) },
                modifier = Modifier
                    .size(48.dp)
                    .semantics(mergeDescendants = true) {
                        contentDescription = shareTargetContentDescription(target)
                    },
            ) {
                if (icon != null) {
                    Image(
                        bitmap = icon,
                        // Decorative - the button's own merged semantics above already carries the
                        // real "Share via WhatsApp" sentence; a second contentDescription here would
                        // risk the identical double-read the monogram Text's own clearAndSetSemantics
                        // below already guards against.
                        contentDescription = null,
                        modifier = Modifier.size(28.dp),
                    )
                } else {
                    Text(
                        text = shareTargetMonogram(target),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.clearAndSetSemantics {},
                    )
                }
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
 *
 * Task 2b (dogfooding fix, task-2b-news-fix-report.md): [NewsUiState.Empty] and [NewsUiState.Error]
 * both render [NewsMessageCard] - the same one-line-message-plus-optional-action shape as
 * `HistoryScreen`'s own `HistoryFooter`'s `loadMoreFailed` row - so the shimmer ALWAYS resolves to
 * something visible under the "IN THE NEWS" eyebrow (real headlines, the zero-dep USGS fallback
 * link, or a Retry row), never quietly back to nothing.
 */
@Composable
private fun NewsSection(
    newsState: NewsUiState,
    nowMillis: Long,
    onArticleClick: (String) -> Unit,
    onRetry: () -> Unit,
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
            is NewsUiState.Empty -> NewsMessageCard(
                message = "No news coverage yet",
                actionLabel = newsState.usgsEventUrl?.let { "More on USGS" },
                onAction = newsState.usgsEventUrl?.let { url -> { onArticleClick(url) } },
            )
            NewsUiState.Error -> NewsMessageCard(message = "Couldn't load news", actionLabel = "Retry", onAction = onRetry)
        }
    }
}

/**
 * Task 2b (dogfooding fix, task-2b-news-fix-report.md): the one-card shape both [NewsUiState.Empty]
 * ("No news coverage yet" + an optional "More on USGS" link) and [NewsUiState.Error] ("Couldn't
 * load news" + Retry) render into - same [Surface] card treatment [NewsUiState.Content]'s own
 * headline list already uses, so the section reads as "one card" regardless of which of the three
 * post-fetch states it's actually showing. [actionLabel]/[onAction] travel together (both null, or
 * both non-null) rather than as a nullable trailing lambda alone - [NewsUiState.Empty] with no
 * [NewsUiState.Empty.usgsEventUrl] (an EMSC-only quake) needs the caption with NO action row at
 * all, not a row with an empty-label button.
 */
@Composable
private fun NewsMessageCard(message: String, actionLabel: String?, onAction: (() -> Unit)?, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(TerraRadii.card),
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 1.dp,
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(start = 14.dp, end = if (onAction != null) 6.dp else 14.dp, top = 10.dp, bottom = 10.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(text = message, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (actionLabel != null && onAction != null) {
                TextButton(onClick = onAction) { Text(actionLabel) }
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
