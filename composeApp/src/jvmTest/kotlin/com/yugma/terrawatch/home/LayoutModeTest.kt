package com.yugma.terrawatch.home

import androidx.window.core.layout.WindowSizeClass
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Plan 4 Task 4 (c): TDD for [layoutMode] — the extracted mapping fn this task's dispatch calls for
 * ("TDD any extracted mapping fn"). [WindowSizeClass.compute] is a plain, Compose-runtime-free
 * factory (decompiled-verified — see `layoutMode`'s own kdoc), so this whole boundary can be pinned
 * with plain `kotlin.test` assertions, no Compose UI test harness needed.
 *
 * **Fix round (post-review):** [layoutMode] gained a height gate — expanded width alone no longer
 * maps to [LayoutMode.TWO_PANE]; height must also clear [WindowSizeClass.HEIGHT_DP_MEDIUM_LOWER_BOUND]
 * (480dp) — see `layoutMode`'s own kdoc for the landscape-phone bug this closes. Every case below
 * that only ever meant to isolate the WIDTH axis (narrow/medium/839/840/well-above/dead-zone) now
 * pins its height comfortably above 480dp in an inline comment, so it keeps testing exactly one axis
 * at a time; the two new device-evidence cases (941x424, 1280x800) and the rewritten height-axis
 * case exercise the new gate itself.
 */
class LayoutModeTest {
    @Test fun `narrow compact width maps to PHONE`() {
        assertEquals(LayoutMode.PHONE, layoutMode(WindowSizeClass.compute(400f, 800f)))
    }

    @Test fun `medium width (600dp) maps to PHONE`() {
        assertEquals(LayoutMode.PHONE, layoutMode(WindowSizeClass.compute(600f, 800f)))
    }

    @Test fun `just under the expanded breakpoint (839dp) maps to PHONE`() {
        // height (800dp) fixed comfortably above the 480dp height gate -- this isolates the width axis.
        assertEquals(LayoutMode.PHONE, layoutMode(WindowSizeClass.compute(839f, 800f)))
    }

    @Test fun `exactly at the expanded breakpoint (840dp) maps to TWO_PANE`() {
        // height (800dp) fixed comfortably above the 480dp height gate -- this isolates the width axis.
        assertEquals(LayoutMode.TWO_PANE, layoutMode(WindowSizeClass.compute(840f, 800f)))
    }

    @Test fun `well above the expanded breakpoint maps to TWO_PANE`() {
        // height (800dp) fixed comfortably above the 480dp height gate -- this isolates the width axis.
        assertEquals(LayoutMode.TWO_PANE, layoutMode(WindowSizeClass.compute(1200f, 800f)))
    }

    @Test fun `the old 900-980dp dead zone band now agrees on TWO_PANE throughout`() {
        // The exact band AppNav.kt's own former kdoc named as the point of disagreement between
        // AppNav's full-window measurement and HomeScreen's own rail-reduced one -- both now read
        // the IDENTICAL WindowSizeClass (see layoutMode's own kdoc), so there is nothing left in
        // this band for the two call sites to disagree about. Height (800dp) held well above the
        // 480dp gate throughout, so this stays a pure width-axis check.
        listOf(900f, 920f, 950f, 980f).forEach { width ->
            assertEquals(LayoutMode.TWO_PANE, layoutMode(WindowSizeClass.compute(width, 800f)), "width=$width")
        }
    }

    @Test fun `height below the MEDIUM lower bound blocks TWO_PANE even at expanded width`() {
        // Fix round (post-review): this case used to be named "height alone never flips the verdict"
        // and asserted TWO_PANE for the second line below -- that was exactly the bug the review
        // caught. A wide-but-short window (expanded width, sub-480dp height) is the landscape-phone
        // shape (see the literal device-evidence case right below this test) and must NOT get
        // two-pane tablet chrome. A tall-but-narrow window still can't reach TWO_PANE either -- it
        // fails the width gate regardless of height -- so that half of the old invariant still holds,
        // just no longer for the same reason as the (now-reversed) wide-but-short half.
        assertEquals(LayoutMode.PHONE, layoutMode(WindowSizeClass.compute(400f, 1600f)))
        assertEquals(LayoutMode.PHONE, layoutMode(WindowSizeClass.compute(900f, 300f)))
    }

    @Test fun `941x424 landscape phone maps to PHONE`() {
        // Real device evidence (98bc1cd8, landscape): width alone (941dp) clears the 840dp expanded
        // breakpoint, but height (424dp) falls well short of the 480dp MEDIUM lower bound -- the
        // exact review finding the height gate exists to fix. Must render PhoneLayout (full map +
        // sheet), never AppNavigationRail + HomeScreen's two-pane split.
        assertEquals(LayoutMode.PHONE, layoutMode(WindowSizeClass.compute(941f, 424f)))
    }

    @Test fun `1280x800 tablet maps to TWO_PANE`() {
        // A real tablet/desktop landscape shape: clears both the 840dp width gate and the 480dp
        // height gate comfortably, so it keeps its two-pane chrome under the new, stricter check.
        assertEquals(LayoutMode.TWO_PANE, layoutMode(WindowSizeClass.compute(1280f, 800f)))
    }
}
