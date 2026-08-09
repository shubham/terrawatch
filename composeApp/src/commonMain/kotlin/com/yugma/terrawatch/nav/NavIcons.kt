package com.yugma.terrawatch.nav

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp

// Task 4 (Plan 3): bottom-nav/rail tab icons, drawn on a plain [Canvas] rather than pulled from an
// icon-font/material-icons-core dependency -- the exact same call core:ui's StatusShield.kt
// already made for its two glyphs ("this project has no icon library dependency anywhere yet, and
// one shape doesn't justify adding a new artifact [...] to core:ui's three-target build"). Three
// tab icons don't justify it either, and material-icons-core isn't already on this module's
// classpath (checked: composeApp only pulls compose.material3/foundation/ui/runtime -- no icons
// artifact anywhere in this project's dependency graph). Each glyph defaults its tint to
// [LocalContentColor], which is exactly what M3's own `Icon` composable reads too -- inside a
// NavigationBarItem/NavigationRailItem's `icon` slot, that resolves to the same animated
// selected/unselected color M3 already drives (see NavigationBarItemDefaults/
// NavigationRailItemDefaults in AppNav.kt), so these read as "token-colored" without this file
// hardcoding a single color of its own.
private val ICON_STROKE_WIDTH_DP = 1.8f

// Standard M3 icon footprint (matches Icon()'s own default size) -- shared so all three glyphs
// below size themselves identically without repeating the literal at each call site.
private val TAB_ICON_SIZE = 24.dp

/**
 * The Home tab -- a plain house silhouette (peaked roof + walls), one closed [Path] stroked in a
 * single pass: `close()` on a path that ends at the left eave draws the missing left roofline
 * segment back to the peak for free, so this needs no separate "draw the roof" step.
 */
@Composable
fun HomeTabIcon(modifier: Modifier = Modifier, tint: Color = LocalContentColor.current) {
    Canvas(modifier = modifier.size(TAB_ICON_SIZE)) {
        val w = size.width
        val h = size.height
        val path = Path().apply {
            moveTo(w * 0.5f, h * 0.12f) // roof peak
            lineTo(w * 0.84f, h * 0.42f) // right eave
            lineTo(w * 0.84f, h * 0.86f) // bottom-right
            lineTo(w * 0.16f, h * 0.86f) // bottom-left
            lineTo(w * 0.16f, h * 0.42f) // left eave
            close() // back to the peak == the left roofline
        }
        drawPath(
            path = path,
            color = tint,
            style = Stroke(width = ICON_STROKE_WIDTH_DP.dp.toPx(), join = StrokeJoin.Round, cap = StrokeCap.Round),
        )
    }
}

/**
 * The History tab -- three horizontal bars (an archive/list glyph), distinct from the house/wave
 * glyphs either side of it in the bar.
 */
@Composable
fun HistoryTabIcon(modifier: Modifier = Modifier, tint: Color = LocalContentColor.current) {
    Canvas(modifier = modifier.size(TAB_ICON_SIZE)) {
        val w = size.width
        val h = size.height
        val startX = w * 0.18f
        val endX = w * 0.82f
        val strokeWidthPx = ICON_STROKE_WIDTH_DP.dp.toPx() * 1.3f
        listOf(h * 0.28f, h * 0.5f, h * 0.72f).forEach { y ->
            drawLine(
                color = tint,
                start = Offset(startX, y),
                end = Offset(endX, y),
                strokeWidth = strokeWidthPx,
                cap = StrokeCap.Round,
            )
        }
    }
}

/**
 * The Insights tab -- a seismograph-style wave (two quadratic humps), evoking the charts that
 * screen shows once Task 6 fills it in.
 */
@Composable
fun InsightsTabIcon(modifier: Modifier = Modifier, tint: Color = LocalContentColor.current) {
    Canvas(modifier = modifier.size(TAB_ICON_SIZE)) {
        val w = size.width
        val h = size.height
        val midY = h * 0.5f
        val amplitude = h * 0.28f
        val path = Path().apply {
            moveTo(w * 0.1f, midY)
            quadraticTo(w * 0.3f, midY - amplitude, w * 0.5f, midY)
            quadraticTo(w * 0.7f, midY + amplitude, w * 0.9f, midY)
        }
        drawPath(
            path = path,
            color = tint,
            style = Stroke(width = ICON_STROKE_WIDTH_DP.dp.toPx(), join = StrokeJoin.Round, cap = StrokeCap.Round),
        )
    }
}
