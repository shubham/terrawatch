# Post-Plan-5 tail — RESULTS

Branch `fix/post-p5-tail` off `main` @ 7932c04. Two fixes closing the two "still open" items from
`docs/qa/plan-5-device-matrix/round2/RESULTS.md`'s concern #6. Device verification: OnePlus 9R
`98bc1cd8` (Android 14, OxygenOS). Moto (Android 16) was disconnected for this whole session —
every Moto row below is **PENDING**, not fabricated.

## Item → verdict

| # | Item | op9 (98bc1cd8) | Moto |
|---|------|-----------------|------|
| 1 | Settings ALERTS row live-refreshes on resume (grant direction) | **PASS** | PENDING |
| 2 | Settings ALERTS row live-refreshes on resume (revoke direction) | **PASS** | PENDING |
| 3 | Map scale-bar/compass no longer collide with the status bar | **PASS** | PENDING |
| 4 | Map bottom ornaments (logo/attribution) vs. nav bar / ad slot | **PASS** (code-level; not independently visible — see note) | PENDING |

## Fix 1 — Settings alerts row not refreshing on resume

**Root cause (device-verified, not assumed):** `rememberNotificationCondition`'s existing
`ON_RESUME` re-read (`NotificationPermissionCompose.kt`) already worked correctly — proven by the
explainer/"Open Settings" affordance correctly disappearing the moment permission condition became
`ENABLED`. The actual bug was one layer deeper: `alertsRowStatusText` requires **both**
`uiState == ENABLED` **and** `enqueued`, and the periodic digest worker is only ever scheduled by
`MainActivity.onCreate`'s cold-start check or the in-app permission dialog's own
`ActivityResultCallback` — neither of which runs for a grant made through the *external* system
Settings page while the app stays merely paused. `AlertDigestScheduler.isEnqueued()` was answering
`false` honestly; nothing had ever called the function that would make it true.

**Fix:** `SettingsViewModel` gained `alertsUiState`/`alertsEnqueued` StateFlows and a
`refreshAlertsState()` method — re-reads the permission condition and, when it's newly `ENABLED`,
calls a new `AlertDigestScheduler.ensureEnqueued()` (android actual: reuses
`enqueueAlertDigestWorker`, the same idempotent call `MainActivity` already makes at cold start).
`SettingsScreen` installs the same `LifecycleEventObserver`/`ON_RESUME` pattern
`rememberNotificationCondition`/`rememberLocationCondition` already establish, calling
`refreshAlertsState()` on resume. `AlertsPermissionRow` now takes `uiState`/`enqueued` as plain
params (`SettingsScreen` collects, same shape every other section of that screen already uses)
instead of its own local `remember`/`LaunchedEffect`.

This is a deliberate, scoped exception to this app's usual "resolve a platform requester directly
at the composable, never through a ViewModel" rule (`HomeScreen.kt`'s own kdoc) — the real bug
needed a place to *own* an idempotent side effect across recompositions and be TDD'd with a fake
that flips between calls, which a bare composable-level read can't give it. Location's identical
pattern (`UseMyLocationAction`) is untouched.

**TDD:** 5 new tests in `SettingsViewModelTest.kt`, using function-typed constructor params
(`readNotificationCondition`, `isDigestEnqueued`, `ensureDigestEnqueued`) as the fake seam — neither
real `NotificationPermissionRequester`/`AlertDigestScheduler` jvm actual can be substituted with a
controllable fake directly (both hardcode constant answers on jvm — see each file's own kdoc), so
these three are the fakeable dependency, mirroring the `EntitlementsProvider`/`FakeEntitlementsProvider`
precedent already established on this same class for `isPlusActive`.

**Device verify (98bc1cd8, fresh install, permission ungranted at start):**
1. Settings → Alerts: Off, explainer + "Open Settings" shown
   (`fix1-op9-settings-alerts-before.png`).
2. Home button → system Settings → "Allow notifications" toggled ON (uiautomator-dump-guided tap;
   `pm revoke`/`pm clear` both blocked by OxygenOS `SecurityException`, confirming the task's own
   note — toggled via system Settings UI instead, per instructions).
3. Returned via **recents** (`KEYCODE_APP_SWITCH` + tap the TerraWatch card, not a fresh launch) —
   row flipped to **Alerts: On**, without leaving the Settings screen
   (`fix1-op9-settings-alerts-after.png`).
4. Independently confirmed via `adb shell dumpsys jobscheduler`: a real
   `androidx.work.systemjobscheduler` job for `com.yugma.terrawatch` is now `active`/`RUNNABLE` —
   the worker genuinely got enqueued, not just a UI-only flip.
5. Reverse direction: system Settings → "Allow notifications" toggled OFF → returned via recents →
   row correctly flipped back to **Alerts: Off** with the explainer/"Open Settings" reappearing
   (`fix1-op9-settings-alerts-revoke-after.png`).

Moto: **PENDING** (disconnected all session).

## Fix 2 — Map ornament inset under status bar

**Root cause (verified via `javap` against the resolved `maplibre-compose-android-0.14.0.aar`, not
guessed):** `OrnamentOptions`'s real constructor (decoded from its synthetic default-args
constructor's own bytecode) is `padding: PaddingValues = PaddingValues(0.dp)` shared by **all four**
ornaments (`logoAlignment` defaults `BottomStart`, `attributionAlignment` `BottomEnd`,
`compassAlignment` `TopEnd`, `scaleBarAlignment` `TopStart`) — one shared inset, not independent
per-ornament margins. `QuakeMap.android.kt` never passed an `options:` argument to `MaplibreMap(...)`
at all, so the scale bar rendered flush at `padding = 0`, literally the physical top-left of the
map's `Box` — which is edge-to-edge full-bleed by design (`HomeScreen.kt`'s own call site is a plain
`Modifier.fillMaxSize()`, no inset consumption).

**Fix:** `QuakeMap.android.kt` now reads `WindowInsets.statusBars`/`.navigationBars` (same package
`SettingsScreen.kt`'s own `WindowInsets.systemBars` already comes from), converts to `Dp` via
`LocalDensity`, and passes `options = MapOptions(ornamentOptions = OrnamentOptions(padding =
PaddingValues(top = statusBarInset, bottom = navigationBarInset)))` to `MaplibreMap(...)`. One
shared `padding` value fixes both the top ornaments (scale bar, compass) and bottom ornaments
(logo, attribution) in a single change, matching the library's own single-field API.

No logic to TDD here (a straight `WindowInsets` → `PaddingValues` wire-up, no branching) — verified
by real API inspection (`javap`) instead, per this file's own established discipline, plus device
screenshots.

**Device verify (98bc1cd8, notch device):**
- Before: scale bar text/ruler rendered overlapping the status bar icons/clock
  (`fix2-op9-map-top-before.png`).
- After: clear gap between the status bar and the scale bar, no overlap
  (`fix2-op9-map-top-after.png`).
- Bottom ornaments (logo/attribution) vs. nav bar/ad slot: **not independently visible** on the
  Home tab in either state — the feed sheet's own peek height permanently covers that region of
  the map's `Box` (the sheet is an overlay, not a layout squeeze — see `HomeScreen.kt`'s own kdoc:
  "the map is meant to run full-bleed under the sheet"), so there was nothing to screenshot before
  OR after. The `bottom` padding was still applied (same shared `OrnamentOptions.padding` field, so
  fixing the top edge fixes the bottom edge in the same change) — correct by construction and by
  the library's own API shape, just not independently device-provable through a visible defect.
  Noted per this task's own "fix if visibly wrong, note if fine" allowance.

Moto: **PENDING** (disconnected all session).

## Tests / compiles

- `./gradlew :composeApp:jvmTest --max-workers=4` — **245 tests, 0 failures, 0 errors** (24 of them
  new/touched in `SettingsViewModelTest.kt` for Fix 1).
- `./gradlew :composeApp:compileDebugKotlinAndroid :composeApp:compileKotlinJvm
  :composeApp:compileKotlinWasmJs --max-workers=4` — all 3 targets green.
- `./gradlew :composeApp:assembleDebug --max-workers=4` — green, APK installed and used for both
  device-verify passes above.

## Concerns

- Fix 2's bottom-ornament half (logo/attribution vs. nav bar) could not be independently
  device-proven on Home — permanently occluded by the feed sheet in every reachable state on that
  screen (see above). Not a regression risk (same shared-field fix as the proven top half), but
  flagging the gap honestly rather than claiming a screenshot that doesn't exist.
- Moto (Android 16) verification is 100% PENDING for both fixes — device was disconnected this
  entire session. Whoever next has it connected should repeat the same two device-verify sequences
  above (grant+revoke via system Settings + recents for Fix 1; scale-bar/status-bar overlap check
  for Fix 2 — Moto's own notchless-but-cutout display was the original site concern #6 was raised
  against).
- `pm revoke`/`pm clear` are both blocked by a `SecurityException` on this OxygenOS build (same
  restriction the task brief already flagged) — every permission-state change in this pass went
  through either a full uninstall/reinstall or the real system Settings UI, never a raw adb
  permission command.
