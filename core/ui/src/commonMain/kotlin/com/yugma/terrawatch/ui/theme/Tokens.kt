package com.yugma.terrawatch.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.yugma.terrawatch.model.MagnitudeBand

/**
 * Calm Guardian design tokens - the raw palette. These hex values are LAW (spec Global
 * Constraints): every screen sources color from here (or from [TerraTheme]'s ColorScheme, which
 * is itself built from these), never from ad-hoc `Color(0x...)` literals at call sites.
 */
object TerraColors {
    val Ink = Color(0xFF17222E)
    val Canvas = Color(0xFFF6FAF9)
    val Water = Color(0xFFD9E9F4)
    val Land = Color(0xFFEFF3EC)
    val Safe = Color(0xFF2FA36B)
    val InfoBlue = Color(0xFF5C8DB8)

    // UI polish findings (docs/superpowers/plans/2026-08-16-ui-polish-findings.md, Part 1 row 5):
    // WarnInk-on-WarnBg (RevisionBadge's fixed pair) measured 2.91:1, failing WCAG AA (4.5:1) for
    // this labelSmall text. Darkened from #B08A2E toward the doc's own proposed direction, picked
    // with headroom above the doc's exact #8C6A1E suggestion (which only cleared 4.53:1, a
    // razor-thin margin for a hardcoded regression-lock floor) - #7A5B19 measures 5.69:1 against
    // the unchanged WarnBg (see ContrastTest). This constant also feeds TerraTheme.kt's dark
    // errorContainer (= WarnInk there too), so the identical fix applies to both usages at once.
    val WarnInk = Color(0xFF7A5B19)
    val WarnBg = Color(0xFFFCF3DD)
    val MagLow = Color(0xFF59B87D)
    val MagModerate = Color(0xFFF5A524)
    val MagStrong = Color(0xFFF0663B)
    val MagMajor = Color(0xFFC43A2F)
    val DuskCanvas = Color(0xFF10161D)
    val DuskCard = Color(0xFF1A222C)

    // Not in the spec's Global Constraints token table - added for TerraTheme's dark
    // surfaceVariant, which was otherwise identical to DuskCard (1.00:1, no distinguishable
    // "variant" plane). One step lighter than DuskCard, still clearly part of the dusk family.
    val DuskCardVariant = Color(0xFF232D39)

    // UI polish findings, Part 1 row 6 (the highest-confidence finding in the doc's whole audit):
    // TerraTheme.kt never defined secondaryContainer, so M3's own unbranded stock baseline
    // (#4A4458 dark / #E8DEF8 light) leaked through onto every selected FilterChip, the Settings
    // sliders' inactive track, and the bottom-nav selected-tab pill - confirmed 3 independent ways
    // (zero color overrides anywhere in this codebase, the real material3:1.8.2 PaletteTokens.kt
    // source, and exact-match pixel sampling of real screenshots). Fix, per the doc's own "an
    // InfoBlue-tinted container, not a fresh hue" instruction: light theme reuses the existing
    // Water token as-is (see TerraTheme.kt); dark theme has no existing token that reads as
    // "InfoBlue family" (DuskCard/DuskCardVariant are both neutral), so this one new token is a
    // deliberate 70/30 blend of DuskCard and InfoBlue - the same "one derived tone, clearly part of
    // an existing family, not an arbitrary new hue" precedent DuskCardVariant itself already set
    // for surfaceVariant. Measures 8.33:1 with Water text on top (see ContrastTest) and is visually
    // distinct from both DuskCard (#1A222C) and DuskCardVariant (#232D39) - noticeably more
    // blue-saturated, so it reads as an intentional accent plane, not another neutral surface.
    val DuskInfoContainer = Color(0xFF2E4256)
}

/**
 * The single source of truth for "what color is this magnitude". Magnitude color must never
 * appear without the number next to it (spec Global Constraints) - this function only supplies
 * the color half of that pairing.
 */
fun magnitudeColor(band: MagnitudeBand): Color = when (band) {
    MagnitudeBand.LOW -> TerraColors.MagLow
    MagnitudeBand.MODERATE -> TerraColors.MagModerate
    MagnitudeBand.STRONG -> TerraColors.MagStrong
    MagnitudeBand.MAJOR -> TerraColors.MagMajor
    MagnitudeBand.UNKNOWN -> TerraColors.Ink.copy(alpha = 0.4f)
}

/** Corner radii - card/sheet/pill are the three shapes used across the whole app. */
object TerraRadii {
    val card = 16.dp
    val sheet = 22.dp
    val pill = 99.dp
}
