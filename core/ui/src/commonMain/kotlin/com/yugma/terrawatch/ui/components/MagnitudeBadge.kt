package com.yugma.terrawatch.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.yugma.terrawatch.model.MagnitudeBand
import com.yugma.terrawatch.ui.format.formatMagnitude
import com.yugma.terrawatch.ui.theme.magnitudeColor

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
            .background(color = magnitudeColor(band), shape = RoundedCornerShape(corner)),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = formatMagnitude(mag),
            color = Color.White,
            fontWeight = FontWeight.Bold,
            fontSize = fontSize,
        )
    }
}
