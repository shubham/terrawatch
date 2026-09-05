# Plus purchase flow + real ads — device RESULTS

Branch `feat/plus-purchase-flow`. Device: **Pixel 8 `3C161FDJH000H2`, Android 17 / API 37**, stock
launcher. Session 2026-09-05/06.

Covers the keyless rows of the plan's §6 matrix. Rows needing a RevenueCat key or a filled ad are
marked **PENDING** below and were not attempted — not silently skipped.

## Build under test

| | |
|---|---|
| applicationId | `com.yugma.terrawatch.debug` (see "Install strategy") |
| AdMob app id | `ca-app-pub-2136592315832501~4421841135` (real, confirmed in merged manifest) |
| AdMob banner unit | `ca-app-pub-2136592315832501/7183735849` (real, confirmed in merged manifest) |
| RevenueCat key | **absent** — deliberately, the project does not exist yet |

### Install strategy — why the debug build has its own applicationId

The Pixel already carries the **Play Store** install (`installer=com.android.vending`, v1.0.0
versionCode 3, installed 2026-09-04): the owner is one of their own closed-test testers. A
same-applicationId debug build fails outright — `INSTALL_FAILED_UPDATE_INCOMPATIBLE: signatures do
not match` — and the only way through would have been uninstalling a live tester install in the
middle of a 14-day continuous-tester window.

`applicationIdSuffix = ".debug"` was added to the debug build type instead. Both packages now
coexist, confirmed:

```
package:com.yugma.terrawatch        installer=com.android.vending   <- tester install, untouched
package:com.yugma.terrawatch.debug  installer=null                  <- this build
```

### AdMob account safety — test device registration

Once a real ad unit id is configured, every debug run requests **live** ads against the owner's
own AdMob account, and a device pass loads that banner repeatedly. Google's guidance is explicit:
*"If you click too many ads without being in test mode, you risk your account being flagged for
invalid activity."*

So this device was registered via `RequestConfiguration.setTestDeviceIds`, gated on
`FLAG_DEBUGGABLE`. Confirmed in logcat:

```
I Ads : This request is sent from a test device.
```

The real unit id is still exercised end to end; only the creative is non-billable.

## Results

| # | Row | Verdict | Evidence |
|---|-----|---------|----------|
| P1 | Paywall with no RevenueCat key: 2 benefits, disabled button, restore present, no crash | **PASS** | `p1-paywall-no-revenuecat-key.png` |
| P1b | Restore with nothing owned reports plainly, not as an error | **PASS** | `p1b-restore-nothing-found.png` |
| A0 | No banner during onboarding (ad-ethics rule) | **PASS** | `a0-onboarding-no-banner.png` |
| X1 | API 37 sweep — every screen, zero crashes | **PASS** | `api37-*.png`, `api37-crash-sweep.txt` |
| A1 | Real ads render | **BLOCKED** — see below | `NO_FILL` in logcat |
| A2 | `trackAdRevenue` fires on a real paid event | **BLOCKED** — depends on A1 | — |
| T1 | Monochrome themed launcher icon | **NOT VERIFIED** — see below | — |
| P2–P5, R4 | Offer price, sandbox purchase, live ad removal, cancel path, reinstall restore | **PENDING** — needs RevenueCat key + Play IAP | — |

### P1 — paywall without a key

Renders exactly as designed. Two benefits, **"Custom alert rules (coming soon)" is gone**. Button
reads "Purchases unavailable" and is visibly disabled. "Restore purchases" present. Status line
"You're on the Free tier".

### P1b — restore path

Tapping Restore produced, verbatim from `RestoreOutcome.NothingToRestore`:

> No previous purchase found on this account.

Which exercises the whole chain — `PaywallScreen` → `PaywallViewModel.restore()` →
`UnavailablePlusPurchases.restore()` → rendered copy — and confirms the deliberately plain,
non-alarming wording for the common case of someone who never bought Plus. **0 crashes.**

### X1 — API 37 forward-compat (the notable result)

`targetSdk` is **36**; this device runs **API 37**. Plan 4 Task 4 could only reason about Android 17
on paper for lack of hardware. Swept Home/map, History, Insights, Settings, paywall, onboarding, and
a detail sheet, with back navigation between each.

**Zero crashes, zero `AndroidRuntime`/`FATAL`/`StrictMode` hits.** Both sweep files are legitimately
empty — that is the clean result, not a capture failure. Insets, predictive back and the detail
sheet's nav-bar clearance all render correctly (`api37-detail-sheet.png`).

### A1/A2 — blocked, and why it is not a defect

`I Ads : Ad failed to load : 3` — error code 3 is `NO_FILL`. Expected: AdMob's own confirmation
dialog states *"New ad units may take up to an hour to start showing ads"*, and the unit was created
minutes earlier. The request itself is correctly wired — real unit id in the merged manifest, test
device recognised. **Re-run once the unit has warmed up.** Nothing here indicates a code problem,
and nothing here is evidence that the banner works either.

### T1 — themed icon, attempted and not achieved

`<monochrome android:drawable="@drawable/ic_launcher_monochrome" />` is correctly declared in
`mipmap-anydpi-v26/ic_launcher.xml`. Attempted to enable themed icons by writing
`android.theme.customization.themed_icon` into `Settings.Secure` directly. The value was written and
read back correctly, but **the Pixel Launcher ignored it** — every launcher icon stayed full-colour,
so themed mode never actually engaged. The toggle evidently needs the Wallpaper & style UI flow,
which writes more state than that one key.

The capture from that attempt was **deleted rather than committed**: it showed unthemed icons and
would have read as evidence of a result that was never obtained.

Still open, same as after `post-p5-tail` — but for a different reason now. It is no longer "no
launcher on hand supports it" (this one does); it is "not drivable from the shell". Ten seconds of
manual toggling in Wallpaper & style → Themed icons would settle it.

**Device state left as found:** the theme setting was restored to its exact original value
(byte-compared, confirmed) and the launcher restarted. The Play tester install was never touched.
