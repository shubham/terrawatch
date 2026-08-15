package com.yugma.terrawatch.ads

/**
 * Task 6 (Plan 4) STUB — RevenueCat ad-revenue wiring (spec §8: "AdMob impressions reported through
 * RevenueCat's ad-monetization AdTracker... so ads + IAP land in one revenue dashboard"). TODO
 * (Task 8, once a RevenueCat account + AdMob mediation both exist): replace this no-op with a real
 * call into `purchases-android`'s `AdTracker`/`loadAndTrack` surface
 * (revenuecat.com/docs/ad-monetization/admob — requires `purchases-android` 8.0+ and is itself an
 * `@ExperimentalPreviewRevenueCatPurchasesAPI`-gated preview surface as of this research). Wiring it
 * now would be speculative rather than real integration work: there is no RevenueCat account this
 * repo can configure a real dashboard product against yet (a USER-GATED prerequisite, plan's own
 * Global Constraints), so any call shape written today is untestable end-to-end regardless of how
 * carefully it's researched.
 *
 * [onAdImpression] is called from [BannerAdSlot]'s real android actual (its `AdListener.
 * onAdImpression()` override) on every real ad impression, TEST ads included — the call SITE
 * already exists end-to-end; only this function's body is a stub. Ad revenue doesn't count toward
 * RevenueCat's billable MTR regardless (spec §8), so this is free to wire whenever Task 8 lands.
 */
object AdRevenueTracker {
    fun onAdImpression(adUnitId: String) {
        // TODO (Task 8): purchases-android AdTracker.trackAdImpression(...)/loadAndTrack(...) once
        // a real RevenueCat account + AdMob mediation exist. No-op today — TEST ads carry no real
        // revenue to track anyway.
    }
}
