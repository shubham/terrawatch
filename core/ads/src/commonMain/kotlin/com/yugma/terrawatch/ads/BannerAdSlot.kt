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
 * [visible] is fed [adSlotVisible] (below), the pure, TDD'd rule (spec §8) — see
 * [AdSlotVisibilityTest][com.yugma.terrawatch.ads.AdSlotVisibilityTest]'s own 2^2 table: ads show
 * unless the detail sheet is open or the user is mid-onboarding.
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
 * pause-not-destroy) apart from "onboarding not finished" (rare, one-time, wants a genuine
 * `destroy()`) — a distinction [adSlotVisible]'s own single `Boolean` can't carry, since it ANDs
 * both negations into one flat result by design (see that function's own kdoc). So the split lives
 * at the CALL SITE instead: `AppNav.kt` only calls this composable at all while `!isOnboarding`
 * (an onboarding change unmounts this composable structurally, which IS a real `destroy()` via
 * `onRelease` — see the android actual) and passes the full [adSlotVisible] (both inputs,
 * [isDetailOpen] included) as [visible] for the fine-grained height/pause decision underneath that.
 * `isDetailOpen` toggling therefore never reaches a point where this composable is removed from
 * composition at all — only its reserved height and its `AdView`'s pause state change.
 */
@Composable
expect fun BannerAdSlot(visible: Boolean, reducedMotion: Boolean = false, modifier: Modifier = Modifier)

/**
 * The ad-ethics rule (spec §8), as one pure, TDD'd truth table: the banner shows unless the quake
 * detail sheet is open or the user is mid-onboarding. A plain AND of two negations — not a
 * priority-ordered set of overrides, so there is no "which one wins" question to get wrong.
 *
 * This used to take a third input, `isPlusActive`. TerraWatch Plus was withdrawn before it ever
 * sold, so there is no longer any user for whom ads are suppressed wholesale, and the parameter was
 * removed rather than left permanently `false` — a parameter that only ever takes one value is a
 * lie about the rule.
 */
fun adSlotVisible(isDetailOpen: Boolean, isOnboarding: Boolean): Boolean =
    !isDetailOpen && !isOnboarding

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
 * [eligible] is [adSlotVisible]'s result (both ad-ethics inputs, [BannerAdSlot]'s own kdoc) — so
 * the slot collapses to 0 on EXACTLY the cases that function already hides the banner for
 * (detail-open/onboarding), and reserves the full adaptive height otherwise. Because this
 * never reads fill state, the slot's footprint is already at its final size for the whole time an
 * ad is loading — a creative arriving later only changes the `AdView`'s own alpha inside an
 * ALREADY-stable box, never the box itself. THE fix for "layout jump when banner fills."
 */
fun adSlotReservedHeightDp(eligible: Boolean, adaptiveHeightDp: Int): Int =
    if (eligible) adaptiveHeightDp else 0
