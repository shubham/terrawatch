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
 * [visible] is fed [adSlotVisible] (below), the pure, TDD'd rule (spec §8, IMMUTABLE, UNCHANGED by
 * Plan 5 Task 3 — see [AdSlotVisibilityTest][com.yugma.terrawatch.ads.AdSlotVisibilityTest]'s own
 * still-green 2^3 table): ads only when NOT Plus, NOT detail-open, NOT onboarding.
 *
 * **Plan 5 Task 3 contract change (user dogfooding: "ads appearing causes glitchy experience") —
 * [visible] no longer means "render nothing at all."** Two independent glitches, two independent
 * fixes, both owned by the `android` actual (`BannerAdSlot.android.kt`'s own kdoc has the full
 * story):
 * 1. **Layout jump on fill** (slot height 0 -> banner height, the moment a creative arrived) — fixed
 *    by [adSlotReservedHeightDp] (below): the slot's reserved height is now a function of
 *    ELIGIBILITY alone, precomputed and stable before any ad ever fills, never of fill state. The
 *    old "renders nothing when hidden" contract *itself* was part of this bug's surface — a
 *    zero-then-nonzero height swing is exactly a layout jump, just triggered by [visible] flipping
 *    rather than by fill.
 * 2. **Reload jank on hide -> show** (Task 6 review's "accepted v1 simplification": every re-show
 *    used to `destroy()` + brand-new `AdView` + a fresh `loadAd()`, i.e. a white flash + a second
 *    jump) — fixed by no longer tearing the `AndroidView` down when [visible] alone flips false.
 *    [visible]'s role is now "should this already-created, possibly-already-loaded `AdView` be
 *    VISUALLY showing and receiving refreshes right now" (height 0 + paused, vs. reserved height +
 *    resumed) — not "does it exist at all."
 *
 * That second fix needs a caller who can tell "detail sheet opened" (frequent, in-session, wants
 * pause-not-destroy) apart from "Plus purchased" / "onboarding not finished" (rare, one-time, wants
 * a genuine `destroy()`) — a distinction [adSlotVisible]'s own single `Boolean` can't carry, since
 * it ANDs all three negations into one flat result by design (see that function's own kdoc). So the
 * split lives at the CALL SITE instead: `AppNav.kt` only calls this composable at all while
 * `!isPlusActive && !isOnboarding` (Plus/onboarding changes unmount this composable structurally,
 * which IS a real `destroy()` via `onRelease` — see the android actual) and passes the full
 * [adSlotVisible] (all 3 inputs, [isDetailOpen] included) as [visible] for the fine-grained
 * height/pause decision underneath that. `isDetailOpen` toggling therefore never reaches a point
 * where this composable is removed from composition at all — only its reserved height and its
 * `AdView`'s pause state change.
 */
@Composable
expect fun BannerAdSlot(visible: Boolean, reducedMotion: Boolean = false, modifier: Modifier = Modifier)

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

/**
 * Plan 5 Task 3, TDD'd (spec: "ad-slot layout stability", user dogfooding "ads appearing causes
 * glitchy experience"): the slot's reserved LAYOUT height — deliberately a function of [eligible]
 * ALONE, never of whether an ad has actually loaded/filled. [adaptiveHeightDp] is the caller's own
 * precomputed `AdSize.getCurrentOrientationAnchoredAdaptiveBannerAdSize(context, widthDp).height`
 * (`BannerAdSlot.android.kt`), read straight back off the real, already-`setAdSize`'d `AdView`
 * instance so this can never disagree with whatever height that specific `AdView` was actually
 * built with — resolved BEFORE `loadAd()`'s async result ever arrives (Google's own adaptive-banner
 * sizing call is a synchronous, local, device-metrics-driven calculation, confirmed against the
 * real resolved `play-services-ads:25.4.0` artifact's own bytecode: `getCurrentOrientationAnchored
 * AdaptiveBannerAdSize` delegates straight to an internal `zzf.zzk(Context, Int, Int, Int)` helper
 * with no network-shaped call anywhere in that trace — main-thread-safe, not just main-thread-
 * legal).
 *
 * [eligible] is [adSlotVisible]'s result (all 3 ad-ethics inputs, [BannerAdSlot]'s own kdoc) — so
 * the slot collapses to 0 on EXACTLY the 3 cases that function already hides the banner for
 * (Plus/detail-open/onboarding), and reserves the full adaptive height otherwise. Because this
 * never reads fill state, the slot's footprint is already at its final size for the whole time an
 * ad is loading — a creative arriving later only changes the `AdView`'s own alpha inside an
 * ALREADY-stable box, never the box itself. THE fix for "layout jump when banner fills."
 */
fun adSlotReservedHeightDp(eligible: Boolean, adaptiveHeightDp: Int): Int =
    if (eligible) adaptiveHeightDp else 0
