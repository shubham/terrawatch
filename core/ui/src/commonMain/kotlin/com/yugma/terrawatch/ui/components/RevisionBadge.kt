package com.yugma.terrawatch.ui.components

import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.yugma.terrawatch.ui.theme.TerraColors
import com.yugma.terrawatch.ui.theme.TerraRadii

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
 */
@Composable
fun RevisionBadge(text: String, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier,
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
