# TerraWatch — Design Document

**Date:** 2026-08-08 · **Status:** Approved for planning · **Package:** `com.yugma.terrawatch`

---

## 1. What we're building

TerraWatch is a Kotlin Multiplatform earthquake monitor that answers two questions: **"Am I safe?"** and **"What's shaking around the world?"** It shows live earthquakes on a full-screen map the moment they happen, keeps a browsable archive going back decades, and quietly notifies you when something significant happens near a place you care about.

It is built with Compose Multiplatform for **Android, Desktop, and Web** (iOS later — the code is ready for it, the $99/year Apple fee is not). The entire stack costs nothing to run: free government seismic APIs, free map tiles, free tooling.

The app is also our entry for **RevenueCat Shipaton 2026** (submission window: Aug 1 – Sep 30, 2026), which shapes two things: the deadline and the monetization layer (AdMob banner + a "TerraWatch Plus" in-app purchase, both wired through RevenueCat).

### Why this app is different from every quake app on the store

1. **Live, not stale.** An EMSC WebSocket pushes new quakes into the UI within seconds. Most competitor apps poll a feed every few minutes and look dead.
2. **Honest about revisions.** Agencies revise magnitudes after publication (M 5.9 often becomes M 6.1 an hour later). TerraWatch stores every revision and shows a "revised from…" badge instead of pretending the first number was final.
3. **Calm, not alarmist.** The design language (we call it *Calm Guardian*) reassures by default and alerts only when warranted. No red sirens for a M 4.0 across the planet.

---

## 2. Decisions at a glance

| Decision | Choice | Why |
|---|---|---|
| Framework | Kotlin Multiplatform + Compose Multiplatform | User preference; JetBrains "Ship Kotlin Everywhere" Shipaton category; shared logic across 3+ platforms |
| v1 platforms | Android, Desktop (JVM), Web (Wasm) | iOS deferred — avoids $99/yr until the app earns it |
| Architecture | Full multi-module (`core:*` / `feature:*`) | Clean test boundaries; TDD lives in Compose-free core modules |
| Quake data | USGS (feeds + FDSN archive) + EMSC (WebSocket) | Both free, no keys, no rate-limit pain; EMSC gives the "live" wow |
| Map | maplibre-compose + OpenFreeMap vector tiles | Free, no key, no card, commercial use allowed; OSM-based |
| Local store | SQLDelight | KMP-native, typed SQL, drivers for all our targets |
| Networking | Ktor client + kotlinx.serialization | KMP standard; WebSocket support built in |
| DI | Koin | Lightweight, KMP-proven |
| Navigation | androidx navigation-compose (KMP) | Official, matches Compose-first direction |
| Monetization | AdMob banner (Android) + TerraWatch Plus IAP via RevenueCat `purchases-kmp` | Shipaton requirement; ads free-tier friendly; Plus removes ads |
| Alerts v1 | On-device polling (WorkManager) + local notifications | Zero backend; honest "digest, not early-warning" framing |
| Alerts v1.5 | GitHub Actions cron → FCM push | Designed now (same rule engine), shipped after v1 |
| Name | TerraWatch | "Epicenter" apps already crowd the store; watch = monitor + guardian |

---

## 3. The product

### 3.1 Surfaces

Three tabs + two modals. The old "Map tab" doesn't exist — the map *is* the home screen.

```
Home      full-bleed live map · floating status pill · swipe-up feed sheet
History   searchable archive of decades of quakes, filterable
Insights  charts: quakes/day, magnitude distribution, strongest-of-period
────
Detail    bottom sheet over the map (side panel on wide screens)
Settings  alert rules, saved places, units, theme, about
```

### 3.2 Home — the map-first screen

Layer order, bottom to top:

1. **MapLibre map**, OpenFreeMap tiles re-tinted to our palette. Quake pins are a symbol layer: size scales with magnitude, color follows the magnitude scale, low zoom clusters. A brand-new quake (from the WebSocket) drops in with a spring animation and two expanding pulse rings — the signature moment of the app.
2. **Status pill** (floating, glass): green ✓ "All calm near you — nothing within 500 km in 24 h", or amber/red with the nearest significant event. Tap → that quake's detail. If location is unknown, the pill becomes a friendly two-button ask (see Error states).
3. **Feed sheet** (draggable): snap points at 30% / 55% / 92%. Header shows "LIVE" with a pulsing dot while the WebSocket is connected, plus "1 NEW" badge when something arrived while collapsed. Rows are `QuakeCard`s: magnitude badge, place, relative time, depth, distance-from-you.
4. **Ad slot** (Android only, Plus inactive only): anchored adaptive banner directly above the nav bar. Hidden while the detail sheet is ≥50% expanded and during onboarding.

### 3.3 Detail

Opens as a sheet over the dimmed map (the pin stays visible above it). Contains: magnitude hero block, place + absolute/relative time, **revision badge** ("revised from M 5.9 · 12 min ago"), stat trio (depth · distance · felt reports), tsunami banner (green "not expected" or red advisory straight from the feed flags), coordinates, source attribution ("USGS · confirmed by EMSC"), shaking intensity (MMI roman numeral when available), Share / Save / Directions.

On desktop and wide web this renders as a right-side panel instead of a sheet.

### 3.4 History

Search box (region text), filter chips (magnitude ≥, near-me radius, year, depth), sticky month headers, infinite scroll. Powered by paged USGS FDSN queries with a time cursor; every fetched page lands in the local DB, so anything you've browsed works offline. Row tap → same Detail surface.

### 3.5 Insights

Period toggle 7d / 30d (a 1y option ships in v1.5 — it needs a one-time archive backfill). Three cards: quakes-per-day bars, magnitude-distribution bars, strongest-of-period highlight. Everything computed by SQL over the local cache — zero extra API calls, fully offline-capable.

### 3.6 Onboarding (first run)

Three lightweight steps, skippable: (1) what the app does — one screen, no carousel-of-five; (2) location ask with equal-weight "Choose city manually" path; (3) notification permission ask *with the default rule shown* ("M 4.5+ within 500 km, M 6+ worldwide — change anytime"). Deniers get full app minus alerts. No account, ever.

---

## 4. Design system — "Calm Guardian"

### 4.1 Personality

Soft, reassuring, competent. A weather app's warmth applied to a scary subject. The user's parents should be able to read it.

### 4.2 Tokens

```
Ink (text)        #17222E          Canvas         #F6FAF9
Water (map)       #D9E9F4          Land (map)     #EFF3EC
Safe green        #2FA36B          Shield gradient #DDF3E6 → #C9EAD8
Info blue         #5C8DB8          Warning amber   #B08A2E on #FCF3DD

Magnitude scale   < 3.0   #59B87D   green
                  3.0–4.5 #F5A524   amber
                  4.5–6.0 #F0663B   orange
                  ≥ 6.0   #C43A2F   red

Dark ("Dusk")     canvas #10161D · cards #1A222C · accents desaturated ~15%
Radii             cards 16 · sheets 22–24 · pills 99
Shadow            rgba(20,40,60,.08), soft, never harsh
Type              system sans; magnitude numerals bold + tabular figures
```

**Color-blindness rule:** magnitude color never carries meaning alone — the badge always shows the number.

### 4.3 The three 2026 rules (from trend research, adopted deliberately)

1. **Glass discipline** (Liquid Glass, applied with restraint): translucent blur *only* on floating overlays — status pill, offline banner, bottom nav, sheet grabber-header. Content cards stay flat white. Glass never sits on glass.
2. **Expressive motion** (Material 3 Expressive): spring physics on sheet snaps and pin drops; the status shield **shape-morphs** between states (calm circle → alert rounded-square); micro scale-in when a magnitude badge revises. A "reduce motion" setting (and the OS-level flag) disables all of it.
3. **Every non-happy state ends in a CTA** — see 4.4.

### 4.4 Error & empty states (all mocked in `docs/design/mockups/error-states.html`)

| State | Treatment |
|---|---|
| Offline | Glass banner "You're offline · showing data from 23 min ago" + Retry link. Map desaturates. Cache stays fully browsable — the app never dead-ends. |
| API down (online) | Friendly illustration, plain-language cause ("USGS isn't responding"), auto-retry with visible countdown + manual "Try again", previously loaded rows remain visible, dimmed. |
| Location denied/unset | Status pill morphs into a calm ask: "Where are you?" with two equal buttons — **Choose city** (manual picker; the default on desktop/web) and **Allow location**. World feed unaffected. Never nags again unless invoked from Settings. |
| No results (History) | Calm face illustration, light copy ("That's good news for penguins"), and a **smart suggestion chip** computed from what *would* return results ("Try M 5+") next to "Clear filters". |
| First load | Skeleton shimmer cards. Never a spinner on a blank screen. |
| WebSocket dropped | Tiny "live paused — reconnecting" chip in the feed header; polling continues; chip disappears on reconnect. |
| Detail fetch failure | Sheet opens with cached fields + an inline retry row for the missing ones. |

Error codes never appear on screen; they go to logs. Copy pattern everywhere: **what happened + what still works + one action.**

### 4.5 Accessibility

Map is never the sole source of information (the list mirrors it). Contrast AA for all text tokens. Touch targets ≥ 48 dp. TalkBack/VoiceOver labels read naturally ("Magnitude 6.1, Mindanao, Philippines, 2 minutes ago, 10 kilometers deep"). Reduced-motion respected.

---

## 5. Architecture

### 5.1 Modules

```
terrawatch/
├── build-logic/          # convention plugins so 13 modules don't repeat config
├── core/
│   ├── model/            # Quake, AlertRule, SavedPlace, Region — pure Kotlin
│   ├── network/          # Ktor: UsgsFeedClient, UsgsFdsnClient, EmscWsClient
│   ├── database/         # SQLDelight schema + drivers per target
│   ├── data/             # QuakeRepository, DedupeEngine, AlertRuleEngine
│   ├── ui/               # Calm Guardian theme, shared components, charts
│   ├── ads/              # expect BannerAd · android=AdMob · desktop/web=no-op
│   └── monetization/     # expect Entitlements · android=RevenueCat purchases-kmp
│                         #   · desktop/web=always-free no-op
├── feature/
│   ├── home/             # map + pill + sheet
│   ├── detail/           # quake detail (sheet/panel)
│   ├── history/          # archive browser
│   ├── insights/         # charts
│   └── settings/         # rules, places, theme, about
└── composeApp/           # nav graph, Koin modules, entry points:
                          #   androidApp / desktopApp / wasmJsApp
```

**Dependency rule (one direction, enforced):**

```
composeApp → feature:* → core:data, core:ui
core:data  → core:model, core:network, core:database
core:ui    → core:model
feature:*  never import each other; Detail is reached by nav route
```

Everything in `core:*` except `ui` and `ads` is Compose-free and tests as plain JVM — that's where TDD lives and where most of the logic sits.

### 5.2 State pattern

One ViewModel per feature (androidx lifecycle KMP), exposing `StateFlow<UiState>` where `UiState` is sealed: `Loading / Content / Empty / Error(cause, retry)`. Every screen renders all four; no screen may render a blank.

---

## 6. Data layer

### 6.1 Sources

| Source | Role | Mechanics |
|---|---|---|
| USGS realtime feed (`all_day.geojson`) | Home feed + Insights-7d | Foreground poll every 60 s with `If-None-Match`; HTTP 304 = free no-op |
| USGS FDSN query API | History archive | Time-cursor paging: `endtime` = oldest loaded, `limit=200`, `orderby=time`; filters map to `minmagnitude`, bbox/radius params; cursor stored per filter-set |
| EMSC WebSocket (`/standing_order/websocket`) | Live push while foregrounded | Auto-reconnect with exponential backoff + jitter; on reconnect, one feed poll fills the gap |

### 6.2 Dedupe engine (the interesting pure function)

The same quake arrives from both agencies with different IDs and slightly different numbers. Match rule: **origin time within ±90 s AND epicenters within 100 km (haversine)**. On match, merge:

- canonical id = USGS id if present, else EMSC id
- `sources` keeps both agency ids
- magnitude preference: reviewed > USGS > most recent
- every magnitude change appends to `revisions` — this list feeds the "revised from M 5.9" badge

Heavily unit-tested: boundary times, antipodal near-misses, triple-report storms, out-of-order arrival.

### 6.3 Schema (SQLDelight, v1)

```sql
quake(id TEXT PK, time INTEGER, lat REAL, lon REAL, depthKm REAL,
      mag REAL, magType TEXT, place TEXT, tsunami INTEGER, felt INTEGER,
      status TEXT, sources TEXT /*json*/, revisions TEXT /*json*/,
      updatedAt INTEGER, fetchedAt INTEGER)
alert_rule(id, minMag REAL, radiusKm REAL?, lat REAL?, lon REAL?, enabled INTEGER)
saved_place(id, label TEXT, lat REAL, lon REAL)
meta(key TEXT PK, value TEXT)   -- etags, history cursors, poll stamps
```

### 6.4 Flow of truth

UI collects `Flow<List<Quake>>` **from the DB only**. Network writers (feed poller, FDSN pager, WS handler) all funnel into one upsert that is revision-aware: newer `updatedAt` wins. A WebSocket event therefore animates onto the map through exactly the same path as a polled row — no special cases.

### 6.5 Alerts

Every upsert diff passes through `AlertRuleEngine` (pure, in `core:data`). Default rule: **M ≥ 4.5 within 500 km of home, OR M ≥ 6 anywhere.** Matches emit `AlertEvent`; androidMain renders local notifications (WorkManager runs the background poll every 30–60 min — honest framing: *digest, not early warning*). The same engine output is the future FCM payload for v1.5's GitHub-Actions push path; nothing gets redesigned.

---

## 7. Platform integrations

| Concern | Android | Desktop | Web (Wasm) |
|---|---|---|---|
| Location | FusedLocation, ask-on-first-use | manual city picker | browser geolocation prompt, picker fallback |
| Notifications | local notifications via WorkManager poll | none v1 | none v1 |
| Ads | AdMob anchored adaptive banner | — | — |
| IAP / Plus | RevenueCat `purchases-kmp` | entitlement honored read-only* | entitlement honored read-only* |
| Background work | WorkManager | — | — |

\* v1 simplification: Plus is purchasable on Android; desktop/web builds run ad-free by nature, and Plus-only features (extra saved places, custom rules) are unlocked there without purchase — acceptable while desktop/web have no billing.

**Web map caveat:** maplibre-compose's Wasm target is its weakest. If it can't render acceptably at build time, web v1 ships History/Insights/feed at full function and Home shows a static region snapshot with Compose-drawn pins. Android/desktop unaffected. Decision point lives in the implementation plan, not here.

---

## 8. Monetization & Shipaton

- **Free tier:** everything, with one anchored banner on Android.
- **TerraWatch Plus** (IAP via RevenueCat): removes ads, multiple saved places (free tier: 1), custom alert rules (free tier: default rule only). Paywall via `purchases-kmp-ui` (Compose Multiplatform paywall, one call).
- **RevenueCat wiring:** `purchases-kmp` v3+ for IAP; AdMob impressions reported through RevenueCat's ad-monetization `AdTracker` (purchases-android 8+, AdMob helper) so ads + IAP land in one revenue dashboard. Ad revenue doesn't count toward RevenueCat's billable MTR — free.
- **Ad ethics rule (hard):** banner hidden whenever a detail view is open (≥50% expanded) and during onboarding — no banner ever sits next to disaster detail content. No interstitials, no rewarded ads in a safety app.
- **Shipaton targets:** main submission + "Ship Kotlin Everywhere" (KMP/CMP) + "Catvertising" (creative RevenueCat Ads use). First public Play release must land inside Aug 1 – Sep 30, 2026.

**Known store risks, accepted:** new personal Play Console account requires a 12-tester × 14-day closed test before production — account creation and tester recruitment start immediately, parallel to development. AdMob needs `app-ads.txt` on a developer domain (GitHub Pages, free). Development proceeds regardless; submission mechanics are tracked in the plan, not blockers here.

---

## 9. Testing strategy

| Layer | What | How |
|---|---|---|
| `core:model`, `core:data` | dedupe engine, alert rules, distance math, cursor logic, revision merge | plain JVM unit tests, TDD, fast (< 5 s suite) |
| `core:network` | GeoJSON/FDSN/EMSC parsing against **recorded fixture files**, ETag handling, WS reconnect state machine | Ktor MockEngine + fixtures checked into repo |
| `core:database` | migrations, upsert semantics, aggregate queries for Insights | SQLDelight in-memory driver |
| `feature:*` ViewModels | state transitions incl. all four UiStates | Turbine over StateFlow, fake repositories |
| UI | QuakeCard/StatusShield render states; magnitude badge colors | Compose UI tests (android) + screenshot tests where cheap |
| E2E (thin) | cold start → feed visible; tap quake → detail; airplane-mode → offline banner | Maestro on Android, 3 flows max in v1 |
| Manual gate | ad behavior, paywall purchase in Play sandbox, notification delivery timing | pre-release checklist in repo |

CI (GitHub Actions, free for public repo): unit + fixture tests on every push; Android assemble + Compose UI tests on PR; screenshot diff job optional.

Non-negotiable: **TDD on `core:*`** — test first, red, then code (hook-enforced per house rules).

---

## 10. Scope

### v1 (ship inside Shipaton window)

Home (map + pill + sheet + live WS) · Detail · History · Insights-7d/30d · onboarding · settings (default rule editing, saved place, theme) · offline cache · error-state system · AdMob banner + Plus IAP via RevenueCat · WorkManager digest alerts · Android on Play + desktop binary + web build (map-fallback allowed).

### v1.5 (post-submission)

FCM push via GitHub Actions cron · EMSC as History fallback source · Insights-1y backfill polish · web live map when maplibre-compose Wasm matures · iOS target when $99 feels justified.

### Explicitly out (YAGNI)

Accounts/login · social features · earthquake *prediction* of any kind (scientifically indefensible) · crowdsourced "I felt it" reporting (v2 candidate, needs moderation) · iOS v1 · paid map providers · interstitial/rewarded ads.

---

## 11. Open questions (tracked into the plan)

1. maplibre-compose Wasm viability — spike in week 1, fallback specified in §7.
2. RevenueCat `AdTracker` from `purchases-kmp` vs. dropping to `purchases-android` API in androidMain — spike alongside ads integration.
3. Exact OpenFreeMap style JSON re-tint (palette above) — done during `core:ui` map work.
4. Personal GitHub identity (name/email) for repo-local git config — needed before first commit.

---

*Mockups: `docs/design/mockups/` (ui-direction, map-home-layout, detail-history-insights, error-states). Trend sources: Material 3 Expressive (Google I/O 2025+), Apple Liquid Glass (iOS 26), UXPin/figr/Mobbin error-state patterns, RevenueCat Shipaton 2026 rules (Devpost).*
