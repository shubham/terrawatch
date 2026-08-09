package com.yugma.terrawatch.home

/** [HomeScreen]'s two chrome arrangements — see [layoutMode]'s own kdoc for the breakpoint. */
enum class LayoutMode { PHONE, TWO_PANE }

/**
 * Task 12 (spike decision, see `docs/superpowers/plans/2026-08-08-terrawatch-plan-2-ui-shell.md`'s
 * Task 12 section): the desktop/tablet two-pane breakpoint. A pure function of raw width, deliberately
 * decoupled from any Compose API (`BoxWithConstraints`, `Dp`, ...) so it's trivially unit-testable
 * without a Compose runtime — [HomeScreen] is the only caller, feeding it its own
 * `BoxWithConstraintsScope.maxWidth.value.toInt()`.
 *
 * 900dp is inclusive on the [LayoutMode.TWO_PANE] side (`>=`, not `>`): the fixed 360dp right panel
 * (`HomeScreen.kt`'s `TwoPaneLayout`) plus a map pane that's still usable rather than a sliver needs
 * a few hundred dp of width to spare over the panel alone, and 900dp is comfortably past that while
 * still admitting typical unfolded-foldable/small-tablet widths — narrower than that, the phone
 * layout's full-bleed map with a peekable sheet makes better use of the space than two cramped
 * columns would.
 */
fun layoutMode(widthDp: Int): LayoutMode =
    if (widthDp >= 900) LayoutMode.TWO_PANE else LayoutMode.PHONE
