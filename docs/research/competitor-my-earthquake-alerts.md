# Competitor study — My Earthquake Alerts (JRustonApps)

Product-benchmarking research for TerraWatch. Covers static APK inspection, live on-device
observation, and web cross-reference of **My Earthquake Alerts** (`com.jrustonapps.myearthquakealerts`),
published by JRustonApps B.V.

Every claim below is tagged **Fact** (directly observed — device command, decoded resource, or
screenshot) or **Inferred** (reasonable conclusion that wasn't independently confirmed). No
proprietary code was copied or reconstructed: static analysis was limited to hostnames, manifest
declarations, and shipped resource strings (all public network/UI-facing surface), never to
reversing their obfuscated business logic.

- **Date of research:** 2026-08-21
- **Device:** OnePlus 9R / LE2101, Android 14 (API 34), OxygenOS, locale `en-IN`, serial
  `adb-98bc1cd8-SvCGJC._adb-tls-connect._tcp` (WiFi adb)
- **Method:** `adb pull` of the installed APK + `aapt dump badging` + `apktool d -s` (resources
  only, no smali) + `unzip`/`strings` over all 5 `classes*.dex` files for hostnames, cross-checked
  against `adb shell dumpsys package` for live permission grants, then live UI walkthrough
  (`monkey` launch, `input tap`/`swipe`, `uiautomator dump`, `screencap`) of every screen reachable
  without login or purchase, then web search/fetch against JRustonApps' own site, Play listing
  mirrors, and app-store metadata. No MITM/traffic interception was performed — network claims rest
  on shipped strings + observed app behavior only, per this task's own constraint.
- **Screenshots:** `docs/research/competitor-screens/` (7 images, listed with each section below).

## 1. App identity & version

| Field | Value | Basis |
|---|---|---|
| Package | `com.jrustonapps.myearthquakealerts` | Fact — `pm path`, device |
| Label | "My Earthquake Alerts" (in-app toolbar reads "Recent Earthquakes") | Fact — `aapt dump badging` / screenshot |
| **versionName / versionCode** | **26.4.6 / 351** | Fact — `aapt dump badging base.apk` and cross-checked live via `dumpsys package` (`versionCode=351 minSdk=24 targetSdk=35`, `versionName=26.4.6`) |
| compileSdk / targetSdk / minSdk | 36 (Android 16) / 35 / 24 | Fact — `aapt dump badging` |
| Developer | JRustonApps B.V. | Fact — Play/App Store listings |
| Sibling app | `com.jrustonapps.myearthquakealertspro` — separate **one-time-purchase** ($2.99) ad-free app, not a subscription; iOS Pro build additionally ships an **Apple Watch app** | Fact (package name from an in-app string + Play link) / Fact (price, Watch support) via Apple App Store listing — Android Pro build not independently inspected |
| Play category (third-party) | Similarweb lists it under **Weather**, #135 in-category (US) | Fact (Similarweb), consistent with `store-assets/listing.md`'s own reasoning for putting TerraWatch under Weather rather than Maps & Navigation |

Web mirrors (Uptodown, ModCombo) show nearby versions (26.4.3–26.4.5) at slightly different capture
dates — consistent with an actively, frequently updated app; the device-observed 26.4.6/351 is
treated as authoritative since it's a direct read, not a third-party mirror.

## 2. Data sources & APIs positively identified

The client **does not talk to `earthquake.usgs.gov` or `seismicportal.eu` directly** — no such
hostname appears anywhere across all 5 dex files. Instead, every earthquake-data call goes to
JRustonApps' own backend, which (per their own published description) aggregates USGS/EMSC
server-side before serving the app:

| Host / endpoint | Purpose (inferred from name + settings behavior) | Status |
|---|---|---|
| `jrustonapps.com/app-apis/earthquakes/search.php` | Search screen query (region + magnitude + date range) | Fact — string in `classes*.dex` |
| `jrustonapps.com/app-apis/earthquakes/notifications.php` | Registers device/prefs for push alert matching | Fact — string in `classes*.dex` |
| `jrustonapps.net/app-apis/earthquakes/get-recent.php` | "Recent Earthquakes" home feed | Fact — string in `classes*.dex` (`.net` mirror of the `.com` paths above — likely a fallback/load-balanced domain, not independently confirmed) |
| `jrustonapps.net/app-apis/earthquakes/{search,notifications}.php` | Same as `.com` variants | Fact — string present |
| `www.jrustonapps.com/app-apis/data/autocomplete.php` | Location/region autocomplete (Settings → "Set custom location", "My Country/State") | Fact — string present |
| `www.jrustonapps.com/app-apis/data/g.php?la=..&lo=..` | Reverse geocode (lat/lon → place name) | Fact — string present |
| `maps.googleapis.com/maps/api/place` + Google Maps SDK | Map rendering (Normal/Satellite/Terrain/Hybrid) and place search | Fact — string present + on-device `content-desc="Google Map"` + Google-logo watermark in `01-home-map-list.png` |
| **Upstream seismic sources: USGS + EMSC** | Raw quake data feeding the backend above | **Inferred / self-reported** — JRustonApps' own Play listing and website state: *"Uses information from a wide variety of US and worldwide earthquake networks, including the USGS and EMSC"* [jrustonapps.com/apps/my-earthquake-alerts]. Not independently verifiable from the client, since the client never queries USGS/EMSC itself — this is JRustonApps' own claim about their server, unverified against raw feeds. |
| `com.google.firebase.messaging.FirebaseMessagingService` + legacy `GcmBroadcastIntentService` (own class) | Push delivery transport for alerts | Fact — manifest `<service>`/`<receiver>` entries |
| `earthquak.es/<id>` | Short-link embedded in "Share Safety"/share text | Fact — string present; purpose (tracking vs. deep link) inferred |
| Ad/analytics hosts: `googlesyndication.com`, `doubleclick.net`, `applovin.com`, `vungle.com`, `fyber.com`, `bidmachine.io`, `amazon-adsystem.com` (Amazon TAM), `appodeal.com`, `app-measurement.com` (Firebase Analytics), `firebase-settings.crashlytics.com`, `sentry.io` | Ad mediation waterfall + crash/analytics telemetry | Fact — strings + manifest `<provider>`/`<receiver>` entries (AppLovin, BidMachine, Vungle, Appodeal all have live manifest init hooks, not just referenced strings) |

**Contrast with TerraWatch:** TerraWatch's `UsgsApi`/`EmscLiveSource` talk to `earthquake.usgs.gov`
and `wss://www.seismicportal.eu/standing_order/websocket` **directly from the client**, with no
TerraWatch-operated backend of any kind (`store-assets/listing.md`). My Earthquake Alerts instead
proxies everything through its own server — which is *why* it can push (the server, not the
device, watches the feeds and decides who to notify), but also means a user's approximate
location/region and alert preferences are known to a third-party server, not just to USGS/EMSC's
own public, unauthenticated feed endpoints.

## 3. Permissions & alert mechanism (manifest facts)

Key permissions (`aapt dump badging`, cross-checked live via `dumpsys package`):

`ACCESS_FINE_LOCATION`, `ACCESS_COARSE_LOCATION`, **`ACCESS_BACKGROUND_LOCATION`**,
`RECEIVE_BOOT_COMPLETED`, `FOREGROUND_SERVICE`, `WAKE_LOCK`, `POST_NOTIFICATIONS`,
`com.google.android.c2dm.permission.RECEIVE` (FCM), plus AD_ID / AdServices Topics/Attribution
permissions for ads.

On the test device, `ACCESS_BACKGROUND_LOCATION` was **denied** (fine/coarse and notifications were
granted) — so the app's most aggressive background mode was not actually active during this
research; behavior described here is what the manifest/settings *declare*, not a confirmed
worst-case battery/latency measurement.

The app's own location-permission rationale string is explicit about why:
> *"This app collects location information in the background to enable accurate notifications about
> earthquakes nearby. It is recommended that you grant the 'Allow all the time' location
> permission."*

And JRustonApps' own Android-notifications FAQ (`jrustonapps.com/faqs/android-notifications/`)
confirms the mechanism is push, and names its real-world failure mode:
> *"Android manufacturers restrict third party apps from functioning when the app is in the
> background. This can break push notification functionality."* ... *"disable battery
> optimizations for this app... even if you have 'battery saver' mode turned off."*

This is a **Fact, self-disclosed**: alert delivery depends on the OS not killing a background push
listener, and the vendor's own troubleshooting page exists because that killing is common —
including on OnePlus/OxygenOS, the exact OEM used for this research, which is well known for
aggressive background-app management.

## 4. Live-observed screens

All screens below were reached without logging in or completing any purchase.
Screenshots: `docs/research/competitor-screens/`.

1. **`01-home-map-list.png`** — "Recent Earthquakes" home: dark-styled Google Map (pins for two
   nearby small quakes) + "My Location" FAB, live scrolling list below (flag icon, place, region,
   relative + absolute time, color-pill magnitude), a banner ad pinned at the bottom of the same
   screen (below the list, not overlapping the map).
2. **`02-overflow-menu.png`** — toolbar overflow: "Pro Version" (upsell, not tapped) and "Settings".
3. **`03-settings-main.png`** / **`04-settings-scroll1.png`** — full Settings list (General /
   Location / Notifications / Recent / Other groups) — see §5 for the exact option values decoded
   from `res/values/arrays.xml`.
4. **`05-interstitial-ad-on-navigation.png`** — tapping a list row to open quake detail surfaced a
   full-screen video interstitial ad first (a real ad served during this session; the specific
   creative is session/targeting-dependent, not a stable app fact — the stable fact is that
   **opening a quake's detail can trigger a full-screen interstitial**, not just a banner).
5. **`06-detail-quake.png`** — the actual quake detail screen (reached after dismissing the
   interstitial with the back button): place name + region, embedded map with pin, magnitude badge
   labeled **"Richter Scale"** with a plain-language description ("Felt slightly by some people."),
   Time / Distance / Location (lat-lon) / Depth stat grid, share + map-type icons in the toolbar,
   and another banner ad at the bottom.
6. **`07-search.png`** — dedicated Search screen: Region filter, Size (magnitude) filter, and an
   explicit from/to date-range picker, separate from the live "Recent" list.

Not reachable/observed as a distinct screen in this pass (see §6 for what this does and doesn't
imply): a dedicated "About/data sources" screen (its content was recovered instead from FAQ/privacy
strings), and the "Notification Settings" sub-screen (sound/display picker) — its existence is
confirmed by its Settings-list description ("Change the notification sound and how notifications
are displayed") but it wasn't individually screenshotted.

## 5. Settings — exact option values (decoded resources, not guessed)

Decoded from `res/values/arrays.xml` via `apktool d -s` (all **Fact**, shipped resource data):

- **Notification Type** (`notificationTypeOptions`): `None · Nearby · Within Distance · U.S. State
  · My Country · My Continent · Worldwide` — 7 geographic scopes.
- **Minimum Magnitude** (`notificationMagnitudeOptions`): `All` then `1.0+` through `7.0+` in 0.5
  steps (13 steps).
- **Recent list**: Time Frame `12/24/36/48 Hours`; Layout `Map + Table / Table / Map`; Sort By
  `Time / Distance Away / Magnitude`; Show Region (continent-level: `North America · South America
  · Europe · Oceania · Asia · Africa`).
- **Map Type**: `Normal / Satellite / Terrain / Hybrid` (Google Maps SDK's own type constants —
  confirms Google Maps, not a custom tile style).
- **Units/format**: Distance `Automatic / Miles / Km`; independent `Show in UTC time?` and `12 hour
  clock?` checkboxes; `Zoom into recent earthquakes?` (auto-zoom to the latest quake vs. current
  location).
- **Other section**: Privacy policy, Rate/Share the app, "More apps by us", **"Buy us a coffee"**
  (donation link, on top of ads), FAQ, **"Notification problems"** (a dedicated troubleshooting FAQ
  entry — itself a signal that missed/delayed alerts are a common-enough complaint to need one),
  Contact support.
- **Map**: "Show fault lines?" checkbox (default off).

## 6. Feature matrix — My Earthquake Alerts vs. TerraWatch

Our own side is checked directly against shipped code (`AlertDigestSupport.kt`, `DetailSheet.kt`,
`HistoryViewModel.kt`, `store-assets/listing.md`), not asserted from memory.

| Area | My Earthquake Alerts | TerraWatch | 
|---|---|---|
| Alert transport | **Push (FCM)**, server-mediated, "shortly after" per their own copy | **Pull**, `AlertDigestWorker` on a fixed 45-min `WorkManager` period, zero backend |
| Alert scope options | 7 modes: off/nearby/custom-distance/state/country/continent/worldwide | 2 fixed rules (home-radius "near" + worldwide M6+) **plus** per-favorite-place rules (`ALL`/`MAJOR_ONLY`/`OFF`), all point+radius — no administrative-boundary (state/country/continent) scope |
| Magnitude floor granularity | 14 steps, 0.5 apart, user-set | User-adjustable "current min-magnitude setting" (e.g. default M≥4.5 near / fixed M≥6 world per onboarding copy), not exposed in 0.5 steps |
| Notification sound/vibrate | Independently configurable | Not found as a distinct setting (uses platform default channel behavior) — **not independently re-verified this pass** |
| Quiet hours / DND window | **Not found** in settings or strings (checked) | Not present |
| Map | Google Maps, 4 style types, fault-line overlay toggle | Custom OpenFreeMap/OSM vector style, radius-ring overlay (no fault lines, no style picker) |
| List | Sort (time/distance/magnitude), layout toggle (map/table/both), 12-48h window | Newest-first only (`HistoryViewModel` comment: "already newest-month-first, with no separate sort step"), magnitude filter chips + year picker, no layout toggle |
| Search | Dedicated screen: region + magnitude + explicit date range, marketed "back to 1970" | History screen filter chips + year picker only; `UsgsApi` already has `queryArchive`/`queryCount` methods per `listing.md`, not yet exposed as a dedicated search UI |
| Detail — felt reports | Generic magnitude-bucketed text ("Felt slightly by some people") — static copy, not a live per-event count | **Live per-event count** from USGS's real `felt` (DYFI) field, shown as a "FELT IT" stat card — already shipped |
| Detail — shaking intensity | An `image_viewer` string titled "Intensity Map" exists (inferred: a linked shake-map image for larger events; not observed on the M2.8 event checked) | Explicitly **not implemented** — team's own code comment: *"there is no such field anywhere in the Quake model (USGS/EMSC shake-map intensity was never ingested)"*, deliberately substituted a Felt row instead |
| Detail — revision transparency | Not observed | **Shipped**: `RevisionBadge` ("revised from M 5.9 · 12 min ago") |
| Detail — tsunami | Not observed on the one event checked (a small inland-ish quake, where no advisory would be expected either way) | **Shipped**: `TsunamiBanner` driven by the feed's own flags |
| Detail — source attribution | Not surfaced per-event in what was observed | **Shipped**: "USGS · confirmed by EMSC" style per-event attribution |
| Post-quake social share | **"Share Safety"**: proximity-triggered prompt, pre-filled "I'm safe from the M{x} earthquake near {place}..." text + `earthquak.es` short link | Generic share (OS sheet + packaged quick-share to specific apps) of a quake, not a proximity-triggered "I'm safe" flow |
| News in detail | Not observed | Built (GDELT-backed) but **currently disabled** via `NewsFeature.ENABLED = false` (GDELT reliability issue from target networks, per `listing.md`) |
| Trends/insights | Not observed | **Shipped**: Insights screen (quakes/day, magnitude breakdown, strongest-in-window) |
| Custom location | Free-text/region-based manual override | City-picker (no-location alternative), not free-text |
| Widget | **Confirmed absent** — no `<receiver>` for any `AppWidgetProvider` in the manifest, no "widget" string anywhere in `strings.xml` | Not present |
| Wear/Watch | Android: **confirmed absent** (no Wear-related manifest entries). iOS Pro (separate, paid): Apple Watch app | Not present |
| Monetization | Ads (large mediation waterfall: AdMob, AppLovin MAX, Vungle, Fyber, BidMachine, Amazon TAM, Appodeal) **+** separate one-time-purchase "Pro" app (no subscription) **+** donation button | AdMob only; `TerraWatch Plus` paywall exists as a **static stub** today (no live purchases; RevenueCat not wired, per `listing.md`) |
| Privacy architecture | Client → JRustonApps' own server (knows push token + approximate region/prefs) → FCM | Fully local: no TerraWatch-operated backend; home location and rules never leave the device |
| Ad placement observed | Banner on home screen (below list) and detail screen; **full-screen interstitial on navigating into quake detail** | Per `listing.md`: banners between screens, explicitly never over the map, never during onboarding — no interstitial-on-navigation confirmed either way this pass (not our own code checked here, cited as spec intent only) |

## 7. Alert model — one-paragraph comparison

My Earthquake Alerts' alerts are **server-mediated and push-based**: the client registers with
JRustonApps' own backend and Firebase Cloud Messaging, and — per the app's own copy ("You will be
notified shortly after an earthquake occurs") and its Android-notifications FAQ, which frames
failure purely as OEM battery managers "breaking push notification functionality" — quakes are
pushed close to real time rather than on a fixed schedule, with scope chosen from seven geographic
modes crossed with a 14-step magnitude floor. The trade-off, confirmed by their own FAQ, is that
reliability now depends on the user fighting their OEM's battery optimization — exactly the
background-execution restriction Android's Doze/App Standby is designed to win by default, and a
documented weak point on aggressive-OEM devices (OnePlus/OxygenOS, the very device used for this
research, among them). TerraWatch's `AlertDigestWorker` takes the opposite trade: a fixed 45-minute
`WorkManager` poll — never push, no backend, nothing about the user's location or thresholds ever
leaves the device — evaluates "near" before "world" before per-favorite rules
(`homeRules.take(1) + favoriteRules + homeRules.drop(1)`, so a more specific favorite rule can win
over the unconditional worldwide catch-all), de-duplicates notified quakes against a 1000-entry
ring buffer keyed by every agency id a quake has ever carried, and caps one run's notifications at
3 individual + 1 summary. TerraWatch trades away sub-minute latency and OEM-battery fragility for a
bounded, honestly-disclosed worst-case delay (up to ~45 minutes) and zero server-side knowledge of
the user; My Earthquake Alerts trades that privacy/simplicity margin away for materially faster
delivery whenever the OS actually lets its push channel run.

## 8. Roadmap input — ranked ideas for TerraWatch

Effort is S (UI/config only or reuses existing infra) / M (new data plumbing or a new screen) / L
(new data source or platform component). None of the five below require paid infrastructure or
touch spec §6.5 (no early-warning/prediction claims) — each is flagged explicitly where relevant.

1. **"Share that you're safe" flow** — Effort **S**. Reuses the share pipeline that already exists
   (`onSharePackaged`/`buildShareText` in `DetailSheet.kt`) — just add a proximity-triggered prompt
   and template, offering to share "I'm safe" after a nearby digest match. Directly evidenced as
   valued: it's a prominent, dedicated feature in the competitor (`share_safety` / `share_exact`
   strings), not a generic afterthought. §6.5-safe: it's a post-event, opt-in share of a fact
   ("this happened near me, I'm OK"), not a prediction or early-warning claim.
2. **List sort + layout toggle** — Effort **S**. Add Time/Distance/Magnitude sort and a
   map/list/both layout choice to the home feed or History — pure UI over data already held
   locally; `HistoryViewModel` today is hardcoded to newest-first. Directly evidenced (competitor's
   `recentSortOptions`/`recentShowOptions`).
3. **Fault-line map overlay (toggle, off by default)** — Effort **S/M**. A static, bundled
   fault-trace dataset (e.g. USGS Quaternary Faults for the US, or the open GEM Global Active
   Faults database for worldwide coverage) rendered as a togglable line layer — no new API, no
   ongoing cost. Directly evidenced (competitor's "Show fault lines?"). §6.5 note: pair it with a
   short caption ("fault context, not a forecast") so a fault line near a user is never misread as
   a prediction.
4. **Dedicated historical Search** (region + magnitude + explicit date range) — Effort **M** (lower
   if `UsgsApi.queryArchive`/`queryCount`, which already exist per `listing.md`, cover most of the
   query need — the remaining work is mostly the screen and result list, not new data access).
   Directly evidenced: competitor markets search "back to 1970" as a headline feature, and today's
   History screen only offers filter chips + a year picker, not an explicit date-range/region query.
5. **Quiet hours for digest notifications** — Effort **S**. A user-set window (e.g. 10pm–7am) during
   which `AlertDigestWorker` still runs and still records state, but suppresses sound/heads-up (or
   defers non-M6+ notifications to the next run outside the window). **Not actually evidenced by
   this competitor** — no quiet-hours/DND setting was found in their Settings or strings either;
   proposed here on general merit (common, low-effort goodwill feature for any digest-style alert
   app), flagged honestly rather than mis-attributed to the competitive research.

**Considered, not in the top 5:**
- *Country/continent alert scope* (matching their 7-mode scope) — real evidenced value, but Effort
  **M**, since USGS/EMSC GeoJSON gives a free-text `place` string, not a structured country/continent
  code; would need a bundled point-in-boundary lookup to do on-device without a backend.
- *Shaking intensity / MMI* — deliberately **not** recommended as "ingest true MMI" (Effort **L**,
  needs a wholly new USGS ShakeMap data product our own team already considered and declined once,
  per `DetailSheet.kt`'s own comment). A cheaper alternative that gets most of the user value: link
  out to USGS's own public event-page/shake-map image URL for significant quakes (Effort **S**,
  just URL construction from the existing quake id, no new ingestion).
- *Home-screen widget* — **not evidenced by this competitor at all** (confirmed absent from their
  manifest and strings). Would be a bet on general category value, not a proven catch-up item;
  Effort **M** (Glance widget + periodic content refresh, new manifest surface to test across
  launchers).

## 9. Concerns / limitations of this research

- The "USGS + EMSC" upstream-source claim is **JRustonApps' own marketing copy**, not independently
  verified — the client never calls USGS/EMSC directly, so their backend could add, filter, or
  delay data in ways invisible from the outside.
- No traffic interception was performed (by design/instruction) — the endpoint list is from static
  strings plus observed settings behavior, not confirmed live requests. A string present in
  `classes*.dex` is evidence the code path exists, not proof it's still reachable or actively used
  today (the parallel `.com`/`.net` endpoint sets suggest at least one is a fallback).
- `ACCESS_BACKGROUND_LOCATION` was denied on the test device during this session, so the app's most
  background-aggressive alert path was not actually exercised live — the push/latency claims rest
  on the manifest + the vendor's own FAQ, not a measured delivery-time comparison against TerraWatch.
- The specific ad creatives shown (PlatinumRx interstitial, Meesho/Olymptrade banners) are
  session/targeting-dependent and not a stable fact about the app — the stable, reportable fact is
  the **placement pattern** (banner on list/detail, interstitial on navigating into detail), not the
  brands.
- Bundled SDKs (Sentry, including a "session-replay" module string) indicate telemetry *capability*
  is present; this research did not attempt to confirm whether session replay is actually enabled
  at runtime (would require reversing init logic, outside this task's read-only-strings scope).
- All screenshots reflect one live snapshot (2026-08-21, India-region feed via this OnePlus 9R) —
  the specific quakes/magnitudes shown are whatever was live at capture time, not representative of
  a "typical" feed.
- Play Store's own listing page did not render via automated fetch (JS-heavy SPA); official
  rating/install counts were not obtained — Similarweb's category/rank was used instead as a
  lower-confidence substitute.

## Sources

- [My Earthquake Alerts — Google Play](https://play.google.com/store/apps/details?id=com.jrustonapps.myearthquakealerts&hl=en_US)
- [My Earthquake Alerts | jRustonApps](https://www.jrustonapps.com/apps/my-earthquake-alerts)
- [jRustonApps Android notifications FAQ](https://www.jrustonapps.com/faqs/android-notifications/index.php?app=5)
- [My Earthquake Alerts Pro — Apple App Store](https://apps.apple.com/us/app/my-earthquake-alerts-pro/id975770717)
- [My Earthquake Alerts & Feed — Apple App Store](https://apps.apple.com/us/app/my-earthquake-alerts-feed/id975709372)
- [Similarweb — My Earthquake Alerts category/rank](https://www.similarweb.com/app/google-play/com.jrustonapps.myearthquakealerts/statistics/)
- [My Earthquake Alerts Pro — AppBrain](https://www.appbrain.com/app/my-earthquake-alerts-pro/com.jrustonapps.myearthquakealerts)
- [My Earthquake Alerts — Uptodown (version history)](https://my-earthquake-alerts.en.uptodown.com/android)
- Device commands: `adb shell pm path`, `adb pull`, `aapt dump badging`, `apktool d -s`,
  `unzip`/`strings` over `classes.dex`–`classes5.dex`, `adb shell dumpsys package`,
  `adb shell uiautomator dump`, `adb shell screencap` — OnePlus 9R, 2026-08-21.
- This repo: `store-assets/listing.md`, `docs/superpowers/specs/2026-08-08-terrawatch-design.md`,
  `core/data/.../AlertDigestSupport.kt`, `composeApp/.../detail/DetailSheet.kt`,
  `composeApp/.../history/HistoryViewModel.kt`.
