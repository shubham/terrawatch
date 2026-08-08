package com.yugma.terrawatch.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.yugma.terrawatch.model.Quake
import com.yugma.terrawatch.model.magnitudeBand
import com.yugma.terrawatch.ui.format.formatDepthKm
import com.yugma.terrawatch.ui.format.formatDistanceKm
import com.yugma.terrawatch.ui.format.formatRelativeTime
import com.yugma.terrawatch.ui.theme.TerraRadii

/**
 * One quake as a tap target: [MagnitudeBadge] on the left, place + meta line on the right. Used by
 * the feed sheet's [androidx.compose.foundation.lazy.LazyColumn] (and, later, History's — same
 * row, different list). Surface color comes from the theme (white in light, Dusk card in dark),
 * never hardcoded, so this reads correctly in both — see the Task 9 device-verify dark-mode pass.
 *
 * [distanceKm] is nullable because the meta line only shows "N km away" once the app knows where
 * "away from" even means (home location resolved) — same null-home reasoning as
 * [com.yugma.terrawatch.data.pillStatus].
 */
@Composable
fun QuakeCard(
    quake: Quake,
    distanceKm: Double?,
    nowMillis: Long,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        onClick = onClick,
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(TerraRadii.card),
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 1.dp,
        shadowElevation = 2.dp,
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            MagnitudeBadge(mag = quake.mag, band = magnitudeBand(quake.mag), size = BadgeSize.Small)
            Column(Modifier.weight(1f)) {
                Text(
                    text = quake.place,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = metaLine(quake, distanceKm, nowMillis),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/** "2 min ago · 10.0 km · 4,102 km away" — the trailing distance segment only when known. */
private fun metaLine(quake: Quake, distanceKm: Double?, nowMillis: Long): String {
    val parts = buildList {
        add(formatRelativeTime(quake.timeMillis, nowMillis))
        add(formatDepthKm(quake.depthKm))
        distanceKm?.let { add("${formatDistanceKm(it)} away") }
    }
    return parts.joinToString(" · ")
}
