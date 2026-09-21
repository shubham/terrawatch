# Ads-Only Monetization Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Remove the TerraWatch Plus one-time purchase entirely, show the banner ad to every user, cap favourite places at 5 for everyone, and grow the city picker to 100+ seismically-weighted cities with search — then ship 1.1.0.

**Architecture:** The `core/monetization` module is deleted outright; its one surviving rule (the favourites cap) moves to `core/model` as a plain product limit. `purchases-kmp-core` survives but relocates to `core/ads`, where `AdRevenueTracker` is now its only consumer. Ad visibility drops from a 3-input rule to a 2-input one. The city catalog moves out of the dialog file into its own `CityCatalog.kt` with a pure, unit-tested search filter.

**Tech Stack:** Kotlin Multiplatform, Compose Multiplatform, Koin, SQLDelight, `play-services-ads` 25.4.0, `purchases-kmp-core` 3.5.0, kotlin.test.

**Spec:** `docs/superpowers/specs/2026-09-21-ads-only-monetization-design.md`

**Parked code:** the deleted purchase/paywall implementation is preserved in full on branch `feat/plus-purchase-parked` (commit `bfdbd8b`). Nothing in this plan needs to preserve it inline — delete freely.

## Global Constraints

- **No user has ever purchased Plus.** `REVENUECAT_API_KEY` has always been blank. No grandfathering, no refunds, no entitlement path to keep. If evidence contradicts this, STOP and escalate.
- **`MAX_FAVORITE_PLACES = 5`**, applied to every user identically.
- **Banner ads only.** Do not add interstitial, native, rewarded, or app-open ad formats.
- **The app must run fully with a blank `REVENUECAT_API_KEY`** — never call `Purchases.configure` with a blank key, and keep `AdRevenueTracker`'s `Purchases.isConfigured` guard. It throws on every paid event without it (commit `3780e11`).
- **Argument order in the `appModule(...)` call is load-bearing** (Kotlin evaluates arguments left to right; commit `415e76c`). When removing parameters, preserve the relative order of those that remain.
- **Do not touch edge-to-edge APIs.** Commit `de86081` settled the Play warning deliberately.
- **Version literals are hand-synced across three files** — `composeApp/build.gradle.kts:219-220`, `SettingsScreen.kt:130`, `README.md:9`.
- **Screenshot discipline:** save a device capture only after a `uiautomator` dump confirms a marker unique to that screen. Never grep the bare `AndroidRuntime` tag in a crash sweep — the uiautomator tooling logs benign startup lines under it.
- **Full test command:** `./gradlew jvmTest :core:database:verifySqlDelightMigration`. Baseline before this plan: 777 passing.
- Commit after every task. Never push or upload to Play without explicit owner confirmation.

---

### Task 1: Move the favourites cap to `core/model` as a flat limit of 5

**Files:**
- Create: `core/model/src/commonMain/kotlin/com/yugma/terrawatch/model/FavoritePlaceLimit.kt`
- Create: `core/model/src/commonTest/kotlin/com/yugma/terrawatch/model/FavoritePlaceLimitTest.kt`
- Delete: `core/monetization/src/commonMain/kotlin/com/yugma/terrawatch/monetization/FavoritePlaceGate.kt`
- Delete: `core/monetization/src/commonTest/kotlin/com/yugma/terrawatch/monetization/FavoritePlaceGateTest.kt`

**Interfaces:**
- Consumes: nothing.
- Produces: `com.yugma.terrawatch.model.MAX_FAVORITE_PLACES: Int` (= 5) and `com.yugma.terrawatch.model.canAddFavorite(currentCount: Int): Boolean`. Tasks 3 and 4 import both from `com.yugma.terrawatch.model`.

- [ ] **Step 1: Write the failing test**

Create `core/model/src/commonTest/kotlin/com/yugma/terrawatch/model/FavoritePlaceLimitTest.kt`:

```kotlin
package com.yugma.terrawatch.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FavoritePlaceLimitTest {

    @Test
    fun `the cap is five`() {
        assertEquals(5, MAX_FAVORITE_PLACES)
    }

    @Test
    fun `adding is allowed below the cap`() {
        assertTrue(canAddFavorite(currentCount = 0))
        assertTrue(canAddFavorite(currentCount = 1))
        assertTrue(canAddFavorite(currentCount = 4))
    }

    @Test
    fun `adding is blocked at and above the cap`() {
        assertFalse(canAddFavorite(currentCount = 5))
        assertFalse(canAddFavorite(currentCount = 6))
    }

    @Test
    fun `the cap applies identically to every user - there is no tier parameter`() {
        // Regression guard for the removed Plus tier: this function must stay single-argument.
        // If someone reintroduces an entitlement parameter, this file stops compiling, which is
        // the intended alarm.
        val allowed: (Int) -> Boolean = ::canAddFavorite
        assertTrue(allowed(0))
        assertFalse(allowed(5))
    }
}
```

- [ ] **Step 2: Run it and watch it fail**

Run: `./gradlew :core:model:jvmTest --tests "com.yugma.terrawatch.model.FavoritePlaceLimitTest"`
Expected: FAIL — unresolved reference `MAX_FAVORITE_PLACES` / `canAddFavorite`.

- [ ] **Step 3: Write the implementation**

Create `core/model/src/commonMain/kotlin/com/yugma/terrawatch/model/FavoritePlaceLimit.kt`:

```kotlin
package com.yugma.terrawatch.model

/**
 * How many favourite places a user may keep, beyond home — which is not a favourite row at all and
 * is never counted here.
 *
 * This used to be a monetization gate: the free tier allowed 1 and TerraWatch Plus removed the
 * limit entirely. Plus was withdrawn before it ever sold (see
 * `docs/superpowers/specs/2026-09-21-ads-only-monetization-design.md`), so the cap is now a plain
 * product limit applying identically to everyone, and it lives in `core/model` next to
 * [FavoritePlace] rather than in a monetization module that no longer exists.
 */
const val MAX_FAVORITE_PLACES = 5

/**
 * Whether another favourite can be added right now, given [currentCount] — the caller's current
 * favourite count, never including home.
 *
 * Deliberately unaware of where the count came from or what happens when it returns `false`. The
 * caller wires the consequence: `SettingsScreen` renders its "Add place" row disabled rather than
 * routing anywhere, since the paywall this once led to no longer exists.
 */
fun canAddFavorite(currentCount: Int): Boolean = currentCount < MAX_FAVORITE_PLACES
```

- [ ] **Step 4: Delete the old gate and its test**

```bash
git rm core/monetization/src/commonMain/kotlin/com/yugma/terrawatch/monetization/FavoritePlaceGate.kt
git rm core/monetization/src/commonTest/kotlin/com/yugma/terrawatch/monetization/FavoritePlaceGateTest.kt
```

Note: `SettingsViewModel` still imports the deleted function and will not compile until Task 3. That is expected; this task's test target (`:core:model:jvmTest`) does not depend on `composeApp`.

- [ ] **Step 5: Run the test and watch it pass**

Run: `./gradlew :core:model:jvmTest --tests "com.yugma.terrawatch.model.FavoritePlaceLimitTest"`
Expected: PASS, 4 tests.

- [ ] **Step 6: Commit**

```bash
git add core/model core/monetization
git commit -m "Move the favourites cap to core/model and flatten it to 5

It was a monetization gate (1 free, unlimited with Plus). Plus is being
withdrawn before it ever sold, so the cap is now a plain product limit
that applies to everyone, and it belongs beside FavoritePlace rather
than in a module about to be deleted."
```

---

### Task 2: Show the banner to everyone

**Files:**
- Modify: `core/ads/src/commonMain/kotlin/com/yugma/terrawatch/ads/BannerAdSlot.kt` (`adSlotVisible`, and the kdoc on `BannerAdSlot`/`adSlotReservedHeightDp` that describes the 3-input rule)
- Modify: `core/ads/src/commonTest/kotlin/com/yugma/terrawatch/ads/AdSlotVisibilityTest.kt`
- Modify: `composeApp/src/commonMain/kotlin/com/yugma/terrawatch/nav/AppNav.kt:284-292`
- Modify: `composeApp/src/jvmTest/kotlin/com/yugma/terrawatch/nav/AppNavAdEligibilityTest.kt` (remove Plus-dependent branches only)

**Interfaces:**
- Consumes: nothing.
- Produces: `adSlotVisible(isDetailOpen: Boolean, isOnboarding: Boolean): Boolean`. Both parameters keep their current meaning. `adSlotReservedHeightDp(eligible, adaptiveHeightDp)` is unchanged.

- [ ] **Step 1: Rewrite the truth-table test**

Replace the body of `AdSlotVisibilityTest` with the exhaustive 2^2 table:

```kotlin
package com.yugma.terrawatch.ads

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AdSlotVisibilityTest {

    @Test
    fun `visible only when neither suppressor is active`() {
        assertTrue(adSlotVisible(isDetailOpen = false, isOnboarding = false))
    }

    @Test
    fun `detail sheet open hides the banner`() {
        assertFalse(adSlotVisible(isDetailOpen = true, isOnboarding = false))
    }

    @Test
    fun `onboarding hides the banner`() {
        assertFalse(adSlotVisible(isDetailOpen = false, isOnboarding = true))
    }

    @Test
    fun `both suppressors at once still hides the banner`() {
        assertFalse(adSlotVisible(isDetailOpen = true, isOnboarding = true))
    }

    @Test
    fun `the full truth table is exhaustive - four cases, one visible`() {
        val cases = listOf(false, true).flatMap { d -> listOf(false, true).map { o -> d to o } }
        assertEquals(4, cases.size)
        assertEquals(1, cases.count { (d, o) -> adSlotVisible(isDetailOpen = d, isOnboarding = o) })
    }
}
```

- [ ] **Step 2: Run it and watch it fail**

Run: `./gradlew :core:ads:jvmTest --tests "com.yugma.terrawatch.ads.AdSlotVisibilityTest"`
Expected: FAIL — `adSlotVisible` still requires three arguments.

- [ ] **Step 3: Change the rule**

In `BannerAdSlot.kt`, replace `adSlotVisible` with:

```kotlin
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
```

Then update the prose in `BannerAdSlot`'s and `adSlotReservedHeightDp`'s kdoc: every phrase saying "ads only when NOT Plus, NOT detail-open, NOT onboarding", "2^3 table", or "all 3 ad-ethics inputs" must be corrected to the two-input rule. Leave the layout-stability and pause-vs-destroy explanations intact — that behaviour is unchanged and still correct.

- [ ] **Step 4: Update the AppNav call site**

In `AppNav.kt`, replace the block at lines 284-292 with:

```kotlin
                BannerAdSlot(
                    visible = adSlotVisible(
                        isDetailOpen = isDetailOpen,
                        isOnboarding = isOnboarding,
                    ) && isAdEligibleRoute(currentRoute),
                    reducedMotion = LocalReducedMotion.current,
                    modifier = Modifier.fillMaxWidth(),
                )
```

The enclosing `if (!isPlusActive && !isOnboarding) { ... }` becomes `if (!isOnboarding) { ... }`. Keep that `if` — onboarding must still unmount the `AdView` structurally (a genuine `destroy()` via `onRelease`), which is a different thing from hiding it. Update the surrounding comment accordingly, and leave `AppBottomBar`'s independent `if (showTabChrome)` gate alone.

- [ ] **Step 5: Strip Plus branches from the nav test**

In `AppNavAdEligibilityTest.kt`, delete any case that varies Plus state. Do not delete the route-eligibility cases — `isAdEligibleRoute` is unchanged and still the thing under test.

- [ ] **Step 6: Run both test targets**

Run: `./gradlew :core:ads:jvmTest :composeApp:jvmTest --tests "*AdSlot*" --tests "*AppNavAdEligibility*"`
Expected: PASS. If `composeApp` fails to compile on unrelated Plus references, that is Task 3's job — note it and continue.

- [ ] **Step 7: Commit**

```bash
git add core/ads composeApp/src/commonMain/kotlin/com/yugma/terrawatch/nav/AppNav.kt composeApp/src/jvmTest
git commit -m "Show the banner to every user, dropping the Plus input

adSlotVisible loses isPlusActive rather than being passed a permanent
false: a parameter with only one possible value misrepresents the rule.
Detail-sheet and onboarding suppression are unchanged and still right."
```

---

### Task 3: Delete the purchase flow, the paywall, and the `core/monetization` module

**Files:**
- Delete: `core/monetization/` (entire module, including `build.gradle.kts`)
- Delete: `composeApp/src/commonMain/kotlin/com/yugma/terrawatch/paywall/PaywallScreen.kt`
- Delete: `composeApp/src/commonMain/kotlin/com/yugma/terrawatch/paywall/PaywallViewModel.kt`
- Delete: `composeApp/src/jvmTest/kotlin/com/yugma/terrawatch/paywall/PaywallButtonStateTest.kt`
- Delete: `composeApp/src/jvmTest/kotlin/com/yugma/terrawatch/paywall/PaywallScreenTest.kt`
- Modify: `settings.gradle.kts:26-31`, `core/ads/build.gradle.kts`, `composeApp/build.gradle.kts`
- Modify: `composeApp/src/commonMain/kotlin/com/yugma/terrawatch/di/AppModule.kt`
- Modify: `composeApp/src/androidMain/kotlin/com/yugma/terrawatch/di/KoinBootstrap.android.kt`
- Modify: `composeApp/src/androidMain/kotlin/com/yugma/terrawatch/MainActivity.kt`
- Modify: `composeApp/src/commonMain/kotlin/com/yugma/terrawatch/nav/AppNav.kt` (routes + injection)
- Modify: `composeApp/src/commonMain/kotlin/com/yugma/terrawatch/settings/SettingsViewModel.kt`
- Modify: jvm and wasmJs `main.kt`
- Modify: `composeApp/src/jvmTest/kotlin/com/yugma/terrawatch/settings/SettingsViewModelTest.kt`

**Interfaces:**
- Consumes: `canAddFavorite(currentCount)` from Task 1.
- Produces: `SettingsViewModel.canAddFavorite(): Boolean` (unchanged name, now tier-free) and a `SettingsViewModel` constructor with no `entitlementsProvider` parameter. Task 4 depends on both.

- [ ] **Step 1: Delete the module and the paywall**

```bash
git rm -r core/monetization composeApp/src/commonMain/kotlin/com/yugma/terrawatch/paywall composeApp/src/jvmTest/kotlin/com/yugma/terrawatch/paywall
```

Remove the `:core:monetization` line from `settings.gradle.kts` and every `implementation(projects.core.monetization)` from module `build.gradle.kts` files (`grep -rn "core.monetization\|core:monetization" --include=*.kts .` finds them all).

- [ ] **Step 2: Move the RevenueCat dependency to `core/ads`**

`AdRevenueTracker` is now the only consumer of `purchases-kmp-core`. Add to `core/ads/build.gradle.kts`'s `androidMain` dependencies:

```kotlin
implementation(libs.revenuecat.purchases.kmp.core)
```

using whatever alias `gradle/libs.versions.toml:72-78` already defines — do not add a new version entry. Then remove that dependency anywhere it was declared solely for the deleted module.

- [ ] **Step 3: Relocate `Purchases.configure`**

In `KoinBootstrap.android.kt`, delete `buildEntitlementsProvider` and `buildPlusPurchases`. Keep `readRevenueCatApiKey`. Replace the configure call that lived inside `buildEntitlementsProvider` with a standalone initialiser called during bootstrap, before `MobileAds.initialize`:

```kotlin
/**
 * Configures RevenueCat, whose only remaining job is recording ad revenue (see
 * `core/ads/AdRevenueTracker.kt`). Purchases were removed in 1.1.0.
 *
 * A blank key is the normal, supported state: we simply never configure, and AdRevenueTracker's
 * own `Purchases.isConfigured` guard turns revenue tracking into a silent no-op. The app is fully
 * functional — ads included — with no RevenueCat account at all.
 */
private fun configureRevenueCatForAdRevenue(context: Context) {
    val key = readRevenueCatApiKey(context) ?: return
    if (key.isBlank()) return
    Purchases.configure(PurchasesConfiguration.Builder(context, key).build())
}
```

Match the exact `PurchasesConfiguration` builder shape the deleted `buildEntitlementsProvider` used — read it from `git show feat/plus-purchase-parked:composeApp/src/androidMain/kotlin/com/yugma/terrawatch/di/KoinBootstrap.android.kt` rather than inventing it.

- [ ] **Step 4: Rewire DI**

In `AppModule.kt`, drop the `entitlementsProvider` and `plusPurchases` parameters from `appModule(...)` and their `single {}` registrations. **Preserve the left-to-right order of the remaining parameters** (`http`, `dao`, `locationProvider`) — this is the load-bearing constraint from commit `415e76c`. Update every call site: `KoinBootstrap.android.kt`, jvm `main.kt`, wasmJs `main.kt`.

- [ ] **Step 5: Clean the ViewModel, nav, and MainActivity**

- `SettingsViewModel`: delete the `entitlementsProvider` constructor parameter and the `isPlusActive` property (line 116). Change `canAddFavorite()` to `fun canAddFavorite(): Boolean = canAddFavorite(currentCount = _favorites.value.size)`, importing from `com.yugma.terrawatch.model`. Rewrite its kdoc — the existing text describes routing to a paywall.
- `AppNav.kt`: remove `Routes.PAYWALL` (L73), its composable destination (L449-464), and the `entitlementsProvider` injection and `isPlusActive` value.
- `MainActivity.kt`: remove the `attemptSilentRestore` call.

- [ ] **Step 6: Fix the ViewModel test**

In `SettingsViewModelTest.kt`, delete the `isPlusActive` passthrough and live-update tests (L219-253) and the local `FakeEntitlementsProvider`. Replace the cap test at L330 with:

```kotlin
    @Test
    fun `canAddFavorite is false once five favorites exist`() = runTest {
        val vm = newViewModel()
        repeat(5) { i -> vm.addFavorite("Place $i", GeoPoint(0.0, 0.0)) }
        advanceUntilIdle()
        assertFalse(vm.canAddFavorite())
    }

    @Test
    fun `canAddFavorite is true below five favorites`() = runTest {
        val vm = newViewModel()
        repeat(4) { i -> vm.addFavorite("Place $i", GeoPoint(0.0, 0.0)) }
        advanceUntilIdle()
        assertTrue(vm.canAddFavorite())
    }
```

Adapt `newViewModel()` to the file's existing construction helper and remove the entitlements argument from it.

- [ ] **Step 7: Verify nothing references the dead subsystem**

```bash
grep -rniE "paywall|entitlement|isPlusActive|PlusPurchases|SilentRestore|terrawatch_plus" --include=*.kt --include=*.kts . | grep -v '/build/'
```

Expected: no hits outside comments that deliberately record the removal. Investigate every hit; do not suppress.

- [ ] **Step 8: Full build and test**

Run: `./gradlew jvmTest :core:database:verifySqlDelightMigration`
Expected: PASS. The total drops from 777 — that is correct, not a regression. Record the new number.

- [ ] **Step 9: Commit**

```bash
git add -A
git commit -m "Delete the Plus purchase, the paywall, and core/monetization

The module's last inhabitant moved to core/model in the previous commit,
so the module goes rather than lingering for one constant. RevenueCat
survives in a much smaller role - AdRevenueTracker is now its only
consumer, so purchases-kmp-core moves to core/ads and configure() moves
with it. A blank key stays a fully supported state.

The full implementation is preserved on feat/plus-purchase-parked."
```

---

### Task 4: Settings — remove the Plus row, disable "Add place" at the cap

**Files:**
- Modify: `composeApp/src/commonMain/kotlin/com/yugma/terrawatch/settings/SettingsScreen.kt` (L138 tag, L249-263 call site, L606-652 rows)

**Interfaces:**
- Consumes: `canAddFavorite()` from Task 3, `MAX_FAVORITE_PLACES` from Task 1.
- Produces: nothing downstream.

- [ ] **Step 1: Delete the Plus row**

Remove `PlusRow` (L631-652), the `SETTINGS_PLUS_ROW_TAG` constant (L138), and the entire `SettingsCard { SettingsSectionLabel("PLUS"); PlusRow(...) }` block from the screen body. Remove the `isPlusActive` local and the `onPlusClick` parameter from `SettingsScreen`'s signature, updating its caller in `AppNav.kt`.

- [ ] **Step 2: Make `AddPlaceRow` express the limit**

Replace `AddPlaceRow` (L606-621) with:

```kotlin
/**
 * The "Add place" action row. [enabled] is the caller's `canAddFavorite()` result: at
 * [MAX_FAVORITE_PLACES] the row goes visibly disabled and says so, rather than staying tappable and
 * doing nothing. It used to route to the paywall when blocked; that screen no longer exists, and a
 * tap that silently does nothing is worse than a row that explains itself before it is tapped.
 *
 * Still purely presentational — the decision is the caller's, same "dumb row, smart caller" split
 * the other action rows on this screen follow.
 */
@Composable
private fun AddPlaceRow(enabled: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier
            .fillMaxWidth()
            .clickable(enabled = enabled, onClick = onClick)
            .testTag(SETTINGS_ADD_PLACE_TAG),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                text = "Add place",
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.SemiBold,
                color = if (enabled) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
            if (!enabled) {
                Text(
                    text = "Maximum $MAX_FAVORITE_PLACES places",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
```

- [ ] **Step 3: Update the call site**

Replace the L249-263 block with:

```kotlin
                    AddPlaceRow(
                        enabled = favorites.size < MAX_FAVORITE_PLACES,
                        onClick = { showAddFavoritePicker = true },
                    )
```

Reading the count from the already-collected `favorites` state (rather than calling `canAddFavorite()` in the click handler) is what makes the row recompose the moment the fifth place is added. Add the `com.yugma.terrawatch.model.MAX_FAVORITE_PLACES` import.

- [ ] **Step 4: Build and test**

Run: `./gradlew jvmTest`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add composeApp/src/commonMain/kotlin/com/yugma/terrawatch/settings/SettingsScreen.kt composeApp/src/commonMain/kotlin/com/yugma/terrawatch/nav/AppNav.kt
git commit -m "Drop the Plus row and let Add place explain its own limit

At five places the row is disabled and says 'Maximum 5 places' instead
of routing to a paywall that no longer exists. Driven off the collected
favourites state so it flips the instant the fifth place lands."
```

---

### Task 5: The 100+ city catalog

**Files:**
- Create: `composeApp/src/commonMain/kotlin/com/yugma/terrawatch/location/CityCatalog.kt`
- Create: `composeApp/src/jvmTest/kotlin/com/yugma/terrawatch/location/CityCatalogTest.kt`
- Modify: `composeApp/src/commonMain/kotlin/com/yugma/terrawatch/location/LocationAskDialog.kt` (remove `PresetCity` and `PRESET_CITIES`, lines 27-55)

**Interfaces:**
- Consumes: `GeoPoint` from `core/model`.
- Produces: `data class PresetCity(val name: String, val country: String, val point: GeoPoint)` and `val PRESET_CITIES: List<PresetCity>`, both in package `com.yugma.terrawatch.location`. Task 6 consumes both.

**This task runs in parallel with Tasks 1-4** — it shares no files with them.

- [ ] **Step 1: Write the integrity test first**

Create `composeApp/src/jvmTest/kotlin/com/yugma/terrawatch/location/CityCatalogTest.kt`:

```kotlin
package com.yugma.terrawatch.location

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CityCatalogTest {

    @Test
    fun `the catalog holds at least a hundred cities`() {
        assertTrue(PRESET_CITIES.size >= 100, "only ${PRESET_CITIES.size} cities")
    }

    @Test
    fun `no city appears twice`() {
        val keys = PRESET_CITIES.map { "${it.name}, ${it.country}" }
        val dupes = keys.groupBy { it }.filterValues { it.size > 1 }.keys
        assertTrue(dupes.isEmpty(), "duplicates: $dupes")
    }

    @Test
    fun `every coordinate is within valid earth bounds`() {
        PRESET_CITIES.forEach { c ->
            assertTrue(c.point.lat in -90.0..90.0, "${c.name} latitude ${c.point.lat}")
            assertTrue(c.point.lon in -180.0..180.0, "${c.name} longitude ${c.point.lon}")
        }
    }

    @Test
    fun `no city sits at null island - a common placeholder mistake`() {
        val atZero = PRESET_CITIES.filter { abs(it.point.lat) < 0.01 && abs(it.point.lon) < 0.01 }
        assertTrue(atZero.isEmpty(), "cities at 0,0: ${atZero.map { it.name }}")
    }

    @Test
    fun `no city has a blank name or country`() {
        PRESET_CITIES.forEach {
            assertTrue(it.name.isNotBlank(), "blank name")
            assertTrue(it.country.isNotBlank(), "blank country for ${it.name}")
        }
    }

    @Test
    fun `known cities sit where they belong`() {
        // A spot-check against independently known coordinates, tight enough (0.5 degrees, roughly
        // 55 km of latitude) to catch a transposed or fabricated pair but loose enough to tolerate
        // differing city-centre conventions. This catches gross errors only - Task 7's independent
        // verification pass is what actually checks all of them.
        val expected = mapOf(
            "Tokyo" to (35.6762 to 139.6503),
            "Jakarta" to (-6.2088 to 106.8456),
            "Istanbul" to (41.0082 to 28.9784),
            "Santiago" to (-33.4489 to -70.6693),
            "Mexico City" to (19.4326 to -99.1332),
            "Kathmandu" to (27.7172 to 85.3240),
            "Reykjavik" to (64.1466 to -21.9426),
            "Wellington" to (-41.2866 to 174.7756),
            "Lima" to (-12.0464 to -77.0428),
            "Manila" to (14.5995 to 120.9842),
            "Taipei" to (25.0330 to 121.5654),
            "Tehran" to (35.6892 to 51.3890),
            "Naples" to (40.8518 to 14.2681),
            "Anchorage" to (61.2181 to -149.9003),
            "San Francisco" to (37.7749 to -122.4194),
        )
        expected.forEach { (name, latLon) ->
            val city = PRESET_CITIES.firstOrNull { it.name == name }
            assertTrue(city != null, "$name missing from the catalog")
            assertTrue(
                abs(city.point.lat - latLon.first) < 0.5 && abs(city.point.lon - latLon.second) < 0.5,
                "$name is at ${city.point}, expected about $latLon",
            )
        }
    }

    @Test
    fun `seismically active regions are well represented`() {
        // The catalog's whole point: an earthquake app's users cluster on plate boundaries.
        val seismicCountries = setOf(
            "Japan", "Indonesia", "Philippines", "Taiwan", "New Zealand", "Chile", "Peru",
            "Mexico", "Turkey", "Greece", "Italy", "Iran", "Nepal", "India", "Pakistan",
            "United States", "Papua New Guinea", "Iceland", "Ecuador", "Colombia", "Afghanistan",
        )
        val seismic = PRESET_CITIES.count { it.country in seismicCountries }
        assertTrue(seismic >= 60, "only $seismic cities in seismically active countries")
    }

    @Test
    fun `every inhabited continent is represented`() {
        listOf("Japan", "India", "Turkey", "Italy", "United States", "Chile", "Nigeria", "Australia")
            .forEach { country ->
                assertTrue(
                    PRESET_CITIES.any { it.country == country },
                    "no city in $country",
                )
            }
    }
}
```

- [ ] **Step 2: Run it and watch it fail**

Run: `./gradlew :composeApp:jvmTest --tests "com.yugma.terrawatch.location.CityCatalogTest"`
Expected: FAIL — `CityCatalog.kt` does not exist yet.

- [ ] **Step 3: Write the catalog**

Create `composeApp/src/commonMain/kotlin/com/yugma/terrawatch/location/CityCatalog.kt` containing `PresetCity` (moved here from `LocationAskDialog.kt`, now with a `country` field) and `PRESET_CITIES`.

Compose roughly 120 entries to this regional budget — the weighting is the point, so hit these counts approximately rather than filling with whatever comes to mind:

| Region | Target | Examples of what belongs |
|---|---|---|
| Japan, Taiwan, Korea | 14 | Tokyo, Osaka, Sendai, Kobe, Fukuoka, Taipei, Kaohsiung, Seoul |
| Indonesia, Philippines, PNG | 14 | Jakarta, Surabaya, Padang, Banda Aceh, Manila, Cebu, Davao, Port Moresby |
| South Asia (India, Nepal, Pakistan, Afghanistan, Bangladesh) | 18 | Delhi, Mumbai, Bengaluru, Chennai, Kolkata, Guwahati, Srinagar, Kathmandu, Pokhara, Islamabad, Quetta, Kabul, Dhaka |
| Western US, Alaska, Canada | 12 | Los Angeles, San Francisco, San Jose, Seattle, Portland, Anchorage, Fairbanks, Vancouver |
| Mexico, Central America, Caribbean | 10 | Mexico City, Oaxaca, Acapulco, Guatemala City, San Salvador, Managua, San José, Port-au-Prince, Kingston |
| South America (Andean belt) | 12 | Santiago, Concepción, Valparaíso, Lima, Arequipa, Quito, Guayaquil, Bogotá, Medellín, La Paz |
| Turkey, Iran, Caucasus, Levant | 12 | Istanbul, Izmir, Ankara, Erzincan, Tehran, Tabriz, Bam, Yerevan, Tbilisi, Beirut, Amman |
| Mediterranean Europe | 10 | Athens, Thessaloniki, Naples, Catania, Rome, L'Aquila, Lisbon, Bucharest, Zagreb, Skopje |
| New Zealand, Pacific | 6 | Wellington, Christchurch, Auckland, Suva, Nuku'alofa, Honolulu |
| Iceland, Nordic | 3 | Reykjavik, Oslo, Helsinki |
| East African Rift + rest of Africa | 8 | Addis Ababa, Nairobi, Kampala, Goma, Cairo, Lagos, Johannesburg, Casablanca |
| Rest of world (population centres, low seismicity) | 12 | London, Paris, Berlin, Madrid, Moscow, Beijing, Shanghai, Singapore, Bangkok, Dubai, Sydney, Melbourne, São Paulo, Buenos Aires, New York, Toronto |

Format, exactly (keep `PRESET_CITIES` sorted by country then name so duplicates are visible on sight):

```kotlin
/** One of [PRESET_CITIES]' entries — a name, its country for disambiguation, and the coordinates
 * [CityPickerDialog] writes into [HomeLocationStore] when tapped. */
data class PresetCity(val name: String, val country: String, val point: GeoPoint)

/**
 * The manual picker's options, weighted toward seismically active regions because that is where an
 * earthquake app's users cluster — the Pacific Ring of Fire, the Himalayan and Alpide belts, the
 * Andes, the East African Rift — topped up with major world population centres so the picker is not
 * useless to someone in London or Lagos.
 *
 * Coordinates are city-centre approximations. They are checked three ways: the range, duplicate and
 * spot-check assertions in `CityCatalogTest`, and an independent verification pass by someone other
 * than this list's author (see the 2026-09-21 plan, Task 7). A wrong coordinate here silently shows
 * a user the wrong region's earthquakes, which is why it is worth that much checking.
 */
val PRESET_CITIES: List<PresetCity> = listOf(
    PresetCity("Kabul", "Afghanistan", GeoPoint(34.5553, 69.2075)),
    PresetCity("Buenos Aires", "Argentina", GeoPoint(-34.6037, -58.3816)),
    PresetCity("Melbourne", "Australia", GeoPoint(-37.8136, 144.9631)),
    PresetCity("Sydney", "Australia", GeoPoint(-33.8688, 151.2093)),
    // ... continue, sorted by country then name
)
```

Write coordinates you are confident in. Where you are not confident about a smaller city, choose a larger one you are sure of instead — a short accurate list beats a long doubtful one, and Task 7 will find what you got wrong regardless.

- [ ] **Step 4: Remove the old definitions**

Delete `PresetCity` and `PRESET_CITIES` from `LocationAskDialog.kt` (lines 27-55, including the kdoc about "ten manual-picker options"). Same package, so no import changes are needed in that file.

- [ ] **Step 5: Run the test and watch it pass**

Run: `./gradlew :composeApp:jvmTest --tests "com.yugma.terrawatch.location.CityCatalogTest"`
Expected: PASS, 8 tests.

- [ ] **Step 6: Commit**

```bash
git add composeApp/src/commonMain/kotlin/com/yugma/terrawatch/location composeApp/src/jvmTest/kotlin/com/yugma/terrawatch/location
git commit -m "Grow the city catalog from 10 to 100+, weighted to seismic regions

Moved out of LocationAskDialog.kt, which was the wrong home for a list
this size, and given a country field - at this size bare city names are
genuinely ambiguous and country text makes search far more useful.

Integrity tests cover ranges, duplicates, null island, and a 15-city
spot-check. Those catch gross errors only; an independent verification
pass over every coordinate follows."
```

---

### Task 6: Search the city picker

**Files:**
- Create: `composeApp/src/commonMain/kotlin/com/yugma/terrawatch/location/CityFilter.kt`
- Create: `composeApp/src/jvmTest/kotlin/com/yugma/terrawatch/location/CityFilterTest.kt`
- Modify: `composeApp/src/commonMain/kotlin/com/yugma/terrawatch/location/LocationAskDialog.kt` (`CityPickerDialog`, ~L141-178)

**Interfaces:**
- Consumes: `PresetCity`, `PRESET_CITIES` from Task 5.
- Produces: `filterCities(query: String, cities: List<PresetCity> = PRESET_CITIES): List<PresetCity>`.

- [ ] **Step 1: Write the failing test**

Create `composeApp/src/jvmTest/kotlin/com/yugma/terrawatch/location/CityFilterTest.kt`:

```kotlin
package com.yugma.terrawatch.location

import com.yugma.terrawatch.model.GeoPoint
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CityFilterTest {

    private val cities = listOf(
        PresetCity("Tokyo", "Japan", GeoPoint(35.6762, 139.6503)),
        PresetCity("Osaka", "Japan", GeoPoint(34.6937, 135.5023)),
        PresetCity("Jakarta", "Indonesia", GeoPoint(-6.2088, 106.8456)),
        PresetCity("San Francisco", "United States", GeoPoint(37.7749, -122.4194)),
    )

    @Test
    fun `a blank query returns everything`() {
        assertEquals(cities, filterCities("", cities))
        assertEquals(cities, filterCities("   ", cities))
    }

    @Test
    fun `matches on city name, case insensitively`() {
        assertEquals(listOf("Tokyo"), filterCities("tok", cities).map { it.name })
        assertEquals(listOf("Tokyo"), filterCities("TOKYO", cities).map { it.name })
    }

    @Test
    fun `matches on country, so a user can find their whole country`() {
        assertEquals(listOf("Tokyo", "Osaka"), filterCities("japan", cities).map { it.name })
    }

    @Test
    fun `matches mid-word, not only at the start`() {
        assertEquals(listOf("San Francisco"), filterCities("fran", cities).map { it.name })
    }

    @Test
    fun `surrounding whitespace in the query is ignored`() {
        assertEquals(listOf("Tokyo"), filterCities("  tokyo  ", cities).map { it.name })
    }

    @Test
    fun `a query matching nothing returns empty rather than everything`() {
        assertTrue(filterCities("atlantis", cities).isEmpty())
    }

    @Test
    fun `result order follows the catalog, so results do not jump around`() {
        assertEquals(listOf("Tokyo", "Osaka"), filterCities("a", cities).map { it.name }.take(2))
    }
}
```

- [ ] **Step 2: Run it and watch it fail**

Run: `./gradlew :composeApp:jvmTest --tests "com.yugma.terrawatch.location.CityFilterTest"`
Expected: FAIL — unresolved reference `filterCities`.

- [ ] **Step 3: Implement the filter**

Create `composeApp/src/commonMain/kotlin/com/yugma/terrawatch/location/CityFilter.kt`:

```kotlin
package com.yugma.terrawatch.location

/**
 * Filters [cities] to those matching [query], case-insensitively, against name and country. A blank
 * query returns everything. Catalog order is preserved, so results never reorder themselves as the
 * user types.
 *
 * Pure and UI-free precisely so the picker's one piece of real logic is covered by fast unit tests
 * rather than a Compose UI test.
 */
fun filterCities(query: String, cities: List<PresetCity> = PRESET_CITIES): List<PresetCity> {
    val needle = query.trim()
    if (needle.isEmpty()) return cities
    return cities.filter {
        it.name.contains(needle, ignoreCase = true) || it.country.contains(needle, ignoreCase = true)
    }
}
```

- [ ] **Step 4: Run the test and watch it pass**

Run: `./gradlew :composeApp:jvmTest --tests "com.yugma.terrawatch.location.CityFilterTest"`
Expected: PASS, 7 tests.

- [ ] **Step 5: Add the search field to the dialog**

In `CityPickerDialog`, add `var query by remember { mutableStateOf("") }` and render an `OutlinedTextField` above the `LazyColumn`, keeping the dialog a dialog:

```kotlin
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    singleLine = true,
                    label = { Text("Search cities") },
                    modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
                )
                val matches = filterCities(query)
                if (matches.isEmpty()) {
                    Text(
                        text = "No cities match \"${query.trim()}\"",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(vertical = 24.dp),
                    )
                } else {
                    LazyColumn(modifier = Modifier.height(CITY_LIST_HEIGHT)) {
                        items(matches, key = { "${it.name}, ${it.country}" }) { city ->
                            Text(
                                text = "${city.name}, ${city.country}",
                                style = MaterialTheme.typography.bodyLarge,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        if (onCityPicked != null) onCityPicked(city) else store.set(city.point)
                                        onDismiss()
                                    }
                                    .padding(vertical = 12.dp),
                            )
                        }
                    }
                }
```

Two details that matter: the `key` becomes name+country because names alone are no longer unique, and the empty state must be rendered rather than leaving a blank box. Add the needed imports (`OutlinedTextField`, `remember`, `mutableStateOf`, `getValue`, `setValue`).

The "Use my location" row stays above the search field, unchanged.

- [ ] **Step 6: Check the favourite label still reads well**

`SettingsScreen` calls `viewModel.addFavorite(city.name, city.point)`. Leave it passing `city.name` alone — a favourites list reading "Tokyo" is better than "Tokyo, Japan", and the country exists for search, not for storage. Confirm this is still the call and do not change it.

- [ ] **Step 7: Run the full suite**

Run: `./gradlew jvmTest`
Expected: PASS.

- [ ] **Step 8: Commit**

```bash
git add composeApp/src/commonMain/kotlin/com/yugma/terrawatch/location composeApp/src/jvmTest/kotlin/com/yugma/terrawatch/location
git commit -m "Add search to the city picker, which 100+ cities made necessary

The filter is a pure function so the picker's only real logic is under
fast unit tests instead of a Compose UI test. Rows now show country and
key on name+country, since names alone stopped being unique."
```

---

### Task 7: Independently verify every coordinate

**Files:**
- Modify: `composeApp/src/commonMain/kotlin/com/yugma/terrawatch/location/CityCatalog.kt` (only where a mismatch is confirmed)
- Create: `docs/qa/ads-only-monetization/city-coordinate-verification.md`

**Interfaces:** consumes Task 5's catalog. Produces no code API.

**This task must be performed by someone who did not write the catalog.** A verifier who authored the list will reproduce its errors.

- [ ] **Step 1: Extract the catalog as data**

```bash
grep -oE 'PresetCity\("[^"]+", "[^"]+", GeoPoint\([-0-9.]+, [-0-9.]+\)\)' \
  composeApp/src/commonMain/kotlin/com/yugma/terrawatch/location/CityCatalog.kt
```

- [ ] **Step 2: Check every entry against independent knowledge**

For each city, compare the committed latitude and longitude against the coordinates you know independently. Flag any entry off by more than ~0.5 degrees in either axis, plus these specific failure shapes, which are the ones that actually occur:
- **Sign errors** — a southern-hemisphere city with positive latitude, or a western-hemisphere city with positive longitude. Check every city in Chile, Peru, Ecuador, Indonesia, Australia, New Zealand, and the Americas.
- **Transposed pairs** — latitude and longitude swapped. Detectable when |lat| > 90 is impossible, so look instead for pairs that place a city in the sea.
- **Right name, wrong country** — e.g. a Naples in Italy given Florida's coordinates.

- [ ] **Step 3: Write the findings down before changing anything**

Create `docs/qa/ads-only-monetization/city-coordinate-verification.md` listing every city checked, the committed coordinate, the expected one, and the verdict. Record confirmed-correct entries too — a verification that only lists failures cannot be distinguished from one that was never run.

- [ ] **Step 4: Correct only confirmed mismatches**

Fix the entries you have positively identified as wrong. Where you are unsure rather than confident, **remove the city** instead of guessing a replacement coordinate. The catalog only needs 100; it does not need any particular city.

- [ ] **Step 5: Re-run the catalog tests**

Run: `./gradlew :composeApp:jvmTest --tests "com.yugma.terrawatch.location.CityCatalogTest"`
Expected: PASS, including the `>= 100` size assertion after any removals.

- [ ] **Step 6: Commit**

```bash
git add composeApp/src/commonMain/kotlin/com/yugma/terrawatch/location/CityCatalog.kt docs/qa/ads-only-monetization
git commit -m "Verify every city coordinate independently, fix what was wrong

Checked by someone other than the list's author, because a verifier who
wrote the list reproduces its errors. Findings recorded in full,
including the entries that were correct - a report listing only failures
is indistinguishable from one never run. Cities we could not confirm
were removed rather than guessed at."
```

---

### Task 8: Version 1.1.0 and documentation corrections

**Files:**
- Modify: `composeApp/build.gradle.kts:219-220`
- Modify: `composeApp/src/commonMain/kotlin/com/yugma/terrawatch/settings/SettingsScreen.kt:130`
- Modify: `README.md:9`
- Modify: `docs/HANDOFF.md` (§3 state, §2 secrets)
- Modify: `store-assets/listing.md`

- [ ] **Step 1: Bump all three version literals together**

`build.gradle.kts`: `versionCode` fallback `3` → `4`, `versionName` `"1.0.0"` → `"1.1.0"`.
`SettingsScreen.kt:130`: `APP_VERSION = "1.0.0"` → `"1.1.0"`.
`README.md:9`: `Version: 1.0.0 (versionCode 3)` → `Version: 1.1.0 (versionCode 4)`.

- [ ] **Step 2: Confirm the version test passes**

A test pins the version (commit `6c26853` pinned it to 1.0.0 to fix CI). Find and update it:

```bash
grep -rn '1\.0\.0' --include=*.kt . | grep -i test
```

Run: `./gradlew jvmTest --tests "*Version*"`
Expected: PASS.

- [ ] **Step 3: Correct `docs/HANDOFF.md`**

§3 currently says a signed AAB "has been handed to the owner to upload as the first Play release". The owner confirms the app **is live on Play** and the **upload-key reset was approved**, so `~/keys/terrawatch/terrawatch-upload-new.jks` signs valid uploads. Rewrite that bullet and the related §2 row to state this. Add a line recording that `feat/plus-purchase-parked` holds the withdrawn purchase implementation.

- [ ] **Step 4: Trim Plus from the store listing**

In `store-assets/listing.md`, remove any mention of TerraWatch Plus, the one-time purchase, ad-free upgrades, or unlimited favourite places. Add a note near the top, for the owner rather than for Play: the listing's **"In-app purchases" declaration must be turned off in Play Console**, because the app no longer contains billing code and leaving it on would be a false declaration. Mention the new 5-place limit if the copy discusses favourites.

- [ ] **Step 5: Commit**

```bash
git add composeApp README.md docs/HANDOFF.md store-assets/listing.md
git commit -m "Ship 1.1.0: version bump and the doc corrections it forces

All three hand-synced version literals move together. HANDOFF's claim
that the app was never uploaded to Play is stale - the owner confirms it
is live and the upload-key reset was approved. Listing copy loses its
Plus references, with a note that the In-app purchases declaration must
be switched off in Play Console."
```

---

### Task 9: Verify on the Pixel 8 and build the release

**Files:**
- Create: `docs/qa/ads-only-monetization/RESULTS.md` and its screenshots

**Device:** Pixel 8, serial `3C161FDJH000H2`, Android 17 / API 37.

- [ ] **Step 1: Full test suite**

Run: `./gradlew jvmTest :core:database:verifySqlDelightMigration`
Expected: PASS. Record the exact count and compare it honestly to the 777 baseline — a drop is expected, since five Plus test files were deleted.

- [ ] **Step 2: Install a release-signed build**

```bash
export CI_KEYSTORE_PATH="$HOME/keys/terrawatch/terrawatch-upload-new.jks"
# The remaining CI_* signing env vars come from the owner's password manager.
./gradlew :composeApp:assembleRelease
adb -s 3C161FDJH000H2 install -r composeApp/build/outputs/apk/release/*.apk
```

Use a release build, not debug: R8 is on for release, and stripping the purchase code is exactly the kind of change that can expose a missing keep rule in `proguard-rules.pro`.

- [ ] **Step 3: Walk the app, capturing evidence**

For each screen: `adb -s 3C161FDJH000H2 shell uiautomator dump`, confirm a marker unique to that screen in the XML, and only then save the screenshot. Verify:
- Settings has **no** "TerraWatch Plus" row and no route to a paywall.
- The banner is visible on the dashboard for a user who has bought nothing.
- The banner disappears while a quake detail sheet is open, and returns on close.
- Adding places works up to 5; at 5 the "Add place" row is visibly disabled and reads "Maximum 5 places".
- The city picker search filters as you type, matches country names, and shows the empty state for nonsense input.
- A cold start shows the splash wordmark at correct proportions (regression check on `de86081`).

- [ ] **Step 4: Crash sweep**

```bash
adb -s 3C161FDJH000H2 logcat -d -b crash | tail -50
adb -s 3C161FDJH000H2 logcat -d | grep -E "FATAL EXCEPTION|E AndroidRuntime.*com\.yugma" | head
```

Do **not** grep the bare `AndroidRuntime` tag — the uiautomator tooling logs benign startup lines under it, which is exactly the false positive commit `de86081` recorded.

- [ ] **Step 5: Write the QA results**

Create `docs/qa/ads-only-monetization/RESULTS.md` following the shape of `docs/qa/android-platform-17/RESULTS.md`: what was tested, on what build, with which screenshot showing it, and what failed. Report failures plainly rather than omitting them.

- [ ] **Step 6: Regenerate the store screenshots**

The committed store screenshots show a "Test Ad" placeholder. This release build carries the real AdMob unit, so recapture the six `store-assets/screenshots/` images from it. Do not fabricate or touch up — if an ad has not filled yet, wait and recapture (a new ad unit can return NO_FILL for up to an hour, per commit `a7e910d`; that is not a bug).

- [ ] **Step 7: Build the App Bundle**

```bash
./gradlew :composeApp:bundleRelease
ls -la composeApp/build/outputs/bundle/release/
```

- [ ] **Step 8: Commit, then STOP**

```bash
git add docs/qa/ads-only-monetization store-assets/screenshots
git commit -m "Device pass on Pixel 8 (Android 17): ads-only build verified"
```

**Do not push, tag, or upload to Play.** Report to the owner: the test count, what the device pass showed, the `.aab` path, and the two actions that are theirs — switching off the In-app purchases declaration, and the upload itself. Before any tagged release, verify the 8 GitHub Actions secrets actually exist (`gh secret list` returned empty during planning, suggesting they do not).

---

## Self-Review

**Spec coverage:** §3.1 module deletion → Task 3. §3.2 RevenueCat relocation → Task 3 steps 2-3. §4 ads → Task 2. §5 cap → Tasks 1 and 4. §6.1 catalog → Task 5. §6.2 coordinate integrity → Tasks 5 and 7. §6.3 search → Task 6. §7 release → Tasks 8 and 9. §7.1 owner actions → Tasks 8 and 9 (flagged, never automated). §7.2 upload path → Task 9 step 8. §8 testing → every task, plus Task 9. §9 risks → each has a task or an explicit stop condition.

**Placeholder scan:** clean — no TBD, no "handle errors appropriately", no "similar to Task N". Task 5's city list is specified by regional budget, exact format, a worked example, and eight integrity assertions rather than 120 literal lines, with Task 7 as the correctness gate.

**Type consistency:** `canAddFavorite(currentCount: Int)` — one signature, Tasks 1/3/4. `adSlotVisible(isDetailOpen, isOnboarding)` — Task 2, consumed unchanged. `PresetCity(name, country, point)` — Task 5, consumed by Tasks 6 and 7. `filterCities(query, cities)` — Task 6. `MAX_FAVORITE_PLACES` — Tasks 1 and 4.

## Execution order

Tasks 1 → 2 → 3 → 4 are one sequential chain (shared files). Task 5 → 6 → 7 is a second chain that runs **in parallel** with the first; they share no files. Task 8 requires both chains complete. Task 9 is last and must run on the real device.
