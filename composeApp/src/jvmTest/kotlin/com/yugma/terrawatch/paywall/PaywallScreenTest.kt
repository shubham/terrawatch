package com.yugma.terrawatch.paywall

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Task 6 (Plan 4): [PLUS_BENEFITS] pins spec §8's own 3-item Plus benefits list verbatim ("removes
 * ads, multiple saved places... custom alert rules") — `internal`, same "so a test can pin it"
 * convention `SettingsScreenTest`'s own `APP_VERSION` pin already establishes. This is the one part
 * of the paywall STUB that's plain data rather than Compose UI, so it's the one part covered by a
 * jvmTest here — the rest of the screen is device-verified (screenshots), matching this codebase's
 * established "pure logic gets a unit test, rendered UI gets a device pass" split.
 */
class PaywallScreenTest {
    @Test fun `PLUS_BENEFITS lists spec section 8's 3 benefits with coming-soon markers`() {
        assertEquals(
            listOf(
                "Remove ads",
                "Unlimited saved places (coming soon)",
                "Custom alert rules (coming soon)",
            ),
            PLUS_BENEFITS,
        )
    }
}
