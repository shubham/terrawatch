# TerraWatch — Play Store listing kit (Plan 4 Task 7)

Draft copy for the Play Console store listing. Everything below is a draft to paste into Play
Console at Task 8 (submission is USER-GATED on a Play Console account) — nothing here has been
submitted anywhere. Every factual claim (data sources, permissions, ads, purchases) was checked
against this repo's actual shipped code as of `feat/plan-4-release`, not written from memory — see
the inline citations.

## Title (≤30 chars)

> **TerraWatch: Earthquake Monitor**

30 characters exactly (verified with `len()`, not by eye — the brief's own suggested title,
"TerraWatch — Earthquake Monitor", is 31 and over the limit). Swapped the em-dash construction for
a colon and confirmed the count in Python:

```
>>> len("TerraWatch: Earthquake Monitor")
30
```

"Monitor" (not "Alerts") is deliberate — keeps the digest-not-early-warning honesty out front even
in the title, before a reader opens the full description.

## Short description (≤80 chars)

> **Live global earthquakes, honest digest alerts, and a full history archive.**

74 characters (verified with `len()`).

## Full description

```
TerraWatch shows you earthquakes happening around the world, right now — pulled live from USGS
and EMSC, the same public seismic feeds agencies and researchers rely on.

WHAT IT DOES
• Live map — every recent quake plotted globally, colored by magnitude, updating as new reports
  arrive.
• Digest alerts — set a home location and a radius, and TerraWatch checks in periodically (not
  instantly) to notify you when something crosses your threshold nearby, or when anything
  magnitude 6+ happens anywhere in the world. This is a digest, not an early-warning system:
  there's no guarantee of advance notice before shaking arrives, and no seconds-count alerting of
  any kind.
• History archive — browse the full quake record, filter by magnitude or year.
• Insights — trends at a glance: quakes per day, a magnitude breakdown, and the strongest event in
  the window you pick.
• Nearby radius — a ring on the map shows exactly what "nearby" means for your alerts, and you
  choose the distance.
• In the news — for major quakes, related headlines from public news sources, one tap away.

HONEST BY DESIGN
TerraWatch does not predict earthquakes and is not a substitute for official emergency alerts,
seismic early-warning systems, or guidance from your local authorities. It reports what public
seismic agencies have already recorded — use it to stay informed, not as your only source for
safety decisions.

PRIVACY
No account required. Your home location is stored only on this device and is never uploaded
anywhere — not even to check for nearby quakes (that check runs locally, against a global feed).

Built on public data: USGS and EMSC for quake feeds, OpenFreeMap/OpenStreetMap for map tiles,
GDELT for related news links.

TerraWatch is free, supported by ads (Google AdMob) shown between screens — never over the map,
and never during onboarding. TerraWatch Plus removes ads. More Plus features are in development.
```

Every claim above traces to real code, checked while drafting this file (not asserted from
memory):
- "USGS and EMSC" feeds: `core/network/.../UsgsApi.kt` (`earthquake.usgs.gov`) and
  `EmscLiveSource.kt` (`wss://www.seismicportal.eu/standing_order/websocket`) — matches the app's
  own About screen copy verbatim (`SettingsScreen.kt`'s `AboutContent`: `"Data sources: USGS ·
  EMSC"`).
- "checks in periodically (not instantly)" / "magnitude 6+ ... anywhere in the world": matches
  `AlertDigestWorker`'s real 45-minute periodic schedule (Plan 4 Task 3) and the app's own
  onboarding copy verbatim (`OnboardingScreen.kt`'s notifications step: `"M ≥ 4.5 within 100 km ·
  M ≥ 6 worldwide"` — device-verified on-screen this task, see task-7-report.md).
- "History archive ... filter by magnitude or year": matches `HistoryScreen.kt`'s real filter chips
  (All/M4.5+/M6+, year picker) — device-verified, `store-assets/screenshots/05-history.png`.
  "Insights ... quakes per day, magnitude breakdown, strongest event": matches `InsightsViewModel`
  / `InsightsScreen.kt` exactly — `store-assets/screenshots/03-insights.png`.
- "a ring on the map shows exactly what 'nearby' means": the real radius-ring map overlay —
  `store-assets/screenshots/04-settings-radius-ring.png`.
- "In the news ... related headlines": `GdeltClient.kt` / `DetailNewsViewModel` /
  `InsightsNewsViewModel` (Plan 4 Task 5).
- "home location is stored only on this device ... never uploaded, not even to check for nearby
  quakes": verified by reading `UsgsApi.kt`'s real method signatures (`fetchFeed`, `queryArchive`,
  `queryCount`) — none take a latitude/longitude parameter. The feed fetch is always global by
  time range; "nearby" filtering happens entirely client-side against the locally stored
  `HomeLocation`. This is a checked fact, not an assumption.
- "OpenFreeMap/OpenStreetMap for map tiles": matches the app's own About screen verbatim (`"©
  OpenStreetMap contributors"` / `"Map data © OpenFreeMap"`).
- "ads (Google AdMob) ... never over the map, and never during onboarding": Plan 4 Task 6's
  `adSlotVisible` rule (TDD'd full 2³ truth table) and its own device verification
  (`docs/qa/plan-4-device-matrix/task6-*.png`).
- "TerraWatch Plus removes ads. More Plus features are in development": matches the paywall's
  honest `PLUS_BENEFITS` display — "Remove ads", plus items 2–3 marked "(coming soon)" per
  `PaywallScreenTest` (Task 7 fix round).

## Category

**Recommend: Weather** (not Maps & Navigation).

Why: Maps & Navigation on Play Store is a routing/wayfinding category (turn-by-turn directions,
transit, points-of-interest search) — TerraWatch's map is a data-visualization surface, not a
navigation tool, and nothing in the app helps a user get from A to B. TerraWatch's actual core
loop — a live conditions feed, threshold-based digest alerts, trend/insights charts, "nothing
nearby" reassurance copy — is the same shape as weather-alert apps, and real-world precedent bears
this out: EMSC's own official app ("LastQuake") and other third-party earthquake-monitoring apps
are conventionally listed under Weather on Play Store, not Maps & Navigation. Recommend Weather as
primary category.

## Content rating questionnaire — draft answers

Reconfirm at actual Play Console submission time (Task 8) against whatever the build's real final
state is then — flagged below wherever today's answer could change.

| Question (IARC-style) | Draft answer | Basis |
|---|---|---|
| Violence | None | No violent content of any kind; the app shows factual magnitude/location data only. |
| Sexual content / nudity | None | N/A |
| Profanity / crude humor | None | N/A |
| Controlled substances (alcohol/tobacco/drugs) | None | N/A |
| Gambling (real or simulated) | None | N/A |
| User-generated content shared with other users | None | No accounts, no chat, no social/sharing-to-other-users feature (the share row shares OUT to WhatsApp/X/Threads via the OS share sheet — that's outbound to apps outside TerraWatch, not user-generated content exchanged between TerraWatch users). |
| Shares user location with other users | No | Single-user, on-device only; no server sync, no accounts. |
| Digital purchases | **No purchases are live today** — `PaywallScreen` (Task 6) is a static stub with a disabled "Purchases available soon" button; `purchases-kmp-ui` is deliberately not wired yet (Task 6 report, §"Concerns"). **Reconfirm before submission** — if Task 8 wires a real RevenueCat product before the actual Play Console upload, this answer flips to Yes and the questionnaire + data-safety form both need the IAP disclosure. |
| Ads | **Yes** | Real `play-services-ads` (Google AdMob) SDK is live today, currently on Google's official TEST ad unit IDs (Task 6, device-verified real TEST creatives + logcat GMS ad-service bind). This is true regardless of test-vs-real unit IDs — the SDK itself runs and this answer doesn't change at Task 8's real-ID swap. |

## Data safety form — draft answers

Same reconfirm-before-submission caveat as above. Categories follow Play Console's own Data
Safety form structure.

**Does your app collect or share any of the required user data types?** — Yes (location,
approximate; device/advertising identifiers via the ads SDK). See below.

| Data type | Collected? | Shared with 3rd parties? | Notes |
|---|---|---|---|
| **Location — Approximate** | Used, not "collected" under Play's own definition (never leaves the device) | No | `ACCESS_COARSE_LOCATION` powers home-location detection ("Use my location" in onboarding/Settings). Stored locally only (`HomeLocationStore`/Room-SQLDelight). Verified fact, not assumed: `UsgsApi`'s real fetch methods take no lat/lon parameter — the feed is fetched globally and filtered client-side, so home coordinates are never transmitted anywhere, not even to USGS/EMSC. Recommend disclosing it anyway on the form for full transparency even though Play's strict "collection = leaves the device" definition would let this be omitted. |
| **Personal info** (name, email, user IDs, etc.) | No | No | No accounts anywhere in the app (grep-verified: no login/auth/OAuth code exists). |
| **Financial info** | No, today | No, today | No purchases are live yet (see content-rating table above). **Reconfirm at Task 8** — if a real RevenueCat product goes live before submission, Google Play Billing purchase data is then handled by Play Billing/RevenueCat under their own standard IAP data-safety disclosures, which this form would need to add. |
| **App activity** (in-app actions, search history, etc.) | No | No | All app state (saved place, alert rules, theme, quake cache) stays in local Room/SQLDelight storage; nothing is transmitted to a TerraWatch-controlled server (there is no backend — this app talks only to USGS/EMSC/OpenFreeMap/GDELT's own public endpoints, plus Google's ad servers). |
| **Device or other identifiers** | Yes, when ads render | Yes — shared with Google (AdMob) | Standard for any app carrying Google Mobile Ads SDK: advertising identifiers are collected/shared with Google for ad serving and measurement whenever the banner slot is visible (`adSlotVisible` rule — hidden during onboarding and while the detail sheet is open). This is live today (Task 6), not a Task 8 future item — the SDK behavior is identical on TEST vs. real ad unit IDs. |
| **Web browsing / search history, financial account numbers, health, messages, etc.** | No | No | Not applicable — TerraWatch has no such features. |

Additional honest notes for whoever fills the real form at Task 8:
- **Data deletion**: nothing to request deletion of on a server, since nothing user-specific is
  ever sent to one. Uninstalling the app deletes all local data (standard Android behavior, no
  special handling needed).
- **Encryption in transit**: all real network calls (USGS, EMSC, OpenFreeMap, GDELT) run over
  HTTPS/WSS. Verify `network_security_config.xml` (`composeApp/src/androidMain/res/xml/`) still
  matches this claim at submission time if it changes.
- **Data is not required for basic app functionality beyond location** — the app is fully usable
  with location permission denied (per Plan 4 Task 4's permission-correctness work: "Use my
  location" is opt-in, city-picker is a no-location alternative, and the world M6+ alert rule
  needs no home location at all).
- **AD_ID permission, confirmed in the real merged manifest (Task 6 fix round)**:
  `com.google.android.gms.permission.AD_ID` (plus `android.permission.ACCESS_ADSERVICES_AD_ID`) is
  present in `composeApp`'s actual merged manifest today — checked directly against
  `processDebugManifest`/`processReleaseManifest`'s real output this fix round, not assumed from
  `play-services-ads`'s own docs. This app declares neither permission itself; `play-services-ads`
  merges both in transitively (standard for any app carrying the Google Mobile Ads SDK). This is
  exactly why the **Device or other identifiers** row above answers "Yes, when ads render / shared
  with Google (AdMob)" — Play Console's own policy review cross-checks a manifest that requests
  `AD_ID` against the Data Safety form's "Advertising ID" collection answer, so leaving that row as
  "No" while this permission is present would be a real submission-time policy mismatch, not just an
  incomplete disclosure. Noted here so whoever fills the real form at Task 8 knows this is a required
  answer, not a discretionary one.

## Screenshots — picks and rationale

6 picked from the existing device matrices (`docs/qa/plan-3-device-matrix/`,
`docs/qa/plan-4-device-matrix/`) — copied as-is into `store-assets/screenshots/`, unframed/
unannotated per this task's own brief ("raw honest screenshots fine for v1"). All real device
captures (98bc1cd8), 1080×2400.

| File | Source | Why this one |
|---|---|---|
| `01-home-map.png` | `plan-3-device-matrix/task13-home-after-onboarding-tokyo.png` | The core "live map" pitch: world map, 2 real magnitude-colored pins, "All calm near you" reassurance pill, live feed list underneath with real quake rows and a "269 NEW" badge — the single best one-shot summary of what the app does. Other home candidates were rejected: `plan-4-device-matrix/task4-home-after-onboarding.png` has a black/unrendered map tile area (a real capture glitch, not representative); `plan-3-device-matrix/task6-home-after-insights-roundtrip.png`'s map has an odd dark cluster marker near Turkey that reads as a rendering artifact at thumbnail size. |
| `02-detail-sheet.png` | `plan-3-device-matrix/task13-detail-sheet-from-feed-card.png` | Shows the detail sheet's real depth: magnitude, "how long ago", depth/distance/felt-it stat row, the honest "Tsunami not expected" line, Share/Dismiss actions — demonstrates the app goes well beyond a bare pin on a map. |
| `03-insights.png` | `plan-3-device-matrix/task13-insights-30d.png` | Clean, information-dense: quakes-per-day chart, magnitude breakdown bars, a real "STRONGEST" card (M7.4). Chosen over the 7-day variant since 30 days gives the chart more visible shape. |
| `04-settings-radius-ring.png` | `plan-3-device-matrix/task7-ring-500km.png` | The one screenshot that visually explains "nearby" — a translucent green 500 km ring around a home location, with a quake marker inside it and the matching "All calm near you · Nothing within 500 km" pill. Directly supports the description's "nearby radius" bullet. |
| `05-history.png` | `plan-3-device-matrix/task13-history-initial.png` | Shows the filter chips (All/M4.5+/M6+, year picker) and a real populated month section — proves the "full history archive" claim isn't just a single list. |
| `06-notification.png` | `docs/qa/plan-4-device-matrix/task7-notification-new-icon.png` | Captured fresh this task (not reused from an earlier one) specifically so the notification's small icon shown is the REAL Task 7 icon (`ic_stat_terrawatch`), not Task 3's placeholder. Real fired digest notification, expanded, showing 3 genuine M6+ world quakes with the honest per-item copy "matches your worldwide M6+ alert rule" — demonstrates both the alerts feature and the new icon in one shot. |

**Known limitation, disclosed rather than silently fixed**: all 6 screenshots are 1080×2400
(ratio ≈2.22:1), which is very slightly over Play Console's documented 2:1 max screenshot aspect
ratio. This task's own brief says framing/cropping is optional/skippable for v1 ("raw honest
screenshots fine"), so they're committed as-is; a ~120px crop off the top or bottom of each would
bring them to exactly 1080×2160 (2:1) if Play Console rejects the raw ratio at actual upload time
(Task 8).

## Store icon and feature graphic

- `store-assets/icon-1024.png` — 1024×1024, same shield/ring/dot composition as the real adaptive
  launcher icon, just unmasked (Play's own guidance: the hi-res icon should look like the launcher
  icon). **Note**: Play Console's actual upload spec for this asset is 512×512 — downscale this
  1024 master at upload time; keeping the source at 1024 is just good practice for a crisper
  downscale later.
- `store-assets/feature-graphic.png` — 1024×500, Water background, "TerraWatch" in Ink bold,
  tagline "Know the ground beneath you" (the app's own onboarding step-1 copy, reused verbatim for
  consistency), the shield/ring/dot mark on the left, 3 scattered magnitude-colored dots
  (Low/Moderate/Strong) as decoration.
