package com.yugma.terrawatch.ui.components

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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.yugma.terrawatch.ui.theme.TerraRadii

// Matches MagnitudeBadge's own BadgeSize.Small footprint exactly, so the placeholder occupies the
// identical space the real badge will pop into once content loads (no layout jump on the
// Loading -> Content transition).
private val BADGE_PLACEHOLDER_SIZE = 32.dp
private val BLOCK_SHAPE = RoundedCornerShape(4.dp)

/**
 * Task 5 (Plan 3): a minimal, static (no shimmer/pulse) gray placeholder matching [QuakeCard]'s own
 * footprint — badge square + two text-line bars — for History's `LoadingFirst` skeleton. The plan's
 * global constraint ("Loading = skeleton, not spinner — design catch-up") applies to every NEW
 * screen this plan adds starting now, History included, rather than waiting on Task 10's own
 * sequencing to backfill it.
 *
 * "Shimmer-free" is a deliberate, named-in-the-brief interim scope: Task 10 ("Design catch-up
 * bundle") owns the animated shimmer version of this exact composable, plus wiring it into
 * FeedSheet's and Insights' own Loading states too (per that task's own brief) — this task only
 * needs a static gray placeholder to exist at all, so callers written today already point at the
 * name Task 10 upgrades in place, rather than each screen inventing (and later migrating off) its
 * own throwaway placeholder shape.
 */
@Composable
fun SkeletonCard(modifier: Modifier = Modifier) {
    val blockColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f)
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
            Box(Modifier.size(BADGE_PLACEHOLDER_SIZE).background(blockColor, RoundedCornerShape(10.dp)))
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Box(Modifier.fillMaxWidth(0.7f).height(14.dp).background(blockColor, BLOCK_SHAPE))
                Box(Modifier.fillMaxWidth(0.45f).height(10.dp).background(blockColor, BLOCK_SHAPE))
            }
        }
    }
}
