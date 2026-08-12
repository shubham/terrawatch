package com.yugma.terrawatch.home

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Task 10 (item e): [shouldShowStalenessBanner]'s truth table, tested with no Compose runtime
 * involved — same "pure fn, jvmTest-pinned" convention [LayoutModeTest] already established for
 * this package. The function takes a plain `isStale: Boolean` (the caller already computed it via
 * [isStale]), so this is a clean 3-input/1-output truth table with no time/clock concern folded
 * in — [IsStaleTest] below separately pins the time-boundary logic [shouldShowStalenessBanner]
 * itself no longer has to worry about.
 *
 * The "4-case truth table" the brief asks for is exactly the four combinations of
 * (`isStale`, `isLive`) below with `refreshFailed = false` — `refreshFailed = true` collapses all
 * four of those back to `true` regardless, pinned separately as the two "always wins" cases.
 */
class HomeScreenBannerTest {
    @Test
    fun `refreshFailed always shows the banner, even while live and fresh`() {
        // THE case a naive "just check refreshFailed" reading might get wrong in the other
        // direction - failure must win regardless of how healthy the connection looks otherwise.
        assertTrue(shouldShowStalenessBanner(refreshFailed = true, isStale = false, isLive = true))
    }

    @Test
    fun `refreshFailed always shows the banner, even while stale and offline too`() {
        assertTrue(shouldShowStalenessBanner(refreshFailed = true, isStale = true, isLive = false))
    }

    // The 4-case (isStale x isLive) truth table, refreshFailed = false throughout.

    @Test
    fun `stale and live does NOT show the banner - the exact bug this task fixes`() {
        // Before this fix: `refreshFailed || isStale(...)` alone would have shown the banner here,
        // directly contradicting a pulsing green LIVE dot on screen at the same time (a quiet-but-
        // connected feed, not a broken one).
        assertFalse(shouldShowStalenessBanner(refreshFailed = false, isStale = true, isLive = true))
    }

    @Test
    fun `stale and offline DOES show the banner`() {
        assertTrue(shouldShowStalenessBanner(refreshFailed = false, isStale = true, isLive = false))
    }

    @Test
    fun `not stale and live shows no banner`() {
        assertFalse(shouldShowStalenessBanner(refreshFailed = false, isStale = false, isLive = true))
    }

    @Test
    fun `not stale and offline shows no banner`() {
        // Not yet stale (e.g. the socket just dropped a moment ago) - no reason to alarm the user
        // before STALE_AFTER_MILLIS has actually elapsed.
        assertFalse(shouldShowStalenessBanner(refreshFailed = false, isStale = false, isLive = false))
    }
}

/**
 * Task 10 (item e), carved out of [HomeScreenBannerTest]: [isStale]'s own time-boundary logic,
 * previously only covered indirectly (through timestamps fed into the old
 * `shouldShowStalenessBanner(..., lastUpdatedMillis, nowMillis)` overload before that function was
 * narrowed to a pure `Boolean`-in truth table). `STALE_AFTER_MILLIS` (10 minutes, `HomeScreen.kt`)
 * is `private`, so these cases use the literal `10 * 60 * 1000L` value directly rather than
 * referencing it — grepped from `HomeScreen.kt` before writing this, not guessed — with offsets
 * chosen to sit unambiguously on either side of that boundary (9 vs 11 minutes, not 10 exactly, so
 * a future off-by-one in the boundary comparison itself would still be caught rather than
 * coincidentally passing).
 */
class IsStaleTest {
    private val now = 1_000_000_000L

    @Test
    fun `under the threshold is not stale`() {
        assertFalse(isStale(lastUpdatedMillis = now - 9 * 60 * 1000L, nowMillis = now))
    }

    @Test
    fun `over the threshold is stale`() {
        assertTrue(isStale(lastUpdatedMillis = now - 11 * 60 * 1000L, nowMillis = now))
    }

    @Test
    fun `never-updated (null lastUpdatedMillis) is not treated as stale`() {
        // A never-updated feed isn't "stale", it just hasn't reported yet.
        assertFalse(isStale(lastUpdatedMillis = null, nowMillis = now))
    }
}
