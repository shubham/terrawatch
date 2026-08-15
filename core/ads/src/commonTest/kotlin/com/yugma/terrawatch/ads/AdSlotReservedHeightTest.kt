package com.yugma.terrawatch.ads

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Plan 5 Task 3, TDD: [adSlotReservedHeightDp]'s truth table. The whole point of this function is
 * that the slot's reserved LAYOUT height is decided by [eligible] ALONE — [adaptiveHeightDp] (the
 * caller's own precomputed `AdSize.getCurrentOrientationAnchoredAdaptiveBannerAdSize(...).height`,
 * see [BannerAdSlot]'s android actual) is just passed through unchanged when eligible, never
 * inspected/validated here, and never awaited — there is no "has the ad actually filled yet?"
 * input to this function at all, which IS the fix: the old bug was the slot's height depending on
 * fill state (0 -> banner height, mid-session, the instant a creative arrived); this function
 * structurally cannot reproduce that bug because fill state isn't one of its two parameters.
 */
class AdSlotReservedHeightTest {
    @Test
    fun `eligible reserves exactly the precomputed adaptive height`() {
        assertEquals(50, adSlotReservedHeightDp(eligible = true, adaptiveHeightDp = 50))
    }

    @Test
    fun `not eligible reserves zero regardless of the adaptive height`() {
        assertEquals(0, adSlotReservedHeightDp(eligible = false, adaptiveHeightDp = 50))
    }

    @Test
    fun `eligible with a different adaptive height still passes it through unchanged`() {
        // A different device width would resolve a different adaptive height (Google's own
        // adaptive-banner table) - proves this isn't hardcoded to the previous case's 50.
        assertEquals(90, adSlotReservedHeightDp(eligible = true, adaptiveHeightDp = 90))
    }

    @Test
    fun `not eligible with a zero adaptive height is still zero`() {
        assertEquals(0, adSlotReservedHeightDp(eligible = false, adaptiveHeightDp = 0))
    }
}
