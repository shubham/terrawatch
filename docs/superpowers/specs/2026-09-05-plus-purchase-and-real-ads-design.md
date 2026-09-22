# TerraWatch Plus — real purchase flow + real ads (design)

Written 2026-09-05. Supersedes Plan 4 Task 8's one-paragraph brief with a real design, now that the
app is uploaded to Play closed testing and the monetization accounts are about to exist.

**Goal:** make `TerraWatch Plus` genuinely purchasable through the RevenueCat SDK, serve real AdMob
ads, and ship the result to the closed track — without regressing any immutable product rule.

**Two independent deadlines drive sequencing, not scope:**

- **Shipaton 2026** closes **Sep 30, 2026 11:45pm PDT**. Its rules require that *"the first public
  version of the Project must be released during the Submission Period on Apple's App Store, the
  Google Play Store, or the Samsung Galaxy Store"* — **closed testing does not qualify** — and that
  the app *"uses the RevenueCat SDK to power at least one in-app or web purchase, or serves ads
  through RevenueCat Ads."* Today's build satisfies neither: nothing is purchasable.
- **Google Play production access** for a new personal account needs **12 testers opted in
  continuously for 14 days**, then a production-access review that *"usually takes seven days or
  less, but can occasionally take longer."* As of writing: **4 of 12 testers**.

Quality is the constraint; the deadline is the stretch goal (owner's explicit call, 2026-09-05). We
do not ship an unverified purchase flow to hit a date.

---

## 1. Product shape

| Field | Value |
|---|---|
| Play product id | `terrawatch_plus` |
| Type | **One-time purchase, NON-CONSUMABLE** |
| RevenueCat entitlement | `plus` (hardcoded in `RevenueCatEntitlements.PLUS_ENTITLEMENT_IDENTIFIER`) |
| Benefits | Remove ads · Unlimited favorite places |

**"Custom alert rules (coming soon)" is removed from `PLUS_BENEFITS`.** It is not built. Once the
button actually charges money, listing it makes an unbuilt feature part of a paid offer — which is
both a Play policy risk and a direct violation of this repo's own honesty rule. It moves to the
roadmap as a free future update.

That leaves Plus with exactly two benefits, both real and both already implemented today:
`adSlotVisible` (core:ads) and `canAddFavorite` (core:monetization).

### Why one-time and not a subscription

TerraWatch has **no backend and no recurring cost** — it reads public USGS/EMSC feeds. There is no
ongoing service to charge rent for, so a subscription would be selling a renewal we don't earn.
Shipaton's rule is satisfied by *"at least one in-app or web purchase"*, with no subscription
requirement. A one-time unlock is the honest fit.

---

## 2. Reinstall & restore — the highest-consequence decision in this design

A Google Play one-time purchase belongs to the **user's Google account**, not the install. On
reinstall the RevenueCat anonymous App User ID is gone (it lived in app data) and a fresh one is
generated with no entitlement. `restorePurchases()` then asks Play what that account owns, finds the
purchase, and re-attaches it to the new anonymous id — Plus returns, on that device and on any other
device signed into the same account.

**This holds if and only if the product is configured NON-CONSUMABLE in RevenueCat.** If it is
misconfigured as consumable, RevenueCat consumes it, and since **Play Billing Client 8 Google
removed the ability to query consumed one-time purchases** — so the purchase becomes *permanently*
unrecoverable for an anonymous user, and this app has no account system to fall back on.

It is silent, irreversible, and invisible in normal testing (it only shows up on reinstall). So it
is not an assumption in this design — it is a verification step with committed device evidence
(§6, matrix row R4).

Three defences, all in scope:

1. **Non-consumable in both dashboards**, confirmed by reading the config back, not by memory.
2. **An explicit "Restore purchases" action on the paywall.** Mandatory, not polish.
3. **One silent restore attempt on first launch** when no entitlement is present. Failures stay
   invisible (the user simply remains free) — never an error toast on a cold start.

Plus `android:allowBackup="true"` declared **explicitly** in the manifest. It is already the platform
default and therefore already active, which is exactly why it should be written down: Android Auto
Backup carries RevenueCat's anonymous id across a reinstall, making restore silent in the common
case, and an undeclared default can regress without anyone noticing.

---

## 3. Code architecture

### 3.1 New: `PlusPurchases` (core:monetization, commonMain)

`EntitlementsProvider` stays **read-only**, exactly as its own kdoc intends ("no
`purchase()`/`restorePurchases()`/paywall-launch surface here at all"). The purchase flow is a
separate, narrow interface:

```kotlin
interface PlusPurchases {
    suspend fun loadOffer(): PlusOffer?          // null = nothing purchasable right now
    suspend fun purchase(): PurchaseOutcome
    suspend fun restore(): RestoreOutcome
}

data class PlusOffer(val formattedPrice: String)  // store-localized, never hand-formatted

sealed interface PurchaseOutcome {
    data object Success : PurchaseOutcome
    data object Cancelled : PurchaseOutcome       // user backed out — NOT an error
    data class Failed(val reason: String) : PurchaseOutcome
}

sealed interface RestoreOutcome {
    data object Restored : RestoreOutcome
    data object NothingToRestore : RestoreOutcome
    data class Failed(val reason: String) : RestoreOutcome
}
```

`Cancelled` is deliberately distinct from `Failed`: a user dismissing the Play sheet must never be
told something went wrong.

**Implementations**, mirroring the `RevenueCatEntitlements` / `AlwaysFreeEntitlements` split that
already exists for entitlements:

- `RevenueCatPlusPurchases` (androidMain) — over the verified 3.5.0 surface.
- `UnavailablePlusPurchases` (commonMain) — `loadOffer()` returns `null`; every call reports
  unavailability. Used by jvm/wasm **and** by Android whenever `REVENUECAT_API_KEY` is absent, which
  is the repo's normal local state. The paywall then renders exactly the honest disabled button it
  shows today.

Wired in `KoinBootstrap.android.kt` behind the **same** `revenueCatKeyIsConfigured(apiKey)` gate that
already chooses the entitlements provider — one gate, two products, no second source of truth.

### 3.2 Verified SDK surface

Confirmed by `javap` against the resolved `purchases-kmp-core-android` / `purchases-kmp-models-android`
**3.5.0** AARs — real bytecode, not docs. This repo has been bitten once already (`awaitCustomerInfo`
is a `ktx` extension, not a member), so the check is the convention:

| Need | Real signature |
|---|---|
| Offerings | `ktx.awaitOfferings(Purchases): Offerings` throws `PurchasesException` |
| Purchase | `ktx.awaitPurchase(Purchases, Package, …): SuccessfulPurchase` throws `PurchasesTransactionException` |
| Restore | `ktx.awaitRestore(Purchases): CustomerInfo` throws `PurchasesException` |
| Live updates | `Purchases.setDelegate(PurchasesDelegate)`; `onCustomerInfoUpdated(CustomerInfo)` |
| Ad revenue | `Purchases.getAdTracker(): AdTracker`; `trackAdRevenue(AdRevenueData)` |

All throw rather than return errors, so each call site maps exceptions onto the sealed outcomes
above — matching this codebase's "network/SDK calls degrade gracefully, never crash" convention.

### 3.3 Live entitlement updates (fixes a real gap)

`RevenueCatEntitlements` currently seeds `isPlusActive` **once**, at construction. A purchase
completing while the app runs would leave the banner on screen until the next cold start — the worst
possible moment to look broken, immediately after taking someone's money.

Fix: register a `PurchasesDelegate` whose `onCustomerInfoUpdated` re-reads the `plus` entitlement
into the existing `MutableStateFlow`. Every downstream consumer (`adSlotVisible`, the Settings Plus
row, `canAddFavorite`) already reacts to that StateFlow, so the banner disappears on purchase with no
further changes anywhere.

**Delegate ownership caveat** to respect at implementation: `setDelegate` is a single slot, not a
listener list. `RevenueCatEntitlements` owns it; nothing else may set it.

### 3.4 Paywall

`PaywallScreen` keeps its layout, its `PLUS_BENEFITS` list (minus item 3) and its existing pinned
test. It gains a small `PaywallViewModel` holding offer/purchase/restore state, and renders:

- the **store-localized** price from `PlusOffer` (never a hardcoded "₹299" — the store is the only
  honest source of a price)
- an enabled buy button, disabled only while a purchase is in flight or no offer loaded
- a "Restore purchases" action
- outcome feedback: success, silent-on-cancel, and a plain message on failure

Pure decision logic is TDD'd — button label/enabled-ness as a function of state, and outcome→message
mapping — keeping the RC-touching layer thin, per the pattern already used for maplibre and Ktor.

### 3.5 Ads

- Real AdMob ids arrive via `composeApp/monetization.properties`; the build plumbing at
  `composeApp/build.gradle.kts:235-238` already forwards all three into the manifest, so **no build
  changes are needed** — only config.
- `docs/app-ads.txt` gets the real publisher id. Its own committed caveat must be resolved rather
  than assumed: a GitHub Pages **project** site serves at `/terrawatch/app-ads.txt`, but crawlers
  check the **origin root**. Verify against AdMob's own app-ads.txt checker; if it rejects the
  subpath, the fix is a custom domain or a `<user>.github.io` root-pages repo.
- `AdRevenueTracker` gets wired for real. Its kdoc claims this needs `purchases-android` 8.0+ — that
  is **wrong for this version**: `Purchases.getAdTracker()` exists in purchases-kmp 3.5.0 (§3.2).
  The real catch is different: `AdRevenueData` requires revenue micros + currency + precision, which
  come from AdMob's `OnPaidEventListener`, **not** the `onAdImpression()` callback the current call
  site uses. So the call site moves from `AdListener.onAdImpression` to
  `AdView.setOnPaidEventListener`. Correct the kdoc while fixing it.
  - Bonus: this earns a **second, independent Shipaton qualifier** (RevenueCat Ads) alongside the
    IAP one — cheap insurance if anything about the purchase path disappoints.
- **`adSlotVisible` and its truth-table tests are untouched.** They are pinned by product rule; this
  work must not weaken them.

---

## 4. Immutable rules this design must not regress

Restated because this is the first change that makes the app take money:

- **Alert honesty** — digest framing only. Untouched here, but Plus must never be described as
  improving alert speed or granting early warning.
- **Ad ethics** — banner hidden while the detail sheet is open and during onboarding; ads only while
  Plus is inactive; no interstitials or rewarded ads. Encoded in `adSlotVisible`; tests stay pinned.
- **Privacy** — no backend, no accounts, home location never transmitted. RevenueCat runs in
  anonymous mode with no `logIn()` and no `appUserId`; this is why §2's restore story matters at all.
- **Notification floor** — M4.0+. Unrelated to Plus, and Plus must not become a way to lower it.

---

## 5. Sequencing

RevenueCat's Play link depends on the service account, and Play's IAP creation depends on an
uploaded AAB (already done), so the order is not arbitrary.

**Owner (blocking):**

1. AdMob → app → banner ad unit → `ADMOB_APP_ID`, `ADMOB_BANNER_UNIT`, publisher id.
2. Play Console → Setup → API access → service-account JSON.
3. Play Console → Monetize → In-app products → `terrawatch_plus`, one-time, **non-consumable**.
4. RevenueCat → project → upload the JSON → entitlement `plus` → attach `terrawatch_plus`
   **as non-consumable** → `goog_…` public key.
5. Deliver `terrawatch-upload.jks` + passwords to the build machine (not currently present).
6. Recruit 8 more testers — the only critical-path item with no queue behind it.

**Agent (unblocked now, before any key arrives):** everything in §3 against
`UnavailablePlusPurchases` and test ad ids — interface, android impl, delegate, paywall, viewmodel,
benefit-list correction, `allowBackup`, ad-tracker call-site move, and the full TDD suite.

**Agent (after keys):** `monetization.properties`, real-ads + sandbox-purchase device pass, the 8
GitHub Actions secrets, dry-run `internal` release, then tag → closed track.

---

## 6. Verification

Real-device only, per standing rule. Device for this work: **Pixel 8 (`3C161FDJH000H2`), Android 17
/ API 37** — stock launcher. Evidence committed under `docs/qa/plus-purchase/`.

| # | Row | Needs keys? |
|---|---|---|
| P1 | Paywall with no key configured — honest disabled button, no crash | no |
| P2 | Paywall with real offering — store-localized price rendered | yes |
| P3 | Sandbox purchase completes (Play **license tester**, no real money) | yes |
| P4 | Banner disappears **live** on purchase, without restart (§3.3) | yes |
| P5 | Purchase sheet dismissed → treated as cancel, no error shown | yes |
| R4 | **Uninstall → reinstall → Restore → Plus returns** (§2's footgun) | yes |
| A1 | Real ads render; `adSlotVisible` rules still hold on detail sheet + onboarding | yes |
| A2 | `trackAdRevenue` fires on a real paid event | yes |

**Two opportunities this specific device unlocks, both previously blocked for lack of hardware:**

- **Themed icon.** `docs/qa/post-p5-tail/RESULTS.md` had to leave the monochrome launcher icon
  unverified — OnePlus and Motorola launchers don't expose the toggle. A stock Pixel does. Close it.
- **API 37 forward-compat.** `targetSdk` is 36 and Plan 4 Task 4 listed Android 17 compatibility as
  documentation-only guesswork. This device runs API 37, so it becomes a real runtime check. Any
  behavior change found here is a production-launch blocker, not a curiosity.

---

## 7. Open questions for the owner

1. **Price** for `terrawatch_plus`. Two real benefits; ₹199–299 (~$2.49–3.99) suggested, owner's call.
2. **app-ads.txt hosting** if AdMob rejects the GitHub Pages project subpath (§3.5) — custom domain
   or a root-pages repo.
