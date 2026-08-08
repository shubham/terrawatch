package com.yugma.terrawatch.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight

// Light mode: Ink-on-Canvas per spec. Surface is plain white so cards read as distinct, slightly
// raised planes against the tinted Canvas background.
private val TerraLightColorScheme = lightColorScheme(
    primary = TerraColors.Ink,
    onPrimary = TerraColors.Canvas,
    secondary = TerraColors.InfoBlue,
    onSecondary = Color.White,
    tertiary = TerraColors.Safe,
    onTertiary = Color.White,
    background = TerraColors.Canvas,
    onBackground = TerraColors.Ink,
    surface = Color.White,
    onSurface = TerraColors.Ink,
    surfaceVariant = TerraColors.Land,
    onSurfaceVariant = TerraColors.Ink,
    // Water at full opacity measures ~1.2:1 against White/Canvas (fails WCAG non-text 3:1) - Ink
    // at partial alpha, same tuning approach as the dark outline below. alpha=0.50 was picked by
    // luminance math, not guesswork: see the Task 5 fix-round-1 report for the full computation
    // (0.48 already clears 3:1 on White/Canvas but dips to 2.94 on Land; 0.50 gives >=3.11 on all
    // three light backgrounds outline can sit on - White, Canvas, Land - with headroom).
    outline = TerraColors.Ink.copy(alpha = 0.50f),
    error = TerraColors.MagMajor,
    onError = Color.White,
    errorContainer = TerraColors.WarnBg,
    onErrorContainer = TerraColors.WarnInk,
)

// Dark mode ("Dusk"): Ink no longer has contrast against a near-black background, so primary/
// on-surface roles invert to Canvas while background/surface move to the two dusk tokens.
private val TerraDarkColorScheme = darkColorScheme(
    primary = TerraColors.Canvas,
    onPrimary = TerraColors.Ink,
    secondary = TerraColors.InfoBlue,
    onSecondary = TerraColors.Ink,
    tertiary = TerraColors.Safe,
    onTertiary = TerraColors.Ink,
    background = TerraColors.DuskCanvas,
    onBackground = TerraColors.Canvas,
    surface = TerraColors.DuskCard,
    onSurface = TerraColors.Canvas,
    // One step lighter than DuskCard - a variant equal to surface renders as a single flat plane
    // with a 1.00:1 self-contrast, defeating the point of having a "variant" role at all.
    surfaceVariant = TerraColors.DuskCardVariant,
    onSurfaceVariant = TerraColors.Water,
    outline = TerraColors.Water.copy(alpha = 0.4f), // ~3.25:1 against DuskCard - verified healthy
    error = TerraColors.MagMajor,
    onError = TerraColors.Canvas,
    errorContainer = TerraColors.WarnInk,
    onErrorContainer = TerraColors.WarnBg,
)

// System-sans defaults from Typography() carry through everywhere except the two styles used for
// magnitude numerals (the big number on a quake card/pin callout), which are bold so the number
// - the single most important glanceable fact in this app - always reads as emphasized.
private val TerraTypography = Typography().let { base ->
    base.copy(
        titleLarge = base.titleLarge.copy(fontWeight = FontWeight.Bold),
        headlineMedium = base.headlineMedium.copy(fontWeight = FontWeight.Bold),
    )
}

/**
 * Calm Guardian theme. Wrap the app root in this instead of a bare `MaterialTheme` so every
 * screen inherits the token-derived ColorScheme and bold-numeral Typography.
 */
@Composable
fun TerraTheme(darkTheme: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (darkTheme) TerraDarkColorScheme else TerraLightColorScheme,
        typography = TerraTypography,
        content = content,
    )
}
