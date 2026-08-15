package com.yugma.terrawatch.monetization

import com.revenuecat.purchases.kmp.LogLevel
import com.revenuecat.purchases.kmp.Purchases
import com.revenuecat.purchases.kmp.configure
import com.revenuecat.purchases.kmp.ktx.awaitCustomerInfo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * Task 6 (Plan 4): the identifier this app expects RevenueCat's own dashboard to name the
 * "TerraWatch Plus" entitlement. Task 8 (once a real RevenueCat account/product exists) MUST
 * configure the dashboard entitlement under exactly this identifier, or [RevenueCatEntitlements]
 * will never observe it as active regardless of a real, completed purchase. `internal` — no other
 * file in this module needs it.
 */
internal const val PLUS_ENTITLEMENT_IDENTIFIER = "plus"

/**
 * Task 6 (Plan 4): the real, android-gated RevenueCat-backed [EntitlementsProvider].
 * [revenueCatKeyIsConfigured] (this module's own pure gate fn) is what decides whether this class
 * is ever constructed at all — every real androidMain call site in this app
 * (`KoinBootstrap.android.kt`) only reaches this constructor once [apiKey] has already been proven
 * non-blank.
 *
 * **UNREACHABLE IN PRACTICE as of Task 6**: no RevenueCat account/dashboard exists yet (a
 * USER-GATED prerequisite, plan's own Global Constraints) — `composeApp/monetization.properties`'s
 * `REVENUECAT_API_KEY` line is absent/blank on every build this task ships, so
 * `KoinBootstrap.android.kt`'s own gate always resolves to [AlwaysFreeEntitlements] instead. Every
 * API surface below was confirmed against the REAL resolved `purchases-kmp-core-android`/
 * `purchases-kmp-models-android` 3.5.0 AARs — `javap -p` against their extracted `classes.jar`
 * (same "verify by actually reading the real bytecode, not just the SDK's own docs" discipline
 * this codebase's `QuakeMap.android.kt`/maplibre-compose history already established for an
 * unfamiliar third-party API) — AND a real `:core:monetization:compileDebugKotlinAndroid` pass.
 * One real, load-bearing correction that first-pass research (RevenueCat's own docs/codelab) got
 * subtly wrong: `awaitCustomerInfo()` is not a member of `Purchases` — it's a suspend EXTENSION
 * function declared in `com.revenuecat.purchases.kmp.ktx` (confirmed via
 * `CoroutinesKt.awaitCustomerInfo(Purchases, CacheFetchPolicy, Continuation)`'s real signature),
 * so calling it needs that package's own explicit import or the call site fails with an
 * "unresolved reference" the docs never hinted at. Everything else (`Purchases.configure`'s
 * `(apiKey, Builder.() -> Unit)` shape, `CustomerInfo.entitlements` returning an indexable
 * `EntitlementInfos` with a real `get(String): EntitlementInfo?` and `EntitlementInfo.isActive`)
 * matched the docs/codelab's own shape exactly once checked against the real bytecode.
 *
 * `Purchases.configure` initializes the SDK — Android's own `Context` is captured internally via
 * AndroidX App Startup per RevenueCat's own KMP docs, so no `Context` parameter is threaded through
 * here (this app also has "no account, ever" — spec §3.6 — so the trailing configuration block is
 * left empty rather than setting an explicit `appUserId`; RevenueCat's own anonymous, SDK-generated
 * id is exactly what an account-less app wants).
 *
 * [isPlusActive] seeds from one [Purchases.sharedInstance] `awaitCustomerInfo()` call at
 * construction time. A live-updating listener (so a purchase completing WHILE this app is already
 * running flips this StateFlow with no restart needed) is real, valuable follow-up work,
 * deliberately deferred to Task 8 alongside the rest of the real purchase flow: there is no
 * dashboard/product to test such a listener against yet, and this class cannot be exercised
 * end-to-end regardless until one exists.
 *
 * Defensive `runCatching` around the fetch: this is real network + real SDK-state code that has
 * never run against a real backend in this repo — any failure (bad key format, no network, an SDK
 * surface this task's research got subtly wrong at the RUNTIME-behavior level, as opposed to the
 * compile-time level a real compile already rules out) degrades [isPlusActive] to `false` (the
 * safe, ads-showing default) rather than crashing app startup — matching this codebase's
 * established "network calls degrade gracefully, never crash" convention (e.g. `GdeltClient`,
 * `UsgsApi`).
 */
class RevenueCatEntitlements(apiKey: String) : EntitlementsProvider {
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private val _isPlusActive = MutableStateFlow(false)
    override val isPlusActive: StateFlow<Boolean> = _isPlusActive

    init {
        Purchases.logLevel = LogLevel.WARN
        Purchases.configure(apiKey = apiKey) {}
        scope.launch {
            runCatching { Purchases.sharedInstance.awaitCustomerInfo() }
                .onSuccess { customerInfo ->
                    _isPlusActive.value = customerInfo.entitlements[PLUS_ENTITLEMENT_IDENTIFIER]?.isActive == true
                }
        }
    }
}
