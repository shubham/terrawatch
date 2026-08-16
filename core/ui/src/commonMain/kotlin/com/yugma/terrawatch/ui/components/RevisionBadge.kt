package com.yugma.terrawatch.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import com.yugma.terrawatch.ui.theme.TerraColors
import com.yugma.terrawatch.ui.theme.TerraRadii

// Task 10 (item a): the badge-revise micro scale-in - starts slightly shrunk, springs up to full
// size.
//
// UI polish findings (docs/superpowers/plans/2026-08-16-ui-polish-findings.md), Part 3 item 1: this
// used to share DampingRatioMediumBouncy (0.5, ~15% overshoot, source-confirmed via the real
// animation-core:1.9.0 VectorizedAnimationSpec.kt - the 2nd-bounciest of 4 built-in presets) with
// StatusShield's shape-morph and QuakeMap.android.kt's pin-drop pop - the app's 3 "signature"
// springs, all directly magnitude/severity-adjacent (this one literally pulses ON a magnitude
// revision), sitting in real tension with "calm brand, nothing playful about severity." Swapped to
// DampingRatioNoBouncy (1.0, critically damped, zero overshoot) uniformly across all 3 - the doc
// offered NoBouncy/LowBouncy as alternatives without a strict per-site mapping; NoBouncy was chosen
// for all 3 rather than differentiating, since the doc's own framing treats them as equally
// magnitude-adjacent (even the pin-drop's size+color are magnitude-derived) and NoBouncy most
// directly and unambiguously serves "never playful about severity."
private const val PULSE_START_SCALE = 0.82f

/**
 * Task 11: the detail sheet's revision-honesty badge - a small amber pill surfacing
 * [com.yugma.terrawatch.ui.format.revisionNote]'s text (e.g. "revised from M 5.9 · 12 min ago").
 * [text] is a plain, non-null String rather than the nullable `revisions`/`nowMillis` inputs
 * `revisionNote` itself takes: the caller (DetailSheet) already computed and null-checked the note
 * before deciding to compose this at all, so this component stays a dumb, trivially-previewable
 * presentational leaf - same division of labor as [StatusShield] taking an already-computed
 * [com.yugma.terrawatch.data.PillStatus] rather than raw quakes.
 *
 * Uses [TerraRadii.pill] (not a bespoke small-badge radius) - same "small pill-shaped chip" idiom
 * already established by [com.yugma.terrawatch.data.PillStatus]-adjacent chips elsewhere in this
 * app (e.g. the feed sheet's "N NEW" pill), rather than inventing a second small-badge shape
 * token. WarnBg/WarnInk are [TerraColors]' law-fixed amber pair (spec Global Constraints) - never
 * theme roles, so this badge reads identically in light and dark.
 *
 * Task 10 (item a, spec §4.3 rule 2 - "micro scale-in when a magnitude badge revises"): a cheap
 * [Animatable]-driven scale pulse keyed on [text] itself - every time the caller passes a NEW
 * revision string (including this badge's first-ever appearance, since [LaunchedEffect] also fires
 * on initial composition), the badge snaps to [PULSE_START_SCALE] and springs back to full size,
 * drawing the eye to the fact that the number just changed. [reducedMotion] (module-boundary note:
 * same as [StatusShield]/[SkeletonCard] - `core:ui` can't reach composeApp's `LocalReducedMotion`
 * directly) skips straight to full scale with no animation.
 */
@Composable
fun RevisionBadge(text: String, modifier: Modifier = Modifier, reducedMotion: Boolean = false) {
    val scale = remember { Animatable(1f) }
    LaunchedEffect(text, reducedMotion) {
        if (reducedMotion) {
            scale.snapTo(1f)
        } else {
            scale.snapTo(PULSE_START_SCALE)
            scale.animateTo(1f, spring(dampingRatio = Spring.DampingRatioNoBouncy))
        }
    }
    Surface(
        modifier = modifier.graphicsLayer { scaleX = scale.value; scaleY = scale.value },
        shape = RoundedCornerShape(TerraRadii.pill),
        color = TerraColors.WarnBg,
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            color = TerraColors.WarnInk,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
        )
    }
}
