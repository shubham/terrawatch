# Android platform 17 batch — RESULTS

Branch `fix/android-platform-17`, stacked on `feat/plus-purchase-flow`. Device: **Pixel 8
`3C161FDJH000H2`, Android 17 / API 37**, stock launcher. Session 2026-09-06.

Three items raised by the owner: a stretched splash wordmark, two Play Console edge-to-edge
warnings, and support for Android 17.

## 1. Splash wordmark stretch — FIXED, with before/after

**Reported:** "App name and logo looks stretched in the device."

**Root cause, measured not guessed.** Android's branded-image slot
(`windowSplashScreenBrandingImage`) is a **fixed 200×80 dp**; the OS scales whatever drawable it is
given to fill that slot. `splash_wordmark.xml` was declared at **184×24 dp** — its exact glyph
bounding box, a 7.65:1 ratio dropped into a 2.5:1 slot. The result is a **~3.3× vertical stretch**.

| | |
|---|---|
| Before | `splash-before-184x24-stretched.png` — letterforms visibly tall and narrow |
| After | `splash-after-200x80.png` — correct Inter SemiBold proportions |

Both were captured on this device by cold-starting the app, so the pair is a genuine before/after of
the same build path rather than a render of the drawable in isolation. Capturing them needed a rapid
on-device `screencap` loop started *before* launch — a single post-launch `screencap` always lands
after the splash has gone.

**The fix changes no glyph data.** Only the frame moved: the drawable is now declared at exactly the
slot's 200×80 dp, `viewportHeight` widened 1513 → 4631.6 so the viewport matches the slot's 2.5:1
ratio, and one `<group android:translateY="1559.3">` centres the original glyph box inside the
taller viewport. Nothing is scaled anisotropically, and the wordmark lands ~26 dp tall — close to
the 24 dp originally intended.

## 2. Play Console edge-to-edge warnings — NOT OURS, and not fixable by upgrading

Play Console flagged, against release 3 (1.0.0):

> *"Edge-to-edge may not display for all users"* and *"Your app uses deprecated APIs or parameters
> for edge-to-edge"*

**This app's own source is clean.** Grepped for the whole deprecated family — `statusBarColor`,
`navigationBarColor`, `setDecorFitsSystemWindows`, `systemUiVisibility`,
`isNavigationBarContrastEnforced`, `windowLightStatusBar` — across every Kotlin and XML source in
`composeApp/` and `core/`: **zero hits.** The app already calls `enableEdgeToEdge()` and pads every
screen through `windowInsetsPadding`.

**The references come from `androidx.activity` itself.** Disassembling the resolved
`activity-1.10.1.aar` shows three classes calling the deprecated window colour setters:

```
androidx.activity.EdgeToEdgeApi23 -> 2 refs
androidx.activity.EdgeToEdgeApi26 -> 2 refs
androidx.activity.EdgeToEdgeApi29 -> 2 refs
```

These are the pre-API-30 implementations `enableEdgeToEdge()` dispatches to. Play's static scan sees
the references in the merged app regardless of which branch actually executes at runtime.

**Upgrading does not help — checked, rather than assumed.** Downloaded the current stable
`activity-1.13.0.aar` (Mar 2026) and disassembled it too:

```
androidx.activity.EdgeToEdgeApi23 -> 2 refs
androidx.activity.EdgeToEdgeApi26 -> 2 refs
androidx.activity.EdgeToEdgeApi29 -> 2 refs
androidx.activity.EdgeToEdgeApi35 -> 2 refs   <- newly added, also references them
```

The latest release has *more* classes touching those APIs, not fewer, and its release notes make no
claim of removing them. **No dependency bump was made**: it would have been version churn plus
Compose-Multiplatform compatibility risk, for zero change to the warning.

**Verdict:** informational, originates in a Google library, not a release blocker, and nothing we
can act on. Recorded here so it is not re-investigated at each release.

## 3. Android 17 / targetSdk 37 — DONE, verified on device

`compileSdk` and `targetSdk` moved **36 → 37** in `composeApp/build.gradle.kts`. The `core:*`
modules stay at `compileSdk 36`; only the application module determines the platform behaviour the
app opts into, and leaving the libraries alone keeps the diff to the thing being changed.

Builds and packages clean. One AGP advisory, no error:

```
WARNING: We recommend using a newer Android Gradle plugin to use compile SDK version 37.0
```

AGP is 8.10.1. This is a recommendation, and everything downstream (resource linking, manifest
merge, dexing, packaging, install) completed normally. Merged manifest confirms
`android:targetSdkVersion="37"`.

**Device sweep at targetSdk 37** — Home, History, Insights, Settings, detail sheet, with back
navigation between each:

| Screen | Verdict | Evidence |
|---|---|---|
| Home (map, pill, feed, distances) | **PASS** | `tsdk37-home.png` |
| History | **PASS** | `tsdk37-history.png` |
| Insights | **PASS** | `tsdk37-insights.png` |
| Settings | **PASS** | `tsdk37-settings.png` |
| Detail sheet | **PASS** | `tsdk37-detail-sheet.png` |
| Crash sweep | **PASS — 0 crashes** | `tsdk37-crash-sweep.txt` (empty = clean) |

Each screenshot was saved **only after a `uiautomator` dump confirmed a marker unique to that
screen** was actually present. That guard caught a real mistake mid-session: an earlier pass had
tapped through a different navigation state and produced a file named `tsdk37-home.png` that
actually showed Settings. Those files were deleted and re-captured rather than committed.

### A note on the runtime-error sweep filter

`tsdk37-androidruntime.txt` filters on `FATAL EXCEPTION` / `E AndroidRuntime`, **not** on the bare
`AndroidRuntime` tag. A plain tag grep is misleading here: the `uiautomator` tool used to verify
these very screenshots logs benign startup lines under that same tag (uid 2000,
`com.android.commands.uiautomator.Launcher`). An earlier run of this sweep produced 30 such lines
and none were from the app.

After filtering: **zero fatal exceptions, zero app crashes.** Six `E`-level lines mention the
package and are all logged *by other processes about* it, not by it:

- `FilePhenotypeFlags` (from Google Play Services, `com.google.android.gms.clearcut_client`) — a GMS
  flag-storage warning about the package name.
- `WindowOrganizerController` (from `system_server`) — task-container bookkeeping.

Neither is app code, and neither is actionable.

### Risk worth stating plainly

Targeting a freshly-released SDK opts the app into every Android 17 behaviour change at once, and
AGP 8.10.1 does not formally claim support for compile SDK 37. Google does not yet *require* target
37 — 36 remains current. This was done because the owner asked for Android 17 support, and it is
verified on a real API 37 device rather than assumed. If anything surfaces in wider testing, the
revert is a two-line change to `composeApp/build.gradle.kts`.

## Verification

- `./gradlew jvmTest` — **777 passing, 0 failures**
- `compileDebugKotlinAndroid`, `compileKotlinJvm`, `compileKotlinWasmJs` — all green
- `:core:database:verifySqlDelightMigration` — green
- Device state: the Play Store tester install (`com.yugma.terrawatch`) was never touched; all work
  ran against `com.yugma.terrawatch.debug`.
