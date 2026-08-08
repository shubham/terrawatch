package com.yugma.terrawatch.ui.format

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Table-driven tests for the pure formatting helpers in Formats.kt. Every table row plus every
 * boundary called out in the plan is asserted explicitly — these are the numbers users see on
 * screen, so silent drift here is a silent product regression.
 */
class FormatsTest {

    @Test
    fun `formatMagnitude formats one decimal and rounds half-up`() {
        val cases = listOf(
            6.1 to "6.1",
            6.0 to "6.0",
            6.049 to "6.0", // rounds down: 6.049 * 10 = 60.49 -> 60
            6.05 to "6.1", // rounds up: 6.05 * 10 = 60.5 -> 61
            -0.44 to "-0.4", // sign must survive the |whole| == 0 case
            10.0 to "10.0",
        )
        for ((input, expected) in cases) {
            assertEquals(expected, formatMagnitude(input), "formatMagnitude($input)")
        }
    }

    @Test
    fun `formatMagnitude returns em dash for null`() {
        assertEquals("—", formatMagnitude(null))
    }

    @Test
    fun `formatDepthKm formats one decimal with unit suffix`() {
        val cases = listOf(
            31.1599998474121 to "31.2 km",
            10.0 to "10.0 km",
            0.52 to "0.5 km",
        )
        for ((input, expected) in cases) {
            assertEquals(expected, formatDepthKm(input), "formatDepthKm($input)")
        }
    }

    @Test
    fun `formatDepthKm returns depth unknown for null`() {
        assertEquals("depth unknown", formatDepthKm(null))
    }

    @Test
    fun `formatRelativeTime buckets by elapsed time, exact boundaries`() {
        // Arbitrary fixed anchor - only the *difference* to `then` matters for these buckets.
        val now = 2_000_000_000_000L
        val cases = listOf(
            (now - 59_999) to "just now", // < 60s
            (now - 60_000) to "1 min ago", // >= 60s
            (now - 3_599_999) to "59 min ago", // < 60min
            (now - 3_600_000) to "1 h ago", // >= 60min
            (now - 86_399_999) to "23 h ago", // < 24h
            (now - 86_400_000) to "1 d ago", // >= 24h
            (now - 604_799_999) to "6 d ago", // < 7d
        )
        for ((then, expected) in cases) {
            assertEquals(expected, formatRelativeTime(then, now), "formatRelativeTime(then=$then, now=$now)")
        }
    }

    @Test
    fun `formatRelativeTime falls back to month-day date at 7 days and beyond`() {
        val then = 1_754_179_200_000L // known epoch -> 2026-08-03T00:00:00Z ("Aug 3")
        val now = then + 604_800_000L // exactly 7 days later -> boundary tips into date formatting
        assertEquals("Aug 3", formatRelativeTime(then, now))
    }

    @Test
    fun `formatDistanceKm rounds to whole km and groups thousands`() {
        val cases = listOf(
            4102.3 to "4,102 km",
            950.0 to "950 km",
            1_000_000.0 to "1,000,000 km",
            0.4 to "0 km",
        )
        for ((input, expected) in cases) {
            assertEquals(expected, formatDistanceKm(input), "formatDistanceKm($input)")
        }
    }
}
