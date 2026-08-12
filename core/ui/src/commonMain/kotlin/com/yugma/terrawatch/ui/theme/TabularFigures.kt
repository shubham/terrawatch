package com.yugma.terrawatch.ui.theme

import androidx.compose.ui.text.TextStyle

/**
 * Task 10 (Plan 3, design catch-up bundle, item f): the OpenType/CSS `font-feature-settings` tag
 * for tabular (fixed-width) figures — every digit glyph claims the same advance width instead of
 * each digit's own proportional width, so a column of stacked numbers (magnitude badges in a feed,
 * distance/depth stats, chart counts) lines up digit-for-digit instead of jittering left/right as
 * the digits themselves change. Per the CSS Fonts Module Level 3 spec, a feature tag with no
 * explicit value defaults to `1` (on) — `"tnum"` alone is the shorthand for `"tnum" 1`, the same
 * shorthand `android.graphics.Paint#setFontFeatureSettings` accepts (it parses the identical CSS
 * syntax) and the form Compose's own `TextStyle.fontFeatureSettings` samples use.
 *
 * A single `const` (not a raw literal repeated at each call site) so every magnitude-bearing style
 * in this app enables the identical feature by referencing the identical value — the same
 * "one source of truth, never a second independent restatement" discipline this codebase already
 * applies to color ([TerraColors]) and radii ([TerraRadii]).
 */
internal const val TABULAR_FIGURES_FONT_FEATURE = "tnum"

/**
 * Applies [TABULAR_FIGURES_FONT_FEATURE] to this style. Spec §4.2: "magnitude numerals bold +
 * tabular figures." Used by every text style this task's brief names as magnitude-bearing:
 * [com.yugma.terrawatch.ui.components.MagnitudeBadge]'s numeral,
 * [com.yugma.terrawatch.ui.components.StatusShield]'s ALERT face,
 * [com.yugma.terrawatch.ui.components.StatRow]/`StatTrioCard`'s stat values, and the chart labels
 * in [com.yugma.terrawatch.ui.charts.BarChart]/[com.yugma.terrawatch.ui.charts.DistributionBars].
 */
fun TextStyle.tabularFigures(): TextStyle = copy(fontFeatureSettings = TABULAR_FIGURES_FONT_FEATURE)
