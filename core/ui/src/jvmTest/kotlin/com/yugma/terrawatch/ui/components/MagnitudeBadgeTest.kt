package com.yugma.terrawatch.ui.components

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Task 10 (item g): [magnitudeContentDescription] — [MagnitudeBadge]'s TalkBack sentence, pinned
 * independent of Compose semantics/instrumentation, same convention as [StatusShieldTest]'s
 * coverage of `pillContentDescription`.
 */
class MagnitudeBadgeTest {
    @Test
    fun `a real magnitude reads as Magnitude plus the formatted number`() {
        assertEquals("Magnitude 6.1", magnitudeContentDescription(6.1))
    }

    @Test
    fun `a whole-number magnitude still shows one decimal place`() {
        assertEquals("Magnitude 6.0", magnitudeContentDescription(6.0))
    }

    @Test
    fun `null magnitude reads as unknown, not as a bare dash`() {
        assertEquals("Magnitude unknown", magnitudeContentDescription(null))
    }

    @Test
    fun `NaN magnitude reads as unknown too`() {
        assertEquals("Magnitude unknown", magnitudeContentDescription(Double.NaN))
    }
}
