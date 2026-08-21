package com.yugma.terrawatch.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import org.jetbrains.compose.resources.Font
import terrawatch.core.ui.generated.resources.Res
import terrawatch.core.ui.generated.resources.inter_medium
import terrawatch.core.ui.generated.resources.inter_regular
import terrawatch.core.ui.generated.resources.inter_semibold

// Light mode: Ink-on-Canvas per spec. Surface is plain white so cards read as distinct, slightly
// raised planes against the tinted Canvas background.
private val TerraLightColorScheme = lightColorScheme(
    primary = TerraColors.Ink,
    onPrimary = TerraColors.Canvas,
    secondary = TerraColors.InfoBlue,
    onSecondary = Color.White,
    // UI polish findings, Part 1 row 6: previously undefined, so M3's stock baseline purple
    // (#E8DEF8/#1D192B) leaked onto every selected FilterChip, the Settings sliders' inactive
    // track, and the bottom-nav selected-tab pill (confirmed via source + pixel sampling - see
    // Tokens.kt's own kdoc on DuskInfoContainer for the full finding). Water is an existing token,
    // not a fresh hue - Ink-on-Water measures 12.96:1 (ContrastTest).
    secondaryContainer = TerraColors.Water,
    onSecondaryContainer = TerraColors.Ink,
    tertiary = TerraColors.Safe,
    onTertiary = Color.White,
    // Doc asked to also "check tertiaryContainer" - no current call site in this app defaults to
    // it (same grep that found zero secondaryContainer overrides also found none for
    // tertiaryContainer), so unlike secondaryContainer this isn't a proven leak. Defined anyway as
    // a defensive completion (reusing existing Land/Ink, no fresh hue) so the identical M3-default
    // leak can't silently reappear the moment a future component call site defaults to this role.
    tertiaryContainer = TerraColors.Land,
    onTertiaryContainer = TerraColors.Ink,
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
    // UI polish findings, Part 1 row 6: the dark-theme half of the same undefined-role leak (M3
    // stock #4A4458/#E8DEF8, pixel-sampled exactly on real screenshots - see Tokens.kt's own kdoc
    // on DuskInfoContainer). DuskInfoContainer is a deliberate InfoBlue-tinted derived tone (not a
    // fresh hue), measuring 8.33:1 with Water text on top (ContrastTest).
    secondaryContainer = TerraColors.DuskInfoContainer,
    onSecondaryContainer = TerraColors.Water,
    tertiary = TerraColors.Safe,
    onTertiary = TerraColors.Ink,
    // Defensive completion, mirroring the light scheme's own note just above - no proven leak here
    // (same grep found zero tertiaryContainer overrides too), but left undefined would silently
    // reintroduce the identical M3-default-leak risk the moment a future call site defaults to it.
    tertiaryContainer = TerraColors.DuskCardVariant,
    onTertiaryContainer = TerraColors.Canvas,
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

/**
 * Font selection doc (docs/superpowers/plans/2026-08-17-font-selection.md): Inter, bundled as 3
 * static instances — Regular(400)/Medium(500)/SemiBold(600) — under this module's own
 * `composeResources/font/` (see that directory's `OFL.txt`-adjacent `files/OFL.txt` for the SIL Open
 * Font License text the OFL requires to travel with the font). Static TTFs, not the single
 * `InterVariable.ttf` the doc's "primary path" estimated at ~856 KB: measured actual static-instance
 * sizes (411,640 / 417,300 / 419,744 B — the doc's own ~100-300 KB/file estimate didn't hold once
 * measured) put the 3-file total at ~1.22 MB, larger than the one variable file, but variable-font
 * `wght`-axis resolution in Compose Multiplatform is independently verified shaky on exactly the two
 * non-Android targets this app compiles for — JetBrains/compose-multiplatform#3127 (open: no
 * FontVariation axis support on iOS/desktop) and #4635 (open: wasmJs renders variable fonts as
 * garbled glyphs) — while JetBrains' own official resources-usage doc bundles Inter as separate
 * static per-weight files, not a variable-axis family. Static wins on "proven path," not raw bytes.
 *
 * Built inside a `@Composable` (not a top-level `val`) because compose-resources' `Font()` must run
 * in composition — matching JetBrains' own canonical Inter-bundling sample verbatim (including
 * leaving this unmemoized): on wasmJs specifically, font-resource loading is asynchronous and
 * recomposition is how the real glyphs replace the fallback once bytes arrive, so wrapping this in
 * `remember` risks silently freezing every target on the fallback face.
 */
@Composable
private fun terraFontFamily(): FontFamily = FontFamily(
    Font(Res.font.inter_regular, FontWeight.Normal),
    Font(Res.font.inter_medium, FontWeight.Medium),
    Font(Res.font.inter_semibold, FontWeight.SemiBold),
)

/**
 * Applies [terraFontFamily] to all 15 M3 type-scale roles — the "one-place change" the font
 * selection doc's Part 5 step 3 asks for. NOT via a `Typography(defaultFontFamily = ...)`
 * convenience constructor: this project's actual resolved Material3
 * (`org.jetbrains.compose.material3:material3:1.8.2`, pulled in transitively by this project's own
 * `composeMultiplatform = "1.9.0"` — confirmed directly via `./gradlew :core:ui:dependencies
 * --configuration jvmCompileClasspath`, then `javap` against that exact resolved jar) has no such
 * parameter: `Typography`'s only real constructor takes the 15 `TextStyle`s directly, no
 * `FontFamily`-typed 16th parameter. (AndroidX's own `androidx.compose.material3` gained
 * `defaultFontFamily` in `1.5.0-alpha19` — the JetBrains Compose Multiplatform fork's material3
 * artifact hasn't caught up to that revision line at this pin.) So every role is set explicitly
 * instead — sizes/line-heights/weights are untouched (`base.<role>.copy(fontFamily = ...)` only ever
 * touches the family), matching the doc's "keep M3 default sizes/line-heights" instruction.
 *
 * `titleLarge`/`headlineMedium` keep their pre-existing extra-emphasis-for-magnitude-numerals role
 * (this theme's own long-standing kdoc: "the big number on a quake card/pin callout") but at
 * SemiBold(600), not Bold(700) — font selection doc, Part 5 item 4's decision (b): this app only
 * bundles Inter Regular/Medium/SemiBold, so asking Skia to draw Bold/700 against this family would
 * synthesize a faux-bold glyph rather than draw a real one, the exact failure mode the doc's own
 * "silently ships faux-bold on every magnitude badge and headline" warning names outright — these
 * two theme-level roles are exactly "every magnitude badge," so they're swept to SemiBold in the same
 * repo-wide pass as the ~27 hand-written `fontWeight = FontWeight.Bold` call sites this task also
 * sweeps (`MagnitudeBadge.kt`, `StatusShield.kt`, etc. — see those files' own call sites).
 */
@Composable
private fun terraTypography(): Typography {
    val family = terraFontFamily()
    val base = Typography()
    return base.copy(
        displayLarge = base.displayLarge.copy(fontFamily = family),
        displayMedium = base.displayMedium.copy(fontFamily = family),
        displaySmall = base.displaySmall.copy(fontFamily = family),
        headlineLarge = base.headlineLarge.copy(fontFamily = family),
        headlineMedium = base.headlineMedium.copy(fontFamily = family, fontWeight = FontWeight.SemiBold),
        headlineSmall = base.headlineSmall.copy(fontFamily = family),
        titleLarge = base.titleLarge.copy(fontFamily = family, fontWeight = FontWeight.SemiBold),
        titleMedium = base.titleMedium.copy(fontFamily = family),
        titleSmall = base.titleSmall.copy(fontFamily = family),
        bodyLarge = base.bodyLarge.copy(fontFamily = family),
        bodyMedium = base.bodyMedium.copy(fontFamily = family),
        bodySmall = base.bodySmall.copy(fontFamily = family),
        labelLarge = base.labelLarge.copy(fontFamily = family),
        labelMedium = base.labelMedium.copy(fontFamily = family),
        labelSmall = base.labelSmall.copy(fontFamily = family),
    )
}

/**
 * Calm Guardian theme. Wrap the app root in this instead of a bare `MaterialTheme` so every
 * screen inherits the token-derived ColorScheme and Inter-backed, SemiBold-numeral Typography.
 */
@Composable
fun TerraTheme(darkTheme: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (darkTheme) TerraDarkColorScheme else TerraLightColorScheme,
        typography = terraTypography(),
        content = content,
    )
}
