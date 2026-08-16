# Plan 5 Batch Device Verification — RESULTS

Device: 98bc1cd8 (OnePlus 9R, OxygenOS, Android 14). Branch `feat/plan-5-polish`, HEAD `a7151b3`.
Date: 2026-08-16. Real device, real dogfooding data (device's actual location: New Delhi, NCR —
not Mumbai as originally guessed; home stored as 28.56°N, 77.44°E, confirmed by both Settings and
by the my-location FAB flying to real New Delhi/NCR place names).

## Item 0 — Upgrade-install migration test (BLOCKER-1 real-world proof)

**PASS.** Device had versionCode 2 / 0.9.0 (schema v1, real dogfooding quake data, no
`favoritePlace` table). Built current branch (`:composeApp:assembleDebug`), installed with
`adb install -r` (no uninstall, no data loss). Full `logcat -c` → install → launch → `logcat -d`
sweep: **zero SQLiteException, zero FATAL EXCEPTION, zero AndroidRuntime errors.** The `1.sqm`
migration (added in fix-round-1, bumping `TerraWatchDb.Schema.version` 1→2) ran automatically on
first launch, creating `favoritePlace` without touching existing `quake`/`meta` rows. Home renders
the live feed immediately; Settings → Places renders fully interactive (Change / Use my location /
Add place), proving the table is genuinely queryable, not just present.

This is the **first real-device confirmation** of the fix-round-1 BLOCKER fix — it was previously
verified only via JVM tests + bytecode inspection (device wasn't connected during that session).

Artifacts: `task0-upgrade-install-home.png`, `task0-upgrade-install-settings-places.png`.
(First screenshot attempt was discarded — it accidentally captured the OS quick-settings shade,
not the app; caught by the evidence-integrity re-check before citing it, foreground activity
verified via `dumpsys activity activities` before recapturing.)

## Task 1 — Map location UX

| Item | Result | Artifact |
|---|---|---|
| Cold-open centering (no forced re-center when fix ≈ home) | PASS | `task1-cold-open-centered.png` |
| Cold-open centering (forced re-center when fix ≫ home, >50km) | PASS | `task1-cold-open-mismatch-recenter-test.png` |
| Pan away + FAB recenter | PASS | `task1-fab-before.png` / `task1-fab-after.png` |
| Rotation (no re-center in landscape) | SKIPPED | — |
| Permission-revoked → FAB hidden | SKIPPED (substituted) | — |

**Cold-open centering — both halves proven, not just observed.** Read `CameraTarget.kt`'s
`startupCameraTarget(savedTarget, fix, permissionGranted)`: returns `null` (leave camera alone)
unless `fix` differs from the stored home by >50km. On first launch the wide Europe/Africa view
looked like a bug at a glance — it wasn't: the device's real fix (~28.56°N,77.45°E, via
`LocationManager.getLastKnownLocation`, COARSE only — FINE is denied on this device but the app
only ever checks COARSE) is <2km from stored home, so the code correctly left the previously-saved
camera pan alone (spec: "avoid fighting deliberate pans"). To prove the *other* half of the fix
actually fires, I deliberately set home to Jakarta via Settings → Change (a ~4,400km mismatch),
force-stopped, relaunched: camera correctly snapped to the real fix near New Delhi (Meerut,
Chandigarh, Saharanpur, Agra visible), not Jakarta. Restored home back to 28.56°N,77.44°E via "Use
my location" afterward (confirmed exact match).

**FAB recenter** — panned to Greenland, tapped "Go to my location": camera flew to a tight,
correctly-zoomed New Delhi view (Sonipat/Meerut/Gurgaon/Faridabad visible). Clean before/after.

**Rotation — SKIPPED, honestly blocked.** `adb shell settings put system user_rotation 1` →
`SecurityException: WRITE_SETTINGS`. Fallback `adb shell content insert --uri
content://settings/system ...` → same `SecurityException`. No physical rotation capability
available to this agent (real hardware, not a simulator). Matches the documented OxygenOS
WRITE_SETTINGS block.

**Permission-revoke — SKIPPED, substituted.** `adb shell pm revoke ... ACCESS_FINE_LOCATION` and
`ACCESS_COARSE_LOCATION` both → `SecurityException: Neither user 2000 nor current process has
android.permission.REVOKE_RUNTIME_PERMISSIONS` (matches documented OxygenOS `pm revoke` block).
Substituted with code inspection: `HomeScreen.kt:574` — `if (locationPermissionGranted) {
MyLocationFab(...) }`, a plain conditional gate; `locationPermissionGranted` is derived from
`reduceLocationPermissionState(...)`, a separately-unit-tested pure reducer. Live end-to-end
permission toggle unverified this pass.

## Task 2 — Favorites matrix

| Item | Result | Artifact |
|---|---|---|
| Add place (Tokyo preset) | PASS | `task2-add-place-tokyo.png` |
| Alert-type segmented control (All/Major/Off) | PASS | `task2-alerttype-major-only.png`, `task2-alerttype-off.png` |
| Home quick-switch chips + tap-to-fly | PASS | `task2-quickswitch-chips-home.png`, `task2-quickswitch-chips-tokyo.png` |
| Free-tier gate (2nd favorite → paywall) | PASS | `task2-gate-paywall.png` |
| Remove favorite | PASS | `task2-remove-favorite.png` |
| Alert-from-favorite (notification with Tokyo attribution) | **FAIL to reproduce via documented technique — root-caused, not an app bug** | `task2-alert-from-favorite-notification.png` |

Add/remove/segmented-control/chips/paywall all straightforward PASSes with clean before/after
screenshots. Quick-switch chip tap flew the camera correctly to real Tokyo (Kawasaki, Chiba,
Funabashi visible).

**Alert-from-favorite is the one real finding of this pass.** Followed the documented technique
exactly: long-pressed the map (debuggable-build-only hook, confirmed in `QuakeMap.android.kt`) to
inject a debug quake at Tokyo's camera center via `HomeViewModel.injectDebugQuake` (fixed M6.0,
`origin=ORIGIN_DEBUG`), then long-pressed Settings' "Alerts" row to fire
`AlertDigestScheduler.triggerNow()`. Logcat confirms `AlertDigestWorker` ran and returned SUCCESS —
but **no notification posted** (confirmed via notification shade + `dumpsys notification
--noredact`, no TerraWatch entry).

Root cause, read directly from source: `Quake.sq`'s `newSince` query is `WHERE fetchedAtMillis >
:sinceMillis AND origin IN ('feed', 'live')` — debug-origin rows are **structurally excluded by
design** (an intentional anti-footgun so fake/debug data can never trigger a real user-facing
notification — `AlertDigestWorker`'s own kdoc calls this "the F5-guard parity"). The debug
long-press hook was never wired to satisfy this query; task-2-report.md's own "Deferred to device"
section shows this exact test was *never previously attempted on a connected device* — this pass
is the first real attempt, and it reveals the technique itself can't produce the notification, by
design, regardless of whether the underlying fix is correct.

I did not attempt to force it further via direct SQLite file surgery (`run-as` works and the DB
file is reachable, but no `sqlite3` binary exists on-device, and pulling/patching/pushing the same
production file that item 0's migration protects felt like an unacceptable risk to the user's real
dogfooding data for a secondary verification path). The underlying **MAJOR_ONLY-favorite dedupe
fix itself is separately covered** by 3 dedicated JVM tests in `AlertDigestSupportTest.kt`
(fix-round-1), which were RED before the fix and are GREEN now. Separately, during unrelated
testing, a **real** M6.1 Vanuatu quake fired a genuine digest notification with correct "matches
your worldwide M6+ alert rule" copy — proof the live notification pipeline itself is healthy end to
end; it's specifically the debug-inject shortcut that can't reach it.

## Task 3 — Ad stability

| Item | Result | Artifact |
|---|---|---|
| First-fill: no layout jump | PASS | `task3-first-fill.mp4` |
| Detail open: banner GONE, nothing clickable in its place | PASS | `task3-detail-open-no-ad.png` |
| Detail close: banner returns, no white flash/reload jank | PASS | `task3-detail-cycle.mp4` |

scrcpy wasn't installed; installed via `brew install scrcpy` (4.1) to get real video evidence
rather than substituting screenshots. First recording needed two attempts — a background/`wait`
scripting bug on my end produced a truncated file the first time; the corrected version (scrcpy +
timed action inside one script, no cross-tool-call latency) worked cleanly. Frame-by-frame
inspection via `ffmpeg -vf fps=N` confirms: map/pill/top rows never move when the ad fades in — it
only occupies previously-reserved space; bottom nav position is pixel-identical across the
OFFLINE→LIVE→ad-filled sequence. Detail open: `uiautomator dump` confirms zero WebView/AdView/
AdManager nodes anywhere in the tree while a detail sheet is open (not just alpha-hidden). Detail
close: frame-by-frame shows the ad back within ~250ms of dismiss, no blank/white frame at 4fps
sampling. Both videos ~4-5MB, well under the 20MB/25MB limits.

## Task 3b — Feed reveal

| Item | Result | Artifact |
|---|---|---|
| "N new quakes ↑" chip appears when scrolled away + new arrival | PASS | `task3b-reveal-chip.png` |
| Tap chip → scrolls to top, chip dismisses | PASS | `task3b-chip-tapped-scrolled-top.png` |
| At-top auto-reveal (new row visible without touching) | PASS | `task3b-auto-reveal-at-top.png` |

Getting a controlled scrolled-away state (to hit `SHOW_CHIP` rather than `AUTO_SCROLL`) took
several iterations — collapsing/expanding the bottom sheet preserves the underlying `LazyListState`
scroll position (same composable, just clipped differently), so the working recipe was: expand,
scroll down, collapse (to reach the map), inject/wait, re-expand. Final chip text matched
`feedRevealChipText` exactly ("9 new quakes ↑"); tapping it cleared the chip and landed on the true
top item.

## Task 5 — News resolution

| Item | Result | Artifact |
|---|---|---|
| Real M5.5+ quake detail → news resolves to headlines or honest error (never vanishes) | PASS | `task5-news-error-state.png` (error), `task5-news-resolved-populated-headlines.png` (real headlines) |
| Retry → screenshot outcome | PASS | `task5-news-retry-outcome.png` |
| News/USGS link opens browser | PASS (bonus) | `task5-news-link-opens-browser.png` |

Tested against two different real quakes (M7.7 Ende, Indonesia and M6.1 Vanuatu). The news section
never vanished — always either the skeleton loader, a genuine "Couldn't load news" + working Retry,
or real headlines. Root-caused the failures with a device-side `curl -v
https://api.gdeltproject.org/...`: DNS resolves, TCP connects on :443, but the **TLS handshake
hangs after Client Hello and times out** — classic SNI-level network filtering, not an app defect
(consistent with this project's own documented Zscaler/corporate-proxy TLS history). Roughly
majority-fail with occasional success in this environment: Insights' own "IN THE NEWS" card (same
quake) succeeded twice with real headlines ("2 dead after powerful 7.7 earthquake strikes off
Indonesia" — abcnews.com; oneindia.com coverage), while the detail sheet's own independent request
for the identical quake failed most of the time — confirming the two call sites don't share a
cache and the underlying condition is genuinely intermittent, not a hard block. Tapping a real
headline opened it correctly in Chrome (real article, real byline). This is the **first populated-
news device capture in this repo's history** (prior plan-3/4/5 passes only ever caught
loading/hidden-after-429 states, per `scripts/screenshots-config.json`'s own pre-existing note).

## Task 6 — Fresh store captures

PASS. Captured: `store/home-map-with-fab-and-chips.png`, `store/detail-sheet-clean-news-error-
state.png`, `store/insights.png` (real populated news), `store/history.png` (M6+ filtered),
`store/notification-shade.png` (real M6.1 Vanuatu alert). Copied the 4 genuine improvements into
`store-assets/screenshots/` (`01-home-map.png`, `02-detail-sheet.png`, `03-insights.png`,
`05-history.png`) and updated `scripts/screenshots-config.json`'s notes to match. Kept 2 existing
assets **unchanged** where they were objectively stronger than anything achievable this pass:
`04-settings-radius-ring.png` (the radius-ring feature needs a manual pinch-zoom to the exact level
where the ring boundary is visible — both the FAB's zoom and the cold-start zoom~6 default keep the
whole viewport inside the 500km ring either way, and `adb input` has no reliable pinch-gesture
primitive) and `06-notification.png` (existing capture shows 3 stacked real M6+ alerts; my fresh
one only had 1 — old is strictly better). Re-ran `python3 scripts/frame-screenshots.py`: **6/6
shots framed successfully**, verified two outputs visually (clean device-bezel framing, correct
crops, headlines legible).

## Task 7 — Final logcat sweep

**PASS — zero TerraWatch crashes across the entire pass.** Full-session `adb logcat -d -s
AndroidRuntime:E` sweep found exactly one FATAL EXCEPTION entry in the whole buffer — and it
belongs to PID 9156, the `com.android.commands.svc.Svc` shell utility (my own `adb shell svc power
stayon usb` diagnostic attempt, run once mid-session to try to stop recurring adb/USB disconnects),
crashing on the identical `WRITE_SETTINGS` `SecurityException` already documented for Rotation/
`pm revoke` above — not a TerraWatch defect. Confirmed via `grep "9156" | grep -i
"yugma\|terrawatch"` (no match) and a separate `--pid`-scoped sweep of the actual TerraWatch process
(zero FATAL/AndroidRuntime lines) plus a dedicated ANR check (zero). Correction to my own mid-
session assumption: I believed at the time that `stayon usb` had succeeded (no error was visible in
the command's own captured output, and `dumpsys power` showed `mWakefulness=Awake`); this sweep
reveals it actually crashed silently, which is consistent with the disconnects continuing to recur
for the rest of the session (worked around each time via `adb kill-server && adb start-server`,
never blocking on more than ~20s).

Saved excerpt: `task7-logcat-androidruntime-sweep.txt`.

## Artifact count

29 screenshots + 2 videos + 1 log excerpt = **32 files**, ~22MB total, committed under
`docs/qa/plan-5-device-matrix/` (including `store/` subfolder), plus 4 updated files in
`store-assets/screenshots/`, 6 regenerated files in `store-assets/screenshots-framed/`, and
`scripts/screenshots-config.json`.

## Concerns

1. **Alert-from-favorite notification path is untestable via the documented debug-inject
   technique** (by design — debug-origin rows are excluded from the digest's `newSince` query).
   The underlying MAJOR_ONLY dedupe fix has solid JVM coverage; the live on-device notification
   path for a *favorite* specifically (as opposed to home/world) remains unverified end-to-end on
   a real device. If this needs a real device proof in the future, the only paths are: wait for a
   genuine M6+ quake to land within a favorite's radius (unpredictable timing), or a future debug
   hook that inserts through `origin='feed'`/`'live'` instead of `'debug'`.
2. **News/GDELT reachability is intermittent from this network** — not an app defect (confirmed via
   `curl -v` TLS-handshake-hangs diagnosis), but it means CI/device passes on this same network will
   keep seeing more error-states than success-states for the News feature. Worth knowing if a future
   pass reports "News is broken" — check reachability to `api.gdeltproject.org` first.
3. **Rotation and permission-revoke remain structurally unverifiable on this specific device**
   (OxygenOS blocks `WRITE_SETTINGS` and `REVOKE_RUNTIME_PERMISSIONS` for the shell user). Both are
   substituted with code inspection; neither is a new gap introduced by this pass.
4. **04-settings-radius-ring.png store asset is unchanged from an earlier plan** (Plan 5 didn't
   touch this feature, and getting a better zoom needs a manual pinch gesture `adb input` can't
   reliably script) — flagging for visibility only, not a regression.
