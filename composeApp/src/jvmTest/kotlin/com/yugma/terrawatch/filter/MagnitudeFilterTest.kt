package com.yugma.terrawatch.filter

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * User review items 3+4: [MAGNITUDE_FILTER_CHIPS] (the shared History/feed-sheet chip vocabulary)
 * and [quakeMatchesMagFilter] (the shared client-side predicate the feed sheet/two-pane list use to
 * apply that vocabulary — History applies the identical semantics server-side, via
 * `QuakeStore.pageBetween`'s own `:minMag IS NULL OR mag >= :minMag` SQL predicate; both are pinned
 * to the same truth table here as a single source of truth for the "how does a magnitude filter
 * actually match" question this codebase now answers in two places).
 */
class MagnitudeFilterTest {
    @Test fun `the shared chip vocabulary is exactly All, 4-0+, 5-0+, 6-0+, in that order`() {
        assertEquals(
            listOf("All" to null, "4.0+" to 4.0, "5.0+" to 5.0, "6.0+" to 6.0),
            MAGNITUDE_FILTER_CHIPS,
        )
    }

    // --- quakeMatchesMagFilter: mirrors QuakeStore.pageBetween's own SQL predicate exactly --------

    @Test fun `a null filter (All) matches every magnitude, including unknown`() {
        assertTrue(quakeMatchesMagFilter(mag = 1.0, minMag = null))
        assertTrue(quakeMatchesMagFilter(mag = 9.0, minMag = null))
        assertTrue(quakeMatchesMagFilter(mag = null, minMag = null))
    }

    @Test fun `a quake at or above the floor matches`() {
        assertTrue(quakeMatchesMagFilter(mag = 4.0, minMag = 4.0))
        assertTrue(quakeMatchesMagFilter(mag = 5.5, minMag = 4.0))
    }

    @Test fun `a quake below the floor does not match`() {
        assertFalse(quakeMatchesMagFilter(mag = 3.9, minMag = 4.0))
        assertFalse(quakeMatchesMagFilter(mag = 2.2, minMag = 4.0))
    }

    @Test fun `an unknown (null) magnitude never satisfies a real floor`() {
        assertFalse(quakeMatchesMagFilter(mag = null, minMag = 4.0))
    }
}
