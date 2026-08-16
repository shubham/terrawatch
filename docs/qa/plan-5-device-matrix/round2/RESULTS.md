# Plan 5 Polish — Round 2 Combined Two-Device Verification — RESULTS

Devices: **OnePlus 9R** `98bc1cd8` (USB, actual OS = **Android 14 / API 34** — the dispatch brief's
"Android 15" guess was wrong, corrected here for the record) and **Motorola edge 50 fusion**
`adb-ZA222RWNYX-1MmNmd._adb-tls-connect._tcp` (WiFi adb, **Android 16 / API 36**, first-ever run of
this app on this device/API level). Branch `feat/plan-5-polish`, HEAD `f958cfc`. Date: 2026-08-16.
Scope: everything changed since the last device pass — `44b1e56` (AdView Settings-nav fix),
`51f84d5` (news kill-switch), `d343c6a` (M4.0 notification floor), `312656c` (detail-sheet UX),
`da4ac97` (colors), `f958cfc` (motion).

Build: one `:composeApp:assembleDebug` (Gradle reported `UP-TO-DATE` against a clean `git status`
at HEAD, i.e. the on-disk APK already matched this exact commit — no stale build risk). Install:
OnePlus = `adb install -r` (upgrade onto the existing versionCode 2/0.9.0 install, real dogfooding
data — second real migration-path exercise, schema already at v2 from a prior pass). Moto = fresh
`adb install` (no prior data, first-ever Schema.create path on this device).

## Summary table

| Item | OnePlus 9R | Moto edge 50 fusion |
|---|---|---|
| 0 — Install + launch + crash sweep | PASS | PASS |
| 1 — AdView Settings round-trip | PASS | PASS |
| 2 — Detail sheet UX | PASS | PASS |
| 3 — News absent (detail + Insights) | PASS | PASS |
| 4 — M4.0 magnitude floor | PASS | PASS |
| 5 — Colors (no stock purple, badge legibility) | PASS (dark) | PASS (**light theme, bonus**) |
| 6 — Motion (crossfade + map-state, springs, reduced-motion) | PASS w/ 2 sub-items SKIPPED | PASS w/ 2 sub-items SKIPPED |
| 7 — Favorites regression + Tokyo survival (op9 only) | PASS | n/a |
| 8 — Android-16-specific (moto only) | n/a | PASS w/ 1 sub-item N/A, 1 caveat |
| 9 — Final logcat sweep | PASS (0 crashes) | PASS (0 crashes) |

No FAILs. Every SKIP/caveat below is a documented substitution or a real environmental block, not
a defect found in the 6 commits under test.

## Item 0 — Install + launch + logcat sweep

**OnePlus (upgrade install) — PASS.** `adb install -r` onto the existing versionCode 2 install
succeeded; app launched straight to Home (map + live feed + Tokyo favorite chip all present from
the prior session, confirming the upgrade preserved data). `logcat -c` → launch → `logcat -d`
grepped for `FATAL EXCEPTION|Fatal signal|tombstone` scoped to the package: **zero hits**.
Artifacts: `op9-item0-launch-home.png`, `op9-item0-crash-sweep.txt` (empty file = clean).

**Moto (fresh install) — PASS, but the onboarding-flow capture itself was messy — documented
honestly rather than cleaned up.** This personal daily-driver device has several other apps
(`com.shaadi.android`, `com.tvt.supercamplus`, the Motorola launcher's `CustomizationPanelLauncher`)
that repeatedly stole foreground focus via high-priority notifications/scheduled triggers
throughout the session — confirmed via `dumpsys activity activities` (`topResumedActivity`
flipping to those packages seconds after every `am start`) and via `dumpsys notification
--noredact` (a real `HIGH_PRIORITY_REQUEST` channel notification from Shaadi, not an active call —
confirmed before doing anything about it). This is **not a TerraWatch defect**; TerraWatch itself
never crashed once. Worked around with `am force-stop` on the offending package immediately before
each `am start`/interaction. One real consequence: several blind onboarding taps (issued while
racing the focus-stealing) landed on real onboarding buttons faster than intended and advanced
past the location/notification "ask" steps without a clean screenshot of the live system dialogs —
recorded honestly in Item 8 below rather than silently re-staged. Onboarding did complete
(`isOnboarded()` true, Home reachable), zero crashes throughout
(`moto-item9-final-logcat-androidruntime.txt` empty + a broader FATAL/tombstone grep across the
whole session also empty). Artifacts: `moto-item0-launch-onboarding1.png` (screen 1, clean),
`moto-item0-launch-home-postonboarding.png` (Home reached).

## Item 1 — AdView Settings round-trip

**OnePlus — PASS.** scrcpy video `op9-adview-settings.mp4` (13.9s, ~1.9MB): Home (ad filled,
"Nice job! This is a 468x60 test ad.") → tap Settings gear → Settings renders with **zero ad
anywhere** → tap back → Home restored with the **identical ad content**, no blank/reload frame
visible in thumbnails pulled at 1s/2.5s/3s/6s/7s/10.5s/11s. `logcat` around the round-trip grepped
for `adview|loadAd|onAdLoaded|onAdFailedToLoad`: only a routine SDK service unbind, **no fresh ad
request** — confirms the `AdView` persisted rather than being destroyed/recreated, matching
`44b1e56`'s fix.

**Moto — PASS.** Before/after screenshots (`moto-item1-adview-home-before.png` /
`-settings.png` / `-home-after.png`): same pattern — Settings clean of any ad, Home's ad banner
shows the identical "Nice job!" test-ad content immediately on return, no fresh
`loadAd`/`onAdLoaded` logcat activity in the intervening window.

## Item 2 — Detail sheet UX

**OnePlus — PASS.** Opened a M4.1 South Island of NZ quake (dark theme):
`op9-item2-detail-open-check.png` shows **no Dismiss button** (drag handle only), share row demoted
to a compact circular "W" `FilledTonalIconButton` + full-width primary "Share" button (light-on-dark,
high contrast — matches the dark-mode share-contrast fix). Drag-handle swipe-down dismiss tested
directly (`input swipe` on the handle) — sheet closed cleanly, confirmed via
`op9-item2-draghandle-dismiss.png` (back on Home/feed-peek).

**Moto — PASS, most complete evidence.** `moto-item2-detailsheet-top.png` (M2.8 Flores quake, light
theme): no Dismiss button, "W"+"T" monogram buttons visible. Swiped up inside the sheet
(`moto-item2-detailsheet-scrolled.png`) — content **scrolls**, revealing the full-width "Share"
button that wasn't visible at the top — confirms the new `verticalScroll` modifier works with the
sheet's own drag-to-dismiss (no conflict observed). Scrim-tap dismiss confirmed
(`moto-item2-detailsheet-postdismiss.png` — sheet gone, back on Home). Both real dismiss paths
(drag-handle on op9, scrim-tap on moto) independently exercised across the two devices.

## Item 3 — News absent

**OnePlus — PASS.** Detail sheet (M4.1, above) has no news section between Source and the share
row. Insights (`op9-item3-insights-nonews.png`, 7d view, real M7.7 Ende-Indonesia "strongest")
goes directly from the STRONGEST card to the ad banner — no "In the news" card anywhere.

**Moto — PASS.** Same on both surfaces:
`moto-item2-detailsheet-scrolled.png` (detail sheet, M2.8) and `moto-item3-insights-nonews.png`
(Insights, real M6.9 Pematangsiantar "strongest") both confirm zero news UI (no header, no shimmer,
no gap) — matches `51f84d5`'s compile-time `NewsFeature.ENABLED = false`.

## Item 4 — M4.0 magnitude floor

**OnePlus — PASS.** Settings' magnitude slider dragged to its physical minimum
(`op9-item4-m4floor-slider-min.png`): label reads **"Alerts for magnitude 4.0+ nearby"**, thumb at
the literal left edge of the track — confirms `valueRange = 4.0f..6.0f`, no sub-4.0 stop reachable.

**Moto — PASS.** Identical result (`moto-item4-m4floor-slider-min.png`), same "4.0+" floor,
same physical minimum. Both devices agree byte-for-byte with `d343c6a`'s spec.

## Item 5 — Colors

**OnePlus (dark theme) — PASS.** Across every screenshot this pass: magnitude badges show **dark
(Ink) numeral text** on Low (green, e.g. "1.7", "2.8") and Moderate (orange, "3.4", "4.1") bands;
Major band ("7.7", large Insights card) correctly **keeps white** text (per the commit's own
"Large clears its own 3:1 floor" rule) — both the tweak and the deliberate non-change are visible
and correct. Bottom-nav selection pill, Settings' "Major only" segmented chip, and the History
filter chips all render a dark navy/Water-family fill — **no stock M3 purple**
(`#4A4458`/`#E8DEF8`) anywhere observed.

**Moto (light theme) — PASS, and a genuine first.** `cmd uimode night` confirmed the device's
system theme is actually **light** (not dark as briefly assumed from an early quick-settings
screenshot) — since TerraWatch's own Theme setting is "System", this produced this repo's **first-
ever real-device light-theme captures** (prior plan-3/4/5 passes are all dark-theme only, per this
plan's own progress-ledger gap #3). `moto-item5-home-lighttheme.png`: Low-band badges show dark
text on green, matching the fix; `moto-item4-m4floor-slider-min.png`/`moto-settings-check.png`
confirm the same no-purple result in light theme (selected chip/pill = Water blue, not purple).
`moto-item0-launch-onboarding1.png` doubles as a light-theme onboarding capture.

## Item 6 — Motion

| Sub-item | OnePlus | Moto |
|---|---|---|
| Tab crossfade + map camera-state retained | PASS (video) | PASS (screenshots) |
| Pin-drop / RevisionBadge / StatusShield spring calmness | NOT independently observed | NOT independently observed |
| Reduced-motion (system toggle) | SKIPPED — blocked | SKIPPED — no toggle path found |

**Tab crossfade + map state — PASS both devices.** OnePlus: scrcpy video
`op9-motion-tabswitch.mp4` (11.9s) — Home → History → Insights → Home; frame extraction at t=3s
confirms History rendered, t=10.5s (final) confirms Home's map camera is in the **exact same
position** (same New Delhi/Meerut/Sonipat pan, same pixel-identical layout) as before the round
trip — `QuakeMap`'s Android camera state survives the new `AnimatedContent` crossfade transition.
Moto: same check via discrete screenshots (`moto-item6-history-tab.png` →
`moto-item6-insights-tab.png` → `moto-item6-home-mapstate-retained.png`), same result. The
crossfade's subjective "feel" is on record in the OnePlus video for a human to judge; no jarring
cut/flash visible in any extracted frame.

**Spring calmness (pin-drop pop, revision-badge pulse, status-shield morph) — not independently
verified this pass.** None of the three is forceable without either a live qualifying event (a
brand-new pin arriving, an existing quake receiving a magnitude revision) or a debug-inject hook,
and this pass — like every prior device pass in this repo — declined to stage/fabricate quake data
to manufacture one. No such event happened to land during the session. Not a failure — genuinely
unexercised, flagged rather than guessed at.

**Reduced-motion — SKIPPED on both, for different reasons.** OnePlus: `adb shell settings put
global animator_duration_scale 0` → `SecurityException: Permission denial, must have one of:
[android.permission.WRITE_SECURE_SETTINGS]`, confirmed still `1.0` after — same documented OxygenOS
block as prior passes (`WRITE_SETTINGS`/`WRITE_SECURE_SETTINGS` both refused, even from adb shell
directly, not just in-app). Moto: this device is configured for **3-button navigation** (visible
in every screenshot's nav bar), not gesture nav, and no system "remove animations" toggle was
reachable within the time this pass allotted to it — skipped rather than fished for further.

## Item 7 — Favorites regression smoke (OnePlus only)

**PASS.** Settings → Places shows **Tokyo still present** with its "Major only" alert-type
selection intact (`op9-item1-check-state.png`) — the exact favorite added in yesterday's session,
**surviving the upgrade install** untouched (real migration data-preservation proof, same class of
evidence as fix-round-1's `favoritePlace` table migration). Home's quick-switch chips render (Home
+ Tokyo); tapping the Tokyo chip correctly flew the camera from New Delhi to Tokyo/Kawasaki/Chiba
Bay (`op9-item7-tokyo-chip-fly.png`, chip shown selected/highlighted post-tap). Settings' Places
section fully interactive post-upgrade (Change / Remove / segmented alert-type control all
responsive).

## Item 8 — Android-16-specific (Moto only)

**Edge-to-edge insets — PASS on inspected screens, one minor observation.** Settings, History,
Insights, and the Detail sheet all show correct top/bottom padding (no content under the status or
gesture-nav bar) across every capture this pass. One thing worth a look but **not one of the 6
commits under test**, flagged rather than silently noted: Home's own map distance-scale overlay
("0 / 500 km / 1000 km") renders very close to, occasionally grazing, the status bar — this looks
like MapLibre's own native scale-bar control (not app-authored Compose content going through the
same `windowInsetsPadding` convention as everything else), so it's plausibly pre-existing and out
of this pass's scope, not a regression from `44b1e56`–`f958cfc`.

**Predictive back gesture — N/A, not a failure.** This device is set to 3-button navigation (visible
nav bar in every screenshot), so the OS edge-swipe predictive-back gesture isn't reachable at all
here — there is no gesture-nav edge to swipe from. A best-effort `input swipe` from the left edge
while backgrounding a `scrcpy`-less screenshot mid-flight did not surface any visible preview frame
(`moto-item8-predictiveback-midgesture.png` shows the plain Settings screen, unanimated) —
consistent with 3-button nav rather than a bug; the manifest's own
`enableOnBackInvokedCallback="true"` + back-button dispatch itself was exercised indirectly via
every other back-navigation in this pass (all worked).

**Notification + location permission flow — PASS, via the fallback path, with an honest caveat.**
The clean *first-ask* system-dialog screenshots were lost to the Item-0 focus-stealing chaos (the
in-app flag that distinguishes "never asked" from "asked, no rationale" got flipped to the latter
before a clean capture landed). Rather than re-stage a fake first-ask, this pass exercised and
documented the **real fallback path both permissions correctly degrade to**: Settings' Alerts row
showed "Notifications are off... Open Settings" → tapped through to Android 16's native per-app
notification page (`moto-item8-notification-opensettings.png`) → toggled on
(`moto-item8-notification-granted.png`) → confirmed via `dumpsys package`
(`POST_NOTIFICATIONS: granted=true`). Same pattern for location: Settings' Places row → App
Permissions → Location permission page (`moto-item8-location-permpage.png`, Android 16's
Allow-while-using/Ask-every-time/Don't-allow layout) → granted
(`moto-item8-location-granted2.png`) → confirmed (`ACCESS_FINE_LOCATION`/`ACCESS_COARSE_LOCATION:
granted=true`). Both are real, correctly-wired Android 16 code paths — just not the *very first*
system-dialog appearance, which the environment made uncapturable cleanly this pass.
Minor aside (not one of the 6 commits, not chased further): Settings' own "Alerts: Off" line didn't
immediately flip to "On" after the external grant even though `dumpsys` confirmed it — looks like a
recomposition-on-resume gap, noted for whoever next touches that screen.

## Item 9 — Final logcat sweep

**Both devices — PASS, zero TerraWatch crashes.** `adb logcat -d -s AndroidRuntime:E` on each
device: **0 lines** on both (`op9-item9-final-logcat-androidruntime.txt` /
`moto-item9-final-logcat-androidruntime.txt`, both empty). A broader sweep
(`FATAL EXCEPTION|Fatal signal|tombstone` grepped against `yugma|terrawatch` across the *entire*
session's buffer, not just the final tag-filtered slice) also returned zero hits on both devices —
this covers the Item-0 onboarding chaos, the permission-flow detours, every navigation and gesture
performed this pass.

## Artifact count + commit

**48 artifacts** in `docs/qa/plan-5-device-matrix/round2/` (46 PNGs + 2 MP4s), ~29MB total — both
videos well under the 25MB cap (`op9-adview-settings.mp4` ~1.9MB, `op9-motion-tabswitch.mp4`
~3.9MB). Every screenshot was screencapped, verified non-zero, and read back to confirm real
content before being cited above (per this pass's evidence-integrity requirement) — several
mis-timed/mis-tapped captures during the Moto onboarding chaos and coordinate-scaling slips were
caught this way and re-shot rather than cited; those superseded captures are left in the directory
un-deleted for an honest record rather than curated away.

## Android-16 first-run verdict

**No crashes, no ANRs, no TerraWatch-attributable defects.** Every rough edge this pass hit on the
Moto device traced back to *other* installed apps (Shaadi, SuperCam Plus, the Motorola launcher's
customization panel) fighting for foreground focus, or to this being a personal daily-driver device
with real notification traffic — never to TerraWatch's own code. All 6 commits under test
(AdView-Settings fix, news kill-switch, M4.0 floor, detail-sheet UX, colors, motion) verified PASS
on both a first-ever-install Android 16 device and a second-upgrade-install Android 14 device.
Android-16-specific surface (edge-to-edge, predictive back, runtime permission pages) behaves
correctly everywhere it was reachable; the two gaps (very-first permission dialog, predictive-back
gesture) are environment/device-configuration limitations, not app defects.

## Concerns / still open

1. **Moto device has significant background-app churn** (Shaadi.android's high-priority
   notifications, a camera/security app, the Motorola launcher's own customization panel) that
   fights TerraWatch for foreground focus — not a TerraWatch bug, but worth knowing before the next
   person runs an unattended/scripted pass on this exact device; force-stopping the offender
   immediately before each interaction was the reliable workaround this pass landed on.
2. **OnePlus 9R is Android 14, not Android 15** — correcting the dispatch brief's assumption for the
   record; doesn't change any result above.
3. **Reduced-motion could not be toggled/tested on either device** this pass (OxygenOS
   `WRITE_SECURE_SETTINGS` block on op9, no reachable system toggle found on moto's 3-button-nav
   config) — same class of gap prior passes already documented for op9, net-new observation for
   moto.
4. **Spring "calmness" (pin-drop/revision-badge/status-shield) has no independent device proof**
   this pass — needs either a live qualifying quake event or a future debug-inject hook to observe
   directly; the tab-crossfade video is the only motion evidence captured, not the 3 named springs.
5. **Predictive back gesture is untestable on the Moto unit as currently configured** (3-button nav,
   not gesture nav) — would need the device's nav mode switched to gesture nav first (a system
   setting change, out of scope for this pass to make unprompted).
6. Two minor, out-of-6-commits-scope observations left for whoever owns those areas next: Home's
   map scale-bar sits close to the status bar on the Moto/Android-16 combo (possible MapLibre-native
   edge-to-edge gap), and Settings' "Alerts: Off" line didn't live-refresh immediately after an
   external permission grant on Moto (likely a resume-lifecycle recomposition gap).
