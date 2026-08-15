package com.yugma.terrawatch.ads

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/**
 * Task 6 (Plan 4): the app's one ad surface — spec §5.1 (`core:ads` module) / §8 (monetization +
 * ad ethics). `android` actual: a real `play-services-ads` anchored adaptive banner, Google's own
 * TEST unit id until a real `ADMOB_BANNER_UNIT` is configured (Task 8) — see
 * `BannerAdSlot.android.kt`'s own kdoc. `jvm`/`wasmJs` actuals: empty composables — the Android-only
 * runtime scope directive (Plan 4 Task 4) means neither target renders ads at all, matching spec
 * §7's own platform table ("Ads: Android [only]").
 *
 * [visible] is the ONE thing every caller must get right — [adSlotVisible] (below) is the pure,
 * TDD'd rule (spec §8, IMMUTABLE): ads only when NOT Plus, NOT detail-open, NOT onboarding. This
 * composable itself trusts [visible] completely and does no gating of its own — `AppNav.kt`'s real
 * call site is the one place that computes it; this function's own contract ("render nothing at
 * all when told not to") is proven by the `if (!visible) return` shape every actual shares, not by
 * a separate Compose test.
 *
 * Renders NOTHING (not "ad reserved-but-blank space") when [visible] is `false` — a deliberate,
 * conservative reading of spec §8's "banner hidden" rule: a fully-collapsed slot is unambiguously
 * hidden, verifiable even independent of whatever happens to be layered on top of it
 * (`DetailSheet`'s own modal, in practice, per `AppNav.kt`'s call site).
 */
@Composable
expect fun BannerAdSlot(visible: Boolean, modifier: Modifier = Modifier)

/**
 * Task 6 (Plan 4): spec §8's ad-ethics rule (IMMUTABLE), as one pure, TDD'd truth table — the
 * banner shows only when ALL THREE hold: the user is NOT TerraWatch Plus, the quake detail sheet is
 * NOT open, and the user is NOT mid-onboarding. Any one of the three being true hides it — a plain
 * AND of three negations, not a priority-ordered set of overrides, so there is no "which one wins"
 * question to get wrong (see [AdSlotVisibilityTest][com.yugma.terrawatch.ads.AdSlotVisibilityTest]'s
 * own full 2^3 case coverage).
 */
fun adSlotVisible(isPlusActive: Boolean, isDetailOpen: Boolean, isOnboarding: Boolean): Boolean =
    !isPlusActive && !isDetailOpen && !isOnboarding
