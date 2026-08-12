package com.yugma.terrawatch.ui.components

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import com.yugma.terrawatch.ui.theme.TerraRadii

// Matches MagnitudeBadge's own BadgeSize.Small footprint exactly, so the placeholder occupies the
// identical space the real badge will pop into once content loads (no layout jump on the
// Loading -> Content transition).
private val BADGE_PLACEHOLDER_SIZE = 32.dp
private val BLOCK_SHAPE = RoundedCornerShape(4.dp)

// Task 10 (item b): the shimmer's alpha pulse range and cadence — same 900ms tween/Reverse shape
// FeedSheet.kt's own LiveDot pulse already established for this app's "gentle breathing" motion
// language, so a skeleton card and a live-dot pulse read as the same visual vocabulary.
private const val SHIMMER_ALPHA_LOW = 0.35f
private const val SHIMMER_ALPHA_HIGH = 0.65f
private const val SHIMMER_DURATION_MS = 900

/**
 * Task 5 (Plan 3) shipped a minimal, static gray placeholder here — Task 10 (item b, this change)
 * is the upgrade that brief's own kdoc pre-announced: a real shimmer (alpha pulsing between
 * [SHIMMER_ALPHA_LOW] and [SHIMMER_ALPHA_HIGH]) shared by every Loading state that used a spinner or
 * a static placeholder before — `FeedSheet` (Home's feed sheet, previously neither: an empty list,
 * see that file's own Task 10 note), `HistoryScreen`'s `LoadingFirst`, and `InsightsScreen`'s
 * `Loading`, all via this one composable so a shimmer tweak only ever needs to happen once.
 *
 * [reducedMotion] (module-boundary note — same as [StatusShield]'s own: `core:ui` can't import
 * composeApp's `LocalReducedMotion` CompositionLocal without a reverse dependency, so this is a
 * plain parameter every composeApp call site threads `LocalReducedMotion.current` into) freezes the
 * pulse at its midpoint alpha instead of animating — spec §4.3's "reduce motion... disables all of
 * it," applied to a loading placeholder exactly like every other signature-motion element in this
 * app.
 *
 * Each block's alpha is read inside its own [graphicsLayer] (draw-phase), not baked into the
 * `Color` passed to `.background()` (a composition-phase read) — same performance discipline
 * `FeedSheet.LiveDot`'s own Fix Round 1 established: this composable's function body never
 * recomposes on the pulse's animation frames, only each block's compositing layer redraws.
 */
@Composable
fun SkeletonCard(modifier: Modifier = Modifier, reducedMotion: Boolean = false) {
    val pulseAlpha: State<Float> = if (reducedMotion) {
        remember { mutableStateOf((SHIMMER_ALPHA_LOW + SHIMMER_ALPHA_HIGH) / 2f) }
    } else {
        val transition = rememberInfiniteTransition(label = "skeleton-shimmer")
        transition.animateFloat(
            initialValue = SHIMMER_ALPHA_LOW,
            targetValue = SHIMMER_ALPHA_HIGH,
            animationSpec = infiniteRepeatable(
                animation = tween(durationMillis = SHIMMER_DURATION_MS),
                repeatMode = RepeatMode.Reverse,
            ),
            label = "skeleton-alpha",
        )
    }
    val blockColor = MaterialTheme.colorScheme.onSurface
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(TerraRadii.card),
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 1.dp,
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Box(
                Modifier.size(BADGE_PLACEHOLDER_SIZE)
                    .graphicsLayer { alpha = pulseAlpha.value }
                    .background(blockColor, RoundedCornerShape(10.dp)),
            )
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Box(
                    Modifier.fillMaxWidth(0.7f).height(14.dp)
                        .graphicsLayer { alpha = pulseAlpha.value }
                        .background(blockColor, BLOCK_SHAPE),
                )
                Box(
                    Modifier.fillMaxWidth(0.45f).height(10.dp)
                        .graphicsLayer { alpha = pulseAlpha.value }
                        .background(blockColor, BLOCK_SHAPE),
                )
            }
        }
    }
}
