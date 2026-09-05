package com.yugma.terrawatch.monetization

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * [attemptSilentRestore] runs on every cold start, so its failure modes are the ones a user actually
 * meets. Two properties matter enough to pin: it must not call the store when there is nothing to
 * recover, and it must never let a store failure escape onto a cold start.
 */
class SilentRestoreTest {

    private class RecordingPurchases(private val outcome: RestoreOutcome) : PlusPurchases {
        var restoreCalls = 0
        override suspend fun loadOffer(): PlusOffer? = null
        override suspend fun purchase(): PurchaseOutcome = PurchaseOutcome.Cancelled
        override suspend fun restore(): RestoreOutcome {
            restoreCalls++
            return outcome
        }
    }

    /** A store round-trip on every launch for someone who already has Plus is pure waste. */
    @Test fun `does not touch the store when Plus is already active`() = runTest {
        val purchases = RecordingPurchases(RestoreOutcome.Restored)
        assertFalse(attemptSilentRestore(purchases, isPlusAlreadyActive = true))
        assertEquals(0, purchases.restoreCalls, "should not have called restore at all")
    }

    @Test fun `attempts exactly one restore when Plus is not active`() = runTest {
        val purchases = RecordingPurchases(RestoreOutcome.NothingToRestore)
        assertTrue(attemptSilentRestore(purchases, isPlusAlreadyActive = false))
        assertEquals(1, purchases.restoreCalls)
    }

    /** The overwhelmingly common case: someone who never bought Plus opens the app. It must be a
     * silent no-op, not an error surfaced at launch. */
    @Test fun `nothing to restore is not treated as a problem`() = runTest {
        val purchases = RecordingPurchases(RestoreOutcome.NothingToRestore)
        assertTrue(attemptSilentRestore(purchases, isPlusAlreadyActive = false))
    }

    @Test fun `a failing store never propagates on a cold start`() = runTest {
        val purchases = RecordingPurchases(RestoreOutcome.Failed("network down"))
        assertTrue(attemptSilentRestore(purchases, isPlusAlreadyActive = false))
    }

    /** A throwing implementation must not take the app's launch down with it — the caller is a
     * fire-and-forget coroutine on a cold start, where an escaping exception is a crash. */
    @Test fun `a throwing store is swallowed rather than crashing launch`() = runTest {
        val throwing = object : PlusPurchases {
            override suspend fun loadOffer(): PlusOffer? = null
            override suspend fun purchase(): PurchaseOutcome = PurchaseOutcome.Cancelled
            override suspend fun restore(): RestoreOutcome = error("store exploded")
        }
        assertTrue(attemptSilentRestore(throwing, isPlusAlreadyActive = false))
    }
}
