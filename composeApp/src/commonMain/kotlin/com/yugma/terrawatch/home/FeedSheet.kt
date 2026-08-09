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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
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
 * Task 12: the "N NEW" chip half of this header is phone-only sheet chrome — the desktop/tablet
 * two-pane right panel (`HomeScreen.kt`'s `TwoPaneLayout`) has no peek/expanded state for a "seen it
 * yet" count to track, so it doesn't reuse this whole composable. It does reuse the other half,
 * [LiveStatusRow] (Fix 2, below), plus [FeedList] — see both composables' own kdocs.
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
        FeedList(
            quakes = quakes,
            nowMillis = nowMillis,
            distanceKm = distanceKm,
            onQuakeClick = onQuakeClick,
            modifier = Modifier.weight(1f),
        )
    }
}

/**
 * Task 12: the plain scrollable list of [QuakeCard]s, extracted out of [FeedSheet] so
 * `HomeScreen.kt`'s desktop/tablet two-pane right panel can reuse the exact same list rendering
 * without [FeedSheet]'s phone-only sheet header. [quakes] is already time-descending (see
 * [HomeViewModel]'s `recentQuakes()`/DAO query) — no re-sort here. [distanceKm] is a per-quake
 * lookup rather than a pre-computed list because the distance depends on the home location, which
 * can resolve (or change) independently of the quake list itself.
 */
@Composable
fun FeedList(
    quakes: List<Quake>,
    nowMillis: Long,
    distanceKm: (Quake) -> Double?,
    onQuakeClick: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyColumn(
        modifier = modifier.fillMaxWidth(),
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

@Composable
private fun FeedSheetHeader(isLive: Boolean, newCount: Int, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        LiveStatusRow(isLive)
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
 * Task 12 Fix 2 (review finding — reviewer's Important 2, "desktop panel has no LIVE/OFFLINE
 * signal"): the [LiveDot] + "LIVE"/"OFFLINE" label pairing, extracted out of [FeedSheetHeader] so
 * `HomeScreen.kt`'s desktop/tablet two-pane right panel can show the exact same honest connection
 * signal the phone sheet's header always has — the panel shipped with none at all in the original
 * Task 12 cut. Deliberately excludes the "N NEW" chip [FeedSheetHeader] still adds on its own:
 * `TwoPaneLayout` keeps that counter pinned at zero (see its own Fix 1 note) precisely because an
 * always-visible list has no "unseen since last look" concept, so a chip that could only ever read
 * "0 NEW" would be dead UI, not a smaller version of the phone one.
 */
@Composable
fun LiveStatusRow(isLive: Boolean, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier,
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
    }
}

/**
 * Task 10: truthful connection state — [isLive] now reflects
 * [com.yugma.terrawatch.data.QuakeRepository.liveConnected] (the real WebSocket state), so this
 * always renders one of two honest faces instead of the old "STATIC dot, only shown when the
 * (always-true) placeholder said live": a pulsing green dot while the socket is actually open, or
 * a static, translucent ink dot while it isn't (paired with the "OFFLINE" label above). The pulse
 * itself (alpha 0.35 -> 1, reversing, non-stopping) is skipped under [LocalReducedMotion] — a
 * steady fully-on dot substitutes so "live" still reads instantly at a glance without motion.
 *
 * Fix Round 1 (entangled minors):
 * - The pulsing alpha used to be read directly in this composable function's body (a `by`-delegated
 *   `State<Float>` feeding a `Color.copy(alpha = ...)` argument passed to `Modifier.background()`).
 *   That is a composition-phase read: Compose must recompose this whole function on every animation
 *   frame (60fps) just to re-derive that `Color` argument. `Modifier.graphicsLayer { alpha = ... }`
 *   defers the same read to the draw phase instead — only this node's compositing layer redraws
 *   each frame; the composable itself never recomposes for the pulse. (Kept as a plain `State`
 *   reference, not a `by`-delegated local, specifically so nothing in this function's own body
 *   reads `.value` outside the `graphicsLayer` lambda.)
 * - The not-live dot used a hardcoded `Color.Gray` — an ad-hoc, off-palette color this codebase's
 *   own token rule (core:ui's `TerraColors` kdoc: "every screen sources color from here... never
 *   ad-hoc `Color(0x...)` literals") forbids. Replaced with `TerraColors.Ink.copy(alpha = 0.35f)`,
 *   which (unlike the old code's structure) now actually renders translucent: the old version's
 *   outer `.copy(alpha = alpha)` — with `alpha` hardcoded to `1f` on this branch — unconditionally
 *   overwrote any alpha baked into the base color, so the intended translucency could never have
 *   shown up even if the base color itself had carried one.
 */
@Composable
private fun LiveDot(isLive: Boolean, modifier: Modifier = Modifier) {
    val reducedMotion = LocalReducedMotion.current
    val baseColor = if (isLive) TerraColors.Safe else TerraColors.Ink.copy(alpha = 0.35f)
    if (isLive && !reducedMotion) {
        val transition = rememberInfiniteTransition(label = "live-dot-pulse")
        val animatedAlpha = transition.animateFloat(
            initialValue = 0.35f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(
                animation = tween(durationMillis = 900),
                repeatMode = RepeatMode.Reverse,
            ),
            label = "live-dot-alpha",
        )
        Box(
            modifier
                .size(8.dp)
                .graphicsLayer { alpha = animatedAlpha.value }
                .background(color = baseColor, shape = CircleShape),
        )
    } else {
        Box(
            modifier
                .size(8.dp)
                .background(color = baseColor, shape = CircleShape),
        )
    }
}
