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
| 5 | Dark mode: favorite places visible/legible (Home quick-switch chips + Settings Places rows) | **PASS** | PENDING |

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

## Fix 3 — Dark mode: favorite places not visible correctly

**Reproduced on device first (98bc1cd8, dark mode confirmed active — `cmd uimode night` → "Night
mode: yes" — before touching any code).** No favorite existed yet on this device, so a "Tokyo"
favorite was added via Settings → Places → Add place to make both surfaces reproducible.

**What was exactly illegible (from the before-screenshots):**
- `darkmode-favorites-before-home.png` (Home quick-switch chip row, `PlaceQuickSwitchChips`,
  floating over the map): the **"Home" chip (selected) was fine** — a solid dark fill with clearly
  readable light text. The **"Tokyo" chip (unselected) was the failure**: no visible container at
  all (the map showed straight through it), leaving only a faint, barely-there border and a label
  whose text color nearly matched the map tile behind it — see the zoomed crop, `Tokyo` reads as a
  near-invisible ghost outline. This is literally the reported bug: a favorite place's own
  quick-switch chip was not visible.
- `darkmode-favorites-before-settings.png` (Settings → Places → `FavoriteRow`'s All/Major
  only/Off alert-type chips): device-checked and **found NOT broken** — both the selected "All"
  chip and the unselected "Major only"/"Off" chips were clearly legible against the flat, opaque
  `SettingsCard` background. Zoomed crop confirms crisp borders and bright, readable labels in both
  states. No fix needed on this surface; called out explicitly rather than silently assumed fine.

**Root cause (device-measured, not assumed) — and a correction to the task brief's own premise:**
the brief suspected "dark map tiles behind" the Home chips. On-device, the map is **never dark** —
`QuakeMap.android.kt` hardcodes `OPENFREEMAP_LIBERTY_STYLE_URL`, a single fixed light basemap used
in *both* app themes (that file's own kdoc: "there is no style-wide/vector desaturation hook in
this library's public API" — already investigated, not a live option). The real defect:
`PlaceQuickSwitchChips` is the one floating control on this screen with no `containerColor`
override, so an *unselected* `FilterChip`'s M3 default (`Color.Transparent`) let the always-light
map show straight through. Dark theme's `onSurfaceVariant`/`outline` tokens (`Water`-based) are
tuned for contrast against *opaque dusk surfaces* elsewhere in the app, not against an arbitrary
light map tile. Pixel-sampled directly off the real screenshot (`python3`/Pillow, exact device
pixels, not guessed): map ocean tile `#9EBDFF`; unselected label text rendered at `#D9E9F4`
(`TerraColors.Water`, confirmed exact match). WCAG ratio (same relative-luminance formula
`ContrastTest.kt` uses):

| Pair | Ratio | Floor | Verdict |
|---|---|---|---|
| Water label text on sampled map-ocean tile (`#9EBDFF`) | **1.51:1** | 4.5:1 (text) | FAIL |
| Water@40% border blend on the same map tile | **1.19:1** | 3.0:1 (non-text) | FAIL |

Every OTHER floating control on this exact screen (`StatusShield`, `StalenessBanner`,
`SettingsGearChip`, `MyLocationFab`) already uses a `MaterialTheme.colorScheme.surface.copy(alpha =
0.78f)` "glass" backing for precisely this reason (floating over unpredictable map imagery) —
`PlaceQuickSwitchChips` was the one component on the screen that never got it, most likely an
oversight from Plan 5 Task 2's original cut.

**Fix (token/component-level, matching the app's existing glass idiom):**
`HomeScreen.kt`'s `PlaceQuickSwitchChips` now passes `colors =
FilterChipDefaults.filterChipColors(containerColor = MaterialTheme.colorScheme.surface.copy(alpha =
0.78f))` to both `FilterChip` call sites (the "Home" chip and each favorite chip) — the exact same
token/alpha this screen's other glass controls already use, verified against the real resolved
`androidx.compose.material3:material3-android:1.3.2` source (`filterChipColors()`'s
`containerColor` param, extracted from the real `-sources.jar`, not guessed/remembered) so only the
container is overridden — `labelColor`/`selectedContainerColor`/`selectedLabelColor` all fall
through to their existing (already-correct) defaults via `Color.Unspecified`/`takeOrElse`. Applied
unconditionally (no `if (darkTheme)` branch) — `MaterialTheme.colorScheme.surface` already resolves
per-theme, and light theme's dark-Ink-on-map pairing was never broken, only made more visually
consistent with its glass siblings by the same change. Settings' `FavoriteRow` chips are untouched —
confirmed not broken, so not touched.

**New locked regression tests (`core/ui/src/jvmTest/.../ContrastTest.kt`, +2, computed in Python
first per this task's ask, then re-verified as committed Kotlin using the file's own existing WCAG
formula):**
- `white-on-map was the proven failure this fix closes` — pins the real sampled map tile
  (`#9EBDFF`) vs. `Water` text at < 2.0:1, so the pre-fix failure stays documented.
- `dark theme unselected quick-switch chip clears 4_5 to 1 even over a worst-case white map tile` —
  composites `TerraColors.DuskCard` at `alpha = 0.78f` over `Color.White` (the lightest a map tile
  could ever be — deliberately more adverse than this map style's real cream/pale-blue tones) as a
  floor, then asserts `Water` text against that composited background clears 4.5:1. Measured
  **6.30:1** worst-case (**7.90:1** against the real sampled map-ocean color) — comfortably clears
  in both the adversarial bound and reality.

**Device verify (98bc1cd8, dark mode, real "Tokyo" favorite, same map region before/after):**
- Before: `darkmode-favorites-before-home.png` / `darkmode-favorites-before-settings.png`.
- After: `darkmode-favorites-after-home.png` — "Tokyo" now renders as a clear, distinct dark glass
  pill with fully legible text, matching "Home"'s treatment; `darkmode-favorites-after-settings.png`
  — unchanged, still fine.
- Light theme regression spot-check (Settings → Theme → Light, no code gates this — same code path
  runs in both themes): `darkmode-favorites-lightmode-home-spotcheck.png` /
  `darkmode-favorites-lightmode-settings-spotcheck.png` — both chip surfaces still clearly legible,
  no regression. Theme restored to **System** (→ dark, device night mode confirmed still active)
  before finishing, matching the device's original state.

Moto: **PENDING** (disconnected all session, same as Fixes 1/2).

## Tests / compiles

- `./gradlew jvmTest --max-workers=4` (all modules) — **BUILD SUCCESSFUL**, 0 failures anywhere:
  `:composeApp:jvmTest` **245 tests** (unchanged — this fix is a token/color choice with no new
  branching logic, nothing new to unit-test at that layer, same reasoning Fix 2 above documents);
  `:core:ui:jvmTest` **61 tests** including `ContrastTest` now at **12 tests** (+2 for this fix).
- `./gradlew :composeApp:compileDebugKotlinAndroid :composeApp:compileKotlinJvm
  :composeApp:compileKotlinWasmJs --max-workers=4` — all 3 targets green.
- `./gradlew :composeApp:assembleDebug --max-workers=4` — green, APK installed (`adb install -r`,
  preserving the "Tokyo" favorite) and used for the device-verify pass above.

## Concerns

- Fix 2's bottom-ornament half (logo/attribution vs. nav bar) could not be independently
  device-proven on Home — permanently occluded by the feed sheet in every reachable state on that
  screen (see above). Not a regression risk (same shared-field fix as the proven top half), but
  flagging the gap honestly rather than claiming a screenshot that doesn't exist.
- Fix 3's task brief assumed "dark map tiles behind" the Home chips; on-device this was factually
  wrong (see Fix 3 above — the basemap is a fixed light style in every theme) — noting the
  correction explicitly rather than silently building the fix around a premise that didn't hold up
  to reproduction.
- Fix 3's border contrast is a known, accepted residual gap: the unselected chip's default hairline
  border (`Water`@40% alpha, unchanged) only reaches ~2.4:1 against the new glass background in the
  same worst-case-white-map bound (floor is 3:1 for non-text UI) — raising the glass alpha enough
  to clear that floor in that exact adversarial case would cost most of the "glass" transparency
  for a realistically-unreachable edge case (this map style never renders pure white). The label
  text (the actual reported defect, and the primary legibility signal) clears 4.5:1 with real
  margin in the same bound; the container's own now-opaque-enough shape is the primary visual
  delineator, the border a secondary refinement. Flagged rather than silently left unmeasured.
- Moto (Android 16) verification is 100% PENDING for all three fixes — device was disconnected this
  entire session. Whoever next has it connected should repeat the device-verify sequences above
  (grant+revoke via system Settings + recents for Fix 1; scale-bar/status-bar overlap check for Fix
  2; Home + Settings Places dark-mode legibility check for Fix 3).
- `pm revoke`/`pm clear` are both blocked by a `SecurityException` on this OxygenOS build (same
  restriction the task brief already flagged) — every permission-state change in this pass went
  through either a full uninstall/reinstall or the real system Settings UI, never a raw adb
  permission command.
