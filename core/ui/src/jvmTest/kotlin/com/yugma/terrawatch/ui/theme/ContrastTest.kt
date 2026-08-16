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

    // Dark-mode favorites fix (post-p5-tail): reproduces the alpha-compositing Compose itself does
    // when a semi-transparent `containerColor` is drawn over whatever's already on screen (here,
    // PlaceQuickSwitchChips' unselected FilterChip over QuakeMap's basemap) - the standard
    // Porter-Duff "over" operator, per RGB channel.
    private fun compositeOver(fg: Color, alpha: Float, bg: Color): Color = Color(
        red = alpha * fg.red + (1 - alpha) * bg.red,
        green = alpha * fg.green + (1 - alpha) * bg.green,
        blue = alpha * fg.blue + (1 - alpha) * bg.blue,
    )

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

    // Dark-mode "favorite places not visible" fix (post-p5-tail, device-reproduced -
    // docs/qa/post-p5-tail/RESULTS.md): HomeScreen.PlaceQuickSwitchChips floats over QuakeMap's
    // basemap, which is a fixed LIGHT map style in every theme (OpenFreeMap "liberty" - no dark
    // variant exists, see QuakeMap.android.kt's own kdoc). The unselected FilterChip previously had
    // no containerColor override (M3 default Color.Transparent), so dark theme's Water-toned label
    // text rendered near-invisibly on top of the map - a real device screenshot measured Water
    // (#D9E9F4) on the sampled map-ocean tile (#9EBDFF) at only 1.51:1.
    @Test fun `white-on-map was the proven failure this fix closes (dark quick-switch chip)`() {
        val sampledMapOceanTile = Color(0xFF9EBDFF) // device-sampled, darkmode-favorites-before-home.png
        assertTrue(contrastRatio(TerraColors.Water, sampledMapOceanTile) < 2.0)
    }

    @Test fun `dark theme unselected quick-switch chip clears 4_5 to 1 even over a worst-case white map tile`() {
        // Fix: containerColor = surface.copy(alpha = 0.78f) - the same "glass" tone/alpha every
        // other floating control on this screen (StatusShield/StalenessBanner/SettingsGearChip/
        // MyLocationFab) already uses. In dark theme, surface = DuskCard. Composited here over
        // Color.White - the lightest a map tile could ever be, deliberately more adverse than this
        // map style's real (cream/pale-blue, never pure white) tones - as a floor: clearing 4.5:1
        // against pure white guarantees clearing it against any real, necessarily-less-extreme map
        // color too.
        val worstCaseChipBackground = compositeOver(fg = TerraColors.DuskCard, alpha = 0.78f, bg = Color.White)
        assertPasses("Water label on worst-case glass chip background (dark)", TerraColors.Water, worstCaseChipBackground)
    }
}
