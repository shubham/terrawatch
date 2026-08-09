package com.yugma.terrawatch.insights

import com.yugma.terrawatch.ui.format.formatShortDate
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Task 6 fix round (review I1): [dayCountLabels] used to take a live `nowMillis` and separately
 * re-derive "today's bucket" from it, which could disagree with whatever bucket
 * `InsightsViewModel.computeContent` actually used once a UTC midnight passed between a recompute
 * and a later render - see that function's own kdoc for the full story. These tests pin the fixed
 * contract directly: the end label's bucket is ALWAYS [nowBucketAtCompute] itself, never a
 * separately-derived "now".
 */
class InsightsScreenTest {
    @Test fun `end label always reflects nowBucketAtCompute exactly, not a separately-derived now`() {
        val frozenBucket = 12_345L
        val (_, endLabel) = dayCountLabels(bucketCount = 7, nowBucketAtCompute = frozenBucket)
        assertEquals(formatShortDate(frozenBucket * DAY_MILLIS), endLabel)
    }

    @Test fun `start label is bucketCount minus one days before the frozen bucket`() {
        val frozenBucket = 12_345L
        val (startLabel, _) = dayCountLabels(bucketCount = 7, nowBucketAtCompute = frozenBucket)
        assertEquals(formatShortDate((frozenBucket - 6) * DAY_MILLIS), startLabel)
    }

    @Test fun `a 30-day range spans exactly 29 days back from the frozen bucket`() {
        val frozenBucket = 12_345L
        val (startLabel, endLabel) = dayCountLabels(bucketCount = 30, nowBucketAtCompute = frozenBucket)
        assertEquals(formatShortDate((frozenBucket - 29) * DAY_MILLIS), startLabel)
        assertEquals(formatShortDate(frozenBucket * DAY_MILLIS), endLabel)
    }

    @Test fun `a single-bucket range uses the same date for both labels`() {
        val frozenBucket = 500L
        val expected = formatShortDate(frozenBucket * DAY_MILLIS)
        assertEquals(expected to expected, dayCountLabels(bucketCount = 1, nowBucketAtCompute = frozenBucket))
    }

    @Test fun `a zero bucket count returns blank labels rather than a bogus date`() {
        assertEquals("" to "", dayCountLabels(bucketCount = 0, nowBucketAtCompute = 12_345L))
    }

    @Test fun `crossing a UTC-year boundary is handled by plain bucket arithmetic, no special-casing`() {
        // 2026-01-01T00:00:00Z's own bucket, walked back 6 days, must land in late December 2025 -
        // exercises the exact "midnight rollover mid-render" scenario this fix closes, expressed as
        // a boundary the pure function itself can be pinned against directly.
        val jan1_2026Bucket = 1_767_225_600_000L / DAY_MILLIS // 2026-01-01T00:00:00Z
        val (startLabel, endLabel) = dayCountLabels(bucketCount = 7, nowBucketAtCompute = jan1_2026Bucket)
        assertEquals("Dec 26", startLabel)
        assertEquals("Jan 1", endLabel)
    }
}
