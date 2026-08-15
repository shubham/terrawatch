package com.yugma.terrawatch.monetization

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Task 6 (Plan 4): the one thing every screen that cares about "is this a paying TerraWatch Plus
 * user" needs to know — spec §8's ad-ethics rule (`core:ads`' own `adSlotVisible`) and Settings'
 * "TerraWatch Plus" row both read this directly, never RevenueCat's own SDK types. Kept to exactly
 * this one property per this task's own brief — no `purchase()`/`restorePurchases()`/paywall-launch
 * surface here at all: Task 8 ("purchases-kmp-ui paywall wiring") is where a real purchase FLOW
 * lands; this interface only ever answers "is Plus currently active," which is all `adSlotVisible`
 * and the Settings row need today. Plus-gates themselves (unlimited saved places, custom rules —
 * spec §8) are NOT enforced anywhere yet regardless of this value; see this task's own report for
 * the "free tier keeps everything until RC live" ruling.
 *
 * `StateFlow`, not a one-shot suspend fun: both real call sites (`AppNav`'s ad-slot gate,
 * `SettingsViewModel`'s mirrored `isPlusActive`) need to react live to a purchase completing or
 * lapsing while the app is already running — the same "live StateFlow mirror" shape every other
 * store-backed setting in this app already uses (e.g. `AlertRuleStore`/`ThemeStore`).
 */
interface EntitlementsProvider {
    val isPlusActive: StateFlow<Boolean>
}

/**
 * Task 6 (Plan 4): the entitlements provider for every target that has no configured RevenueCat
 * integration — every jvm/wasmJs build (Android-only runtime scope directive, in force since Plan 4
 * Task 4: zero monetization-feature investment on desktop/web), AND Android itself for as long as
 * `composeApp/monetization.properties`'s `REVENUECAT_API_KEY` stays absent/blank (this repo's real
 * state throughout Task 6 — no RevenueCat account exists yet, a USER-GATED prerequisite the plan's
 * own Global Constraints section names). `isPlusActive` is a constant `false` — the free tier, one
 * anchored banner showing, per spec §8's own "Free tier: everything, with one anchored banner"
 * framing.
 *
 * Naming/scope note: the design doc's original §7 footnote sketched desktop/web as getting
 * Plus-only features unlocked-without-purchase (no billing surface there). This task's own
 * dispatch supersedes that for jvm/wasmJs explicitly ("AlwaysFreeEntitlements: jvm/wasm +
 * android-until-RC-configured"), consistent with the Android-only scope directive already
 * deferring all desktop/web feature investment — so this object means "always false," not "always
 * unlocked," on every target that reaches it.
 */
object AlwaysFreeEntitlements : EntitlementsProvider {
    override val isPlusActive: StateFlow<Boolean> = MutableStateFlow(false)
}
