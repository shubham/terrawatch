package com.yugma.terrawatch.ui.components

import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.ui.unit.dp
import com.yugma.terrawatch.ui.theme.TerraColors
import com.yugma.terrawatch.ui.theme.TerraRadii

// Same "no icon-font dependency" reasoning as StatusShield's hand-drawn glyphs - except here the
// mockup's own glyph IS a plain Unicode emoji (not an icon-font symbol), so reusing that literal
// character is both the simplest option and the most faithful to the approved mock, with zero new
// dependency risk (emoji render from the system's own font on every target this app ships).
private const val WAVE_EMOJI = "🌊" // 🌊

/**
 * Task 11: the detail sheet's tsunami status - a calm green "not expected" tint by default,
 * flipping to an alarmed red "advisory issued" tint when [tsunami] is true (straight from the
 * feed's own flag, per the design spec's §3.3). Both faces share one layout, differing only in
 * accent color/text - a light [accent]-alpha tint for the surface plus full-strength [accent] for
 * the text, since [TerraColors] defines no separate light-tint/dark-ink pair for Safe/MagMajor the
 * way it does for the amber Warn pair ([RevisionBadge]'s WarnBg/WarnInk) - alpha-deriving from the
 * one law-fixed token each state already has is preferred over inventing new hex literals outside
 * that token table (spec Global Constraints).
 */
@Composable
fun TsunamiBanner(tsunami: Boolean, modifier: Modifier = Modifier) {
    val accent = if (tsunami) TerraColors.MagMajor else TerraColors.Safe
    val text = if (tsunami) "Tsunami advisory issued" else "Tsunami not expected"
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(TerraRadii.card),
        color = accent.copy(alpha = 0.15f),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 13.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(9.dp),
        ) {
            Text(text = WAVE_EMOJI, style = MaterialTheme.typography.bodyMedium)
            Text(
                text = text,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                color = accent,
            )
        }
    }
}
