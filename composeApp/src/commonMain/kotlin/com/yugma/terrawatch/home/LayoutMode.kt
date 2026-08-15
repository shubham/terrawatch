package com.yugma.terrawatch.home

import androidx.window.core.layout.WindowSizeClass

/** [HomeScreen]'s two chrome arrangements — see [layoutMode]'s own kdoc for the breakpoint. */
enum class LayoutMode { PHONE, TWO_PANE }

/**
 * Plan 4 Task 4 (c): replaces the old raw-width breakpoint (a plain `widthDp: Int >= 900`, each
 * caller feeding it its own independently-measured `BoxWithConstraints.maxWidth`) with
 * material3-adaptive's own [WindowSizeClass] — compact/medium both collapse to [LayoutMode.PHONE];
 * expanded width (>= [WindowSizeClass.WIDTH_DP_EXPANDED_LOWER_BOUND], 840dp) **together with** a
 * tall-enough height (>= [WindowSizeClass.HEIGHT_DP_MEDIUM_LOWER_BOUND], 480dp — see the "fix round"
 * paragraph below for why height joined this check) map to [LayoutMode.TWO_PANE].
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
 *
 * **Fix round (post-review): why height had to join the check.** The width-only version of this
 * function that first shipped in Task 4 (c) flipped to [LayoutMode.TWO_PANE] on width alone. Review
 * caught the real case that misses: a **landscape phone**. Rotate almost any phone on its side and
 * width alone can cross 840dp while height collapses to a few hundred dp — this device's own real
 * measurement, 98bc1cd8 in landscape, is **941dp wide x 424dp tall** (`LayoutModeTest`'s own
 * `941x424 landscape phone` case pins this exact number). Width-expanded, nowhere near
 * tablet-proportioned in the other dimension. A width-only check would still hand that rotated phone
 * `AppNavigationRail` plus `HomeScreen`'s fixed-360dp two-pane split — tablet chrome squeezed into
 * 424dp of vertical space, strictly worse than the `PhoneLayout` it was rotated away from. Gating on
 * [WindowSizeClass.HEIGHT_DP_MEDIUM_LOWER_BOUND] (480dp) alongside the existing width gate pulls
 * exactly this case back down to [LayoutMode.PHONE], without re-opening the 900-980dp dead zone
 * above: real tablets/desktops clear both bounds together (`LayoutModeTest`'s own `1280x800 tablet`
 * case; the emulator's `task4-adaptive-840dp-two-pane.png` capture was also comfortably tall).
 *
 * Why [WindowSizeClass.HEIGHT_DP_MEDIUM_LOWER_BOUND] (480dp) rather than the stricter
 * [WindowSizeClass.HEIGHT_DP_EXPANDED_LOWER_BOUND] (900dp)? A real landscape tablet — e.g. 1280dp x
 * 800dp — clears MEDIUM comfortably but falls well short of EXPANDED height, and still deserves
 * [LayoutMode.TWO_PANE] on any reasonable reading of "this is a tablet, not a phone". MEDIUM is high
 * enough to reject the 424dp landscape-phone data point above while staying low enough to admit
 * every realistic tablet/desktop landscape height — the controller-level ruling this fix round
 * implements.
 *
 * `isHeightAtLeastBreakpoint`/`HEIGHT_DP_MEDIUM_LOWER_BOUND` decompile-verified the same way
 * `isWidthAtLeastBreakpoint`/`WIDTH_DP_EXPANDED_LOWER_BOUND` were above: `javap`'d against the
 * actual RESOLVED `androidx.window:window-core-android:1.5.0` AAR `classes.jar` — the version
 * `./gradlew :composeApp:dependencies --configuration debugRuntimeClasspath` genuinely resolves for
 * this project's android target (`org.jetbrains.androidx.window:window-core:1.4.0`'s own android
 * variant delegates straight to it), not assumed from Google's public docs alone. Confirmed present:
 * `public final boolean isHeightAtLeastBreakpoint(int)` alongside the already-used
 * `isWidthAtLeastBreakpoint(int)`, and `public static final int HEIGHT_DP_MEDIUM_LOWER_BOUND = 480`
 * as a real compile-time-constant static field, same shape as `WIDTH_DP_EXPANDED_LOWER_BOUND`. (The
 * same jar also exposes a combined `isAtLeastBreakpoint(widthBreakpoint, heightBreakpoint)` —
 * bytecode-disassembly-confirmed to be exactly `isWidthAtLeastBreakpoint(width) &&
 * isHeightAtLeastBreakpoint(height)` — kept below as two separate named calls instead, so each
 * breakpoint constant's own name stays next to the call it gates rather than collapsing into two
 * bare positional `Int`s.)
 */
fun layoutMode(windowSizeClass: WindowSizeClass): LayoutMode =
    if (windowSizeClass.isWidthAtLeastBreakpoint(WindowSizeClass.WIDTH_DP_EXPANDED_LOWER_BOUND) &&
        windowSizeClass.isHeightAtLeastBreakpoint(WindowSizeClass.HEIGHT_DP_MEDIUM_LOWER_BOUND)
    ) {
        LayoutMode.TWO_PANE
    } else {
        LayoutMode.PHONE
    }
