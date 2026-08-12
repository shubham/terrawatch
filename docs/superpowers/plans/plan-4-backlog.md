# Plan 4 Backlog

Accumulator for Plan 4 (monetization + notifications + release + external-review items). Scope directive in force: **Android mobile only** — desktop/web deferred; real-device (98bc1cd8) verification only.

## From external team review (2026-08-10)

1. **Android 36 support audit** — app already compiles/targets SDK 36; audit runtime behaviors that API 36 enforces: edge-to-edge (statusBars insets done — sweep remaining screens), predictive back gesture (opt-in state + animations), foreground-service/notification changes relevant to Plan 4's alerts, permission-flow changes. Runtime verification constraint: physical device is not on Android 16 — best-effort API-36 emulator smoke (black-map caveat documented) or defer runtime pass until a 16 device exists; compile/manifest audit is fully doable.
2. **Insights data density** ("not enough data for insights") — Insights aggregates only the local cache (24h feed + whatever History paging pulled). Add USGS FDSN `/count` endpoint backfill (free, no key, aggregate counts without storing rows) to give the 30d window real density, plus honest "based on N cached quakes" caption. Alternative considered: bulk-ingest 30d of rows (heavy). Decide at plan-writing.
3. **News for major earthquakes (detail sheet)** — spike GDELT DOC API (free, no key, full-text news search): query = place tokens + "earthquake", window = event time +72h, show top 3 headlines w/ source + link in DetailSheet for M5.5+ events. Fallback if GDELT quality poor: USGS event-page link row ("More on USGS →") — zero-dependency honest v1.
4. **Global earthquake news** — same GDELT source, "In the news" card in Insights (top headlines for M6+ last 7d). Ship only if item 3's spike validates quality.
5. **Share targets: WhatsApp / X / Threads** — DetailSheet quick-share row with package-targeted ACTION_SEND intents (com.whatsapp, com.twitter.android, com.instagram.barcelona) + graceful fallback to system chooser when app absent. Keep existing chooser button.

## Carried from Plans 2/3 ledgers

- Antimeridian ring MultiPolygon split (characterization test may not flip red if fix is a wrapper — implementer note).
- Launch-time location permission fires over onboarding step 1 — defer request until step 2 / settings trigger.
- Release-build compilation of debug inject hook (minify/proguard decision).
- SavedState story (desktop/wasm synthetic owners are no-persistence; Android fine).
- `alertEvents` buffer-16 tryEmit drops + no replay — revisit when notifications land (Plan 4 core).
- InMemoryQuakeStore recent() conflation doc-precision.
- Map desaturation offline (spec §4.4) — no hook in maplibre vector basemap (javap-proven); revisit on lib upgrade.

## Core Plan 4 (from spec §8/§10)

- RevenueCat purchases-kmp + TerraWatch Plus IAP + paywall; AdMob anchored banner (Android, Plus-inactive only, hidden on detail); ad-ethics rules.
- WorkManager digest alerts + notification permission flow (honest framing).
- app-ads.txt (GitHub Pages), Play listing assets, closed-testing track, Shipaton submission kit (2-min video, icon, screenshots).
