package com.yugma.terrawatch.ads

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Task 6 (Plan 4), TDD: spec §8's ad-ethics rule (IMMUTABLE) as the full 2^3 truth table for
 * [adSlotVisible] — every case named for which rule (if any) is doing the hiding, proving this is a
 * plain AND-of-negations (any one hides it) rather than a priority-ordered override chain where
 * only "the first matching rule" would apply.
 */
class AdSlotVisibilityTest {
    @Test
    fun `free tier, no detail open, not onboarding - banner shows`() {
        assertTrue(adSlotVisible(isPlusActive = false, isDetailOpen = false, isOnboarding = false))
    }

    @Test
    fun `plus active hides the banner even when nothing else would`() {
        assertFalse(adSlotVisible(isPlusActive = true, isDetailOpen = false, isOnboarding = false))
    }

    @Test
    fun `detail open hides the banner even for a free-tier user outside onboarding`() {
        assertFalse(adSlotVisible(isPlusActive = false, isDetailOpen = true, isOnboarding = false))
    }

    @Test
    fun `onboarding hides the banner even for a free-tier user with no detail open`() {
        assertFalse(adSlotVisible(isPlusActive = false, isDetailOpen = false, isOnboarding = true))
    }

    @Test
    fun `plus and detail open together are still just hidden`() {
        assertFalse(adSlotVisible(isPlusActive = true, isDetailOpen = true, isOnboarding = false))
    }

    @Test
    fun `plus and onboarding together are still just hidden`() {
        assertFalse(adSlotVisible(isPlusActive = true, isDetailOpen = false, isOnboarding = true))
    }

    @Test
    fun `detail open and onboarding together are still just hidden`() {
        assertFalse(adSlotVisible(isPlusActive = false, isDetailOpen = true, isOnboarding = true))
    }

    @Test
    fun `all three true - still just hidden`() {
        assertFalse(adSlotVisible(isPlusActive = true, isDetailOpen = true, isOnboarding = true))
    }
}
