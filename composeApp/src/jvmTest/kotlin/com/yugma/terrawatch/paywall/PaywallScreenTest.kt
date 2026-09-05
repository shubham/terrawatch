package com.yugma.terrawatch.paywall

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * [PLUS_BENEFITS] pins the paywall's benefit copy verbatim — `internal`, the same "so a test can pin
 * it" convention `SettingsScreenTest`'s own `APP_VERSION` pin already establishes. This is the one
 * part of the paywall that's plain data rather than Compose UI, so it's the one part covered by a
 * jvmTest here; the rest of the screen is device-verified, matching this codebase's established
 * "pure logic gets a unit test, rendered UI gets a device pass" split.
 *
 * **Plus purchase flow (2026-09-05): "Custom alert rules (coming soon)" is REMOVED.** Plan 4 Task 6
 * shipped that item while Plus was unpurchasable, where naming an unbuilt feature was a roadmap note
 * sitting under a permanently disabled button. The purchase flow makes that button charge real
 * money, at which point the same line becomes part of a paid offer for something that does not
 * exist — a Play policy risk, and a direct breach of the honesty rule this product is built on.
 * Custom alert rules move to the roadmap as a free future update.
 *
 * Both remaining items are real and already enforced in code today, not promises:
 * `adSlotVisible` (core:ads) hides the banner for Plus users, and
 * [com.yugma.terrawatch.monetization.canAddFavorite] (core:monetization) is what the free tier's
 * one-favorite limit actually reads.
 */
class PaywallScreenTest {
    @Test fun `PLUS_BENEFITS lists only benefits that actually exist`() {
        assertEquals(
            listOf(
                "Remove ads",
                "Unlimited favorite places",
            ),
            PLUS_BENEFITS,
        )
    }
}
