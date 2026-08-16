# TerraWatch demo video — shot list

Plan 5 Task 5, phase 2. Device `98bc1cd8` (OnePlus 9R, OxygenOS, Android 14), branch
`feat/plan-5-polish`, HEAD `0321cc8`. Installed build: versionCode 2 / 0.9.0 (upgrade-installed
during today's device matrix pass, `docs/qa/plan-5-device-matrix/RESULTS.md`; diff between that
install and current HEAD is `docs/ACCOUNT-SETUP.md` only — 2 doc lines, no app code — so the
installed APK is representative of HEAD for this recording). Real device, real dogfooding data,
real location (New Delhi/NCR, 28.56°N 77.44°E). No uninstall/reinstall planned unless the installed
build is found stale at record time.

**Evidence integrity**: honest footage only. No staged fakes. The debug long-press quake-inject
hook exists in this build but is deliberately AVOIDED here — the live feed already has real M6+
quakes (Vanuatu 6.1, Indonesia 6.9/6.1/7.7, Colombia 7.4, South Sandwich 6.0, all within the last
few days per History/M6+ filter). Every on-screen number, place name, and headline in this video is
real.

Coordinates below are physical pixels (device is 1080×2400, density override 408) captured via
`adb shell uiautomator dump` + `adb exec-out screencap`, confirmed against the actual running build
this session before the device dropped mid-rehearsal (see Concerns in the final report). All `adb`
commands assume `-s 98bc1cd8`.

## Known coordinates (rehearsed this session, pre-drop)

| Element | Coordinates | Notes |
|---|---|---|
| Bottom nav — Home | (173, 2226) | |
| Bottom nav — History | (539, 2226) | |
| Bottom nav — Insights | (906, 2226) | |
| Home — Settings (gear/sliders) icon | (978, 205) | top-right of map |
| Home — my-location FAB | (978, 1239) | content-desc "Go to my location" |
| Home — feed sheet drag handle | (540, ~1400) | y shifts with sheet expansion state |
| Home — "Home" favorite chip | (126, 1239) | |
| Home — "Tokyo" favorite chip | (315, 1239) | |
| Home — status pill ("All calm near you" / alert state) | (448, 211) | |
| Settings — back arrow | (59, 167) | |
| Settings — radius SeekBar | full-width track x:[51,1029], y=475 | 5 discrete stops: 50/100/250/500/1000 km (`RADIUS_STEPS_KM`); current value 500 km (index 3) |
| Settings — magnitude SeekBar | full-width track x:[51,1029], y=714 | range 3.0–6.0, current 4.5 |
| Settings — Alerts on/off row | y=864 | |
| Settings — Places: home coords row / Change | y=1113, Change=(914,1113) | |
| Settings — Use my location | (227, 1245) | |
| Settings — Tokyo row / Remove | y=1418, Remove=(911,1418) | |
| Settings — alert-type segmented (All/Major only/Off) | centers (138,1550)/(337,1550)/(540,1550) | currently "Major only" (Tokyo) |
| Settings — Add place | (168, 1690) | |
| Settings — Theme: System | (273, 2170) | |
| Detail sheet — scrim (tap to close) | anywhere y<1200 | or system back |
| Detail sheet — stat row (depth/away/felt) | y≈1601 | |
| Detail sheet — tsunami banner | y≈1751 | |
| Detail sheet — coordinates/source/felt table | y≈1880–2082 | |
| Detail sheet — "IN THE NEWS" header | y≈2180 | |
| History — filter chips (All/M4.5+/M6+) | y≈285 (approx, from earlier capture) | M6+ currently selected |
| History — year chips (2026/2025/All) | y≈416 | All currently selected |

Radius-ring approach: rather than fight `adb`'s lack of a reliable pinch-zoom primitive (documented
dead end in `RESULTS.md` Concern #4 — the ring only becomes visible on-screen at a manually
pinch-zoomed-out level for the 500 km default), this recording drags the radius **slider down to
the 50 km step instead of zooming the map out**. A 50 km ring at the Home screen's default city-level
zoom (scale bar ≈ 50 km per the map's own on-screen ruler) should render as a clean, fully-visible
circle with no gesture beyond a slider drag. Slider is restored to 500 km / magnitude to 4.5
(original values) after the recording.

## Segment plan

| # | Time | Screen(s) | Action | Technique |
|---|---|---|---|---|
| 0 | 0:00–0:15 | Home (cold open) | force-stop → relaunch → map loads → camera settles (fix≈home, no forced re-center — correct per `CameraTarget.kt`) → LIVE pill / status pill appears | `adb shell am force-stop com.yugma.terrawatch`; `adb shell am start -n com.yugma.terrawatch/.MainActivity`; wait ~3s for cold start; dwell |
| 1 | 0:15–0:30 | Home | feed sheet: swipe up from handle to expand → scroll quake list (swipe up ×2-3) → opportunistic "N new quakes ↑" reveal chip if one lands live (not forced, not blocked on) | swipe (540,1900)→(540,400); scroll swipes on list body |
| 2 | 0:30–0:50 | Home → Detail sheet | tap a significant M6+ quake from the scrolled feed (prefer one with live-resolved news headlines if available at record time — Vanuatu 6.1 / Indonesia 6.9 or 7.7 are the live candidates); show mag/depth/distance stat row, news section (headlines or honest error+retry — never fabricated), glimpse of share row | tap on located quake row; dwell on stat row, tsunami/coords, news card, share row |
| 3 | 0:50–1:05 | Home | pan map away from New Delhi toward a quake-dense region (Pacific/Indonesia) to surface real pins, tap my-location FAB → snap back home; tap "Tokyo" favorite chip → camera flies to Tokyo | swipe map to pan; tap (978,1239); tap (315,1239) |
| 4 | 1:05–1:25 | Settings | radius slider: drag 500→50 (shrink) → cut to Home to show ring, back to Settings, drag 50→1000→back to 500 (restore); Places list + alert-type segmented control (tap between All/Major only/Off on Tokyo, leave at original) | swipe on SeekBar track y=475; tap segmented control centers |
| 5 | 1:25–1:45 | History → Insights | History: tap filter chips (All/M4.5+/M6+), tap a row; Insights: 7d/30d toggle, quakes-per-day chart, magnitude distribution, strongest card, news card | tap (539,2226) for History tab, filter chips; tap (906,2226) for Insights |
| 6 | 1:45–2:00 | Notification shade OR Settings Alerts (honest fallback) | Pre-check at record time: `dumpsys notification --noredact` — as of shot-list-writing time, **no live TerraWatch notification is in the shade** (today's earlier Vanuatu 6.1 digest notification, confirmed fired per `RESULTS.md`, is no longer present; confirmed via notification-shade screenshot showing only System UI entries). Per the dispatch brief's own pre-approved fallback: if still absent at record time, show Settings' Alerts section instead (radius/magnitude/on-off — already real, already covered in segment 4) rather than staging a fake notification | `adb shell cmd statusbar expand-notifications` to check live; collapse before cutting |

## Deltas from the dispatch brief's template (planned, written before recording)

- Segment 6 is very likely to be the honest-fallback path (Settings Alerts), not a live notification
  shade capture — flagging this now, before recording, since the brief anticipated exactly this
  possibility ("else show alert settings instead — honest").
- Radius ring is demonstrated via shrinking the slider value (50 km stop), not via zooming the map
  out — `adb` has no reliable pinch-zoom primitive (same dead end `RESULTS.md` hit); this achieves
  the same "ring visibly grows/shrinks" beat without an unscriptable gesture.
- Segment 1's reveal chip is explicitly opportunistic per the brief; not forced via debug-inject.

## Executed vs. planned (post-recording)

- **Cold open (seg 0):** the persisted camera pan surviving `force-stop` turned out to be a wide
  Europe/Africa world view (not a tight New Delhi zoom) — matches `RESULTS.md`'s own
  `task1-cold-open-centered.png` almost frame-for-frame (same cluster-bubble positions), confirmed
  this is the documented, already-QA'd-PASS "fix≈home, leave camera alone" behavior, not a bug or a
  bad take. Tapping the Home favorite chip beforehand did NOT change this (chip fly-tos are a
  transient camera animation, not the persisted value `startupCameraTarget` reads — confirmed by
  reading `CameraTarget.kt` directly), so the cold open genuinely, honestly opens on the world view
  plus the "All calm near you" status pill and LIVE feed.
- **Segment 1 bonus:** a real "N new quakes" reveal chip appeared opportunistically (as anticipated)
  while queuing up segment 3 — captured it as a short bonus clip (tap → scrolls to top → chip
  dismisses) rather than skip it.
- **Segment 2:** recorded from History's M6+ filter (not the raw Home feed scroll) for a reliable
  significant-quake tap — the live feed sorts by recency, not magnitude, so reaching a real M6+ quake
  by scrolling Home alone would have taken many more swipes. Picked the Vanuatu 6.1 (real "revised
  from M 6.0" badge, 1 felt report, USGS·EMSC-confirmed). News resolved to the honest "Couldn't load
  news" + Retry state both before and after tapping Retry — consistent with `RESULTS.md`'s documented
  GDELT TLS-hang intermittency, not a new defect. Never fabricated headlines.
- **Segment 3:** combined the pan + FAB + favorites-chip beats into one continuous take — the pan
  landed on a genuinely quake-dense Pacific/Indonesia/PNG cluster (many real pins visible) before the
  FAB snapped back to a tight New Delhi zoom and the Tokyo chip flew the camera to Tokyo.
- **Segment 4 (radius ring):** the 50 km slider value fully solved the "ring needs a pinch-zoom"
  problem from `RESULTS.md` — the ring rendered as a clean, fully-visible circle around New Delhi at
  default zoom, no gesture beyond a slider drag. **Dropped the live alert-type-toggle demo** (tapping
  between All/Major only/Off) after repeated mid-recording USB drops made it too failure-prone to
  re-attempt further; the Places section + segmented control (Tokyo, "Major only") is still clearly
  visible as a static element in the radius-slider recording, satisfying the "Places list w/
  alert-type control" beat without the extra interactive risk. Radius and magnitude sliders were
  restored to their original values (500 km / 4.5) afterward.
- **Segment 6 (notification fallback):** confirmed at record time — as predicted — no live TerraWatch
  notification was in the shade (checked via both `dumpsys notification --noredact` and an actual
  pulled-down shade screenshot, twice, hours apart). Used the pre-approved honest fallback: Settings'
  Alerts section (radius/magnitude/on-off).
- **Device connectivity:** the device dropped far more often than "occasional" during this resumed
  session — roughly a dozen brief USB drops, most self-recovering within seconds, a few needing
  `adb kill-server && adb start-server`. Two recording attempts were genuinely truncated mid-capture
  (scrcpy's own "Device disconnected" warning fired) and were re-recorded successfully: the Vanuatu
  detail-sheet segment (first attempt's video stream silently stopped at ~13s despite a ~24s
  container) and the Places/alert-type segment (second attempt still hit a live "Device disconnected"
  exit and was abandoned in favor of the simpler places-visible-only approach described above). One
  drop mid-sequence briefly bounced the foreground to the OS's own Battery settings screen (recovered
  via the recent-apps switcher, no data lost).

## Status

**Executed.** Both `demo.mp4` (105.9s) and `demo-30s.mp4` (30.1s) assembled, QC'd (ffprobe +
8 spot-checked thumbnails total, all matching claims above), and committed. See the final report for
exact durations, sizes, and commit hash.
