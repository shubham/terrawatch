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
    val WarnInk = Color(0xFFB08A2E)
    val WarnBg = Color(0xFFFCF3DD)
    val MagLow = Color(0xFF59B87D)
    val MagModerate = Color(0xFFF5A524)
    val MagStrong = Color(0xFFF0663B)
    val MagMajor = Color(0xFFC43A2F)
    val DuskCanvas = Color(0xFF10161D)
    val DuskCard = Color(0xFF1A222C)
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
