package com.yugma.terrawatch.monetization

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Task 6 (Plan 4), TDD: [revenueCatKeyIsConfigured] is the one pure decision
 * `RevenueCatEntitlements`' android-gated construction collapses to (this task's own dispatch: "TDD
 * entitlements gate logic (pure)") — see that function's own kdoc for why blank (not just null)
 * must resolve identically to null.
 */
class EntitlementsGateTest {
    @Test
    fun `null api key is not configured`() {
        assertFalse(revenueCatKeyIsConfigured(null))
    }

    @Test
    fun `empty string api key is not configured`() {
        assertFalse(revenueCatKeyIsConfigured(""))
    }

    @Test
    fun `whitespace-only api key is not configured`() {
        assertFalse(revenueCatKeyIsConfigured("   "))
    }

    @Test
    fun `a real-looking api key is configured`() {
        assertTrue(revenueCatKeyIsConfigured("goog_aBcDeFgHiJkLmNoPqRsT"))
    }
}
