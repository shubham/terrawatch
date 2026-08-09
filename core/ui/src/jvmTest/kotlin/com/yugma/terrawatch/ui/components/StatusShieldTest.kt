package com.yugma.terrawatch.ui.components

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Fix Round 1 (I3): [calmSubtitle] regression coverage. StatusShield.kt's own kdoc records the bug
 * this closes: the CALM subtitle used to hardcode "Nothing within 500 km · 24 h" regardless of the
 * real configured [radiusKm], caught only by eye during Task 7's device verification, never by a
 * test. `ComponentsTest` (composeApp's androidInstrumentedTest) only ever asserted the CALM face's
 * bold "All calm near you" title line, never this subtitle's actual text - and an instrumented test
 * needs a device/emulator to even run, unlike this one. These are the first jvmTest-able,
 * non-device assertions on the real string shape.
 */
class StatusShieldTest {
    @Test fun `calmSubtitle at the shipped 100km default`() {
        assertEquals("Nothing within 100 km · 24 h", calmSubtitle(100.0))
    }

    @Test fun `calmSubtitle at 500km matches the widened-radius shape`() {
        // 500.0 is exactly the value StatusShield used to hardcode unconditionally - this is the
        // literal repro case from Task 7's device-caught bug, now pinned both as a real value AND
        // (via the 100km test above) proof it's no longer the ONLY value ever rendered.
        assertEquals("Nothing within 500 km · 24 h", calmSubtitle(500.0))
    }

    @Test fun `calmSubtitle at the widest 1000km radius step is thousands-grouped`() {
        // calmSubtitle reuses formatCount's digit-grouping - 1000 must read "1,000", matching every
        // other count/distance formatted elsewhere in this app, not a bare "1000".
        assertEquals("Nothing within 1,000 km · 24 h", calmSubtitle(1000.0))
    }

    @Test fun `calmSubtitle at a mid-range radius step`() {
        assertEquals("Nothing within 250 km · 24 h", calmSubtitle(250.0))
    }
}
