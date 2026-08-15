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
 *
 * Task 2 (Plan 5): "Unlimited favorite places" drops its "(coming soon)" suffix here — the FIRST
 * REAL Plus gate ([com.yugma.terrawatch.monetization.canAddFavorite], wired into Settings' "Add
 * place" row) ships this task, so the item is no longer a promise, it's live. The other two items
 * keep their current honest state verbatim (per this task's own dispatch: "drop '(coming soon)' from
 * that item ONLY") — ad removal was already real (Task 6), custom alert rules are still not built.
 */
class PaywallScreenTest {
    @Test fun `PLUS_BENEFITS lists spec section 8's 3 benefits, favorites now real per Plan 5 Task 2`() {
        assertEquals(
            listOf(
                "Remove ads",
                "Unlimited favorite places",
                "Custom alert rules (coming soon)",
            ),
            PLUS_BENEFITS,
        )
    }
}
