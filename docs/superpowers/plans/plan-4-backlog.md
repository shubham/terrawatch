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

## From Plan 3 final review

1. **DB retention policy** — `quake` table (`core/database/.../Quake.sq`) has no pruning/retention query, only per-id `delete` and the debug-only `deleteByIdPrefix`; every ingested row (live feed + full FDSN archive backfill via History's deep-scroll) accumulates forever. Product decision needed: History's "cached pages browse offline" contract (`QuakeRepository.loadArchivePage`'s own kdoc) implicitly wants old rows kept around, which is in tension with any future pruning — decide the retention window (if any exists) against that offline contract before unbounded growth becomes a real on-device storage/query-latency concern.
2. **Archive ingest must not feed alert evaluation before notifications land** — `QuakeRepository.loadArchivePage` (History's deep-scroll backfill) runs every paged-in row through the same `ingest()` → `AlertRuleEngine.evaluate()` path the live/refresh loop uses (see that function's own kdoc, which already flags this). Harmless today because nothing consumes `alertEvents` yet — but the instant Plan 4 wires real notifications off that stream, a user deep-scrolling History past old M6+ quakes will notification-storm on events that are years old. `AlertRuleEngine` has no recency window of its own (only `minMag`/`radiusKm`); needs one before notifications land, or `loadArchivePage` needs to stop evaluating alerts for backfilled rows entirely.
3. **M4: pill/world-rule vocabulary split** — `PillStatus.pillStatus()` (the Home status pill) only ever reflects a home-relative "near" check; it has no notion of `AlertRuleEngine.DEFAULT_RULES`'s independent "world" rule (M6.0+, unbounded radius — fires for a major quake anywhere on Earth). A world-rule match populates `alertEvents` today with zero visible effect, but once Plan 4 surfaces real notifications, a user could receive a "world M6+" push while the pill on their own Home screen still reads CALM. Decide at notifications: either the pill grows a third state for world-rule matches, or notifications scope to near-rule matches only.
4. **Minify/R8 MUST land before any Play upload** — restates the "Release-build debug-hook stripping" ledger item above (Tasks 12/13) as a hard release gate, not passive debt: `injectDebugQuake`/`ingestDebugBypassingDedupe`/`purgeDebugQuakes`/`isDebuggableBuild` ship unobfuscated in the release APK today (no ProGuard/R8 rules configured yet). This blocks any Play Store upload, closed-testing track included — it is not optional polish.
5. **900-980dp layout dead zone** — `AppNav.kt`'s own `BoxWithConstraints` (measures the full window width to pick rail-vs-bottom-bar chrome) and `HomeScreen.kt`'s independent `BoxWithConstraints` (measures width minus the rail, once shown) both call the same `layoutMode()` 900dp breakpoint, but disagree in the roughly 900-980dp band: the nav rail can show here while Home still falls back to its phone (sheet) layout underneath it, instead of the two-pane layout the rail implies. Documented as accepted-for-now directly in `AppNav.kt` (no test or device screenshot gates this narrow band today); worth a second look whenever a real desktop pass happens.

## From Plan 4 Task 3 re-review (2026-08-15, Round 2)

1. **Ingest content-diff gate** (traced this round, not fixed — see task-3-report.md's Round 2
   section for the ring-buffer-cap mitigation this motivated) — `QuakeRepository.ingest()`'s
   `dao.replaceAndDelete(result.canonical, deleteIds, origin = effectiveOrigin)` call is
   unconditional: every quake present in a feed poll's response gets rewritten every single poll,
   even when the reconciled canonical is byte-identical to what's already stored. `QuakeDao.toRow()`
   stamps `fetchedAtMillis = clock()` on every write with no content check first, so that
   unconditional rewrite re-stamps EVERY still-current quake's fetch time on EVERY poll, not just
   genuinely new-or-revised ones. Two costs, one root cause: (a) `AlertDigestWorker`'s
   `store.newSince(lastRun)` cursors on exactly this column, so it re-selects every still-qualifying
   quake as "new" on every digest run regardless of whether anything actually changed — the ring
   buffer (`AlertDigestSupport.kt`'s `NOTIFIED_IDS_CAP`) is the ONLY thing currently suppressing a
   re-notify, which is why its adequacy under a high-volume rule mattered enough to need a Round 2
   cap bump (100 -> 1000); (b) every poll performs a full DB rewrite of the whole current feed
   window even when nothing needs writing, which is wasted I/O independent of alerting. A
   content-diff gate in `ingest()` — skip `replaceAndDelete` (and the `fetchedAtMillis` re-stamp
   that comes with it) whenever the reconciled canonical is unchanged from what `previous` already
   holds — would remove both costs at the source instead of a downstream mitigation absorbing the
   symptom. Bigger scope than a fix-round slice (touches the one shared `ingest()` path every
   origin funnels through — feed, live, archive — so needs its own equality/dirty-check design and
   TDD pass); logged here rather than folded into this round.
