package com.yugma.terrawatch.ui.theme

import androidx.compose.ui.graphics.Color
import com.yugma.terrawatch.model.MagnitudeBand
import com.yugma.terrawatch.ui.components.BadgeSize
import com.yugma.terrawatch.ui.components.magnitudeBadgeTextColor
import kotlin.math.pow
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * UI polish findings (docs/superpowers/plans/2026-08-16-ui-polish-findings.md), Part 1: a cheap,
 * committed regression lock for this commit's contrast fixes - so a future palette edit can't
 * silently reintroduce a WCAG failure on the app's own "single most important glanceable fact" (the
 * magnitude badge), the revision-honesty pill, or the new secondaryContainer/tertiaryContainer
 * roles, without a jvmTest catching it immediately. WCAG 2.x relative-luminance contrast math,
 * reimplemented here as committed test code (the doc's own `color_audit.py` was scratchpad-only,
 * per its own evidence-integrity disclosure) - no Compose runtime needed, so this runs in plain
 * jvmTest with no compose-ui-test/robolectric involved.
 */
class ContrastTest {

    private fun channel(c: Float): Double {
        val s = c.toDouble()
        return if (s <= 0.03928) s / 12.92 else ((s + 0.055) / 1.055).pow(2.4)
    }

    private fun luminance(color: Color): Double =
        0.2126 * channel(color.red) + 0.7152 * channel(color.green) + 0.0722 * channel(color.blue)

    private fun contrastRatio(a: Color, b: Color): Double {
        val l1 = luminance(a)
        val l2 = luminance(b)
        val lighter = maxOf(l1, l2)
        val darker = minOf(l1, l2)
        return (lighter + 0.05) / (darker + 0.05)
    }

    private fun assertPasses(pairName: String, fg: Color, bg: Color, minRatio: Double = 4.5) {
        val ratio = contrastRatio(fg, bg)
        assertTrue(ratio >= minRatio, "$pairName measured %.2f:1, need >= $minRatio:1".format(ratio))
    }

    // Reproduces the doc's own cited WCAG-fail numbers for the pre-fix white-on-fill pairing, so a
    // future reader can see exactly what was wrong (all three intentionally below their applicable
    // floor here) rather than just trusting the doc's prose.
    @Test fun `white-on-fill was the proven failure this commit fixes`() {
        assertTrue(contrastRatio(Color.White, TerraColors.MagLow) < 3.0)
        assertTrue(contrastRatio(Color.White, TerraColors.MagModerate) < 3.0)
        assertTrue(contrastRatio(Color.White, TerraColors.MagStrong) < 4.5) // fails the normal-text floor
    }

    @Test fun `MagLow badge text clears 4_5 to 1 at both sizes`() {
        assertPasses("Ink on MagLow (Small)", magnitudeBadgeTextColor(MagnitudeBand.LOW, BadgeSize.Small), TerraColors.MagLow)
        assertPasses("Ink on MagLow (Large)", magnitudeBadgeTextColor(MagnitudeBand.LOW, BadgeSize.Large), TerraColors.MagLow)
    }

    @Test fun `MagModerate badge text clears 4_5 to 1 at both sizes`() {
        assertPasses("Ink on MagModerate (Small)", magnitudeBadgeTextColor(MagnitudeBand.MODERATE, BadgeSize.Small), TerraColors.MagModerate)
        assertPasses("Ink on MagModerate (Large)", magnitudeBadgeTextColor(MagnitudeBand.MODERATE, BadgeSize.Large), TerraColors.MagModerate)
    }

    @Test fun `MagStrong Small badge text clears 4_5 to 1`() {
        assertPasses("Ink on MagStrong (Small)", magnitudeBadgeTextColor(MagnitudeBand.STRONG, BadgeSize.Small), TerraColors.MagStrong)
    }

    @Test fun `MagMajor badge text stays comfortably clear, unchanged`() {
        assertPasses("White on MagMajor", magnitudeBadgeTextColor(MagnitudeBand.MAJOR, BadgeSize.Small), TerraColors.MagMajor)
    }

    @Test fun `RevisionBadge WarnInk-on-WarnBg clears 4_5 to 1 (was 2_91 to 1)`() {
        assertPasses("WarnInk on WarnBg", TerraColors.WarnInk, TerraColors.WarnBg)
    }

    @Test fun `light theme secondaryContainer pair clears 4_5 to 1`() {
        assertPasses("Ink on Water (secondaryContainer, light)", TerraColors.Ink, TerraColors.Water)
    }

    @Test fun `dark theme secondaryContainer pair clears 4_5 to 1`() {
        assertPasses("Water on DuskInfoContainer (secondaryContainer, dark)", TerraColors.Water, TerraColors.DuskInfoContainer)
    }

    @Test fun `light theme tertiaryContainer pair clears 4_5 to 1`() {
        assertPasses("Ink on Land (tertiaryContainer, light)", TerraColors.Ink, TerraColors.Land)
    }

    @Test fun `dark theme tertiaryContainer pair clears 4_5 to 1`() {
        assertPasses("Canvas on DuskCardVariant (tertiaryContainer, dark)", TerraColors.Canvas, TerraColors.DuskCardVariant)
    }
}
