package com.yugma.terrawatch.home

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Task 12: the desktop/tablet two-pane breakpoint is a pure function of width, tested directly
 * with no Compose runtime involved. The three cases pin the boundary exactly rather than just
 * "a phone-ish width" and "a desktop-ish width" — 899/900/901 proves the switch happens AT 900,
 * not near it.
 */
class LayoutModeTest {

    @Test
    fun `899dp is PHONE, just below the breakpoint`() {
        assertEquals(LayoutMode.PHONE, layoutMode(899))
    }

    @Test
    fun `900dp is TWO_PANE, exactly at the breakpoint`() {
        assertEquals(LayoutMode.TWO_PANE, layoutMode(900))
    }

    @Test
    fun `901dp is TWO_PANE, just above the breakpoint`() {
        assertEquals(LayoutMode.TWO_PANE, layoutMode(901))
    }
}
