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
            color = Color.White,
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
