package com.yugma.terrawatch.ui.components

import com.yugma.terrawatch.data.PillStatus
import com.yugma.terrawatch.model.MagRevision
import com.yugma.terrawatch.model.Quake
import com.yugma.terrawatch.model.QuakeStatus
import com.yugma.terrawatch.model.Source
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

    // Task 10 (item g): pillContentDescription - the pill's TalkBack sentence, pinned independent
    // of Compose semantics/instrumentation the same way calmSubtitle's own text is pinned above.

    @Test fun `pillContentDescription for CALM states the radius in kilometers`() {
        assertEquals(
            "All calm near you, nothing within 100 kilometers",
            pillContentDescription(PillStatus(PillStatus.Kind.CALM, null), radiusKm = 100.0, nowMillis = 0L),
        )
    }

    @Test fun `pillContentDescription for CALM at a wider radius is thousands-grouped`() {
        assertEquals(
            "All calm near you, nothing within 1,000 kilometers",
            pillContentDescription(PillStatus(PillStatus.Kind.CALM, null), radiusKm = 1000.0, nowMillis = 0L),
        )
    }

    @Test fun `pillContentDescription for ASK_LOCATION matches the visible copy`() {
        assertEquals(
            "Where are you? Set location for nearby alerts",
            pillContentDescription(PillStatus(PillStatus.Kind.ASK_LOCATION, null), radiusKm = 100.0, nowMillis = 0L),
        )
    }

    @Test fun `pillContentDescription for ALERT reads magnitude, place, time, and depth naturally`() {
        // Deliberately shaped close to spec 4.5's own example: "Magnitude 6.1, Mindanao,
        // Philippines, 2 minutes ago, 10 kilometers deep" - timeMillis == nowMillis below pins the
        // "just now" boundary exactly (ComponentsTest's own convention for this), and depthKm=10.0
        // exercises formatDepthKm's "10.0 km" shape rather than a hand-rolled second spelling.
        val quake = Quake(
            id = "q1",
            timeMillis = 1_000_000L,
            lat = 7.1,
            lon = 126.5,
            depthKm = 10.0,
            mag = 6.1,
            magType = "mw",
            place = "Mindanao, Philippines",
            tsunami = false,
            felt = null,
            status = QuakeStatus.AUTOMATIC,
            sources = mapOf(Source.USGS to "q1"),
            revisions = listOf(MagRevision(6.1, "mw", 1_000_000L, Source.USGS)),
            updatedAtMillis = 1_000_000L,
        )
        assertEquals(
            "Alert. Magnitude 6.1, Mindanao, Philippines, just now, 10.0 km deep",
            pillContentDescription(PillStatus(PillStatus.Kind.ALERT, quake), radiusKm = 100.0, nowMillis = 1_000_000L),
        )
    }

    @Test fun `pillContentDescription for ALERT with no quake falls back to a bare Alert`() {
        // Defensive branch only - PillStatus's own contract never actually produces ALERT with a
        // null quake (see PillStatus.kt), but AlertContent's Compose `when` still guards it, so this
        // pure fn does too, and it's free to pin.
        assertEquals(
            "Alert",
            pillContentDescription(PillStatus(PillStatus.Kind.ALERT, null), radiusKm = 100.0, nowMillis = 0L),
        )
    }
}
