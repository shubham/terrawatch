package com.yugma.terrawatch.monetization

/**
 * Task 6 (Plan 4): the one decision `RevenueCatEntitlements`'s own android-gated construction
 * collapses to — extracted as its own pure, TDD'd function (this task's own brief: "TDD
 * entitlements gate logic (pure)") so the RULE is unit-tested without pulling
 * `RevenueCatEntitlements` (a real `purchases-kmp-core` dependency, android-only) into a
 * `commonTest` run at all.
 *
 * `null`/blank means "no configured key" — `composeApp/monetization.properties` absent entirely,
 * or present with an empty `REVENUECAT_API_KEY=` line (this repo's actual state throughout Task 6:
 * no RevenueCat account exists yet, a USER-GATED prerequisite named in the plan's own Global
 * Constraints). Blank (not just null) matters specifically because the real value is read from
 * Android manifest metadata sourced from a `java.util.Properties` file (`composeApp/build.gradle.kts`
 * / `KoinBootstrap.android.kt`) — a present-but-empty template line resolves to `""`, never `null`,
 * so a null-only check would silently treat an intentionally-blank line as "configured."
 */
fun revenueCatKeyIsConfigured(apiKey: String?): Boolean = !apiKey.isNullOrBlank()
