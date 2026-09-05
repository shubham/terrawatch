# TerraWatch Plus — Purchase Flow & Real Ads Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make `TerraWatch Plus` genuinely purchasable through the RevenueCat SDK, serve real AdMob ads, and ship the result to the Play closed-testing track without regressing any immutable product rule.

**Architecture:** A new, narrow `PlusPurchases` interface in `core:monetization` carries the purchase flow, leaving the existing `EntitlementsProvider` read-only exactly as its kdoc intends. Android gets a `purchases-kmp` implementation; jvm/wasm and key-absent Android share one `Unavailable` no-op, resolved through the **same** `revenueCatKeyIsConfigured` gate that already picks the entitlements provider. A `PurchasesDelegate` makes `isPlusActive` live, so the banner disappears the instant a purchase completes.

**Tech Stack:** purchases-kmp 3.5.0 (`purchases-kmp-core`, androidMain only) · play-services-ads 25.4.0 · Koin 4 · Compose Multiplatform 1.9 · kotlin.test

**Spec:** [`docs/superpowers/specs/2026-09-05-plus-purchase-and-real-ads-design.md`](../specs/2026-09-05-plus-purchase-and-real-ads-design.md)

## Global Constraints

- **Branch** `feat/plus-purchase-flow` off `main`. Never commit non-trivial work straight to `main`.
- **JDK 17 only.** JDK 25 crashes the Kotlin compiler. On this machine:
  `export JAVA_HOME=/Users/shubham/.gradle/jdks/eclipse_adoptium-17-aarch64-os_x.2/jdk-17.0.20.1+1/Contents/Home`
  (Android Studio's bundled JBR is now 25 — do **not** use it.)
- **Test command:** `./gradlew jvmTest` — baseline is **762 passing, 0 failures**. Any task that ends with a different failure count than it started with has broken something.
- **Real-device verification only**, never emulator. Device: **Pixel 8 `3C161FDJH000H2`, Android 17 / API 37**. Evidence committed under `docs/qa/plus-purchase/`.
- **Evidence integrity.** Never claim a file, symbol or endpoint without grepping it first. Never narrate a screenshot you did not save. Fabrication triggers a redo.
- **Commit trailer:** every commit ends with `Co-Authored-By: Claude Opus 5 (1M context) <noreply@anthropic.com>`.
- **Immutable product rules — do not regress:**
  - *Alert honesty:* digest framing only, never "early warning". Plus must never be described as improving alert speed.
  - *Ad ethics:* banner hidden while the detail sheet is open and during onboarding; ads only while Plus is inactive; no interstitials or rewarded ads. `adSlotVisible`'s truth-table tests are **pinned — do not weaken them**.
  - *Privacy:* no backend, no accounts. RevenueCat stays anonymous — never call `logIn()`, never set an `appUserId`.
  - *Notification floor:* M4.0+. Plus must not become a way to lower it.
- **Entitlement identifier is `plus`**, hardcoded at `RevenueCatEntitlements.PLUS_ENTITLEMENT_IDENTIFIER`. The RevenueCat dashboard must match exactly.
- **Play product id is `terrawatch_plus`**, one-time, **NON-CONSUMABLE**. See spec §2 — misconfiguring this as consumable makes purchases permanently unrecoverable.
- **Never hardcode a price.** The store is the only honest source; render `PlusOffer.formattedPrice`.

**Tasks 1-9 are unblocked today.** Tasks 10-11 are gated on owner-supplied keys and are marked **[GATED]**.

---

### Task 1: Honest Plus benefits + explicit backup declaration ✅ DONE (`2d533c2`)

Plus is about to start charging money. Two small correctness fixes must land before that: stop advertising an unbuilt feature, and pin down the backup behavior that spec §2's restore story quietly depends on.

**Files:**
- Modify: `composeApp/src/commonMain/kotlin/com/yugma/terrawatch/paywall/PaywallScreen.kt` (`PLUS_BENEFITS`, ~line 160)
- Modify: `composeApp/src/androidMain/AndroidManifest.xml:42-47` (`<application>` tag)
- Test: `composeApp/src/jvmTest/kotlin/com/yugma/terrawatch/paywall/PaywallScreenTest.kt`

**Interfaces:**
- Consumes: nothing.
- Produces: `PLUS_BENEFITS: List<String>` — now exactly 2 entries. Task 6 renders it unchanged.

- [ ] **Step 1: Update the pinned test to the honest 2-item list**

The existing test pins 3 items. Rewrite the assertion and the kdoc reason:

```kotlin
/**
 * Plus purchase flow (2026-09-05): "Custom alert rules (coming soon)" is REMOVED. Task 6 of Plan 4
 * shipped this list while Plus was unpurchasable, where naming an unbuilt feature was a roadmap
 * note. The purchase flow makes the button charge real money, at which point the same line becomes
 * part of a paid offer for something that does not exist — a Play policy risk and a direct breach
 * of this repo's own honesty rule. Custom alert rules move to the roadmap as a free future update.
 *
 * Both remaining items are real and already enforced in code:
 * `adSlotVisible` (core:ads) and `canAddFavorite` (core:monetization).
 */
class PaywallScreenTest {
    @Test fun `PLUS_BENEFITS lists only benefits that actually exist`() {
        assertEquals(
            listOf(
                "Remove ads",
                "Unlimited favorite places",
            ),
            PLUS_BENEFITS,
        )
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `./gradlew :composeApp:jvmTest --tests "*PaywallScreenTest*"`
Expected: **FAIL** — `expected:<[Remove ads, Unlimited favorite places]> but was:<[Remove ads, Unlimited favorite places, Custom alert rules (coming soon)]>`

- [ ] **Step 3: Drop the third benefit**

In `PaywallScreen.kt`, replace the `PLUS_BENEFITS` declaration and rewrite its kdoc — the existing kdoc explicitly explains why item 3 was kept, so leaving it would contradict the code:

```kotlin
/** Spec §8's Plus benefits, reduced to the two that are REAL. Ad removal is enforced by
 * `adSlotVisible` (core:ads); unlimited favorite places by
 * [com.yugma.terrawatch.monetization.canAddFavorite]. "Custom alert rules (coming soon)" was
 * dropped when the paywall became a real purchase — see [PaywallScreenTest]'s own kdoc for the
 * reasoning. `internal` so that test can pin the exact copy without a Compose runtime. */
internal val PLUS_BENEFITS = listOf(
    "Remove ads",
    "Unlimited favorite places",
)
```

- [ ] **Step 4: Run the test to verify it passes**

Run: `./gradlew :composeApp:jvmTest --tests "*PaywallScreenTest*"`
Expected: **PASS**

- [ ] **Step 5: Declare allowBackup explicitly**

`android:allowBackup` is currently undeclared, so it is `true` by default — spec §2 relies on that (Android Auto Backup carries RevenueCat's anonymous id across a reinstall, making restore silent in the common case). Write it down so it cannot regress unnoticed. In `AndroidManifest.xml`, add the attribute and a comment to the `<application>` tag:

The comment must sit **before** the opening tag, not between attributes — XML comments cannot appear inside a tag, and the obvious-looking placement will not parse:

```xml
    <!-- android:allowBackup is already the platform default (true); it is declared explicitly here
         because the Plus restore story depends on it rather than merely tolerating it. RevenueCat
         runs anonymous in this app (no accounts, ever - spec section 3.6), so its App User ID lives
         in this app's own SharedPreferences and dies with an uninstall. Android Auto Backup
         carrying that id across a reinstall is what makes a previously-purchased Plus come back
         silently instead of requiring the user to find the Restore button. A default nobody wrote
         down is a default that can regress in review without anyone noticing what it cost. -->
    <application
        android:label="TerraWatch"
        android:icon="@mipmap/ic_launcher"
        android:roundIcon="@mipmap/ic_launcher_round"
        android:theme="@style/Theme.App.Starting"
        android:allowBackup="true"
        android:enableOnBackInvokedCallback="true">
```

Note also that em dashes and curly quotes are avoided inside this comment: the manifest is read by tooling that is not always UTF-8 forgiving, and every existing comment in this file uses plain ASCII punctuation.

- [ ] **Step 6: Verify the manifest still builds**

Run: `./gradlew :composeApp:assembleDebug`
Expected: **BUILD SUCCESSFUL**

- [ ] **Step 7: Commit**

```bash
git add composeApp/src/commonMain/kotlin/com/yugma/terrawatch/paywall/PaywallScreen.kt \
        composeApp/src/jvmTest/kotlin/com/yugma/terrawatch/paywall/PaywallScreenTest.kt \
        composeApp/src/androidMain/AndroidManifest.xml
git commit -m "$(cat <<'EOF'
Stop advertising an unbuilt feature on a paywall about to charge money

"Custom alert rules (coming soon)" was honest while Plus was unpurchasable.
Once the button takes payment it becomes a paid promise for something that
does not exist. Dropped to the two benefits that are actually enforced in
code: adSlotVisible and canAddFavorite.

Also declares android:allowBackup explicitly. It was already true by
default, and the reinstall-restore path depends on it: RevenueCat's
anonymous App User ID lives in SharedPreferences, and Auto Backup carrying
it across a reinstall is what makes Plus return without a manual restore.

Co-Authored-By: Claude Opus 5 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

### Task 2: `PlusPurchases` interface + unavailable implementation

The purchase surface, as pure common code with no SDK dependency, so every target compiles and the honest "nothing purchasable" state is the default rather than an afterthought.

**Files:**
- Create: `core/monetization/src/commonMain/kotlin/com/yugma/terrawatch/monetization/PlusPurchases.kt`
- Test: `core/monetization/src/commonTest/kotlin/com/yugma/terrawatch/monetization/PlusPurchasesTest.kt`

**Interfaces:**
- Consumes: nothing.
- Produces: `interface PlusPurchases` with `suspend fun loadOffer(): PlusOffer?`, `suspend fun purchase(): PurchaseOutcome`, `suspend fun restore(): RestoreOutcome`; `data class PlusOffer(val formattedPrice: String)`; sealed `PurchaseOutcome` (`Success`, `Cancelled`, `Failed(reason)`); sealed `RestoreOutcome` (`Restored`, `NothingToRestore`, `Failed(reason)`); `object UnavailablePlusPurchases : PlusPurchases`. Tasks 3, 5, 6, 7 all depend on these exact names.

- [ ] **Step 1: Write the failing test**

Create `PlusPurchasesTest.kt`:

```kotlin
package com.yugma.terrawatch.monetization

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PlusPurchasesTest {
    @Test fun `unavailable provider offers nothing to buy`() = runTest {
        assertNull(UnavailablePlusPurchases.loadOffer())
    }

    @Test fun `unavailable provider fails a purchase instead of pretending it worked`() = runTest {
        val outcome = UnavailablePlusPurchases.purchase()
        assertTrue(outcome is PurchaseOutcome.Failed, "expected Failed, got $outcome")
    }

    @Test fun `unavailable provider reports nothing to restore, not an error`() = runTest {
        assertEquals(RestoreOutcome.NothingToRestore, UnavailablePlusPurchases.restore())
    }

    @Test fun `a cancelled purchase is not a failure`() {
        assertTrue(PurchaseOutcome.Cancelled !is PurchaseOutcome.Failed)
    }
}
```

- [ ] **Step 2: Run it to make sure it fails**

Run: `./gradlew :core:monetization:jvmTest --tests "*PlusPurchasesTest*"`
Expected: **FAIL** — compilation error, `Unresolved reference: UnavailablePlusPurchases`

- [ ] **Step 3: Add the coroutines-test dependency if absent**

`core/monetization/build.gradle.kts`'s `commonTest.dependencies` currently has only `kotlin("test")`. Add:

```kotlin
        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.kotlinx.coroutines.test)
        }
```

Verify the alias exists first: `grep -n "coroutines-test" gradle/libs.versions.toml`. If it is missing, add it next to the existing coroutines entries rather than inventing a new version.

- [ ] **Step 4: Write the minimal implementation**

Create `PlusPurchases.kt`:

```kotlin
package com.yugma.terrawatch.monetization

/**
 * The purchase FLOW for TerraWatch Plus — deliberately separate from [EntitlementsProvider], which
 * stays read-only ("is Plus active right now") exactly as its own kdoc intends. Two interfaces
 * because they have two different lifetimes: entitlement state is observed continuously by
 * `adSlotVisible`, the Settings row and `canAddFavorite`, while purchasing is a one-shot user
 * action reached only from the paywall.
 *
 * Every function suspends and returns an outcome rather than throwing: the android implementation
 * wraps an SDK whose calls all throw, and this codebase's convention is that store/network calls
 * degrade gracefully rather than crash (see `GdeltClient`, `UsgsApi`, `RevenueCatEntitlements`).
 */
interface PlusPurchases {
    /** The current purchasable offer, or `null` when nothing is purchasable — no configured
     * RevenueCat key, no offering on the dashboard, or the store is unreachable. The paywall renders
     * an honestly disabled button in that case rather than a fake price. */
    suspend fun loadOffer(): PlusOffer?

    /** Launches the store purchase sheet and waits for the user to finish or back out. */
    suspend fun purchase(): PurchaseOutcome

    /** Re-attaches an already-owned purchase to this install. The reinstall path (spec §2): the
     * purchase belongs to the user's Google account, not the install, so this is what brings Plus
     * back after the anonymous App User ID is lost. */
    suspend fun restore(): RestoreOutcome
}

/** [formattedPrice] is the STORE's own localized string ("₹299", "$3.99") — never assembled here.
 * The store knows the user's currency and locale; this app does not, and guessing produces a price
 * that disagrees with the one Play actually charges. */
data class PlusOffer(val formattedPrice: String)

sealed interface PurchaseOutcome {
    data object Success : PurchaseOutcome

    /** The user dismissed the store sheet. Deliberately NOT a [Failed]: backing out of a purchase
     * is a normal choice, and telling someone something went wrong when they simply changed their
     * mind is both untrue and hostile. The paywall shows nothing for this. */
    data object Cancelled : PurchaseOutcome

    data class Failed(val reason: String) : PurchaseOutcome
}

sealed interface RestoreOutcome {
    data object Restored : RestoreOutcome

    /** The store reported no owned purchase to restore. An ordinary answer, not an error — the
     * common case is someone tapping Restore who never bought Plus. */
    data object NothingToRestore : RestoreOutcome

    data class Failed(val reason: String) : RestoreOutcome
}

/**
 * The [PlusPurchases] for every build with no configured RevenueCat integration: all jvm/wasmJs
 * builds (Android-only runtime scope directive), AND Android whenever
 * `composeApp/monetization.properties`'s `REVENUECAT_API_KEY` is absent or blank — which is this
 * repo's normal local state and every CI build. Mirrors [AlwaysFreeEntitlements] exactly, and is
 * chosen by the SAME [revenueCatKeyIsConfigured] gate, so entitlements and purchases can never
 * disagree about whether RevenueCat is live.
 *
 * [restore] returns [RestoreOutcome.NothingToRestore] rather than a failure on purpose: with no
 * store integration there is genuinely nothing owned to restore, and the silent first-launch
 * restore attempt must stay silent on every build that reaches this object.
 */
object UnavailablePlusPurchases : PlusPurchases {
    override suspend fun loadOffer(): PlusOffer? = null
    override suspend fun purchase(): PurchaseOutcome =
        PurchaseOutcome.Failed("Purchases aren't available in this build.")
    override suspend fun restore(): RestoreOutcome = RestoreOutcome.NothingToRestore
}
```

- [ ] **Step 5: Run the tests to verify they pass**

Run: `./gradlew :core:monetization:jvmTest --tests "*PlusPurchasesTest*"`
Expected: **PASS**, 4 tests

- [ ] **Step 6: Verify all three targets still compile**

Run: `./gradlew :core:monetization:compileKotlinJvm :core:monetization:compileKotlinWasmJs :core:monetization:compileDebugKotlinAndroid`
Expected: **BUILD SUCCESSFUL**

- [ ] **Step 7: Commit**

```bash
git add core/monetization/src/commonMain/kotlin/com/yugma/terrawatch/monetization/PlusPurchases.kt \
        core/monetization/src/commonTest/kotlin/com/yugma/terrawatch/monetization/PlusPurchasesTest.kt \
        core/monetization/build.gradle.kts gradle/libs.versions.toml
git commit -m "$(cat <<'EOF'
Add PlusPurchases: the purchase flow, separate from entitlement reads

EntitlementsProvider stays read-only as its kdoc always intended. The new
interface carries loadOffer/purchase/restore, with UnavailablePlusPurchases
as the honest default for jvm/wasm and for Android without a RevenueCat key
-- selected by the same revenueCatKeyIsConfigured gate that already picks
the entitlements provider, so the two can never disagree.

Cancelled is a distinct outcome from Failed: dismissing the store sheet is
a normal choice, not an error to report back to the user.

Co-Authored-By: Claude Opus 5 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

### Task 3: `RevenueCatPlusPurchases` (androidMain)

The real implementation, over the API surface verified by `javap` against the resolved 3.5.0 AARs.

**Files:**
- Create: `core/monetization/src/androidMain/kotlin/com/yugma/terrawatch/monetization/RevenueCatPlusPurchases.kt`

**Interfaces:**
- Consumes: `PlusPurchases`, `PlusOffer`, `PurchaseOutcome`, `RestoreOutcome`, `PLUS_ENTITLEMENT_IDENTIFIER` (Task 2 + existing).
- Produces: `class RevenueCatPlusPurchases : PlusPurchases` — no constructor arguments. Task 5 constructs it.

**Verified signatures — do not deviate, and do not trust the SDK docs over this table:**

| Call | Real signature |
|---|---|
| `ktx.awaitOfferings(Purchases)` | returns `Offerings`, throws `PurchasesException` |
| `ktx.awaitPurchase(Purchases, Package, …)` | returns `SuccessfulPurchase`, throws `PurchasesTransactionException` |
| `ktx.awaitRestore(Purchases)` | returns `CustomerInfo`, throws `PurchasesException` |

`awaitCustomerInfo` is a `ktx` extension, **not** a member of `Purchases` — the same trap this repo already documented once in `RevenueCatEntitlements`. All three above live in `com.revenuecat.purchases.kmp.ktx` and need that explicit import.

- [ ] **Step 1: Write the implementation**

There is no unit test for this file. It is thin platform glue over an SDK that cannot be exercised without a live RevenueCat account, and this codebase's established split is "pure logic gets a unit test, platform glue gets a device pass" (Task 9/10 rows P2, P3, P5, R4 cover it). Do not fake a test with a mocked SDK — it would assert only that the mock was called.

```kotlin
package com.yugma.terrawatch.monetization

import com.revenuecat.purchases.kmp.Purchases
import com.revenuecat.purchases.kmp.ktx.awaitOfferings
import com.revenuecat.purchases.kmp.ktx.awaitPurchase
import com.revenuecat.purchases.kmp.ktx.awaitRestore
import com.revenuecat.purchases.kmp.models.PurchasesException
import com.revenuecat.purchases.kmp.models.PurchasesTransactionException

/**
 * The real, android-only [PlusPurchases] over purchases-kmp 3.5.0.
 *
 * Only ever constructed when [revenueCatKeyIsConfigured] has already passed (see
 * `KoinBootstrap.android.kt`), which also guarantees [RevenueCatEntitlements] has run
 * `Purchases.configure` — so [Purchases.sharedInstance] is safe to touch here without configuring
 * a second time. Configuring twice would reset the SDK's state mid-session.
 *
 * Every SDK call in this file THROWS on failure rather than returning an error, so each one is
 * wrapped and mapped onto [PlusPurchases]' sealed outcomes. That mapping is the whole job of this
 * class; there is no logic here worth unit-testing independently of a live store.
 */
class RevenueCatPlusPurchases : PlusPurchases {

    /**
     * Reads the CURRENT offering's own [Package] list and takes the first package. TerraWatch sells
     * exactly one thing, so "the first package of the current offering" is the whole product
     * catalogue — deliberately not a package-identifier lookup, which would add a second string
     * that has to match the dashboard (alongside the entitlement id) with no benefit.
     *
     * Returns `null` on every unhappy path — no key, no current offering, empty offering, store
     * unreachable — because the paywall's honest response to all of them is identical: show a
     * disabled button, not a price we cannot charge.
     */
    override suspend fun loadOffer(): PlusOffer? = try {
        val pkg = Purchases.sharedInstance.awaitOfferings().current?.availablePackages?.firstOrNull()
        pkg?.let { PlusOffer(formattedPrice = it.storeProduct.price.formatted) }
    } catch (e: PurchasesException) {
        null
    }

    /**
     * [PurchasesTransactionException] carries `userCancelled`, which is the ONLY thing separating
     * "the user changed their mind" from "the purchase broke". Mapping it to
     * [PurchaseOutcome.Cancelled] is what stops the paywall showing an error to someone who simply
     * backed out — see [PurchaseOutcome.Cancelled]'s own kdoc.
     *
     * Success does not set entitlement state here. [RevenueCatEntitlements]' `PurchasesDelegate`
     * observes `onCustomerInfoUpdated` and flips `isPlusActive` for the whole app, so there is
     * exactly one place that decides whether Plus is active. Writing it from here too would create
     * a second source of truth that could disagree.
     */
    override suspend fun purchase(): PurchaseOutcome {
        val pkg = try {
            Purchases.sharedInstance.awaitOfferings().current?.availablePackages?.firstOrNull()
        } catch (e: PurchasesException) {
            null
        } ?: return PurchaseOutcome.Failed("Couldn't reach the store. Please try again.")

        return try {
            Purchases.sharedInstance.awaitPurchase(pkg)
            PurchaseOutcome.Success
        } catch (e: PurchasesTransactionException) {
            if (e.userCancelled) PurchaseOutcome.Cancelled
            else PurchaseOutcome.Failed("That purchase didn't go through. You haven't been charged.")
        }
    }

    /**
     * Spec §2's reinstall path. `awaitRestore` asks the store what this Google account owns and
     * re-attaches it to the current (post-reinstall, freshly generated) anonymous App User ID.
     *
     * This only ever finds the purchase while `terrawatch_plus` is configured NON-CONSUMABLE in the
     * RevenueCat dashboard. Configured consumable, RevenueCat consumes it and — since Play Billing
     * Client 8 removed querying of consumed one-time purchases — it is gone permanently, with no
     * account system here to recover it from. That configuration is verified on-device (matrix row
     * R4), never assumed.
     */
    override suspend fun restore(): RestoreOutcome = try {
        val info = Purchases.sharedInstance.awaitRestore()
        if (info.entitlements[PLUS_ENTITLEMENT_IDENTIFIER]?.isActive == true) RestoreOutcome.Restored
        else RestoreOutcome.NothingToRestore
    } catch (e: PurchasesException) {
        RestoreOutcome.Failed("Couldn't reach the store. Please try again.")
    }
}
```

- [ ] **Step 2: Confirm the property paths against the real bytecode before compiling**

`storeProduct.price.formatted` and `PurchasesTransactionException.userCancelled` are the two shapes above that this plan did not `javap`-verify end to end. Check them rather than assume — this file's whole risk is exactly this kind of guess:

```bash
JH=/Users/shubham/.gradle/jdks/eclipse_adoptium-17-aarch64-os_x.2/jdk-17.0.20.1+1/Contents/Home
SP=/tmp/rcverify && mkdir -p $SP && cd $SP
unzip -oq ~/.gradle/caches/modules-2/files-2.1/com.revenuecat.purchases/purchases-kmp-models-android/3.5.0/*/models-release.aar -d models
"$JH/bin/javap" -cp models/classes.jar com.revenuecat.purchases.kmp.models.Package | grep -i product
"$JH/bin/javap" -cp models/classes.jar com.revenuecat.purchases.kmp.models.StoreProduct | grep -i price
"$JH/bin/javap" -cp models/classes.jar com.revenuecat.purchases.kmp.models.Price | grep -i formatted
"$JH/bin/javap" -cp models/classes.jar com.revenuecat.purchases.kmp.models.PurchasesTransactionException | grep -i cancel
```

Adjust the implementation to whatever the bytecode actually says. If a name differs, fix the code — do not "fix" the plan by ignoring it.

- [ ] **Step 3: Compile the android target**

Run: `./gradlew :core:monetization:compileDebugKotlinAndroid`
Expected: **BUILD SUCCESSFUL**. An `Unresolved reference` here means step 2 was skipped or its findings were not applied.

- [ ] **Step 4: Verify jvm/wasm are unaffected**

Run: `./gradlew :core:monetization:compileKotlinJvm :core:monetization:compileKotlinWasmJs`
Expected: **BUILD SUCCESSFUL** — this file is androidMain-only, so neither target should even see it.

- [ ] **Step 5: Commit**

```bash
git add core/monetization/src/androidMain/kotlin/com/yugma/terrawatch/monetization/RevenueCatPlusPurchases.kt
git commit -m "$(cat <<'EOF'
Implement the real Plus purchase flow over purchases-kmp 3.5.0

Signatures verified by javap against the resolved AARs rather than taken
from the docs -- awaitOfferings/awaitPurchase/awaitRestore are all ktx
extensions that throw, the same shape that already caught this repo out
once with awaitCustomerInfo.

userCancelled is mapped to Cancelled rather than Failed so someone who
backs out of the store sheet is never told something went wrong. Success
deliberately does not write entitlement state: the PurchasesDelegate owns
that, so there is only one source of truth for whether Plus is active.

Co-Authored-By: Claude Opus 5 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

### Task 4: Live entitlement updates via `PurchasesDelegate`

Today `isPlusActive` is seeded once at construction. A purchase completing mid-session would leave the banner on screen until the next cold start — looking broken at the exact moment someone has just paid.

**Files:**
- Modify: `core/monetization/src/androidMain/kotlin/com/yugma/terrawatch/monetization/RevenueCatEntitlements.kt`

**Interfaces:**
- Consumes: existing `RevenueCatEntitlements(apiKey: String)`.
- Produces: unchanged public shape — `isPlusActive: StateFlow<Boolean>` now also updates live.

- [ ] **Step 1: Add the delegate**

Add these imports:

```kotlin
import com.revenuecat.purchases.kmp.PurchasesDelegate
import com.revenuecat.purchases.kmp.models.CustomerInfo
import com.revenuecat.purchases.kmp.models.StoreProduct
```

Replace the `init` block, keeping the existing seed fetch and adding the delegate:

```kotlin
    init {
        Purchases.logLevel = LogLevel.WARN
        Purchases.configure(apiKey = apiKey) {}

        // Live updates. `setDelegate` is a SINGLE SLOT, not a listener list — this class owns it,
        // and nothing else in this app may set it or it would silently unregister this one.
        Purchases.sharedInstance.delegate = object : PurchasesDelegate {
            override fun onCustomerInfoUpdated(customerInfo: CustomerInfo) {
                _isPlusActive.value =
                    customerInfo.entitlements[PLUS_ENTITLEMENT_IDENTIFIER]?.isActive == true
            }

            // Play Store promotional purchases (initiated from the store listing, not from inside
            // the app). TerraWatch configures none, so this cannot fire today. Declining by not
            // starting the purchase is the correct handling for an offer we never made; the
            // delegate above still reports any entitlement change that results.
            override fun onPurchasePromoProduct(
                product: StoreProduct,
                startPurchase: (
                    (com.revenuecat.purchases.kmp.models.PurchasesError, Boolean) -> Unit,
                    (com.revenuecat.purchases.kmp.models.StoreTransaction, CustomerInfo) -> Unit,
                ) -> Unit,
            ) = Unit
        }

        scope.launch {
            runCatching { Purchases.sharedInstance.awaitCustomerInfo() }
                .onSuccess { customerInfo ->
                    _isPlusActive.value = customerInfo.entitlements[PLUS_ENTITLEMENT_IDENTIFIER]?.isActive == true
                }
        }
    }
```

- [ ] **Step 2: Update the class kdoc**

The existing kdoc says a live listener is "deferred to Task 8". That is now false, and a stale kdoc is worse than none. Replace that paragraph:

```
 * [isPlusActive] seeds from one [Purchases.sharedInstance] `awaitCustomerInfo()` call at
 * construction AND stays live via a [PurchasesDelegate]: `onCustomerInfoUpdated` fires whenever
 * RevenueCat's view of the customer changes, including the moment a purchase completes. Without it
 * the anchored banner would survive the purchase until the next cold start — the worst possible
 * moment for this app to look broken. Every consumer (`adSlotVisible`, the Settings Plus row,
 * `canAddFavorite`) already observes this StateFlow, so nothing downstream needed changing.
```

- [ ] **Step 3: Verify the delegate signature against the bytecode**

`PurchasesDelegate.onPurchasePromoProduct`'s second parameter is a nested function type that is easy to get subtly wrong. Confirm before compiling:

```bash
JH=/Users/shubham/.gradle/jdks/eclipse_adoptium-17-aarch64-os_x.2/jdk-17.0.20.1+1/Contents/Home
"$JH/bin/javap" -cp /tmp/rcverify/../rc/core/classes.jar com.revenuecat.purchases.kmp.PurchasesDelegate
```

(If that path is stale, re-extract `purchases-kmp-core-android/3.5.0/*/core-release.aar` the same way Task 3 step 2 does.) Also confirm whether `delegate` is a settable property or requires `setDelegate(...)`.

- [ ] **Step 4: Compile**

Run: `./gradlew :core:monetization:compileDebugKotlinAndroid`
Expected: **BUILD SUCCESSFUL**

- [ ] **Step 5: Commit**

```bash
git add core/monetization/src/androidMain/kotlin/com/yugma/terrawatch/monetization/RevenueCatEntitlements.kt
git commit -m "$(cat <<'EOF'
Make isPlusActive live so the banner dies the instant Plus is bought

isPlusActive was seeded once at construction, so a purchase completing
mid-session left the ad banner on screen until the next cold start -- the
worst possible moment for this app to look broken.

A PurchasesDelegate now re-reads the entitlement on every customer-info
update. Every consumer already observes this StateFlow, so adSlotVisible,
the Settings Plus row and canAddFavorite all react with no change of their
own. setDelegate is a single slot, so this class owns it exclusively.

Co-Authored-By: Claude Opus 5 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

### Task 5: Wire `PlusPurchases` through Koin

**Files:**
- Modify: `composeApp/src/commonMain/kotlin/com/yugma/terrawatch/di/AppModule.kt` (`appModule` signature)
- Modify: `composeApp/src/androidMain/kotlin/com/yugma/terrawatch/di/KoinBootstrap.kt`
- Modify: `composeApp/src/jvmMain/kotlin/com/yugma/terrawatch/main.kt:35`
- Modify: `composeApp/src/wasmJsMain/kotlin/com/yugma/terrawatch/main.kt:55`

**Interfaces:**
- Consumes: `PlusPurchases`, `UnavailablePlusPurchases`, `RevenueCatPlusPurchases`, `revenueCatKeyIsConfigured`.
- Produces: `PlusPurchases` resolvable from Koin via `get()` / `koinInject()`. Tasks 6 and 7 depend on this.

- [ ] **Step 1: Add the parameter to `appModule`**

```kotlin
fun appModule(
    http: HttpClient,
    dao: QuakeStore,
    locationProvider: LocationProvider,
    entitlementsProvider: EntitlementsProvider,
    plusPurchases: PlusPurchases,
): Module = module {
    single { entitlementsProvider }
    single { plusPurchases }
    // ...existing registrations unchanged
```

Add `import com.yugma.terrawatch.monetization.PlusPurchases`. If `entitlementsProvider` is already registered elsewhere in the module body, put `single { plusPurchases }` immediately beside it rather than adding a second registration.

- [ ] **Step 2: Resolve it behind the same gate on Android**

In `KoinBootstrap.kt`, add next to `buildEntitlementsProvider`:

```kotlin
/**
 * The [PlusPurchases] counterpart to [buildEntitlementsProvider], deliberately reading the SAME key
 * through the SAME [revenueCatKeyIsConfigured] gate. Two providers, one decision: if these were
 * gated independently they could disagree — a build that reads entitlements from RevenueCat but
 * refuses to sell, or worse, a paywall that offers a purchase the entitlement side would never
 * observe.
 */
private fun buildPlusPurchases(context: Context): PlusPurchases {
    val apiKey = readRevenueCatApiKey(context)
    return if (revenueCatKeyIsConfigured(apiKey)) RevenueCatPlusPurchases() else UnavailablePlusPurchases
}
```

with imports for `PlusPurchases`, `RevenueCatPlusPurchases`, `UnavailablePlusPurchases`.

Then update the `startKoin` call inside `ensureKoinStarted`:

```kotlin
            startKoin {
                modules(
                    appModule(
                        http,
                        dao,
                        locationProvider,
                        buildEntitlementsProvider(appContext),
                        buildPlusPurchases(appContext),
                    )
                )
            }
```

**Ordering matters:** `buildEntitlementsProvider` must be evaluated **before** `buildPlusPurchases`, because it is what calls `Purchases.configure`. Kotlin evaluates arguments left to right, and the parameter order above preserves that. Do not reorder them.

- [ ] **Step 3: Update the jvm and wasmJs entry points**

`composeApp/src/jvmMain/kotlin/com/yugma/terrawatch/main.kt:35`:

```kotlin
    startKoin { modules(appModule(http, dao, LocationProvider(), AlwaysFreeEntitlements, UnavailablePlusPurchases)) }
```

`composeApp/src/wasmJsMain/kotlin/com/yugma/terrawatch/main.kt:55`:

```kotlin
    startKoin { modules(appModule(http, store, LocationProvider(), AlwaysFreeEntitlements, UnavailablePlusPurchases)) }
```

Add `import com.yugma.terrawatch.monetization.UnavailablePlusPurchases` to both.

- [ ] **Step 4: Find and fix every other `appModule(` call site**

Tests construct this too, and missing one is a compile error in a target that may not run by default:

```bash
grep -rn "appModule(" --include="*.kt" composeApp core
```

Update each to pass `UnavailablePlusPurchases` as the fifth argument.

- [ ] **Step 5: Run the full suite**

Run: `./gradlew jvmTest`
Expected: **762 passing, 0 failures** — the baseline, unchanged. This task adds no tests; it must also remove none.

- [ ] **Step 6: Compile all three targets**

Run: `./gradlew compileDebugKotlinAndroid compileKotlinJvm compileKotlinWasmJs`
Expected: **BUILD SUCCESSFUL**

- [ ] **Step 7: Commit**

```bash
git add -A
git commit -m "$(cat <<'EOF'
Resolve PlusPurchases through the same gate as entitlements

One key, one revenueCatKeyIsConfigured check, two providers. Gating them
independently would let them disagree -- a paywall offering a purchase the
entitlement side would never observe.

buildEntitlementsProvider is evaluated first on purpose: it owns
Purchases.configure, and RevenueCatPlusPurchases assumes the SDK is already
configured when it touches sharedInstance.

Co-Authored-By: Claude Opus 5 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

### Task 6: Paywall view model + real purchase UI

**Files:**
- Create: `composeApp/src/commonMain/kotlin/com/yugma/terrawatch/paywall/PaywallViewModel.kt`
- Modify: `composeApp/src/commonMain/kotlin/com/yugma/terrawatch/paywall/PaywallScreen.kt`
- Modify: `composeApp/src/commonMain/kotlin/com/yugma/terrawatch/di/AppModule.kt` (register the view model)
- Test: `composeApp/src/jvmTest/kotlin/com/yugma/terrawatch/paywall/PaywallButtonStateTest.kt`

**Interfaces:**
- Consumes: `PlusPurchases`, `PlusOffer`, `PurchaseOutcome`, `RestoreOutcome`, `EntitlementsProvider`.
- Produces: `PaywallViewModel` with `uiState: StateFlow<PaywallUiState>`, `fun buy()`, `fun restore()`; and the pure `fun paywallButtonLabel(offer: PlusOffer?, inFlight: Boolean, isPlus: Boolean): String` / `fun paywallButtonEnabled(offer: PlusOffer?, inFlight: Boolean, isPlus: Boolean): Boolean`.

- [ ] **Step 1: Write the failing test for the pure button rules**

Create `PaywallButtonStateTest.kt`:

```kotlin
package com.yugma.terrawatch.paywall

import com.yugma.terrawatch.monetization.PlusOffer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The paywall's button copy and enabled-ness, as a pure function of state — the one part of this
 * screen worth unit-testing, per this codebase's "pure logic gets a unit test, rendered UI gets a
 * device pass" split.
 *
 * The load-bearing case is `no offer`: it is what every build without a RevenueCat key renders,
 * which is every local build and all of CI. It must never show a price or an enabled button.
 */
class PaywallButtonStateTest {
    private val offer = PlusOffer(formattedPrice = "₹299")

    @Test fun `no offer means an honestly disabled button and no invented price`() {
        val label = paywallButtonLabel(offer = null, inFlight = false, isPlus = false)
        assertEquals("Purchases unavailable", label)
        assertFalse(paywallButtonEnabled(offer = null, inFlight = false, isPlus = false))
    }

    @Test fun `an offer shows the store's own price and enables the button`() {
        assertEquals("Unlock Plus — ₹299", paywallButtonLabel(offer, inFlight = false, isPlus = false))
        assertTrue(paywallButtonEnabled(offer, inFlight = false, isPlus = false))
    }

    @Test fun `a purchase in flight disables the button so it can't be double-tapped`() {
        assertFalse(paywallButtonEnabled(offer, inFlight = true, isPlus = false))
    }

    @Test fun `an active Plus user is never asked to buy again`() {
        assertEquals("You have Plus", paywallButtonLabel(offer, inFlight = false, isPlus = true))
        assertFalse(paywallButtonEnabled(offer, inFlight = false, isPlus = true))
    }

    @Test fun `Plus wins over a still-loading offer`() {
        assertEquals("You have Plus", paywallButtonLabel(offer = null, inFlight = false, isPlus = true))
    }
}
```

- [ ] **Step 2: Run it to make sure it fails**

Run: `./gradlew :composeApp:jvmTest --tests "*PaywallButtonStateTest*"`
Expected: **FAIL** — `Unresolved reference: paywallButtonLabel`

- [ ] **Step 3: Write the view model and the pure functions**

Create `PaywallViewModel.kt`:

```kotlin
package com.yugma.terrawatch.paywall

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.yugma.terrawatch.monetization.EntitlementsProvider
import com.yugma.terrawatch.monetization.PlusOffer
import com.yugma.terrawatch.monetization.PlusPurchases
import com.yugma.terrawatch.monetization.PurchaseOutcome
import com.yugma.terrawatch.monetization.RestoreOutcome
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/** `null` [offer] is the normal state on every build without a RevenueCat key. [message] is
 * transient user-facing feedback; `null` means show nothing, which is deliberately what a cancelled
 * purchase produces. */
data class PaywallUiState(
    val offer: PlusOffer? = null,
    val inFlight: Boolean = false,
    val message: String? = null,
)

/** Button copy. Extracted as a pure function so the rules are unit-tested without a Compose runtime
 * — the [isPlus] branch matters most: someone who already paid must never be shown a buy button. */
fun paywallButtonLabel(offer: PlusOffer?, inFlight: Boolean, isPlus: Boolean): String = when {
    isPlus -> "You have Plus"
    inFlight -> "Working…"
    offer == null -> "Purchases unavailable"
    else -> "Unlock Plus — ${offer.formattedPrice}"
}

fun paywallButtonEnabled(offer: PlusOffer?, inFlight: Boolean, isPlus: Boolean): Boolean =
    !isPlus && !inFlight && offer != null

class PaywallViewModel(
    private val purchases: PlusPurchases,
    entitlements: EntitlementsProvider,
) : ViewModel() {
    val isPlusActive: StateFlow<Boolean> = entitlements.isPlusActive

    private val _uiState = MutableStateFlow(PaywallUiState())
    val uiState: StateFlow<PaywallUiState> = _uiState

    init {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(offer = purchases.loadOffer())
        }
    }

    fun buy() {
        if (_uiState.value.inFlight) return
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(inFlight = true, message = null)
            val outcome = purchases.purchase()
            _uiState.value = _uiState.value.copy(
                inFlight = false,
                // Cancelled shows NOTHING on purpose — see PurchaseOutcome.Cancelled's kdoc.
                message = when (outcome) {
                    PurchaseOutcome.Success -> "Thanks — TerraWatch Plus is active."
                    PurchaseOutcome.Cancelled -> null
                    is PurchaseOutcome.Failed -> outcome.reason
                },
            )
        }
    }

    fun restore() {
        if (_uiState.value.inFlight) return
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(inFlight = true, message = null)
            val outcome = purchases.restore()
            _uiState.value = _uiState.value.copy(
                inFlight = false,
                message = when (outcome) {
                    RestoreOutcome.Restored -> "Your purchase is back."
                    RestoreOutcome.NothingToRestore -> "No previous purchase found on this account."
                    is RestoreOutcome.Failed -> outcome.reason
                },
            )
        }
    }
}
```

- [ ] **Step 4: Run the tests to verify they pass**

Run: `./gradlew :composeApp:jvmTest --tests "*PaywallButtonStateTest*"`
Expected: **PASS**, 5 tests

- [ ] **Step 5: Register the view model in Koin**

In `AppModule.kt`, beside the existing `viewModel { ... }` registrations:

```kotlin
    viewModel { PaywallViewModel(get(), get()) }
```

with `import com.yugma.terrawatch.paywall.PaywallViewModel`.

- [ ] **Step 6: Wire the screen**

In `PaywallScreen.kt`: take `viewModel: PaywallViewModel = koinViewModel()` instead of injecting the provider directly, read `isPlusActive` from it, replace the hardcoded disabled button, and add the restore action. Replace the disabled `Button` block with:

```kotlin
            val ui by viewModel.uiState.collectAsState()

            Button(
                onClick = viewModel::buy,
                enabled = paywallButtonEnabled(ui.offer, ui.inFlight, isPlusActive),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(paywallButtonLabel(ui.offer, ui.inFlight, isPlusActive))
            }
            Spacer(Modifier.height(8.dp))
            // Mandatory, not polish: this is the ONLY way a user recovers Plus after a reinstall
            // (spec §2) — the purchase belongs to their Google account, but the anonymous App User
            // ID that knew about it is gone with the old install.
            TextButton(
                onClick = viewModel::restore,
                enabled = !ui.inFlight,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Restore purchases")
            }
            ui.message?.let { message ->
                Spacer(Modifier.height(12.dp))
                Text(
                    text = message,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
```

Add imports for `TextButton`, `koinViewModel`, and the state helpers. Match the existing file's import ordering. Update the file's class kdoc: it currently describes itself as a "Task 6 STUB" with a deliberately disabled button, which is no longer true.

- [ ] **Step 7: Run the full suite and compile**

Run: `./gradlew jvmTest compileDebugKotlinAndroid compileKotlinJvm compileKotlinWasmJs`
Expected: **771 passing, 0 failures** (762 baseline + 4 from Task 2 + 5 new), all targets compiling.

- [ ] **Step 8: Commit**

```bash
git add -A
git commit -m "$(cat <<'EOF'
Turn the paywall stub into a real purchase screen

Button copy and enabled-ness are a pure function of (offer, inFlight,
isPlus) so the rules are unit-tested without a Compose runtime. The
load-bearing case is "no offer": every build without a RevenueCat key --
all local builds and all of CI -- must show an honestly disabled button and
never an invented price.

Adds the Restore action, which is mandatory rather than polish: it is the
only way a user gets Plus back after a reinstall, since the purchase
belongs to their Google account but the anonymous App User ID does not
survive.

A cancelled purchase deliberately renders no message at all.

Co-Authored-By: Claude Opus 5 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

### Task 7: Silent first-launch restore

Someone who reinstalls should usually get Plus back without knowing "Restore purchases" exists.

**Files:**
- Create: `core/monetization/src/commonMain/kotlin/com/yugma/terrawatch/monetization/SilentRestore.kt`
- Test: `core/monetization/src/commonTest/kotlin/com/yugma/terrawatch/monetization/SilentRestoreTest.kt`
- Modify: `composeApp/src/androidMain/kotlin/com/yugma/terrawatch/MainActivity.kt`

**Interfaces:**
- Consumes: `PlusPurchases`, `EntitlementsProvider`.
- Produces: `suspend fun attemptSilentRestore(purchases: PlusPurchases, isPlusAlreadyActive: Boolean): Boolean` — returns whether a restore was actually attempted.

- [ ] **Step 1: Write the failing test**

```kotlin
package com.yugma.terrawatch.monetization

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SilentRestoreTest {
    private class RecordingPurchases(private val outcome: RestoreOutcome) : PlusPurchases {
        var restoreCalls = 0
        override suspend fun loadOffer(): PlusOffer? = null
        override suspend fun purchase(): PurchaseOutcome = PurchaseOutcome.Cancelled
        override suspend fun restore(): RestoreOutcome {
            restoreCalls++
            return outcome
        }
    }

    @Test fun `does not bother the store when Plus is already active`() = runTest {
        val purchases = RecordingPurchases(RestoreOutcome.Restored)
        assertFalse(attemptSilentRestore(purchases, isPlusAlreadyActive = true))
        assertTrue(purchases.restoreCalls == 0, "should not have called restore")
    }

    @Test fun `attempts a restore when Plus is not active`() = runTest {
        val purchases = RecordingPurchases(RestoreOutcome.NothingToRestore)
        assertTrue(attemptSilentRestore(purchases, isPlusAlreadyActive = false))
        assertTrue(purchases.restoreCalls == 1)
    }

    @Test fun `a failing store never propagates an error on a cold start`() = runTest {
        val purchases = RecordingPurchases(RestoreOutcome.Failed("network down"))
        assertTrue(attemptSilentRestore(purchases, isPlusAlreadyActive = false))
    }
}
```

- [ ] **Step 2: Run it to make sure it fails**

Run: `./gradlew :core:monetization:jvmTest --tests "*SilentRestoreTest*"`
Expected: **FAIL** — `Unresolved reference: attemptSilentRestore`

- [ ] **Step 3: Implement**

```kotlin
package com.yugma.terrawatch.monetization

/**
 * One quiet restore attempt on a cold start, so the common reinstall case (spec §2) recovers Plus
 * without the user needing to discover the "Restore purchases" button at all.
 *
 * Deliberately silent in BOTH directions: it reports nothing on success (the entitlement StateFlow
 * updating is the only visible effect, which is exactly right — ads simply stop appearing) and
 * nothing on failure. A cold start is the worst possible moment to interrupt someone with a store
 * error they did not ask for, and a failed attempt costs them nothing: the manual Restore button on
 * the paywall is still there.
 *
 * Skipped entirely when Plus is already active — there is nothing to recover, and calling restore
 * needlessly is a store round-trip on every launch.
 *
 * Returns whether an attempt was made, for tests and for callers that want to avoid retrying in the
 * same session. Never throws.
 */
suspend fun attemptSilentRestore(
    purchases: PlusPurchases,
    isPlusAlreadyActive: Boolean,
): Boolean {
    if (isPlusAlreadyActive) return false
    runCatching { purchases.restore() }
    return true
}
```

- [ ] **Step 4: Run the tests to verify they pass**

Run: `./gradlew :core:monetization:jvmTest --tests "*SilentRestoreTest*"`
Expected: **PASS**, 3 tests

- [ ] **Step 5: Call it once per process from MainActivity**

Read `MainActivity.kt` first and follow whatever Koin-injection and lifecycle-scope pattern it already uses. Add a once-per-process attempt after `ensureKoinStarted`, guarded by its own `AtomicBoolean` (the same shape `mobileAdsInitStarted` uses in `KoinBootstrap.kt` — do not reuse that flag, it answers a different question):

```kotlin
// One quiet restore per process. Deliberately fire-and-forget on a lifecycle scope: nothing on
// screen waits for it, and its only visible effect is the ad banner not appearing for someone who
// already paid. See attemptSilentRestore's own kdoc.
if (silentRestoreStarted.compareAndSet(false, true)) {
    lifecycleScope.launch {
        val purchases: PlusPurchases = getKoin().get()
        val entitlements: EntitlementsProvider = getKoin().get()
        attemptSilentRestore(purchases, entitlements.isPlusActive.value)
    }
}
```

- [ ] **Step 6: Full suite + android compile**

Run: `./gradlew jvmTest :composeApp:compileDebugKotlinAndroid`
Expected: **774 passing, 0 failures** (771 + 3), compile successful.

- [ ] **Step 7: Commit**

```bash
git add -A
git commit -m "$(cat <<'EOF'
Recover Plus after a reinstall without making the user find a button

One quiet restore attempt per process when Plus is not already active.
Silent in both directions: success shows nothing (the ads simply never
appear) and failure shows nothing, because a cold start is the worst moment
to interrupt someone with a store error they did not ask for. The manual
Restore button remains the fallback.

Co-Authored-By: Claude Opus 5 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

### Task 8: Real ad-revenue tracking via `OnPaidEventListener`

**Files:**
- Modify: `core/ads/src/androidMain/kotlin/com/yugma/terrawatch/ads/AdRevenueTracker.kt`
- Modify: `core/ads/src/androidMain/kotlin/com/yugma/terrawatch/ads/BannerAdSlot.android.kt:158-172`
- Modify: `core/ads/build.gradle.kts` (androidMain needs `purchases-kmp-core`)

**Interfaces:**
- Consumes: `Purchases.getAdTracker()`, `AdRevenueData`.
- Produces: `AdRevenueTracker.onAdPaid(adUnitId: String, valueMicros: Long, currencyCode: String, precisionType: Int)` — replaces `onAdImpression(adUnitId: String)`.

**The correction this task encodes:** `AdRevenueTracker`'s current kdoc says wiring this needs `purchases-android` 8.0+. That is **wrong for this version** — `Purchases.getAdTracker()` exists in purchases-kmp 3.5.0, javap-verified. The real obstacle is different: `AdRevenueData` requires revenue micros, currency and precision, none of which `AdListener.onAdImpression()` provides. They come from AdMob's `OnPaidEventListener`. So the call site moves.

- [ ] **Step 1: Verify the AdTracker and AdRevenueData shapes**

```bash
JH=/Users/shubham/.gradle/jdks/eclipse_adoptium-17-aarch64-os_x.2/jdk-17.0.20.1+1/Contents/Home
SP=/tmp/rcverify && cd $SP
"$JH/bin/javap" -cp core/classes.jar com.revenuecat.purchases.kmp.AdTracker
"$JH/bin/javap" -cp models/classes.jar com.revenuecat.purchases.kmp.models.AdRevenueData
"$JH/bin/javap" -cp models/classes.jar com.revenuecat.purchases.kmp.models.AdMediatorName
"$JH/bin/javap" -cp models/classes.jar com.revenuecat.purchases.kmp.models.AdFormat
"$JH/bin/javap" -cp models/classes.jar com.revenuecat.purchases.kmp.models.AdRevenuePrecision
```

Write the exact constructor parameter order and the enum constant names into the implementation. `AdRevenueData`'s constructor takes 9 arguments — getting the order wrong compiles fine if two adjacent parameters are both `String`, so read it, do not guess.

- [ ] **Step 2: Add the dependency**

In `core/ads/build.gradle.kts`, `androidMain.dependencies`:

```kotlin
            implementation(libs.revenuecat.purchases.kmp.core)
```

- [ ] **Step 3: Replace the tracker stub**

Rewrite `AdRevenueTracker.kt` — including the kdoc, which currently states a claim this task disproves:

```kotlin
package com.yugma.terrawatch.ads

import com.revenuecat.purchases.kmp.Purchases

/**
 * Reports AdMob ad revenue into RevenueCat, so ad and IAP income land in one dashboard (spec §8).
 *
 * Correction to this file's previous kdoc: it claimed the integration needed `purchases-android`
 * 8.0+ and an experimental preview surface. Not true for the version this app already ships —
 * `Purchases.getAdTracker()` is present in purchases-kmp 3.5.0 (javap-verified against the resolved
 * AAR). The real obstacle was different, and is why the call site moved: [AdRevenueData] needs the
 * revenue amount, currency and precision, and `AdListener.onAdImpression()` carries none of them.
 * Those values only exist on AdMob's `OnPaidEventListener`, so that is what drives this now.
 *
 * Every call is wrapped: this fires on a real ad callback, and an analytics report is never worth
 * crashing a user's session over. Silently degrades when RevenueCat is unconfigured, which is the
 * normal state of every local and CI build.
 */
object AdRevenueTracker {
    fun onAdPaid(adUnitId: String, valueMicros: Long, currencyCode: String, precisionType: Int) {
        runCatching {
            Purchases.sharedInstance.adTracker.trackAdRevenue(
                // Fill in from the javap output of step 1 — exact parameter order and enum names.
            )
        }
    }
}
```

Map AdMob's `precisionType` int (`AdValue.PRECISION_TYPE_*`) onto RevenueCat's `AdRevenuePrecision` enum with an exhaustive `when`, defaulting unknown values to the least-confident constant rather than throwing.

- [ ] **Step 4: Move the call site**

In `BannerAdSlot.android.kt`, inside the `AdView(context).apply { … }` block, delete the `onAdImpression` override and add a paid-event listener. Keep `onAdLoaded` exactly as it is — `hasLoadedOnce` drives the fade-in from Plan 5 Task 3:

```kotlin
            adListener = object : AdListener() {
                override fun onAdLoaded() {
                    hasLoadedOnce = true
                }
            }
            // Ad revenue, not impressions: AdRevenueData needs amount/currency/precision, which only
            // this callback carries. See AdRevenueTracker's own kdoc.
            setOnPaidEventListener { adValue ->
                AdRevenueTracker.onAdPaid(
                    adUnitId = bannerUnitId,
                    valueMicros = adValue.valueMicros,
                    currencyCode = adValue.currencyCode,
                    precisionType = adValue.precisionType,
                )
            }
```

Add `import com.google.android.gms.ads.OnPaidEventListener` if the lambda form does not resolve. Also update this file's kdoc paragraph describing the `onAdImpression` hook as an unwired stub.

- [ ] **Step 5: Confirm the ad-ethics tests are untouched**

Run: `./gradlew :core:ads:jvmTest`
Expected: **PASS**, same count as before. `AdSlotVisibilityTest` and `AdSlotReservedHeightTest` are pinned by product rule — if this task changed their behavior, it went wrong.

- [ ] **Step 6: Full suite + compile**

Run: `./gradlew jvmTest compileDebugKotlinAndroid compileKotlinJvm compileKotlinWasmJs`
Expected: **774 passing, 0 failures** (Task 8 adds no tests), all targets compiling.

- [ ] **Step 7: Commit**

```bash
git add -A
git commit -m "$(cat <<'EOF'
Wire real ad revenue into RevenueCat, correcting a stale kdoc claim

AdRevenueTracker's kdoc claimed this needed purchases-android 8.0+ and an
experimental preview API. Not true for the version already shipping:
Purchases.getAdTracker() is in purchases-kmp 3.5.0, javap-verified.

The real obstacle was different. AdRevenueData needs amount, currency and
precision, and AdListener.onAdImpression() carries none of them -- they
only exist on AdMob's OnPaidEventListener. So the call site moved there.

adSlotVisible and its pinned ethics tests are untouched.

Co-Authored-By: Claude Opus 5 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

### Task 9: Device verification — the rows that need no keys

**Files:**
- Create: `docs/qa/plus-purchase/RESULTS.md` + screenshots

- [ ] **Step 1: Install a debug build on the Pixel 8**

```bash
export JAVA_HOME=/Users/shubham/.gradle/jdks/eclipse_adoptium-17-aarch64-os_x.2/jdk-17.0.20.1+1/Contents/Home
./gradlew :composeApp:installDebug
adb -s 3C161FDJH000H2 shell am start -n com.yugma.terrawatch/.MainActivity
```

- [ ] **Step 2: Row P1 — the honest disabled paywall**

Navigate Settings → TerraWatch Plus. With no key configured, confirm: exactly 2 benefits, button reads "Purchases unavailable" and is disabled, a "Restore purchases" button is present, tapping Restore shows "No previous purchase found on this account.", no crash. Capture `p1-paywall-no-key.png`.

- [ ] **Step 3: Row A0 — ad ethics still hold**

Test ads are live in a debug build. Confirm the banner shows on Home, disappears while the detail sheet is open, and is absent during onboarding. Capture `a0-banner-home.png`, `a0-banner-hidden-detail.png`.

- [ ] **Step 4: Close the themed-icon question this device finally makes answerable**

`docs/qa/post-p5-tail/RESULTS.md` had to leave the monochrome launcher icon unverified — neither OnePlus's nor Motorola's launcher exposes the toggle. A stock Pixel does. Long-press home → Wallpaper & style → Themed icons → on. Confirm `ic_launcher_monochrome.xml` renders as a proper monochrome mark and not a filled blob. Capture `themed-icon-pixel8.png`.

- [ ] **Step 5: API 37 forward-compat sweep**

`targetSdk` is 36; this device runs **API 37**, which Plan 4 Task 4 could only reason about on paper. Walk every screen (Home/map, detail sheet, History, Insights, Settings, onboarding, paywall) plus a permission grant/deny cycle, watching for insets, predictive back and permission-flow regressions. Capture a logcat crash sweep:

```bash
adb -s 3C161FDJH000H2 logcat -d -b crash > docs/qa/plus-purchase/api37-crash-sweep.txt
adb -s 3C161FDJH000H2 logcat -d | grep -iE "AndroidRuntime|FATAL|StrictMode" > docs/qa/plus-purchase/api37-androidruntime.txt
```

**Any behavior change found here is a production-launch blocker, not a curiosity.** Record it honestly, including "no issues found" if that is the result — and note that empty sweep files are the clean result, not a capture failure.

- [ ] **Step 6: Write RESULTS.md and commit**

One row per check with a verdict, the device id, and the artifact filename beside it. Mark every keyed row (P2, P3, P4, P5, R4, A1, A2) explicitly **PENDING — needs RevenueCat/AdMob keys**. Do not leave them looking tested.

```bash
git add docs/qa/plus-purchase/
git commit -m "$(cat <<'EOF'
Device pass on Pixel 8 (Android 17 / API 37): keyless rows

Closes the themed-icon question that post-p5-tail had to leave open for
lack of a stock launcher -- OnePlus and Motorola never exposed the toggle.

Also the first real API 37 runtime check against a targetSdk-36 build;
Plan 4 Task 4 could only reason about Android 17 on paper.

Keyed rows (P2-P5, R4, A1, A2) are marked PENDING, not tested.

Co-Authored-By: Claude Opus 5 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

### Task 10 **[GATED — needs owner keys]**: Real ads, sandbox purchase, and the reinstall test

**Prerequisites:** `REVENUECAT_API_KEY`, `ADMOB_APP_ID`, `ADMOB_BANNER_UNIT`, AdMob publisher id, `terrawatch_plus` created in Play as one-time **non-consumable**, attached in RevenueCat to entitlement `plus` **as non-consumable**, and a Play **license tester** account on the device.

- [ ] **Step 1: Write the local config**

```bash
cp composeApp/monetization.properties.example composeApp/monetization.properties
# fill in the three values; the file is gitignored and must never be committed
```

- [ ] **Step 2: Verify the non-consumable configuration before spending a test purchase**

Read it back from both dashboards — Play Console → Monetize → In-app products → `terrawatch_plus` (type: one-time), and RevenueCat → Products → `terrawatch_plus`. Screenshot both. **This is the single highest-consequence setting in the integration** (spec §2): wrong here, and every purchase becomes permanently unrecoverable for an anonymous user. Verifying it after a test purchase is too late to be cheap.

- [ ] **Step 3: Real-ads build + rows A1, A2**

Install a release-signed build with the real IDs. Confirm real ads render, `adSlotVisible` rules still hold, and `trackAdRevenue` fires — check RevenueCat's dashboard for the ad-revenue event rather than trusting the absence of a crash.

- [ ] **Step 4: Rows P2, P3, P4, P5 — the purchase**

P2: the paywall shows the store's own localized price. P3: complete a sandbox purchase as a license tester (no real money). **P4: the banner disappears without a restart** — this is what Task 4 exists for. P5: reopen the paywall, dismiss the store sheet, confirm no error message appears.

- [ ] **Step 5: Row R4 — uninstall, reinstall, restore**

The row this whole design is shaped around:

```bash
adb -s 3C161FDJH000H2 uninstall com.yugma.terrawatch
./gradlew :composeApp:installRelease
adb -s 3C161FDJH000H2 shell am start -n com.yugma.terrawatch/.MainActivity
```

Confirm on a fresh install that Plus returns — silently via Task 7, or via the paywall's Restore button. **Capture both outcomes separately.** If Plus does *not* come back, stop: the product is misconfigured as consumable, and shipping it would sell people something they permanently lose.

- [ ] **Step 6: Update app-ads.txt and resolve the hosting caveat**

Put the real publisher id into `docs/app-ads.txt`, then verify with AdMob's own app-ads.txt checker that the GitHub Pages **project-site subpath** is accepted. Its committed caveat is real: crawlers conventionally check the origin root, which this repo does not control. If rejected, the fix is a custom domain or a `<user>.github.io` root-pages repo — do not assume it passed.

- [ ] **Step 7: Commit evidence (never the keys)**

```bash
git status --porcelain | grep monetization.properties && echo "STOP: gitignored file is staged"
git add docs/qa/plus-purchase/ docs/app-ads.txt
git commit -m "Device pass: real ads, sandbox purchase, reinstall restore verified"
```

---

### Task 11 **[GATED — needs keystore + service account]**: Release to the closed track

**Prerequisites:** `terrawatch-upload.jks` + passwords present on this machine (currently **absent**), Play service-account JSON, Task 10 green.

- [ ] **Step 1: Populate the 8 GitHub Actions secrets**

`UPLOAD_KEYSTORE_B64` (`base64 -i upload.jks`), `KEYSTORE_PASSWORD`, `KEY_ALIAS` (`terrawatch-upload`), `KEY_PASSWORD`, `PLAY_SERVICE_ACCOUNT_JSON`, `REVENUECAT_API_KEY`, `ADMOB_APP_ID`, `ADMOB_BANNER_UNIT`.

- [ ] **Step 2: Merge to main after review**

Open a PR, confirm CI green, review the whole branch adversarially, merge.

- [ ] **Step 3: Dry-run an internal release**

Actions → "Release to Play" → Run workflow → track: **internal**. Confirms signing, versioning and Play API auth end to end without touching testers.

- [ ] **Step 4: Tag for the closed track**

```bash
git tag v1.0.1 && git push origin v1.0.1
```

`release.yml` builds a signed AAB (versionCode = 10 + run number) and uploads to the **beta** closed track with `mapping.txt`.

- [ ] **Step 5: Confirm testers receive it**

Verify the update reaches an opted-in tester. This also supplies Google's production-access review with the evidence of active iteration it explicitly looks for.

---

## Self-Review

**Spec coverage:** §1 product shape → Task 1 (benefits) + Task 10 step 2 (product config). §2 restore → Tasks 1 (allowBackup), 3 (`restore`), 6 (button), 7 (silent), 10 step 5 (R4). §3.1 interface → Task 2. §3.2 verified surface → Task 3. §3.3 delegate → Task 4. §3.4 paywall → Task 6. §3.5 ads → Task 8 + Task 10 steps 3, 6. §4 immutable rules → Global Constraints + Task 8 step 5. §5 sequencing → Tasks 10-11 gating. §6 verification → Tasks 9-10. **Gap found and closed:** Koin wiring is implied by the spec but named in no section — it is Task 5.

**Placeholder scan:** Task 3 step 1 and Task 8 step 3 deliberately leave constructor arguments to be filled from javap output. That is not a placeholder standing in for absent thinking — it is the repo's verify-the-bytecode rule, and both tasks carry the exact commands and an explicit instruction to fix the code rather than the plan when reality disagrees. Every other step contains real code.

**Type consistency:** `PlusOffer.formattedPrice`, `PurchaseOutcome.{Success,Cancelled,Failed}`, `RestoreOutcome.{Restored,NothingToRestore,Failed}`, `UnavailablePlusPurchases`, `attemptSilentRestore`, `paywallButtonLabel`/`paywallButtonEnabled`, `AdRevenueTracker.onAdPaid` — each defined once and used with the same signature everywhere. `appModule`'s fifth parameter is `plusPurchases: PlusPurchases` in Tasks 5 and 6 alike. Test-count arithmetic: baseline 762 → 766 (Task 2, +4) → 771 (Task 6, +5) → 774 (Task 7, +3). Tasks 1 and 8 change tests in place without adding any. The per-task expectations were initially inconsistent with this chain and have been corrected in the steps themselves. Executors should still trust the count they actually observe over the number this plan predicts — a mismatch means a test was silently dropped, which is worth stopping for.
