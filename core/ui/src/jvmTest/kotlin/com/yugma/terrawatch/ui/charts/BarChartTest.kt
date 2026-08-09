package com.yugma.terrawatch.ui.charts

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Task 6 (Plan 3): [barHeightFraction] is the one piece of actual logic inside [BarChart]'s
 * otherwise-presentational [androidx.compose.foundation.Canvas] draw scope - pulled out
 * `internal` (not `private`) so it is unit-testable without any Compose UI test infrastructure,
 * same "internal, not private, so a test can pin it" convention
 * `com.yugma.terrawatch.detail.DetailSheet.buildShareText` already established. The instrumented
 * render test for [BarChart]/[DistributionBars] themselves is Task 13's own responsibility per the
 * plan brief ("Charts: instrumented render test Task 13") - this file only pins the pure math.
 */
class BarChartTest {
    @Test
    fun `zero draws no bar`() {
        assertEquals(0f, barHeightFraction(0L, maxValue = 100L))
    }

    @Test
    fun `a negative count is treated defensively as zero`() {
        assertEquals(0f, barHeightFraction(-5L, maxValue = 100L))
    }

    @Test
    fun `the max value itself fills the full height`() {
        assertEquals(1f, barHeightFraction(100L, maxValue = 100L))
    }

    @Test
    fun `a value proportionally between zero and max scales linearly`() {
        assertEquals(0.5f, barHeightFraction(50L, maxValue = 100L))
    }

    @Test
    fun `a tiny nonzero value next to a large max is clamped up to the minimum visible height`() {
        // 1/1000 = 0.001, far below the minimum - a single quake on an otherwise-busy period must
        // still render as a visible sliver, not an invisible one-pixel smear.
        assertEquals(MIN_HEIGHT_FRACTION, barHeightFraction(1L, maxValue = 1_000L))
    }

    @Test
    fun `a zero or negative maxValue is coerced to 1 rather than dividing by zero`() {
        assertEquals(1f, barHeightFraction(5L, maxValue = 0L))
    }
}
