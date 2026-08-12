package com.yugma.terrawatch.home

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Task 10 (item g): [liveStatusContentDescription] — [LiveStatusRow]'s TalkBack sentence, pinned
 * independent of Compose semantics/instrumentation, same convention as [HomeScreenBannerTest]'s
 * coverage of `shouldShowStalenessBanner`.
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
}
