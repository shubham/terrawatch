package com.yugma.terrawatch.home

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Task 10 (item g): [liveStatusContentDescription] — [LiveStatusRow]'s TalkBack sentence, pinned
 * independent of Compose semantics/instrumentation, same convention as [HomeScreenBannerTest]'s
 * coverage of `shouldShowStalenessBanner`.
 *
 * Task 3b: [feedRevealAction]/[isAtTopOfFeed]/[feedRevealChipText]/[feedRevealChipContentDescription]
 * — the live-feed reveal wiring's own pure decision logic, same "pin the truth table independent of
 * Compose" convention as the rest of this class. The actual LaunchedEffect/LazyListState wiring
 * that CALLS these (FeedSheet.kt's `FeedSheet` composable) is not exercisable here — no Compose
 * runtime in jvmTest (see task-3-report.md's identical "Compose-level render test not possible in
 * jvm scope" note) — so that half stays device-verification-pending; only the decision logic itself
 * is pinned in this file.
 */
class FeedSheetTest {
    @Test
    fun `live reads as an active connection sentence`() {
        assertEquals("Live connection active", liveStatusContentDescription(isLive = true))
    }

    @Test
    fun `not live reads as an offline connection sentence`() {
        assertEquals("Live connection offline", liveStatusContentDescription(isLive = false))
    }

    // feedRevealAction: the 3-way truth table driving FeedSheet's reveal wiring. -----------------

    @Test
    fun `feedRevealAction is NONE when nothing new has arrived, regardless of scroll position`() {
        assertEquals(FeedRevealAction.NONE, feedRevealAction(atTop = true, newCount = 0))
        assertEquals(FeedRevealAction.NONE, feedRevealAction(atTop = false, newCount = 0))
    }

    @Test
    fun `feedRevealAction is AUTO_SCROLL when new items arrive while already at the top`() {
        assertEquals(FeedRevealAction.AUTO_SCROLL, feedRevealAction(atTop = true, newCount = 1))
        assertEquals(FeedRevealAction.AUTO_SCROLL, feedRevealAction(atTop = true, newCount = 5))
    }

    @Test
    fun `feedRevealAction is SHOW_CHIP when new items arrive while scrolled away from the top`() {
        assertEquals(FeedRevealAction.SHOW_CHIP, feedRevealAction(atTop = false, newCount = 1))
        assertEquals(FeedRevealAction.SHOW_CHIP, feedRevealAction(atTop = false, newCount = 5))
    }

    // feedExpandRevealAction: the T3c peek-to-expanded reconciliation on top of feedRevealAction. ---

    @Test
    fun `feedExpandRevealAction is AUTO_SCROLL on a peek-to-expanded transition with unseen arrivals, even when scrolled away`() {
        assertEquals(FeedRevealAction.AUTO_SCROLL, feedExpandRevealAction(justExpanded = true, atTop = false, newCount = 3))
    }

    @Test
    fun `feedExpandRevealAction is AUTO_SCROLL on a peek-to-expanded transition with unseen arrivals, when already at top`() {
        assertEquals(FeedRevealAction.AUTO_SCROLL, feedExpandRevealAction(justExpanded = true, atTop = true, newCount = 3))
    }

    @Test
    fun `feedExpandRevealAction defers to feedRevealAction when nothing arrived, even on a fresh expand`() {
        assertEquals(FeedRevealAction.NONE, feedExpandRevealAction(justExpanded = true, atTop = false, newCount = 0))
        assertEquals(FeedRevealAction.NONE, feedExpandRevealAction(justExpanded = true, atTop = true, newCount = 0))
    }

    @Test
    fun `feedExpandRevealAction defers to feedRevealAction's own atTop-conditional choice for mid-expanded arrivals`() {
        // justExpanded = false is the "topId changed while already expanded" case T3b already
        // owned before this feature — byte-for-byte the same truth table as feedRevealAction alone.
        assertEquals(FeedRevealAction.AUTO_SCROLL, feedExpandRevealAction(justExpanded = false, atTop = true, newCount = 2))
        assertEquals(FeedRevealAction.SHOW_CHIP, feedExpandRevealAction(justExpanded = false, atTop = false, newCount = 2))
        assertEquals(FeedRevealAction.NONE, feedExpandRevealAction(justExpanded = false, atTop = false, newCount = 0))
    }

    // visitSummary: the since-last-visit banner's copy/gating decision. -----------------------------

    @Test
    fun `visitSummary is null when there is no recorded prior visit, regardless of count`() {
        assertEquals(null, visitSummary(lastVisitMillis = null, nowCount = 5))
        assertEquals(null, visitSummary(lastVisitMillis = null, nowCount = 0))
    }

    @Test
    fun `visitSummary is null when the count is zero or negative, even with a real prior visit`() {
        assertEquals(null, visitSummary(lastVisitMillis = 1_000L, nowCount = 0))
        assertEquals(null, visitSummary(lastVisitMillis = 1_000L, nowCount = -1))
    }

    @Test
    fun `visitSummary uses singular copy for exactly one qualifying quake`() {
        assertEquals("1 quake M4.0+ since your last visit", visitSummary(lastVisitMillis = 1_000L, nowCount = 1))
    }

    @Test
    fun `visitSummary uses plural copy for more than one qualifying quake`() {
        assertEquals("3 quakes M4.0+ since your last visit", visitSummary(lastVisitMillis = 1_000L, nowCount = 3))
    }

    // isAtTopOfFeed: the epsilon-tolerant "is the list genuinely at its top" check. ----------------
    // Epsilon decision (documented here, not just in the implementation): a small tolerance
    // (FEED_AT_TOP_EPSILON_PX) absorbs sub-pixel fling-deceleration residue that can leave
    // firstVisibleItemScrollOffset a few px shy of a bit-for-bit 0 even though the list reads as
    // "at the top" to the eye — an exact `== 0` would spuriously treat that as "scrolled away."

    @Test
    fun `isAtTopOfFeed is true at exactly index 0, offset 0`() {
        assertEquals(true, isAtTopOfFeed(firstVisibleItemIndex = 0, firstVisibleItemScrollOffset = 0))
    }

    @Test
    fun `isAtTopOfFeed tolerates a sub-pixel offset within the epsilon`() {
        assertEquals(
            true,
            isAtTopOfFeed(firstVisibleItemIndex = 0, firstVisibleItemScrollOffset = FEED_AT_TOP_EPSILON_PX),
        )
    }

    @Test
    fun `isAtTopOfFeed is false once scrolled past the epsilon`() {
        assertEquals(
            false,
            isAtTopOfFeed(firstVisibleItemIndex = 0, firstVisibleItemScrollOffset = FEED_AT_TOP_EPSILON_PX + 1),
        )
    }

    @Test
    fun `isAtTopOfFeed is false once the first visible item is not index 0, regardless of offset`() {
        assertEquals(false, isAtTopOfFeed(firstVisibleItemIndex = 1, firstVisibleItemScrollOffset = 0))
    }

    // Chip copy: a short glanceable visible label vs. a full TalkBack sentence. --------------------

    @Test
    fun `feedRevealChipText reads as a short glanceable label`() {
        assertEquals("3 new quakes ↑", feedRevealChipText(newCount = 3))
    }

    @Test
    fun `feedRevealChipContentDescription reads as a full sentence naming the action`() {
        assertEquals("3 new earthquakes, scroll to top", feedRevealChipContentDescription(newCount = 3))
    }
}
