package com.yugma.terrawatch.home

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.yugma.terrawatch.model.Quake
import com.yugma.terrawatch.motion.LocalReducedMotion
import com.yugma.terrawatch.ui.components.QuakeCard
import com.yugma.terrawatch.ui.theme.TerraColors
import com.yugma.terrawatch.ui.theme.TerraRadii

/**
 * The feed sheet's own content — everything below the grabber handle that
 * [androidx.compose.material3.BottomSheetScaffold] already draws for us via its default
 * `sheetDragHandle` ("grabber auto from M3" per the Task 9 brief). HomeScreen.kt hosts the actual
 * `BottomSheetScaffold` call (it also needs the screen's own `BoxWithConstraints` to compute
 * `sheetPeekHeight` as a fraction of screen height) and passes this composable as its
 * `sheetContent`; this file only draws what's inside the sheet, at whatever height the scaffold
 * gives it — peeking (30% of screen height) or fully expanded (this same content, just with more
 * of the list visible below the fold).
 *
 * [quakes] is already time-descending (see [HomeViewModel]'s `recentQuakes()`/DAO query) — no
 * re-sort here. [distanceKm] is a per-quake lookup rather than a pre-computed list because the
 * distance depends on the home location, which can resolve (or change) independently of the
 * quake list itself.
 */
@Composable
fun FeedSheet(
    quakes: List<Quake>,
    isLive: Boolean,
    newCount: Int,
    nowMillis: Long,
    distanceKm: (Quake) -> Double?,
    onQuakeClick: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier.fillMaxWidth()) {
        FeedSheetHeader(
            isLive = isLive,
            newCount = newCount,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        )
        LazyColumn(
            modifier = Modifier.fillMaxWidth().weight(1f),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(quakes, key = { it.id }) { quake ->
                QuakeCard(
                    quake = quake,
                    distanceKm = distanceKm(quake),
                    nowMillis = nowMillis,
                    onClick = { onQuakeClick(quake.id) },
                )
            }
        }
    }
}

@Composable
private fun FeedSheetHeader(isLive: Boolean, newCount: Int, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        LiveDot(isLive)
        Text(
            text = if (isLive) "LIVE" else "OFFLINE",
            style = if (isLive) MaterialTheme.typography.labelMedium else MaterialTheme.typography.labelSmall,
            fontWeight = if (isLive) FontWeight.Bold else FontWeight.Normal,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (newCount > 0) {
            Surface(shape = RoundedCornerShape(TerraRadii.pill), color = TerraColors.InfoBlue) {
                Text(
                    text = "$newCount NEW",
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                    color = Color.White,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                )
            }
        }
    }
}

/**
 * Task 10: truthful connection state — [isLive] now reflects
 * [com.yugma.terrawatch.data.QuakeRepository.liveConnected] (the real WebSocket state), so this
 * always renders one of two honest faces instead of the old "STATIC dot, only shown when the
 * (always-true) placeholder said live": a pulsing green dot while the socket is actually open, or
 * a static gray dot while it isn't (paired with the "OFFLINE" label above). The pulse itself
 * (alpha 0.35 -> 1, reversing, non-stopping) is skipped under [LocalReducedMotion] — a steady
 * fully-on dot substitutes so "live" still reads instantly at a glance without motion.
 */
@Composable
private fun LiveDot(isLive: Boolean, modifier: Modifier = Modifier) {
    val reducedMotion = LocalReducedMotion.current
    val alpha = if (isLive && !reducedMotion) {
        val transition = rememberInfiniteTransition(label = "live-dot-pulse")
        val animatedAlpha by transition.animateFloat(
            initialValue = 0.35f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(
                animation = tween(durationMillis = 900),
                repeatMode = RepeatMode.Reverse,
            ),
            label = "live-dot-alpha",
        )
        animatedAlpha
    } else {
        1f
    }
    Box(
        modifier
            .size(8.dp)
            .background(
                color = (if (isLive) TerraColors.Safe else Color.Gray).copy(alpha = alpha),
                shape = CircleShape,
            ),
    )
}
