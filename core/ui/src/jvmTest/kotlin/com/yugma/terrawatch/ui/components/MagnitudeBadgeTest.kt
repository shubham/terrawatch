package com.yugma.terrawatch.ui.components

import androidx.compose.ui.graphics.Color
import com.yugma.terrawatch.model.MagnitudeBand
import com.yugma.terrawatch.ui.theme.TerraColors
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Task 10 (item g): [magnitudeContentDescription] — [MagnitudeBadge]'s TalkBack sentence, pinned
 * independent of Compose semantics/instrumentation, same convention as [StatusShieldTest]'s
 * coverage of `pillContentDescription`.
 */
class MagnitudeBadgeTest {
    @Test
    fun `a real magnitude reads as Magnitude plus the formatted number`() {
        assertEquals("Magnitude 6.1", magnitudeContentDescription(6.1))
    }

    @Test
    fun `a whole-number magnitude still shows one decimal place`() {
        assertEquals("Magnitude 6.0", magnitudeContentDescription(6.0))
    }

    @Test
    fun `null magnitude reads as unknown, not as a bare dash`() {
        assertEquals("Magnitude unknown", magnitudeContentDescription(null))
    }

    @Test
    fun `NaN magnitude reads as unknown too`() {
        assertEquals("Magnitude unknown", magnitudeContentDescription(Double.NaN))
    }
}

/**
 * UI polish findings (docs/superpowers/plans/2026-08-16-ui-polish-findings.md), Part 1 rows 1/2/3:
 * white-on-fill badge text fails WCAG for LOW (2.45:1) and MODERATE (2.04:1) at both sizes, and for
 * STRONG (3.15:1) specifically at [BadgeSize.Small] (13sp, below WCAG's large-text bold floor -
 * unlike the 19sp [BadgeSize.Large] hero badge, which clears the 3:1 large-text floor and is kept
 * white per the doc's own "Keep for the Large hero badge" verdict). [magnitudeBadgeTextColor] is the
 * pure decision this codebase's own "TDD what's logic" convention wants pinned before wiring it into
 * the composable - see [ContrastTest] for the actual WCAG-ratio regression lock on top of this.
 */
class MagnitudeBadgeTextColorTest {
    @Test fun `LOW reads Ink at both sizes - white measured only 2_45 to 1`() {
        assertEquals(TerraColors.Ink, magnitudeBadgeTextColor(MagnitudeBand.LOW, BadgeSize.Small))
        assertEquals(TerraColors.Ink, magnitudeBadgeTextColor(MagnitudeBand.LOW, BadgeSize.Large))
    }

    @Test fun `MODERATE reads Ink at both sizes - white measured only 2_04 to 1`() {
        assertEquals(TerraColors.Ink, magnitudeBadgeTextColor(MagnitudeBand.MODERATE, BadgeSize.Small))
        assertEquals(TerraColors.Ink, magnitudeBadgeTextColor(MagnitudeBand.MODERATE, BadgeSize.Large))
    }

    @Test fun `STRONG Small reads Ink - white measured 3_15 to 1, below the 4_5 normal-text floor`() {
        assertEquals(TerraColors.Ink, magnitudeBadgeTextColor(MagnitudeBand.STRONG, BadgeSize.Small))
    }

    @Test fun `STRONG Large stays white - 3_15 to 1 clears the large-bold-text 3 to 1 floor`() {
        assertEquals(Color.White, magnitudeBadgeTextColor(MagnitudeBand.STRONG, BadgeSize.Large))
    }

    @Test fun `MAJOR stays white at both sizes - already 5_26 to 1, comfortably passing`() {
        assertEquals(Color.White, magnitudeBadgeTextColor(MagnitudeBand.MAJOR, BadgeSize.Small))
        assertEquals(Color.White, magnitudeBadgeTextColor(MagnitudeBand.MAJOR, BadgeSize.Large))
    }

    @Test fun `UNKNOWN stays white - untouched, alpha-blended fill is out of this audit's scope`() {
        assertEquals(Color.White, magnitudeBadgeTextColor(MagnitudeBand.UNKNOWN, BadgeSize.Small))
        assertEquals(Color.White, magnitudeBadgeTextColor(MagnitudeBand.UNKNOWN, BadgeSize.Large))
    }
}
