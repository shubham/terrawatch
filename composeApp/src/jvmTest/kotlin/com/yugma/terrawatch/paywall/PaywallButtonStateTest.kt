package com.yugma.terrawatch.paywall

import com.yugma.terrawatch.monetization.PlusOffer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The paywall's button copy and enabled-ness, as a pure function of state — the one part of this
 * screen worth a unit test, per this codebase's established "pure logic gets a unit test, rendered
 * UI gets a device pass" split (see [PaywallScreenTest]'s own kdoc for the same reasoning applied
 * to the benefits list).
 *
 * The load-bearing case is `offer == null`. That is not an error path: it is what every build
 * without a configured RevenueCat key renders, which today means every local build and all of CI.
 * It must never show a price, and must never present an enabled button that would fail the instant
 * it was tapped.
 */
class PaywallButtonStateTest {
    private val offer = PlusOffer(formattedPrice = "₹299")

    @Test fun `no offer means a disabled button and no invented price`() {
        assertEquals("Purchases unavailable", paywallButtonLabel(offer = null, inFlight = false, isPlus = false))
        assertFalse(paywallButtonEnabled(offer = null, inFlight = false, isPlus = false))
    }

    @Test fun `an offer shows the store's own price verbatim`() {
        // The price string comes from the store, so the label must embed it rather than reformat it
        // — this app does not know the user's currency or locale conventions.
        assertEquals("Unlock Plus — ₹299", paywallButtonLabel(offer, inFlight = false, isPlus = false))
        assertTrue(paywallButtonEnabled(offer, inFlight = false, isPlus = false))
    }

    @Test fun `a purchase in flight disables the button so it cannot be double-tapped`() {
        assertFalse(paywallButtonEnabled(offer, inFlight = true, isPlus = false))
        assertEquals("Working…", paywallButtonLabel(offer, inFlight = true, isPlus = false))
    }

    @Test fun `someone who already paid is never asked to buy again`() {
        assertEquals("You have Plus", paywallButtonLabel(offer, inFlight = false, isPlus = true))
        assertFalse(paywallButtonEnabled(offer, inFlight = false, isPlus = true))
    }

    @Test fun `active Plus wins over a still-loading offer`() {
        // Ordering matters: a Plus user opening the paywall before the offer resolves must see
        // "You have Plus", never a momentary "Purchases unavailable" that suggests they lost it.
        assertEquals("You have Plus", paywallButtonLabel(offer = null, inFlight = false, isPlus = true))
        assertFalse(paywallButtonEnabled(offer = null, inFlight = false, isPlus = true))
    }
}
