# Post-Plan-5 tail — RESULTS

Branch `fix/post-p5-tail` off `main` @ 7932c04. Two fixes closing the two "still open" items from
`docs/qa/plan-5-device-matrix/round2/RESULTS.md`'s concern #6. Device verification: OnePlus 9R
`98bc1cd8` (Android 14, OxygenOS). Moto (Android 16) was disconnected for the original session —
every Moto row was **PENDING**, not fabricated. **Moto verification pass completed 2026-08-16 on
`main` @ 807a2c0** — per-item results folded into the rows/sections below; see the "Moto
verification pass" section at the end of this file for build/install/crash-sweep details and the
full artifact list.

## Item → verdict

| # | Item | op9 (98bc1cd8) | Moto (edge 50 fusion, Android 16) |
|---|------|-----------------|------|
| 1 | Settings ALERTS row live-refreshes on resume (grant direction) | **PASS** | **PASS** |
| 2 | Settings ALERTS row live-refreshes on resume (revoke direction) | **PASS** | **PASS** |
| 3 | Map scale-bar/compass no longer collide with the status bar | **PASS** | **PASS** |
| 4 | Map bottom ornaments (logo/attribution) vs. nav bar / ad slot | **PASS** (code-level; not independently visible — see note) | **PASS** (code-level; not independently visible — same feed-sheet occlusion as op9) |
| 5 | Dark mode: favorite places visible/legible (Home quick-switch chips + Settings Places rows) | **PASS** | **PASS** |

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

**Moto verify (`adb-ZA222RWNYX-1MmNmd (2)._adb-tls-connect._tcp`, Android 16, upgrade install over
round2's install; `POST_NOTIFICATIONS` already granted at session start, so the revoke direction was
exercised first):**
1. Settings → Alerts: On (granted from round2) — `moto/fix1-moto-settings-alerts-before.png`.
2. Home → TerraWatch's system per-app notification page (`android.settings.APP_NOTIFICATION_SETTINGS`
   deep link, landed directly on the real page) → toggled **off** via the real switch —
   `moto/fix1-moto-syssettings-notif-revoked.png`; `dumpsys package` confirms
   `POST_NOTIFICATIONS: granted=false`.
3. Returned via **recents** (`KEYCODE_APP_SWITCH` + tap the TerraWatch card — confirmed same task id
   before/after via `dumpsys activity activities`, not a fresh relaunch) — row flipped to **Alerts:
   Off** with the explainer/"Open Settings" affordance reappearing, live, without leaving the
   Settings screen — `moto/fix1-moto-settings-alerts-after-revoke.png`.
4. Reverse direction: system notification settings → toggled back **on** —
   `moto/fix1-moto-syssettings-notif-granted.png`; `dumpsys package` confirms `granted=true` —
   returned via recents (same task id again) — row flipped back to **Alerts: On** —
   `moto/fix1-moto-settings-alerts-after-grant.png`. `dumpsys jobscheduler` cross-check: a real
   `androidx.work.systemjobscheduler` job for `com.yugma.terrawatch` shows `RUNNABLE`, matching op9's
   worker-actually-enqueued proof.

Both directions **PASS** — byte-for-byte the same live-refresh behavior op9 proved.

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

**Moto verify (same upgrade install).** Home screen at launch
(`moto/moto-upgrade-install-launch-home.png`, duplicated as `moto/fix2-moto-map-top-after.png`) vs.
the original complaint framing (`docs/qa/plan-5-device-matrix/round2/moto-item1-adview-home-before.png`)
— cropped the top ~350px of both for a direct comparison: **before**, the scale-bar ruler and labels
("0", "500 km", "1000 km") sat on the exact same row as the status-bar clock/icons, visibly
overlapping; **after**, the status bar (time, wifi/battery icons) renders cleanly on its own row
with a clear gap before the scale bar ("0", "2500 km", "5000 km") begins — no overlap anywhere.
Confirms the fix on a second, independent notch/cutout geometry (Android 16 edge-to-edge insets).
Bottom ornaments: still not independently visible on Moto either — every Home screenshot this pass
shows the feed sheet covering the same region of the map's `Box`, the identical occlusion op9 hit —
consistent with (not a regression from) the shared-`OrnamentOptions.padding` fix.

**PASS.**

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

**Moto verify — and a correction to this task's own brief.** The dispatch brief for this pass asked
to reach dark mode by "enabling dark theme via Moto quick settings/display settings." On device,
TerraWatch's own **Theme setting was already explicitly "Dusk"** (Settings screen, scrolled to the
THEME card) — not "System" — so the app had been rendering in dark theme all session regardless of
the device's own system light/dark setting (`cmd uimode night` read "Night mode: no", i.e.
system-light, the entire time TerraWatch's own UI was already dark). Home's quick-switch chips were
captured in this already-dark state first (`moto/darkmode-moto-home-chips.png`): **both** "Home"
(selected) and "Delhi" (unselected — the real favorite on this device) render as clearly legible dark
glass pills over the map — no invisible-chip regression, matching the fix. To honor the letter of
the brief anyway, the device's own system Dark theme toggle (Settings → Display → Dark theme) was
switched **on** (`cmd uimode night` → "Night mode: yes") and Home re-captured
(`moto/darkmode-moto-home-chips-systemdark.png`) — pixel-identical to the system-light capture,
confirming TerraWatch's explicit "Dusk" selection is correctly independent of the system theme (no
System-tracking regression either). Device theme was restored to its original **light/off** state
afterward (`cmd uimode night` → "Night mode: no", matching the state found at session start).
Settings → Places alert-type chips (All/Major only/Off) legibility cross-checked incidentally via
the Fix 1 screenshots above (e.g. `moto/fix1-moto-settings-alerts-after-grant.png`) — clearly
legible, unchanged, matching op9's "not broken" finding for that surface.

**PASS** (both the fix itself, and the dark/light-independence of the app's own Theme setting).

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
- Moto (Android 16) verification is now **complete** (2026-08-16, `main` @ 807a2c0 — see the
  consolidated "Moto verification pass" section at the end of this file) — all three fixes **PASS**.
  One correction surfaced during that pass: TerraWatch's own Theme setting on this device was already
  explicitly "Dusk," not "System," so the device's system dark/light setting was never actually the
  mechanism keeping Fix 3's chips dark on this device — flagged rather than silently assuming the
  original brief's premise held.
- `pm revoke`/`pm clear` are both blocked by a `SecurityException` on this OxygenOS build (same
  restriction the task brief already flagged) — every permission-state change in this pass went
  through either a full uninstall/reinstall or the real system Settings UI, never a raw adb
  permission command.

## Logo finalization + application (Plan 5 Task 4 phase 2 / Task 6 phase 2)

User pick: **G2's geometry** (`store-assets/brand/round3/direction-g2.svg` — dial + cardinal ticks)
with **G4's color treatment** (`store-assets/brand/round3/direction-g4.svg` — epicenter accent dot).
Full merge reasoning, including one hex correction made against both source files' own stated
value, lives in `store-assets/brand/final/final-rationale.md` — summarized here.

### Merge decision

Disc, four cardinal ticks (Water @ 0.6 opacity), and the sweep needle are G2's exact geometry,
unchanged. The contact dot takes G4's treatment (recolor + resize, r4.5→5.25) — **with one
deliberate correction**: G4's file and `round3-rationale.md` both cite the dot hex as `#B08A2E`,
which was accurate when they were written but is now stale. Re-grepping `core/ui`'s `Tokens.kt`
fresh for this task found a same-day change
(`docs/superpowers/plans/2026-08-16-ui-polish-findings.md`): `WarnInk` was darkened from `#B08A2E`
to `#7A5B19` to fix a real WCAG AA failure elsewhere in the app (`RevisionBadge`'s
`WarnInk`-on-`WarnBg` pair, 2.91:1 → 5.69:1), and this token also feeds `TerraTheme.kt`'s dark
`errorContainer`. `Tokens.kt`'s own header calls these hex values "LAW." The final mark uses the
**current** `#7A5B19`, not the stale `#B08A2E` — G4's own argument for WarnInk was "reuse the app's
real token," and honoring that argument means following the token to where it now points. Honest
consequence: the user picked G4 by looking at a render made with the brighter, now-stale hex;
`#7A5B19` is a visibly more muted amber (confirmed side-by-side in `final-preview.png`'s 48px crops)
— it still reads clearly as the mark's one warm element, but doesn't "pop" quite as much as what
was actually viewed at pick time. Surfaced here rather than silently substituted.

Final mark = 4 hexes (Water/Ink/Safe/WarnInk) — inherits G4's own already-disclosed departure from
the 3-hex budget every other direction held to. Not a new trade-off, just an inherited one.

### Renders

`store-assets/brand/final/final-preview.png` — light canvas (Canvas `#F6FAF9`) / dark canvas
(DuskCanvas `#10161D`) × 512px true raster / 48px true raster (plus a 4x nearest-neighbor zoom
crop of the 48px raster for honest inspection, not a resize-up of the 512px version). Same Pillow
(11.3.0) 8x-supersample-then-LANCZOS method rounds 1–3 used. **48px verdict**: all four ticks stay
individually visible against the Ink disc, matching G2's own "strongest performer at 48px" claim —
confirmed again on the actual merged mark, not just assumed to carry over. The WarnInk dot reads as
a clear warm accent at 48px but is visibly more muted than a brighter amber would be (see hex
correction above) — legible, not broken, but the honest ceiling of the corrected color.

### Adaptive icon (vector pipeline, not PNG)

Matched the existing pipeline exactly: hand-authored VectorDrawable XML (same as Plan 4 Task 7's
shield design), not an SVG→PNG density pipeline. Updated
`ic_launcher_foreground.xml`/`ic_launcher_monochrome.xml` (new disc+ticks+needle+dot geometry,
pathData transliterated directly from the final SVG) and `ic_stat_terrawatch.xml` (notification
glyph). `ic_launcher_background.xml` needed no geometry change — confirmed (not assumed) that its
existing Water rect already matches the new mark's own background exactly; only its header comment
was updated to stop pointing at the superseded shield design. Legacy `mipmap-*dpi/ic_launcher*.png`
rasters (pre-API26 fallback only — this app's minSdk is 26, so adaptive icons are what every real
device resolves) regenerated via the same Pillow rasterizer, at each density's exact target size.

**Notification glyph — a bigger deviation than "simplify," reasoned through and rendered, not
assumed:** a literal transcription (filled disc + solid-white needle + solid-white dot — all one
color, since Android notification icons discard fillColor/strokeColor entirely) was rendered once
to check. Result: needle and dot are completely indistinguishable from the disc — confirmed on the
actual render, not just predicted. This is categorical (no color channel survives at any
resolution), not a "make it bigger" problem. Fix: the glyph instead reuses this app's own
already-shipped ring ("the radius ring," unchanged geometry) as the "dial," with the needle and dot
as solid shapes inside the hollow interior for contrast. Ticks dropped entirely (not just
"optional" — there's no rim for them to sit against on a hollow ring, and 24dp is well under their
established 48px legibility floor anyway). Both candidates rendered and compared before deciding
(see `final-rationale.md`'s "Notification-icon derivative" section for exact geometry).

Safe-zone check (66dp/r33 circle): disc max extent 27, ticks 26, needle ≈25.75, dot ≈23.26 — all
clear with margin, computed not assumed (table in `final-rationale.md`).

### Store assets

`store-assets/icon-1024.png` regenerated (1024×1024, mark on Water, saved as RGB — zero
transparency, no rounded corners baked, matching Play's spec and the file's own pre-existing
convention). `store-assets/feature-graphic.png` regenerated: reverse-engineered the existing
layout by connected-component color analysis (not eyeballed) to recover the exact mark
center/scale, wordmark/tagline bounding boxes, and the 3 decorative magnitude-dot positions/colors
— then rebuilt the same canvas with only the mark swapped. Wordmark/tagline/dots are pixel-for-
pixel the same content, same Arial/Arial Bold convention `screenshots-framed/README.md` already
documents for this asset pipeline.

### Social art (`store-assets/social/art/`, 9 files)

Per `store-assets/social/art-specs.md`: `avatar-youtube-800.png`, `avatar-instagram-320.png`,
`avatar-x-400.png`, `avatar-threads-320.png` (byte-identical to the IG avatar, per spec's "Threads
reuse IG"), `youtube-channel-art-2560x1440.png` (safe area 1546×423 centered per the spec's own
math, mark+wordmark placed inside it), `x-header-1500x500.png` (safe band y:[60,440], group
centered), and 3 IG posts (`ig-post-1-home-map-1080.png`, `-2-notification-1080.png`,
`-3-radius-ring-1080.png`) — the spec's own "accepted v1 shortcut": existing
`screenshots-framed/*.png` pillarboxed onto a 1080×1080 Water canvas, scale 0.5625, 236px bars each
side (matches the spec's own worked arithmetic exactly). Avatars needed no extra safe-margin
adjustment: the mark's largest element (disc) sits at 50% of the canvas half-width, comfortably
inside any reasonable circular crop.

### Device verify (98bc1cd8, OnePlus 9R, Android 14/OxygenOS)

`./gradlew :composeApp:assembleDebug --max-workers=4` → green → `adb install -r` → Success.

- **Launcher grid**: real icon visible in the app drawer alongside neighbors (Substack, Theme
  Store) — `docs/qa/post-p5-tail/logo-01-launcher-grid.png`. OS's own rounded-square mask applied
  correctly; disc/ticks/needle/dot all render undistorted (zoomed crop confirmed all 4 ticks + the
  needle + the dot individually, matching the design). Long-press context menu
  (`logo-02-longpress-menu.png`) is App info/Share/Edit/Uninstall only — no per-icon option, as
  expected.
- **Themed icon**: checked 3 real locations, not just one glance — Wallpapers & style → Icons
  (`logo-04-oxygenos-icons-page.png`: this is OxygenOS's own icon-shape/pack switcher + "ART+
  Icons" curated third-party redesigns, a different mechanism from AOSP's monochrome themed-icon
  API, and not something a small indie app would ever be curated into regardless); Wallpapers &
  style → Colours (`logo-05-oxygenos-colours-page.png`: system accent-color picker, no icon-theming
  toggle); Home Screen & Lock Screen settings (`logo-06-home-lock-screen-settings.png`: layout/
  gesture options only). **Verdict: this OxygenOS build does not expose the standard Android 13+
  themed-icon toggle in any checked location** — noted honestly rather than assumed unsupported
  after one glance. `ic_launcher_monochrome.xml` is implemented correctly per platform spec
  regardless, and will activate on launchers/OS builds that do support it (stock/Pixel Android).
- **Notification glyph**: `adb shell dumpsys notification --noredact` → 0 active `NotificationRecord`
  entries for `com.yugma.terrawatch` — no live notification existed or could be honestly triggered
  (no favorite/alert-rule state was set up on this fresh install, and forcing one would not be the
  "real M6+ quake happens to be pending" check the task asked for). Per the task's own honest
  fallback: verified via direct drawable-render inspection instead —
  `docs/qa/post-p5-tail/logo-07-notification-glyph-render-inspection.png` (status-bar mock, shade-
  card mock, true-24dp and 4x-zoom crops, all rendered from the exact shipped VectorDrawable
  geometry). Reads clearly as a dial-with-needle-and-dot glyph at true size in both mocks.

### Device verify (Moto edge 50 fusion, `adb-ZA222RWNYX-1MmNmd (2)._adb-tls-connect._tcp`, Android 16)

**2026-08-16, `main` @ 807a2c0, upgrade install.** First real themed-icon test this repo has run on
a device that might plausibly support the AOSP/Pixel-style monochrome API — round2/plan-5's only
other device is OxygenOS, which doesn't; this is a second, independent OEM skin on a newer Android
version.

- **Launcher grid**: real icon visible in the full alphabetical app-drawer grid alongside real
  neighbors (SuperCam, Threads, Truecaller, Uber) — `moto/logo-moto-01-launcher-grid.png`. Also
  confirmed via the drawer's own search (`moto/logo-moto-01-launcher-search.png`, superseded by the
  grid capture but left un-deleted per this file's own evidence-integrity convention). Moto's
  teardrop adaptive-icon mask applies correctly; disc/ticks/needle/dot all render undistorted.
  Long-press context menu (`moto/logo-moto-02-longpress-menu.png`) is Pause app/App info/Install in
  Secure folder only — no per-icon option, same "nothing app-specific broken" result as op9
  (different option set, an OS/launcher convention difference, not a defect).
- **Themed icon** — checked 3 real locations, same discipline as op9: system Settings' own search
  indexed **nothing** for "themed icons" (empty result set); Settings → Display's Appearance card
  (Dark theme + Colours: Natural) has no themed-icon entry; Moto's own launcher personalization hub
  (long-press Home → Personalise → Icon shape) offers only shape-mask presets (rounded square/
  circle/teardrop variants), no monochrome/themed toggle anywhere in that flow either. **Verdict:
  this Motorola Android 16 build (its own launcher, not Pixel Launcher) also does not expose the
  standard Android 13+ themed-icon toggle in any checked location** — the same non-support
  conclusion as OxygenOS, now confirmed on a second OEM skin and a newer Android version. Still a
  launcher-level gap, not an app-level one; `ic_launcher_monochrome.xml` remains correctly
  implemented per platform spec for whichever launcher/OS build does support it. No launcher
  theme/icon-shape state was left changed on the device — an accidental "Save theme?" prompt
  (triggered while navigating this flow) was **discarded**, not saved, and the Icon shape page was
  closed via its X, not its Save button.
- **Notification glyph — SKIP, same structural reason already documented above, now reproduced on a
  second device.** `dumpsys notification --noredact` → 0 active `NotificationRecord` entries at
  session start. Tried this task's own suggested fallback: long-pressed the map to inject a debug
  M6.0 quake (`origin=ORIGIN_DEBUG`), then long-pressed Settings' "Alerts" row to fire
  `AlertDigestScheduler.triggerNow()` — logcat confirms `AlertDigestWorker` ran and returned
  `SUCCESS`, but `dumpsys notification --noredact` still shows 0 `NotificationRecord` entries
  afterward. This reproduces `docs/qa/plan-5-device-matrix/RESULTS.md`'s already-documented root
  cause exactly (`Quake.sq`'s `newSince` query structurally excludes `origin='debug'` rows from ever
  notifying, by design — "the F5-guard parity") — on a second device, this is a re-confirmation, not
  a new finding. No real M6+ world quake happened to be pending in the digest at trigger time
  either. Honest skip, per this task's own allowance — the glyph's own rendering stays verified via
  `logo-07-notification-glyph-render-inspection.png` (op9 pass, drawable-render inspection,
  unaffected by which device runs it).

### Tests / compiles

- `./gradlew jvmTest --max-workers=4` — **BUILD SUCCESSFUL**, all tasks UP-TO-DATE (this task
  touched only Android drawable/mipmap/PNG resources, no Kotlin source — nothing for jvmTest to
  newly exercise, same reasoning Fix 2/3 above document for resource-only changes).
- `./gradlew :composeApp:assembleDebug --max-workers=4` — green (see device verify above).

### Concerns

- The WarnInk hex correction (above) means the shipped dot color is objectively more muted than
  what the user looked at when picking G4 — technically the right call (follows the app's own
  current "LAW" token, avoids reintroducing a value the app moved away from for a real
  accessibility reason elsewhere), but it's a color the user hasn't actually seen yet. Worth a
  quick glance before this ships further.
- Themed-icon support could not be positively demonstrated on this specific device/OS build (see
  above) — the monochrome layer is implemented correctly per spec, but real-device confirmation of
  the re-tint behavior itself remains outstanding for whichever device/launcher does support it.
- Notification-glyph verification is drawable-render inspection only, not a live on-device shade
  screenshot — no TerraWatch notification existed to capture honestly at verification time.
- Moto (Android 16) device-verify pass is now complete (2026-08-16, `main` @ 807a2c0) — launcher
  grid **PASS**, themed-icon toggle confirmed **not supported** on this launcher/OS build either (a
  second, independent confirmation, see above), notification glyph live-capture still an honest
  **SKIP** (structural debug-quake exclusion, not a defect — reproduced identically to the
  already-documented root cause).

## Moto verification pass — main @ 807a2c0 (2026-08-16)

Clears the accumulated Moto-PENDING backlog above. Device: Motorola edge 50 fusion,
`adb-ZA222RWNYX-1MmNmd (2)._adb-tls-connect._tcp`, Android 16/API 36, WiFi adb. Personal
daily-driver device — same focus-stealing-app quirks documented in
`docs/qa/plan-5-device-matrix/round2/RESULTS.md` (Shaadi, SuperCam Plus; the Moto launcher's own
main/Home activity is literally named `CustomizationPanelLauncher` — a class-name quirk, not an
actual stuck customization panel, confirmed by screenshot before assuming otherwise); `am
force-stop`'d before interactions, same as round2. 3-button nav confirmed
(`settings get secure navigation_mode` → `0`, not `2`) — predictive back stays N/A, unchanged from
round2.

**Build + install:** `./gradlew :composeApp:assembleDebug --max-workers=4` → `BUILD SUCCESSFUL` (all
tasks UP-TO-DATE against a clean `git status` at `807a2c0` — no stale-build risk). `adb install -r`
onto the existing round2 install (Moto's **first upgrade-path install** — round2 was a fresh install;
op9 has had upgrade installs already) — Success, both favorites (Home + Delhi) survived the upgrade.
Launch + initial crash sweep: `moto/moto-item0-launch-crash-sweep.txt` (empty, 0 hits) —
`moto/moto-upgrade-install-launch-home.png`.

### Per-item results

| Item | Verdict | Artifact(s) |
|---|---|---|
| a. Fix 1 — Alerts row live-refresh, grant direction | **PASS** | `fix1-moto-settings-alerts-before.png`, `fix1-moto-syssettings-notif-granted.png`, `fix1-moto-settings-alerts-after-grant.png` |
| a. Fix 1 — Alerts row live-refresh, revoke direction | **PASS** | `fix1-moto-syssettings-notif-revoked.png`, `fix1-moto-settings-alerts-after-revoke.png` |
| b. Fix 2 — scale-bar inset at original complaint site | **PASS** | `fix2-moto-map-top-after.png` vs. `round2/moto-item1-adview-home-before.png` |
| c. Dark-mode favorite chips | **PASS** | `darkmode-moto-home-chips.png`, `darkmode-moto-home-chips-systemdark.png` |
| d. Logo — launcher grid | **PASS** | `logo-moto-01-launcher-grid.png`, `logo-moto-02-longpress-menu.png` |
| d. Logo — themed icons (AOSP monochrome toggle) | **NOT SUPPORTED** (verified across 3 locations, not assumed) | no screenshot artifact — nothing existed to capture; see Logo Moto device-verify above for the 3 checked locations |
| e. Notification glyph live check | **SKIP** (honest — structural, matches already-documented root cause) | logcat only (`AlertDigestWorker` → `SUCCESS`, 0 `NotificationRecord`s before and after) |
| f. M4.0 magnitude floor re-confirm | **PASS** | `moto-item4-m4floor-slider-min.png` |
| f. News-absent re-confirm (detail sheet + Insights) | **PASS** | `moto-item3-detailsheet-nonews.png`, `moto-item3-insights-nonews.png` |
| Bottom map ornaments vs. nav bar (item 4 in the top table) | **PASS** (code-level; still not independently visible, same occlusion as op9) | — |
| Final full-session crash sweep | **PASS** (0 crashes) | `moto-item9-final-logcat-androidruntime.txt`, `moto-item9-broad-crash-sweep.txt` (both empty) |

All artifacts above live under `docs/qa/post-p5-tail/moto/`.

**Themed-icon verdict, spelled out (first real test of this specific question in this repo):** every
prior pass only had an OxygenOS device on hand, which doesn't support the AOSP Android-13+ per-app
monochrome "Themed icons" toggle. This pass had a second, different OEM skin (Motorola's own
launcher) on a newer Android version (16 vs. 14) available, and checked three independent real
locations (system Settings search, Settings → Display → Appearance, and Moto's own launcher
Personalise → Icon shape flow) — **none exposes it either.** Now a two-for-two "not supported by the
OEM launchers on hand" result, reinforcing that the gap is launcher-specific, not something wrong
with this app's own `ic_launcher_monochrome.xml` (which remains correctly implemented per the
platform spec and will activate on a launcher that does support it — e.g. stock/Pixel Android, still
untested for lack of that hardware).

**Device state left as found:** `POST_NOTIFICATIONS: granted=true` (ended on the grant direction,
matching the state found at session start), system dark theme restored to **off**
(`cmd uimode night` → "Night mode: no", matching session start), 3-button nav untouched, no launcher
theme/icon-shape change saved (an accidental "Save theme?" prompt was discarded, not saved).

**Artifacts:** 18 files added under `docs/qa/post-p5-tail/moto/` this pass (15 PNGs + 3 crash-sweep
`.txt` files, all verified non-zero where non-empty and read back to confirm real content before
being cited above, per this file's own evidence-integrity convention; the 3 `.txt` files are
legitimately empty — that's the clean-crash-sweep result, not a capture failure).

**Commit:** flips every Moto-PENDING row in this file to a verdict; no source changes this pass
(verification only).
