# Device pass — ads-only monetization (1.1.0)

Date: 2026-09-21
Device: Pixel 8 (`shiba`), serial `3C161FDJH000H2`, Android 17 / API 37
Build: `:composeApp:assembleRelease`, arm64-v8a, R8 + resource shrinking ON
Branch: `feat/ads-only-monetization`
Plan: `docs/superpowers/plans/2026-09-21-ads-only-monetization.md`, Task 9

## Build signing — read this before trusting the artifact

The APK tested here and the `.aab` produced alongside it are **debug-signed**. `CI_KEYSTORE_PATH`
was not set, so the release `signingConfig` took its documented debug fallback. The release
*configuration* is otherwise real — R8, resource shrinking, and the production `applicationId` with
no debug suffix — which is what makes this a meaningful test of code stripping.

**Neither artifact is uploadable to Play.** The owner must rebuild with the four `CI_*` signing
variables set, from the upload keystore at `~/keys/terrawatch/`.

## Tests

`./gradlew jvmTest :core:database:verifySqlDelightMigration` — **765 passing, 0 failures, 0 errors**,
migration guard green.

Baseline before this work was 777. The drop is expected and correct: five Plus test files were
deleted (`EntitlementsGateTest`, `PlusPurchasesTest`, `SilentRestoreTest`, `PaywallButtonStateTest`,
`PaywallScreenTest`), partly offset by new tests for the flat cap, the two-input ad rule, the city
catalog's integrity, and `filterCities`.

One counting trap worth recording: a first run reported **784**, because Gradle's
`build/test-results/` still held XML from the deleted test classes — including a
`FavoritePlaceGateTest` result for a file that no longer exists. Purging `test-results` and
re-running gave the true 765. Any future count taken after deleting test files needs the same purge.

## What was verified

| # | Check | Result | Evidence |
|---|---|---|---|
| 1 | No banner during onboarding | PASS | `01-onboarding-no-ad.png` |
| 2 | Banner shows on home for a user who bought nothing | PASS | `03-home-banner-visible-testad.png` |
| 3 | Settings has no "TerraWatch Plus" row and no paywall route | PASS — sections are ALERTS / PLACES / THEME / ABOUT | `04-settings-no-plus-row.png` |
| 4 | City picker has a search field, rows show "City, Country" | PASS | `05-city-picker-search.png` |
| 5 | Search filters by country name | PASS — "japan" returns only Japanese cities | `06-city-search-japan.png` |
| 6 | Empty state renders for a non-matching query | PASS | `07-city-search-empty-state.png` |
| 7 | Five favourites can be added | PASS — Tokyo, Lima, Athens, Manila, Quito | `08-favorites-capped-at-5.png` |
| 8 | At five, "Add place" is disabled and reads "Maximum 5 places" | PASS | `08-favorites-capped-at-5.png` |
| 9 | The disabled row is genuinely inert, not just relabelled | PASS — tapping it does not open the picker | — |
| 10 | Version reads 1.1.0 in Settings → ABOUT | PASS | `08-favorites-capped-at-5.png` |
| 11 | Banner returns on home with 5 favourites saved | PASS | `10-home-banner-with-5-places.png` |
| 12 | Banner suppressed while a quake detail sheet is open | PASS | `11-detail-open-banner-suppressed.png` |
| 13 | Cold-start crash sweep | PASS — no FATAL EXCEPTION, no app-tagged errors | — |
| 14 | Release AAB packages the real AdMob publisher ID, not a test ID | PASS | — |

Crash sweep deliberately did **not** grep the bare `AndroidRuntime` tag: the uiautomator tooling
used to confirm these screens logs benign startup lines under it, a false positive recorded in
commit `de86081`.

Every screenshot was saved only after a `uiautomator` dump confirmed a marker unique to that screen.

## The ad-fill caveat — the one thing not proven with production configuration

With the **real** ad unit configured, the banner did **not** render. Logcat showed
`Ad failed to load : 3` (`ERROR_CODE_NO_FILL`) — the SDK initialised, reached
`googleads.g.doubleclick.net`, and was told there was no ad to serve.

To separate "our code is wrong" from "AdMob has no inventory", the banner unit was temporarily
swapped for Google's official test unit and the app rebuilt. The banner then rendered immediately
(checks 2, 11, 12 above, all showing the "Test Ad" creative). **The ads-for-everyone code path is
therefore proven; only live fill is unproven.**

The real unit was restored immediately afterwards, and the packaged `.aab` manifest was inspected
to confirm the production publisher ID is what actually ships (check 14). No test ID remains
anywhere in the build.

No-fill on a young ad unit is expected and is documented in commit `a7e910d`; a debug-signed build
of an app whose Play listing is live makes fill less likely still. This is worth re-checking from a
properly signed build before drawing any conclusion about ad revenue.

## Two false alarms, recorded so they are not re-investigated

1. **"Empty state missing."** The uiautomator dump for a non-matching search query showed no
   "No cities match" text, suggesting the empty state had not been implemented. The source had it,
   and the screenshot shows it rendering correctly. The accessibility dump simply omitted that
   Compose node. **The screenshot was right and the dump was wrong** — worth remembering, since the
   whole screenshot discipline here rests on dumps being trustworthy.
2. **"Favourites capped at 3."** The "Add place" row disappeared after three additions. It had
   scrolled below the fold as the list grew; scrolling found it enabled, and two more places were
   added normally. uiautomator only dumps visible nodes.

## Not done

- **Store screenshots were not regenerated.** The plan calls for recapturing them from a real-ads
  build, and the committed set still shows a "Test Ad" placeholder. With the real unit returning
  no-fill, a recapture today would either show an empty slot or, worse, reuse a test creative while
  claiming to show production. Blocked until the real unit serves; owner should recapture, or ask
  for a recapture once fill is confirmed.
- **No upload to Play, no tag, no push.** Task 9 stops here by design.
