package com.yugma.terrawatch.monetization

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * [UnavailablePlusPurchases] is not an edge case — it is what EVERY build without a configured
 * RevenueCat key resolves to: every jvm/wasmJs build (Android-only runtime scope directive), every
 * local Android build, and every CI build. It is the default state of this repo, so its behaviour
 * is worth pinning precisely rather than assuming.
 *
 * The distinction these tests exist to protect is [PurchaseOutcome.Cancelled] versus
 * [PurchaseOutcome.Failed]. Collapsing them would mean telling a user who simply backed out of the
 * store sheet that something went wrong — untrue, and the kind of small dishonesty this product's
 * own rules exist to prevent.
 */
class PlusPurchasesTest {

    @Test fun `unavailable provider offers nothing to buy`() = runTest {
        assertNull(
            UnavailablePlusPurchases.loadOffer(),
            "no configured key must mean no offer at all - never a placeholder price the store " +
                "would refuse to honour",
        )
    }

    @Test fun `unavailable provider fails a purchase rather than pretending it succeeded`() = runTest {
        val outcome = UnavailablePlusPurchases.purchase()
        assertTrue(
            outcome is PurchaseOutcome.Failed,
            "expected Failed so the paywall can say something honest, got $outcome",
        )
    }

    @Test fun `unavailable provider reports nothing to restore, which is not an error`() = runTest {
        // Deliberately NOT Failed: the silent first-launch restore attempt runs on every cold start,
        // including every build that reaches this object. A failure here would be a store error
        // surfaced to someone who never bought anything.
        assertEquals(RestoreOutcome.NothingToRestore, UnavailablePlusPurchases.restore())
    }

    @Test fun `a cancelled purchase is not a failed one`() {
        assertFalse(
            PurchaseOutcome.Cancelled is PurchaseOutcome.Failed,
            "backing out of the store sheet is a choice, not an error to report back",
        )
    }

    @Test fun `a failed purchase carries user-facing copy, not a stack trace`() = runTest {
        // The paywall renders `reason` verbatim, so it has to read as a sentence to a person.
        val outcome = UnavailablePlusPurchases.purchase()
        val reason = (outcome as PurchaseOutcome.Failed).reason
        assertTrue(reason.first().isUpperCase(), "should read as a sentence, got: $reason")
        assertTrue(reason.endsWith("."), "should read as a sentence, got: $reason")
    }
}
