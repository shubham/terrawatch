# Plan 4 Backlog

Accumulator for Plan 4 (monetization + notifications + release + external-review items). Scope directive in force: **Android mobile only** — desktop/web deferred; real-device (98bc1cd8) verification only.

## From external team review (2026-08-10)

1. **Android 36 support audit** — app already compiles/targets SDK 36; audit runtime behaviors that API 36 enforces: edge-to-edge (statusBars insets done — sweep remaining screens), predictive back gesture (opt-in state + animations), foreground-service/notification changes relevant to Plan 4's alerts, permission-flow changes. Runtime verification constraint: physical device is not on Android 16 — best-effort API-36 emulator smoke (black-map caveat documented) or defer runtime pass until a 16 device exists; compile/manifest audit is fully doable.
2. **Insights data density** ("not enough data for insights") — Insights aggregates only the local cache (24h feed + whatever History paging pulled). Add USGS FDSN `/count` endpoint backfill (free, no key, aggregate counts without storing rows) to give the 30d window real density, plus honest "based on N cached quakes" caption. Alternative considered: bulk-ingest 30d of rows (heavy). Decide at plan-writing.
3. **News for major earthquakes (detail sheet)** — spike GDELT DOC API (free, no key, full-text news search): query = place tokens + "earthquake", window = event time +72h, show top 3 headlines w/ source + link in DetailSheet for M5.5+ events. Fallback if GDELT quality poor: USGS event-page link row ("More on USGS →") — zero-dependency honest v1.
4. **Global earthquake news** — same GDELT source, "In the news" card in Insights (top headlines for M6+ last 7d). Ship only if item 3's spike validates quality.
5. **Share targets: WhatsApp / X / Threads** — DetailSheet quick-share row with package-targeted ACTION_SEND intents (com.whatsapp, com.twitter.android, com.instagram.barcelona) + graceful fallback to system chooser when app absent. Keep existing chooser button.

## Carried from Plans 2/3 ledgers

### Deferred features / technical debt

- **Antimeridian ring MultiPolygon split** (Task 7) — map ring for nearby radius wraps at ±180° longitude. Characterization test may not flip red if fix is a wrapper — implementer note.
- **Map desaturation offline** (Task 10, spec §4.4) — offline banner says map desaturates; no desaturation hook found in maplibre-compose-android vector basemap (javap-verified), only for raster layers; revisit on maplibre lib upgrade.
- **Launch-time location permission before onboarding step 2** (Task 2/8) — OS permission dialog fires over step 1 before step 2's in-context ask. Defer launch-time request until step 2 or Settings trigger; coordinate with notification permission flow.
- **SavedState story** (Tasks 4, 9) — desktop/wasm synthetic SavedStateRegistryOwner use no-persistence design; Android Activity scope works; revisit when desktop persistence (e.g., camera state) planned.
- **FallbackMapPane offline desaturation** (Task 10) — JVM/wasmJs offline map render explicitly deferred per Android-only directive; remove code/tests when next running FallbackMapPane full-platform pass.

### Minor items + test gaps (traceability only)

- **HistoryViewModel** (Task 5) — stale "loadedCount" wording in one test assertion message (minor doc fix).
- **QuakeCard / DetailSheet distance inconsistency** (Tasks 6/10/13) — Insights and History both hardcode `distanceKm=null` on card/detail renders (v1 scope); they show em-dashes where Home shows distances — inventory added to Concerns; design decision documented as intentional v1 boundary.
- **Insights bar chart time-boundary cue** (Task 6) — "today" bar in 30d view lacks "so far" label (partial-day semantics); UTC-day boundary disclosed (IST "today" flips 05:30 local).
- **All-null-magnitude window** (Task 6) — edge case where all cached quakes lack magnitude data — traced correct in code, path untested (rare; real USGS/FDSN data always includes mag).
- **SymbolLayer root cause** (Task 11) — cluster-label params (textField, textFont, textColor) guessed on first attempt instead of from spec; full params javap-verified on maplibre-compose-android-0.14.0.aar before ship.
- **Status pill TalkBack double-read** (Task 10 fix round 1) — mergeDescendants on Surface pulled nested MagnitudeBadge description into pill's merged announcement; fixed via clearAndSetSemantics on badge, verified via RED-then-GREEN on real device (98bc1cd8).
- **Insights label drift** (Task 6 fix round 1) — Content bucket computed at render time; day labels derived from live 30s ticker on separate re-renders; fixed by storing `nowBucketAtCompute` in UiState, threaded to label function — true guarantee now holds.
- **Insights invalidation fan-out** (Task 6 fix round 1) — `.conflate()` added to recentQuakes drop(1) collector to batch SQLDelight's table-level invalidation fan-out (not row-level); behavior-preserving optimization.
- **Strongest quake tiebreak** (Task 6 fix round 1) — `ORDER BY mag DESC, timeMillis DESC LIMIT 1` added to SQL for deterministic tie-breaking (was relying on incidental SQLite row order).
- **setFilter mid-flight cancellation** (Task 5) — user-triggered filter change during loading cancels in-flight request per flatMapLatest semantics; traced correct, untested (normal flow; edge case on slow devices).
- **Double.toString cross-target** (Task 5) — HistoryPager.stableKey() relies on Double.toString() consistency for 3 literal filter values (minMag); JVM-only observable, enumerated all combos (~3x3 space).
- **InMemoryQuakeStore recent() conflation** (Task 9) — in-memory store emits on duplicate identical content writes where SQLDelight defers (table-level invalidation); doc-precision discrepancy, behavior functional.
- **Release-build debug-hook stripping** (Tasks 12/13) — debug inject functions (injectDebugQuake, ingestDebugBypassingDedupe, purgeDebugQuakes, isDebuggableBuild) remain unobfuscated in release APK; R8 minification deferred to Plan 4 (current: no ProGuard rules yet).
- `alertEvents` buffer-16 tryEmit drops + no replay — revisit when notifications land (Plan 4 core).

## Core Plan 4 (from spec §8/§10)

- RevenueCat purchases-kmp + TerraWatch Plus IAP + paywall; AdMob anchored banner (Android, Plus-inactive only, hidden on detail); ad-ethics rules.
- WorkManager digest alerts + notification permission flow (honest framing).
- app-ads.txt (GitHub Pages), Play listing assets, closed-testing track, Shipaton submission kit (2-min video, icon, screenshots).
