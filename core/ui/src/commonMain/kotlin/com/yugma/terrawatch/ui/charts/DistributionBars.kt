package com.yugma.terrawatch.ui.charts

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.unit.dp
import com.yugma.terrawatch.model.MagnitudeBand
import com.yugma.terrawatch.ui.format.formatCount
import com.yugma.terrawatch.ui.theme.magnitudeColor

private val BAR_HEIGHT = 9.dp
private const val TRACK_ALPHA = 0.10f

/**
 * Task 6 (Plan 3): the "by magnitude" distribution card - one horizontal bar per entry in
 * [bands], leading band label / trailing count, filled to its fraction of the LARGEST count in
 * the list (matching the mockup's own per-row percentage fill), colored via [magnitudeColor] - the
 * same color function every other magnitude-colored surface in this app (badges, pins) already
 * sources from, never a second palette.
 *
 * [bands] is expected already gap-filled by the caller (`com.yugma.terrawatch.insights.
 * fillBandGaps`) - LOW/MODERATE/STRONG/MAJOR always present (zero-count included), UNKNOWN only
 * when it actually has a nonzero count - this composable draws exactly one row per list entry, in
 * the order given, with no reordering/filtering of its own.
 */
@Composable
fun DistributionBars(bands: List<Pair<MagnitudeBand, Long>>, modifier: Modifier = Modifier) {
    val maxCount = bands.maxOfOrNull { it.second } ?: 0L
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        bands.forEach { (band, count) -> DistributionRow(band = band, count = count, maxCount = maxCount) }
    }
}

@Composable
private fun DistributionRow(band: MagnitudeBand, count: Long, maxCount: Long, modifier: Modifier = Modifier) {
    val trackColor = MaterialTheme.colorScheme.onSurface.copy(alpha = TRACK_ALPHA)
    val fillColor = magnitudeColor(band)
    val fraction = if (maxCount <= 0L) 0f else (count.toFloat() / maxCount.toFloat()).coerceIn(0f, 1f)
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = bandLabel(band),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.widthIn(min = 72.dp),
        )
        Canvas(Modifier.weight(1f).height(BAR_HEIGHT)) {
            val corner = CornerRadius(size.height / 2f)
            drawRoundRect(color = trackColor, cornerRadius = corner)
            if (fraction > 0f) {
                drawRoundRect(color = fillColor, size = Size(size.width * fraction, size.height), cornerRadius = corner)
            }
        }
        Text(
            text = formatCount(count),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(36.dp),
        )
    }
}

/** Plain, human-readable band names - deliberately NOT re-deriving the magnitude-range wording
 * (e.g. "< 3.0") a second time here: [MagnitudeBand]'s edges already live in exactly one place,
 * `com.yugma.terrawatch.model.magnitudeBand()` - restating them as label text would risk the two
 * drifting apart on a future edge change. */
private fun bandLabel(band: MagnitudeBand): String = when (band) {
    MagnitudeBand.LOW -> "Low"
    MagnitudeBand.MODERATE -> "Moderate"
    MagnitudeBand.STRONG -> "Strong"
    MagnitudeBand.MAJOR -> "Major"
    MagnitudeBand.UNKNOWN -> "Unknown"
}
