# Design: ads-only monetization, 5 favourite places, 100+ searchable cities

Date: 2026-09-21
Status: design direction approved in chat by owner 2026-09-21; this written spec awaiting owner review
Supersedes, in part: `docs/superpowers/specs/2026-09-05-plus-purchase-and-real-ads-design.md`
— that design's **purchase half is withdrawn**; its ads half survives and is extended here.

## 1. Why

TerraWatch currently ships a one-time "TerraWatch Plus" purchase that unlocks exactly two
things: no ads, and unlimited favourite places. The owner has decided to stop selling it and
monetize purely through ads.

The decisive fact, established during recon rather than assumed: **the purchase has never been
sellable.** `composeApp/monetization.properties` has a blank `REVENUECAT_API_KEY`, so the app
falls back to `AlwaysFreeEntitlements` / `UnavailablePlusPurchases` at runtime, and no build
carrying a live key has ever reached production. There are therefore **no existing purchasers**:
no refunds, no grandfathering, and no entitlement-honouring code path to preserve. This is what
makes the aggressive removal below safe, and it is the single assumption most worth re-checking
if this document is read later.

## 2. Scope

In scope:
1. Delete the Plus purchase flow, the paywall, and the entitlement system.
2. Show the banner ad to every user.
3. Remove the Plus row from Settings.
4. Replace the tiered favourites limit with a flat cap of 5 for everyone.
5. Grow the city catalog from 10 to 100+, weighted toward seismically active regions, with a
   search filter in the picker.
6. Reduce RevenueCat to a single job: recording ad revenue.
7. Ship 1.1.0 to Google Play.

Explicitly **not** in scope:
- Interstitial or native ads. The owner chose banner-only. Interstitials are the most common
  cause of AdMob policy strikes and this app has no track record to spend.
- Any subscription. Removing one purchase is not a prompt to invent another.
- Rewriting the city picker as a full screen. Considered and rejected: search solves the
  scroll problem, and a screen would touch nav plus three call sites for no user-visible gain.
- Chasing the `activity` AAR's deprecated edge-to-edge warning. Commit `de86081` settled this
  deliberately; do not re-investigate.

## 3. Removing the purchase

### 3.1 Delete the `core/monetization` module entirely

Once Plus is gone the module's only surviving inhabitant is `FavoritePlaceGate.kt`, and a
"maximum 5 places" product limit is no longer a monetization concern. Keeping a module alive for
one constant is worse than moving the constant.

Deleted outright:

| File | Note |
|---|---|
| `core/monetization/**` (whole module) | Includes `EntitlementsProvider.kt`, `EntitlementsGate.kt`, `PlusPurchases.kt`, `SilentRestore.kt`, `RevenueCatEntitlements.kt`, `RevenueCatPlusPurchases.kt` |
| `composeApp/.../paywall/PaywallScreen.kt` | |
| `composeApp/.../paywall/PaywallViewModel.kt` | |
| Tests: `EntitlementsGateTest`, `PlusPurchasesTest`, `SilentRestoreTest`, `PaywallButtonStateTest`, `PaywallScreenTest` | |

Moved, not deleted: the favourites cap, relocated to `core/model` (see §5).

Edited:
- `settings.gradle.kts:26-31` — drop the `:core:monetization` include.
- `composeApp/.../nav/AppNav.kt` — remove `Routes.PAYWALL` (L73), its composable destination
  (L449-464), the `entitlementsProvider` injection, and `isPlusActive` from the ad mount gate.
- `composeApp/.../settings/SettingsScreen.kt` — delete `PlusRow` (L631-652) and its
  `SETTINGS_PLUS_ROW_TAG`; rework `AddPlaceRow`'s click handler (L257) per §5.
- `composeApp/.../settings/SettingsViewModel.kt` — drop `isPlusActive` (L116) and the
  `entitlementsProvider` constructor parameter.
- `composeApp/.../di/AppModule.kt` and `androidMain/.../di/KoinBootstrap.kt` — remove
  `buildEntitlementsProvider`, `buildPlusPurchases`, and their `single {}` registrations.
- `composeApp/androidMain/.../MainActivity.kt` — remove the `attemptSilentRestore` hook.
- jvm and wasmJs `main.kt` — stop passing `AlwaysFreeEntitlements`.

**Load-bearing constraint.** Commit `415e76c` records that argument order in the `appModule(...)`
call is semantically significant (Kotlin evaluates arguments left to right), and warns that a
future tidy-up could silently break it. Removing two parameters from that call must preserve the
relative order of the remaining ones.

### 3.2 RevenueCat survives, in a much smaller role

`AdRevenueTracker` pipes AdMob's `OnPaidEventListener` into
`Purchases.sharedInstance.adTracker.trackAdRevenue(...)`, so `purchases-kmp-core` is still
needed — but by `core/ads`, not by the deleted module. The dependency declaration moves
accordingly, and `Purchases.configure(...)` moves out of `buildEntitlementsProvider` into a
small ad-revenue initialiser in `KoinBootstrap`.

The blank-key path must keep working: with `REVENUECAT_API_KEY` empty we simply never call
`configure`, and `AdRevenueTracker`'s existing `Purchases.isConfigured` guard (mandatory — per
commit `3780e11` it throws on every paid event without it) makes tracking a silent no-op. The
app must be fully functional and ad-serving with no RevenueCat key at all. The owner may supply
the key later with no code change.

## 4. Ads for everyone

`adSlotVisible(isPlusActive, isDetailOpen, isOnboarding)` becomes
`adSlotVisible(isDetailOpen, isOnboarding)`. Suppression during onboarding and while a detail
sheet is open is deliberate, good, and retained. `AppNav.kt`'s structural unmount of
`BannerAdSlot` loses its `!isPlusActive` condition.

`AdSlotVisibilityTest`'s exhaustive truth table shrinks from 2^3 to 2^2 cases and keeps its
exhaustive character. `AppNavAdEligibilityTest`'s Plus-dependent branches are removed.

Ad identifiers are unchanged and already real: `ADMOB_APP_ID` and `ADMOB_BANNER_UNIT` are
populated in the gitignored `composeApp/monetization.properties` under publisher
`ca-app-pub-2136592315832501`. No new ad unit is required, because no new ad format is added.

## 5. Favourite places: flat cap of 5

`canAddFavorite(currentCount: Int, isPlus: Boolean)` becomes `canAddFavorite(currentCount: Int)`,
returning `currentCount < MAX_FAVORITE_PLACES` where `MAX_FAVORITE_PLACES = 5`. The function and
constant move to `core/model` alongside `FavoritePlace`.

The behavioural change that matters is the **failure path**. Today, tapping "Add place" at the
limit navigates to the paywall. That screen will not exist, and a tap that does nothing is worse
than no tap. At 5 places the row renders disabled with the supporting text "Maximum 5 places",
so the limit is legible before it is hit rather than after.

No storage migration is needed: `favoritePlace` is an ordinary SQLDelight table with no row-count
constraint, and nothing downstream indexes favourites by position. `AlertDigestSupport` and
`AlertDigestWorker` iterate the full list with `mapNotNull` and scale to 5 without change.

Existing users can currently hold at most 1 favourite (free tier), so **no user can be over the
new cap** and no truncation logic is required.

Tests rewritten: `FavoritePlaceGateTest` (its `currentCount=1` block assertion encodes the old
cap) and `SettingsViewModelTest:330`.

## 6. City catalog: 100+ cities, searchable

### 6.1 The data

`PRESET_CITIES` moves out of `LocationAskDialog.kt` — the wrong home for a list this size — into
a dedicated `CityCatalog.kt` in the same `location` package.

`PresetCity` gains a `country: String` field. At 100+ entries, bare city names are genuinely
ambiguous, and country text materially improves search.

Selection is **weighted toward seismically active regions**, since an earthquake app's users
cluster there. Target composition of ~120 entries:
- Pacific Ring of Fire: Japan, Indonesia, Philippines, Taiwan, New Zealand, Chile, Peru, Mexico,
  US West Coast, Alaska, Papua New Guinea.
- Himalayan / Alpide belt: India, Nepal, Pakistan, Afghanistan, Iran, Turkey, Greece, Italy.
- Other notable seismic zones: California, Caribbean, East African Rift, Iceland, Romania.
- Plus major world population centres regardless of seismicity, so the picker is not useless to
  a user in London or Lagos.

### 6.2 Coordinate integrity

Coordinates written from a model's memory are exactly the kind of data that is plausibly wrong,
and a wrong coordinate silently shows a user the wrong region's earthquakes. Three guards:

1. A unit test asserting: no duplicate `name + country` pairs, latitude in -90..90, longitude in
   -180..180, and catalog size >= 100.
2. A curated spot-check in that same test pinning ~15 well-known cities to within 0.5 degrees.
3. An independent verification pass by a separate agent that did not author the list,
   cross-checking every entry and reporting mismatches rather than silently fixing them.

Guard 3 is the one that matters; 1 and 2 only catch gross errors.

### 6.3 Search

A pure `filterCities(query: String, cities: List<PresetCity>): List<PresetCity>` in commonMain,
matching case-insensitively against name and country, with a blank query returning everything.
Being pure, it is covered by fast unit tests and needs no UI test.

`CityPickerDialog` gains a search `TextField` above a height-constrained `LazyColumn`. It stays a
dialog. All three call sites — `LocationAskDialog`, `SettingsScreen`, `OnboardingScreen:392` —
keep their current integration unchanged.

## 7. Release

Version 1.0.0 -> **1.1.0**, versionCode 3 -> 4. Three literals are hand-synced and must move
together — the repo documents this landmine in two places, and CI overrides versionCode via
`-PciVersionCode` regardless:
- `composeApp/build.gradle.kts:219` (versionCode fallback) and `:220` (versionName)
- `composeApp/.../settings/SettingsScreen.kt:130` (`APP_VERSION`)
- `README.md:9`

Doc corrections required: `docs/HANDOFF.md` §3 states the app has never been uploaded to Play.
The owner has confirmed it **is live** and that the upload-key reset was **approved**, so the
replacement keystore at `~/keys/terrawatch/` signs valid uploads. That section is stale and gets
corrected as part of this work.

`store-assets/listing.md` mentions Plus benefits and needs its copy trimmed.

### 7.1 Owner-only actions (cannot and should not be automated)

- Turn **off** the "In-app purchases" declaration on the Play listing. The app will contain no
  billing code; leaving the flag on is a false declaration.
- Upload regenerated screenshots. Current ones show a "Test Ad" placeholder banner.
- Provide the RevenueCat key if and when ad-revenue reporting is wanted.

### 7.2 Upload path

`.github/workflows/release.yml` can deploy on a `v*` tag via `r0adkll/upload-google-play`, but
`gh secret list` returned empty, suggesting the 8 required secrets are unset. Before any tagged
release the secrets are verified; if absent, the deliverable is a locally signed `.aab` for the
owner to upload. **No upload to Play happens without explicit per-release confirmation from the
owner**, automated path or not.

## 8. Testing

- `./gradlew jvmTest :core:database:verifySqlDelightMigration` — the migration guard must stay
  green even though no schema changes here.
- Baseline is 777 tests. Expect a net drop from deleting ~5 Plus test files, partly offset by new
  cap, catalog-integrity, and `filterCities` tests. The final number is reported honestly rather
  than compared to 777 as if it should grow.
- TDD: tests are updated or written before the implementation they describe.
- Device pass on the connected Pixel 8 (`3C161FDJH000H2`, Android 17 / API 37): banner visible
  with no purchase surface anywhere, favourites capped at 5 with a disabled row at the limit,
  city search filtering correctly, no Plus row in Settings, no route to a paywall, and a
  cold-start crash sweep.
- Screenshot discipline, per `879d1ce` and `de86081`: a capture is saved only after a
  `uiautomator` dump confirms a marker unique to that screen, and the crash sweep does not grep
  the bare `AndroidRuntime` tag because the uiautomator tooling logs benign startup lines there.

## 9. Risks

| Risk | Mitigation |
|---|---|
| A user did somehow buy Plus | Recon says impossible (blank key, never shipped). If contradicted, stop and reopen the grandfathering decision rather than proceeding. |
| Hallucinated city coordinates | Independent verification agent + range/duplicate tests (§6.2). |
| Koin `appModule` argument order broken by parameter removal | Explicit constraint called out in §3.1; DI smoke-checked on device. |
| Removing IAP while the listing still declares it | Owner action, tracked in §7.1. |
| Upload key rejected despite reported approval | Verify signing before promoting; the fallback is a manual upload and existing installs are never at risk. |

## 10. Execution

Planning by a reasoning model; implementation fanned out to cheaper agents on **disjoint file
sets**, which is what makes the parallelism safe rather than merely fast.

| Agent | Scope | Ordering |
|---|---|---|
| A | Purchase and module removal, DI, nav, ad gate | first |
| B | Favourites cap, Settings UI | after A — both touch `SettingsScreen.kt` |
| C | City catalog, `filterCities`, picker search | parallel with A |
| C2 | Independent coordinate verification | after C |
| D | Version bump, README / HANDOFF / listing corrections | last |

A and B are the only pair with a genuine file conflict and must not run concurrently.
