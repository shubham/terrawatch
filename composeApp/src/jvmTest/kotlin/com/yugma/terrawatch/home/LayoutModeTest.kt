package com.yugma.terrawatch.home

import androidx.window.core.layout.WindowSizeClass
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Plan 4 Task 4 (c): TDD for [layoutMode] — the extracted mapping fn this task's dispatch calls for
 * ("TDD any extracted mapping fn"). [WindowSizeClass.compute] is a plain, Compose-runtime-free
 * factory (decompiled-verified — see `layoutMode`'s own kdoc), so this whole boundary can be pinned
 * with plain `kotlin.test` assertions, no Compose UI test harness needed.
 */
class LayoutModeTest {
    @Test fun `narrow compact width maps to PHONE`() {
        assertEquals(LayoutMode.PHONE, layoutMode(WindowSizeClass.compute(400f, 800f)))
    }

    @Test fun `medium width (600dp) maps to PHONE`() {
        assertEquals(LayoutMode.PHONE, layoutMode(WindowSizeClass.compute(600f, 800f)))
    }

    @Test fun `just under the expanded breakpoint (839dp) maps to PHONE`() {
        assertEquals(LayoutMode.PHONE, layoutMode(WindowSizeClass.compute(839f, 800f)))
    }

    @Test fun `exactly at the expanded breakpoint (840dp) maps to TWO_PANE`() {
        assertEquals(LayoutMode.TWO_PANE, layoutMode(WindowSizeClass.compute(840f, 800f)))
    }

    @Test fun `well above the expanded breakpoint maps to TWO_PANE`() {
        assertEquals(LayoutMode.TWO_PANE, layoutMode(WindowSizeClass.compute(1200f, 800f)))
    }

    @Test fun `the old 900-980dp dead zone band now agrees on TWO_PANE throughout`() {
        // The exact band AppNav.kt's own former kdoc named as the point of disagreement between
        // AppNav's full-window measurement and HomeScreen's own rail-reduced one -- both now read
        // the IDENTICAL WindowSizeClass (see layoutMode's own kdoc), so there is nothing left in
        // this band for the two call sites to disagree about.
        listOf(900f, 920f, 950f, 980f).forEach { width ->
            assertEquals(LayoutMode.TWO_PANE, layoutMode(WindowSizeClass.compute(width, 800f)), "width=$width")
        }
    }

    @Test fun `height alone never flips the verdict -- this app's breakpoint is width-only`() {
        // A tall-but-narrow window (e.g. a phone in portrait) and a short-but-narrow one must both
        // read PHONE; a wide window reads TWO_PANE regardless of height. Guards against a future
        // edit accidentally switching to isAtLeastBreakpoint's combined width+height check.
        assertEquals(LayoutMode.PHONE, layoutMode(WindowSizeClass.compute(400f, 1600f)))
        assertEquals(LayoutMode.TWO_PANE, layoutMode(WindowSizeClass.compute(900f, 300f)))
    }
}
