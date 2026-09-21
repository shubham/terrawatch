package com.yugma.terrawatch.ads

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AdSlotVisibilityTest {

    @Test
    fun `visible only when neither suppressor is active`() {
        assertTrue(adSlotVisible(isDetailOpen = false, isOnboarding = false))
    }

    @Test
    fun `detail sheet open hides the banner`() {
        assertFalse(adSlotVisible(isDetailOpen = true, isOnboarding = false))
    }

    @Test
    fun `onboarding hides the banner`() {
        assertFalse(adSlotVisible(isDetailOpen = false, isOnboarding = true))
    }

    @Test
    fun `both suppressors at once still hides the banner`() {
        assertFalse(adSlotVisible(isDetailOpen = true, isOnboarding = true))
    }

    @Test
    fun `the full truth table is exhaustive - four cases, one visible`() {
        val cases = listOf(false, true).flatMap { d -> listOf(false, true).map { o -> d to o } }
        assertEquals(4, cases.size)
        assertEquals(1, cases.count { (d, o) -> adSlotVisible(isDetailOpen = d, isOnboarding = o) })
    }
}
