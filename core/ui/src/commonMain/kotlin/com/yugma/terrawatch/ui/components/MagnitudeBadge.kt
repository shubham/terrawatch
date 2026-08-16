package com.yugma.terrawatch.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.yugma.terrawatch.model.MagnitudeBand
import com.yugma.terrawatch.ui.format.formatMagnitude
import com.yugma.terrawatch.ui.theme.TerraColors
import com.yugma.terrawatch.ui.theme.magnitudeColor
import com.yugma.terrawatch.ui.theme.tabularFigures

/**
 * The two sizes the design calls for: [Small] inside [QuakeCard] and [StatusShield]'s ALERT face,
 * [Large] reserved for Task 11's detail-sheet magnitude hero block (unused until then, but part
 * of this task's interface so that call site doesn't need a third variant invented later).
 */
enum class BadgeSize(val dp: Dp) { Small(32.dp), Large(54.dp) }

/**
 * The single "how big was it" glyph used everywhere a magnitude needs to be shown: a
 * rounded-square, band-colored tile with the formatted number in bold white. Magnitude color must
 * never appear without the number next to it (spec Global Constraints) — this composable is the
 * one place that pairing is guaranteed, since [magnitudeColor] alone only supplies the color half.
 *
 * Task 10 (item f): the numeral renders through [tabularFigures] — this is THE magnitude-bearing
 * text style named explicitly in the brief — so a stacked column of badges (the feed list, History)
 * has every digit claim the same advance width instead of jittering as magnitudes change.
 *
 * Task 10 (item g, a11y): [formatMagnitude] alone (e.g. "6.1") reads as a bare, contextless number
 * to TalkBack — [clearAndSetSemantics] replaces that with [magnitudeContentDescription] (spec
 * §4.5's own example phrasing, "Magnitude 6.1"), and — since `clearAndSetSemantics` discards
 * whatever semantics the child [Text] node would otherwise contribute — deliberately prevents the
 * bare number from ALSO being announced separately (no "Magnitude 6.1, 6.1" double-read).
 */
@Composable
fun MagnitudeBadge(
    mag: Double?,
    band: MagnitudeBand,
    size: BadgeSize,
    modifier: Modifier = Modifier,
) {
    val corner = when (size) {
        BadgeSize.Small -> 10.dp
        BadgeSize.Large -> 18.dp
    }
    val fontSize = when (size) {
        BadgeSize.Small -> 13.sp
        BadgeSize.Large -> 19.sp
    }
    Box(
        modifier = modifier
            .size(size.dp)
            .background(color = magnitudeColor(band), shape = RoundedCornerShape(corner))
            .clearAndSetSemantics { contentDescription = magnitudeContentDescription(mag) },
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = formatMagnitude(mag),
            color = magnitudeBadgeTextColor(band, size),
            fontWeight = FontWeight.Bold,
            fontSize = fontSize,
            style = LocalTextStyle.current.tabularFigures(),
        )
    }
}

/**
 * Task 10 (item g): [MagnitudeBadge]'s TalkBack sentence — extracted to a pure function so it can
 * be pinned directly in `core:ui`'s jvmTest, same "TDD pure a11y-string builders" convention
 * [com.yugma.terrawatch.ui.components.pillContentDescription] already establishes for
 * [StatusShield]. Null/NaN reads "Magnitude unknown" rather than the visual glyph's own em-dash,
 * which would otherwise announce as literally "Magnitude —" or be silently dropped depending on
 * the TTS engine's dash handling.
 */
internal fun magnitudeContentDescription(mag: Double?): String =
    if (mag == null || mag.isNaN()) "Magnitude unknown" else "Magnitude ${formatMagnitude(mag)}"

/**
 * UI polish findings (docs/superpowers/plans/2026-08-16-ui-polish-findings.md), Part 1 table rows
 * 1/2/3: white badge numeral text fails WCAG contrast against [magnitudeColor]'s own fill for the
 * two most common bands - `White` on `MagLow` measures **2.45:1**, on `MagModerate` **2.04:1**
 * (both fail even the 3:1 large-text floor; LOW+MODERATE are 92% of all quakes in the doc's sampled
 * week) - and, more narrowly, on `MagStrong` at [BadgeSize.Small] specifically (**3.15:1**, which
 * clears the 3:1 *large-text* floor `MagStrong` @ [BadgeSize.Large]'s 19sp bold numeral qualifies
 * for, but not the 4.5:1 floor 13sp bold text needs).
 *
 * Fix: switch the numeral to [TerraColors.Ink] for LOW/MODERATE (both sizes) and for STRONG@Small
 * only - `Ink` measures 6.57:1 / 7.89:1 / 5.11:1 respectively against those three fills (see
 * [ContrastTest] for the pinned regression lock). MAJOR (5.26:1) and STRONG@Large (3.15:1, already
 * at/above its applicable large-text floor per the doc's own "Keep for the Large hero badge"
 * verdict) stay white, unchanged. UNKNOWN is untouched (out of the doc's audited scope - its fill is
 * a semi-transparent `Ink` tint whose effective contrast depends on whatever surface sits behind
 * it, not a fixed pair this function can reason about).
 *
 * Deliberately changes only THIS numeral's text color, never [magnitudeColor]'s own fill hexes -
 * the map's quake pins ([com.yugma.terrawatch.map.QuakeMap]) read `magnitudeColor` directly with no
 * [MagnitudeBadge]/text involved at all, so this fix cannot affect pin-on-map legibility in any way
 * (the doc's own "check pin-on-dark-map legibility" note only applies to a fill-hex change, which
 * this isn't).
 */
internal fun magnitudeBadgeTextColor(band: MagnitudeBand, size: BadgeSize): Color = when {
    band == MagnitudeBand.LOW -> TerraColors.Ink
    band == MagnitudeBand.MODERATE -> TerraColors.Ink
    band == MagnitudeBand.STRONG && size == BadgeSize.Small -> TerraColors.Ink
    else -> Color.White
}
