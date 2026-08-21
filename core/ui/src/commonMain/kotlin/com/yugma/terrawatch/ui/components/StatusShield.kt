package com.yugma.terrawatch.ui.components

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.yugma.terrawatch.data.PillStatus
import com.yugma.terrawatch.model.magnitudeBand
import com.yugma.terrawatch.ui.format.formatCount
import com.yugma.terrawatch.ui.format.formatDepthKm
import com.yugma.terrawatch.ui.format.formatMagnitude
import com.yugma.terrawatch.ui.format.formatRelativeTime
import com.yugma.terrawatch.ui.theme.TerraColors
import com.yugma.terrawatch.ui.theme.TerraRadii
import com.yugma.terrawatch.ui.theme.tabularFigures
import kotlin.math.roundToLong

private val ALERT_CORNER = 20.dp
private val GLYPH_SIZE = 26.dp

// Task 10 (item g): spec 4.5's literal "Touch targets >= 48 dp" - a defaultMinSize floor rather
// than a fixed height, so a face whose natural content is already taller (CALM/ASK_LOCATION's
// two-line Column) is never shrunk, only ever padded UP when under this floor.
private val MIN_TOUCH_TARGET = 48.dp

/**
 * The "am I safe?" answer — a glass pill meant to float over the live map (HomeScreen owns the
 * actual top-center placement/status-bar inset; this composable only draws the pill itself).
 * "Glass" here means translucent surface + tonal elevation (matching HomeScreen's staleness
 * banner treatment) rather than a real blur, which isn't available uniformly across this
 * project's Compose Multiplatform targets.
 *
 * Three faces, per [PillStatus.Kind]:
 * - [PillStatus.Kind.CALM]: green check, "All calm near you".
 * - [PillStatus.Kind.ALERT]: the nearest significant quake's own [MagnitudeBadge] + "M X.X · place
 *   · time".
 * - [PillStatus.Kind.ASK_LOCATION]: a neutral location pin, "Where are you?" — shown until
 *   [com.yugma.terrawatch.data.pillStatus] has a home point to answer its own question with.
 *
 * Corner shape differs slightly by kind (CALM/ASK_LOCATION use the full pill radius, ALERT a
 * slightly squarer corner) per the Task 9 brief's shape-morph note. Task 10 (item a, spec §4.3 rule
 * 2 - "the status shield shape-morphs between states"): the corner now *animates* between the two
 * radii via [animateDpAsState] instead of snapping — a [spring] with
 * [Spring.DampingRatioNoBouncy] so a CALM<->ALERT flip visibly morphs the pill's silhouette rather
 * than cutting between two static shapes, with zero overshoot.
 *
 * UI polish findings (docs/superpowers/plans/2026-08-16-ui-polish-findings.md), Part 3 item 1: this
 * used to be [Spring.DampingRatioMediumBouncy] (matching this app's other 2 signature-motion
 * springs: the map's pin-drop pop, [RevisionBadge]'s own revise pulse) - source-confirmed as the
 * 2nd-bounciest of 4 built-in `animation-core` presets, in real tension with "calm brand, nothing
 * playful about severity" for a shape that IS the app's own alert/status indicator. Swapped to
 * [Spring.DampingRatioNoBouncy] (critically damped) along with the other 2 signature springs, chosen
 * uniformly across all 3 rather than differentiated (see [RevisionBadge]'s own kdoc for the full
 * reasoning). [reducedMotion] (threaded in explicitly,
 * not read from `LocalReducedMotion` — this module can't depend on composeApp's motion package;
 * see this file's own module-boundary note below) swaps the spring for [snap] so the corner still
 * changes, just instantly, honoring spec §4.3's "a reduce motion setting... disables all of it."
 *
 * Task 7 (Plan 3) fix, device-caught (USER REQUIREMENT — found live on 98bc1cd8 while verifying
 * the radius slider, not from reading the diff): the CALM subtitle used to hardcode "Nothing within
 * 500 km · 24 h" regardless of [radiusKm] — a caller could set the stored radius to 100 (the new
 * default) and this pill would keep claiming "500 km" forever, directly contradicting the very
 * feature this task exists to ship. [radiusKm] now drives that text for real.
 *
 * Task 10 (item g, a11y): [pillContentDescription] replaces whatever TalkBack would otherwise
 * assemble from this pill's child [Text] nodes with one hand-authored, natural sentence per spec
 * §4.5 ("TalkBack... labels read naturally") — `semantics(mergeDescendants = true)` (not
 * `clearAndSetSemantics`) deliberately, so the click action [Surface]'s own internal `clickable`
 * modifier contributes (chained AFTER this composable's `modifier` parameter, i.e. logically
 * *inside* it) is preserved rather than wiped: `clearAndSetSemantics` on this outer modifier would
 * discard everything inside it, including that action, silently making the pill untappable via
 * TalkBack's activate gesture. `defaultMinSize(minHeight = 48.dp)` is spec §4.5's "touch targets
 * >= 48 dp" literally — a floor, not a fixed size, so it only ever pads a face that would otherwise
 * measure under it (measured on-device: see task-10-report.md).
 *
 * Module-boundary note: this composable lives in `core:ui`, which the spec's own dependency rule
 * (§5.1: `core:ui -> core:model` [and, via this file's own Task 9 kdoc, `core:data`] only, never
 * `composeApp`) forbids from importing `composeApp`'s `com.yugma.terrawatch.motion.
 * LocalReducedMotion` CompositionLocal directly — that would be a reverse/circular module
 * dependency (composeApp already depends on core:ui). [reducedMotion] is therefore a plain
 * `Boolean` parameter, same shape [radiusKm] already uses, with composeApp's real call sites
 * (`HomeScreen`'s two [StatusShield] uses) threading `LocalReducedMotion.current` through
 * explicitly.
 */
@Composable
fun StatusShield(
    status: PillStatus,
    nowMillis: Long,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    radiusKm: Double = 100.0,
    reducedMotion: Boolean = false,
) {
    val targetCorner = if (status.kind == PillStatus.Kind.ALERT) ALERT_CORNER else TerraRadii.pill
    val animatedCorner by animateDpAsState(
        targetValue = targetCorner,
        animationSpec = if (reducedMotion) snap() else spring(dampingRatio = Spring.DampingRatioNoBouncy),
        label = "pill-corner-morph",
    )
    val shape = RoundedCornerShape(animatedCorner)
    Surface(
        onClick = onClick,
        modifier = modifier
            .defaultMinSize(minHeight = MIN_TOUCH_TARGET)
            .semantics(mergeDescendants = true) {
                contentDescription = pillContentDescription(status, radiusKm, nowMillis)
            },
        shape = shape,
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.78f),
        tonalElevation = 4.dp,
        shadowElevation = 2.dp,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            when (status.kind) {
                PillStatus.Kind.CALM -> CalmContent(radiusKm)
                PillStatus.Kind.ALERT -> AlertContent(status, nowMillis)
                PillStatus.Kind.ASK_LOCATION -> AskLocationContent()
            }
        }
    }
}

/**
 * Fix Round 1 (I3): pure-function extraction of [CalmContent]'s own subtitle text — previously
 * inlined directly into a `Text` composable, which meant this exact "Nothing within N km · 24 h"
 * shape had no non-device-instrumented regression test of its own. This file's own kdoc above
 * already tells the story of what happens when that goes untested: the CALM subtitle silently kept
 * claiming a stale hardcoded "500 km" until a device pass caught it by eye. `internal`, not
 * `private`, so `core:ui`'s own jvmTest source set can call it directly — same "so a test can pin
 * it" convention this codebase already uses for `BarChart`'s `barHeightFraction`/`InsightsScreen`'s
 * `dayCountLabels`.
 */
internal fun calmSubtitle(radiusKm: Double): String =
    "Nothing within ${formatCount(radiusKm.roundToLong())} km · 24 h"

/**
 * Task 10 (item g): the pill's whole TalkBack sentence, one per [PillStatus.Kind] — spec §4.5's own
 * worked example ("Magnitude 6.1, Mindanao, Philippines, 2 minutes ago, 10 kilometers deep") is the
 * ALERT branch's model, reusing [formatDepthKm]'s existing "10.0 km" shape rather than hand-rolling
 * a second "kilometers"-spelled-out formatter that could drift from it (this codebase's own
 * repeated lesson — see [calmSubtitle]'s neighboring bug story above). CALM/ASK_LOCATION read close
 * to their own visible copy rather than inventing unrelated wording, so a sighted user glancing at
 * the screen while a TalkBack user hears the description hear materially the same thing. `internal`
 * for [StatusShieldTest] to pin directly, same convention as [calmSubtitle].
 */
internal fun pillContentDescription(status: PillStatus, radiusKm: Double, nowMillis: Long): String =
    when (status.kind) {
        PillStatus.Kind.CALM ->
            "All calm near you, nothing within ${formatCount(radiusKm.roundToLong())} kilometers"
        PillStatus.Kind.ASK_LOCATION ->
            "Where are you? Set location for nearby alerts"
        PillStatus.Kind.ALERT -> {
            val quake = status.quake
            if (quake == null) {
                "Alert"
            } else {
                "Alert. Magnitude ${formatMagnitude(quake.mag)}, ${quake.place}, " +
                    "${formatRelativeTime(quake.timeMillis, nowMillis)}, ${formatDepthKm(quake.depthKm)} deep"
            }
        }
    }

@Composable
private fun CalmContent(radiusKm: Double) {
    CheckGlyph(tint = TerraColors.Safe, modifier = Modifier.size(GLYPH_SIZE))
    Column {
        Text(
            text = "All calm near you",
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Text(
            text = calmSubtitle(radiusKm),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun AlertContent(status: PillStatus, nowMillis: Long) {
    val quake = status.quake
    // Fix round 1 (code review, Important): StatusShield's own Surface merges descendant
    // semantics (mergeDescendants = true, see this file's kdoc) - without this, MagnitudeBadge's
    // OWN contentDescription ("Magnitude 6.1") gets swept into the pill's merged announcement
    // ON TOP OF pillContentDescription's already-complete ALERT sentence (which restates the same
    // magnitude itself), producing a double-read ("...10.0 km deep, Magnitude 6.1"). Confirmed
    // against Compose's semantics-merge behavior: MagnitudeBadge's own `clearAndSetSemantics` only
    // blocks ITS children from being collected — it does NOT stop the badge's own node from being
    // pulled into an ANCESTOR's merge. `Modifier.clearAndSetSemantics {}` here (empty block) is the
    // fix: it removes this call site's contribution entirely, same technique, applied one level up.
    MagnitudeBadge(
        mag = quake?.mag,
        band = magnitudeBand(quake?.mag),
        size = BadgeSize.Small,
        modifier = Modifier.clearAndSetSemantics {},
    )
    val text = if (quake == null) {
        "Alert"
    } else {
        "M ${formatMagnitude(quake.mag)} · ${quake.place} · " +
            formatRelativeTime(quake.timeMillis, nowMillis)
    }
    Text(
        text = text,
        // Task 10 (item f): magnitude-bearing (the "M X.X" prefix) - tabularFigures per the brief.
        style = MaterialTheme.typography.bodyMedium.tabularFigures(),
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.onSurface,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
}

@Composable
private fun AskLocationContent() {
    LocationPinGlyph(tint = TerraColors.InfoBlue, modifier = Modifier.size(GLYPH_SIZE))
    Column {
        Text(
            text = "Where are you?",
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Text(
            text = "Set location for nearby alerts",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * A green circle with a white checkmark, drawn on a [Canvas] rather than pulled from an
 * icon-font/material-icons dependency — this project has no icon library dependency anywhere yet,
 * and one shape doesn't justify adding a new artifact (and its own cross-target availability
 * risk) to core:ui's three-target (android/jvm/wasmJs) build.
 */
@Composable
private fun CheckGlyph(tint: Color, modifier: Modifier = Modifier) {
    Canvas(modifier = modifier) {
        drawCircle(color = tint)
        val w = size.width
        val h = size.height
        val path = Path().apply {
            moveTo(w * 0.28f, h * 0.52f)
            lineTo(w * 0.44f, h * 0.68f)
            lineTo(w * 0.74f, h * 0.32f)
        }
        drawPath(
            path = path,
            color = Color.White,
            style = Stroke(width = w * 0.11f, cap = StrokeCap.Round, join = StrokeJoin.Round),
        )
    }
}

/**
 * A simple map-pin silhouette: a filled triangle "point" with a filled circle placed over its top
 * half, plus a small white circle to read as the classic pin "hole" — two plain fills of the same
 * color rather than one path with an arc, so there's no dependence on exactly how any one
 * target's [Path.arcTo]-equivalent behaves. Same "draw it, don't depend on an icon font" reasoning
 * as [CheckGlyph].
 */
@Composable
private fun LocationPinGlyph(tint: Color, modifier: Modifier = Modifier) {
    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height
        val cx = w / 2f
        val topRadius = w * 0.34f
        val topCenterY = h * 0.36f
        val point = Path().apply {
            moveTo(cx - topRadius * 0.85f, topCenterY + topRadius * 0.35f)
            lineTo(cx + topRadius * 0.85f, topCenterY + topRadius * 0.35f)
            lineTo(cx, h * 0.92f)
            close()
        }
        drawPath(path = point, color = tint)
        drawCircle(color = tint, radius = topRadius, center = Offset(cx, topCenterY))
        drawCircle(color = Color.White, radius = topRadius * 0.4f, center = Offset(cx, topCenterY))
    }
}
