package com.yugma.terrawatch.ui.charts

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.unit.dp
import com.yugma.terrawatch.ui.theme.TerraColors

private val CHART_HEIGHT = 80.dp
private val BAR_GAP = 4.dp
private val BAR_CORNER = 3.dp

/** Exposed `internal` (not `private`) solely so [BarChartTest] can pin it directly - see
 * [barHeightFraction]'s own kdoc. */
internal const val MIN_HEIGHT_FRACTION = 0.06f

/**
 * Task 6 (Plan 3): the "quakes per day" bar chart - hand-rolled [Canvas], no charting library (the
 * plan's own Tech Stack constraint: "charts hand-rolled; no chart lib per spec"). One vertical bar
 * per entry in [values], evenly spaced with rounded tops/square bottoms; every bar is Water-blue
 * except the LAST one (always "today", by construction of the caller's own bucket range - see
 * `com.yugma.terrawatch.insights.InsightsViewModel`), which renders in Safe-green - the same
 * today's-bar-is-the-accent treatment the approved mockup uses.
 *
 * [values] is expected already gap-filled by the caller (`com.yugma.terrawatch.insights.
 * fillDayGaps`) - every calendar day in the period gets an entry, zero-quake days included as
 * `0L` - this composable has no notion of "which buckets were actually queried" and draws exactly
 * one bar per list entry, in order.
 *
 * [labels] is the baseline's left/right date text (period start / period end) - plain strings
 * rather than millis, since this composable only ever sees bare counts (no bucket dates); the
 * caller (`InsightsScreen`) is the one that knows both the period and "now" and formats them via
 * `com.yugma.terrawatch.ui.format.formatShortDate`.
 */
@Composable
fun BarChart(values: List<Long>, labels: Pair<String, String>, modifier: Modifier = Modifier) {
    val barColor = TerraColors.Water
    val highlightColor = TerraColors.Safe
    Column(modifier.fillMaxWidth()) {
        Canvas(Modifier.fillMaxWidth().height(CHART_HEIGHT)) {
            if (values.isEmpty()) return@Canvas
            val maxValue = values.max()
            val gapPx = BAR_GAP.toPx()
            val barWidth = (size.width - gapPx * (values.size - 1)) / values.size
            val corner = CornerRadius(BAR_CORNER.toPx())
            values.forEachIndexed { index, value ->
                val fraction = barHeightFraction(value, maxValue)
                if (fraction <= 0f) return@forEachIndexed
                val barHeight = size.height * fraction
                val left = index * (barWidth + gapPx)
                val color = if (index == values.lastIndex) highlightColor else barColor
                val path = Path().apply {
                    addRoundRect(
                        RoundRect(
                            rect = Rect(left, size.height - barHeight, left + barWidth, size.height),
                            topLeft = corner,
                            topRight = corner,
                            bottomLeft = CornerRadius.Zero,
                            bottomRight = CornerRadius.Zero,
                        ),
                    )
                }
                drawPath(path, color = color)
            }
        }
        Row(Modifier.fillMaxWidth().padding(top = 4.dp), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(labels.first, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(labels.second, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/**
 * Pure bar-height math, pulled out of the [Canvas] draw scope so it is unit-testable without any
 * Compose UI test infra (mirrors `DetailSheet.buildShareText`'s own "internal, not private, so a
 * test can pin it" convention - see [BarChartTest]). A zero or negative [value] draws no bar at
 * all (`0f`); every genuinely positive value gets at least [MIN_HEIGHT_FRACTION] of the chart's
 * height even when it is tiny next to [maxValue] - a single quake on an otherwise-quiet day must
 * still be a visible sliver, not an invisible one-pixel smear. [maxValue] is coerced to at least
 * `1` so a degenerate all-zero [values] list (which [BarChart] itself never actually passes here,
 * since every element failing `value <= 0L` short-circuits before this matters) can never divide
 * by zero.
 */
internal fun barHeightFraction(value: Long, maxValue: Long): Float {
    if (value <= 0L) return 0f
    val safeMax = maxValue.coerceAtLeast(1L).toFloat()
    return (value.toFloat() / safeMax).coerceIn(MIN_HEIGHT_FRACTION, 1f)
}
