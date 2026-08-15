package com.yugma.terrawatch.home

import androidx.window.core.layout.WindowSizeClass

/** [HomeScreen]'s two chrome arrangements — see [layoutMode]'s own kdoc for the breakpoint. */
enum class LayoutMode { PHONE, TWO_PANE }

/**
 * Plan 4 Task 4 (c): replaces the old raw-width breakpoint (a plain `widthDp: Int >= 900`, each
 * caller feeding it its own independently-measured `BoxWithConstraints.maxWidth`) with
 * material3-adaptive's own [WindowSizeClass] — compact/medium both collapse to [LayoutMode.PHONE];
 * expanded (width >= [WindowSizeClass.WIDTH_DP_EXPANDED_LOWER_BOUND], 840dp) maps to
 * [LayoutMode.TWO_PANE].
 *
 * THE bug this closes (`AppNav.kt`'s own former "900-980dp dead zone" kdoc, deleted along with the
 * code it described): `AppNav`'s rail-vs-bottom-bar decision and `HomeScreen`'s own pane decision
 * used to each run an INDEPENDENT `BoxWithConstraints` measurement — `AppNav`'s measured the FULL
 * window, `HomeScreen`'s measured whatever was left over ONCE `AppNav`'s own rail had already
 * claimed its ~80dp — so in the 900-980dp window-width band the rail could show while `HomeScreen`'s
 * own (narrower, rail-reduced) measurement still fell under that same 900dp cutoff and rendered its
 * phone layout underneath it, disagreeing with the chrome around it. Both callers now feed this
 * function the exact same `currentWindowAdaptiveInfo().windowSizeClass` — a read of the FULL
 * window's size, not whatever's locally left over after a sibling has already claimed space — so the
 * two call sites can no longer structurally disagree with each other: there is exactly one source of
 * truth, read identically by both.
 *
 * Pure and Compose-runtime-free: [WindowSizeClass.compute] (unlike `currentWindowAdaptiveInfo()`
 * itself, which needs a live composition to read the window's actual size) is a plain factory
 * method — decompiled-verified against the real `androidx.window.core.layout.WindowSizeClass` class
 * this Task 4 (c) dependency transitively pulls in (not assumed from Google's own public API docs
 * alone, matching this codebase's established "verify against the real jar" discipline — see
 * `AppNav.kt`'s own `CameraStateSaver` kdoc for the precedent) — so this mapping is directly
 * unit-testable (`LayoutModeTest`, jvmTest) with no Compose runtime in the loop, same "pure fn
 * decoupled from the Compose API" shape this function's prior `Int`-based version already had.
 */
fun layoutMode(windowSizeClass: WindowSizeClass): LayoutMode =
    if (windowSizeClass.isWidthAtLeastBreakpoint(WindowSizeClass.WIDTH_DP_EXPANDED_LOWER_BOUND)) {
        LayoutMode.TWO_PANE
    } else {
        LayoutMode.PHONE
    }
