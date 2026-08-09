# TerraWatch Plan 3: Screens & Debts Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** The complete three-tab app — History (archive browser), Insights (charts), Settings + onboarding — plus navigation, web enablement, and Plan 2's five entry-condition debts paid.

**Architecture:** Navigation via androidx navigation-compose (KMP) with 3 destinations + settings/onboarding routes. New feature packages in composeApp (`history/`, `insights/`, `settings/`, `onboarding/`); aggregates as SQL in core:database; charts hand-rolled Canvas in core:ui. HomeViewModel splits before tabs land.

**Tech Stack:** Plan 2 stack + `org.jetbrains.androidx.navigation:navigation-compose` (KMP artifact — resolve current stable at Task 4, ~2.8.x). No other new libraries (charts hand-rolled; no chart lib per spec).

## Global Constraints

- Branch: `feat/plan-3-screens` off `main`. All prior global constraints carry (tokens LAW, glass allow-list [pill/banner/nav/sheet-header], four-states rule on every screen, TDD in logic, commit trailer `Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>`, per-target compiles, EVIDENCE INTEGRITY: grep before citing, label media by actual content, verbatim outputs).
- **Real-device verification (user mandate): every UI task ends with install + screenshots on 98bc1cd8** (OxygenOS: pm clear/shell rotation blocked — emulator for those) + emulator-5554 (map canvas black — Zscaler; chrome-only).
- Entry conditions doc is binding: docs/superpowers/plans/plan-3-entry-conditions.md — rulings listed there are NOT re-litigated.
- History uses `QuakeRepository.loadArchivePage` (throws by design — the History VM wraps: this is the documented contract finally meeting its caller).
- All new screens: Loading = skeleton (not spinner — design catch-up), Content, Empty (with CTA), Error (with Retry).

---

### Task 1: Debt — refresh loop, retry, sliding window, throw-guard

**Files:** Modify `core/data/.../QuakeRepository.kt`, `composeApp/.../home/HomeViewModel.kt`, `composeApp/.../home/HomeScreen.kt` (banner Retry). Tests extend existing.

**Interfaces:**
```kotlin
// HomeViewModel:
//  - init refresh coroutine becomes a loop: refreshFeed() every 60s (delay-based), each iteration
//    wrapped in runCatching (a throw marks refreshFailed=true, loop continues; startLive launched
//    SEPARATELY so a refresh throw can never kill LIVE).
//  - refreshFailed clears on ANY successful refresh (UPDATED or NOT_MODIFIED), not only on new inserts.
//  - fun retryNow() — user-triggered immediate refresh (banner CTA); coalesce if one in flight.
// QuakeRepository.recentQuakes: sliding window — re-emit against a fresh cutoff when a poll tick lands
//    (implementation freedom: simplest correct = VM re-collects with fresh windowMs on each poll tick,
//    OR repository exposes recentQuakes(clockTick: Flow<Unit>) — choose, document, TDD).
// StalenessBanner gains onRetry: (() -> Unit)? — shows "Retry" text-button when non-null.
```
TDD: loop iterations tolerate throws (fake repo throwing once → refreshFailed true → next tick success → false); retryNow coalesces; sliding window emits fresh-cutoff list. Device: airplane on → banner+Retry appears → airplane off → tap Retry → pins/data return in-session (screenshot pair). This kills the F1 family.

### Task 2: Debt — close the location loop

**Files:** Modify `MainActivity.kt` (grant callback → HomeLocationStore.set + REMOVE the location Log.d + bare CoroutineScope [release hygiene item, do it here]), `HomeViewModel` (homeLocation becomes reactive: re-read store on a `locationUpdates: SharedFlow<GeoPoint>` the store now emits on set), `core/data/.../HomeLocation.kt` (store gains `val updates: SharedFlow<GeoPoint>` emitted on set — TDD), `HomeScreen` ASK-pill tap → `LocationAskDialog` (two buttons per spec: "Use my location" → triggers permission request via a callback wired from MainActivity-registered launcher exposed through a simple `LocationRequester` expect/actual; "Choose city" → `CityPickerDialog` with 10 preset cities [Bengaluru, Delhi, Mumbai, Tokyo, Jakarta, Istanbul, LA, Mexico City, Santiago, Athens — lat/lon literals] writing store). jvm/wasm: ask-dialog shows city picker only.

TDD store updates flow; VM reacts (grant mid-session → pill flips ASK→CALM without restart — device proof screenshot pair). HttpTimeout while in MainActivity/main.kt: `install(HttpTimeout) { requestTimeoutMillis = 15_000; connectTimeoutMillis = 10_000 }` both clients (F12).

### Task 3: Debt — split HomeViewModel + SavedStateHandle

**Files:** New `composeApp/.../home/QuakeSelectionViewModel.kt` (selectedQuake/select/dismiss/selectJob + SavedStateHandle["selectedId"] restore: on init, if saved id non-null re-select), HomeViewModel sheds selection (keeps map/pill/sheet state). HomeScreen wires both VMs (koinViewModel each; selection VM shared at nav-graph scope later — Task 4 hooks it). All existing tests migrate; counts honest.

TDD: selection VM restore-from-handle test (seed handle, init, selectedQuake populated). Device: select → process-death sim (`am kill` + relaunch from recents — works on OxygenOS?) if blocked, emulator; sheet restores.

### Task 4: Navigation — 3 tabs + routes

**Files:** libs.versions.toml (+navigation-compose KMP artifact), composeApp deps, new `composeApp/.../nav/AppNav.kt` (NavHost: home/history/insights + settings + onboarding routes), `App.kt` rewires, bottom NavigationBar (phone: 3 items, TerraColors, glass per allow-list — bottom nav IS on the list) / NavigationRail (desktop two-pane keeps its layout; rail replaces bottom bar ≥900dp), ad-slot Spacer(50.dp) placeholder above bottom bar (Plan 4 fills — honor Plan 2 deferral).

Onboarding gate: first-run flag in meta ("onboarded"=true) → if absent, nav starts at onboarding route. TDD: none (nav wiring) — instrumented smoke in Task 13. Device: tab switching screenshots, state survives tab flips (Home map NOT recreated — single-instance lesson: keep Home composable alive via saveState/restoreState nav options; verify no white screen on return to Home tab — THE regression to watch; screenshot after round-trip).

### Task 5: History screen

**Files:** New `composeApp/.../history/HistoryViewModel.kt` + `HistoryScreen.kt`; `core/data` gains `HistoryPager` (TDD, pure-ish): wraps `loadArchivePage` with runCatching → sealed PageResult {Loaded(count), Error(cause), End}; cursor = oldest-loaded timeMillis per filter-set persisted in meta ("history_cursor_<filterhash>").

**Interfaces:**
```kotlin
data class HistoryFilter(val minMag: Double? = null, val yearRange: IntRange? = null)
class HistoryViewModel(repository, pager) : ViewModel {
    val items: Flow<List<Quake>>            // dao.pageBefore-backed, filter-aware, from cache
    val state: StateFlow<HistoryUiState>    // Loading(skeleton)/Content/Empty(CTA widen filters)/Error(retry)
    fun loadMore(); fun setFilter(f: HistoryFilter); fun retry()
}
```
UI: search-less v1 (region search = Plan 4 polish; filters only — minMag chips [All/4.5+/6+], year chips [2026/2025/All]), month sticky headers (derive from timeMillis, group in VM), infinite scroll (LazyColumn onLastVisible → loadMore), QuakeCard reuse, detail via shared QuakeSelectionViewModel. Offline: cached pages browse; Error row w/ retry at list end on page failure.

TDD pager (loaded/error/end/cursor-advance/filter-isolation). Device: scroll 3+ pages deep (real FDSN!), filter flip, offline browse of loaded pages, detail from history row — screenshots.

### Task 6: Insights screen

**Files:** `core/database` Quake.sq + dao: TDD'd aggregates `quakesPerDay(sinceMillis): List<DayCount>` (SQL GROUP BY date bucket via timeMillis/86400000), `bandDistribution(sinceMillis): List<BandCount>` (CASE bands), `strongest(sinceMillis): Quake?`; `core/ui` charts: `BarChart(values, labels)` + `DistributionBars(bands)` Canvas composables (no lib) using magnitudeColor; new `composeApp/.../insights/InsightsViewModel.kt` + `InsightsScreen.kt` — 7d/30d segmented toggle, three cards (per mockup): quakes/day bars, band distribution, strongest card (QuakeCard reuse → detail).

TDD aggregates against seeded in-memory db (exact counts, band edges 4.5/6.0, empty-db zeros). Charts: instrumented render test Task 13. Device: both periods screenshot, tap strongest → detail. Insights = offline-pure (cache only, zero network) — verify airplane mode still renders.

### Task 7: Settings + theme + about

**Files:** New `composeApp/.../settings/SettingsViewModel.kt` + `SettingsScreen.kt`; alert-rule editor writes meta ("rule_minmag","rule_radiuskm" — AlertRuleEngine consumers read via a TDD'd `AlertRuleStore` in core:data mirroring HomeLocationStore pattern; DEFAULT_RULES used when unset); saved place row (shows current home, tap → CityPicker/location flow from Task 2); theme selector (System/Light/Dusk → meta "theme"; App() reads via a `ThemeStore` flow → TerraTheme(darkTheme=resolved)); About section (version, data sources "USGS · EMSC", "© OpenStreetMap contributors" attribution line, licenses note).

Settings reached via gear icon on Home (top-right, glass chip) + nav route. TDD stores round-trips + rule fallback to defaults. Device: change rule → verify meta via debug query; theme flip live screenshot ×3 (system/light/dusk); about renders.

### Task 8: Onboarding (first-run)

**Files:** New `composeApp/.../onboarding/OnboardingScreen.kt` (3 pager steps per spec §3.6: what-it-does [one screen, shield illustration = big StatusShield CALM preview], location ask [reuses Task 2 dialog flows; skippable], notifications PREVIEW [static explanation + default rule shown — actual permission = Plan 4 with notifications; "you'll be asked later"]), meta "onboarded" flag, skip button all steps. Nav: onboarding → home on finish/skip.

No TDD (composition); instrumented smoke Task 13. Device: pm-clear equivalent → fresh-run onboarding walkthrough screenshots ×3 steps (emulator for pm clear), skip path, never-shows-again relaunch.

### Task 9: Web enablement

**Files:** `core/database` wasmJs: real SqlDriver — evaluate SQLDelight web-worker driver w/ generateAsync (BLOCKED in Plan 1 — spike 30min max: if generateAsync=true migration is invasive across modules, FALLBACK: in-memory repository for wasm [InMemoryQuakeDao implementing the dao surface used by repository — smaller than it sounds: ~8 methods] — decide, document); wasm main.kt: startKoin with wasm-appropriate graph (Ktor Js engine, in-memory/worker dao, LocationProvider null-actual) → renders REAL App() (HomeScreen w/ FallbackMapPane + tabs). Browser verify: wasmJsBrowserDevelopmentRun, screenshot (browser tools or headless capture), pins render from live USGS fetch (browser CORS: USGS sends permissive CORS headers — verify; EMSC WS from browser — verify or disable WS on wasm w/ honest OFFLINE).

This makes web a real target. Zscaler blocks localhost browser fetch? Browser uses system trust (Zscaler root trusted in macOS) — should work. Document reality.

### Task 10: Design catch-up bundle

**Files:** core:ui + screens. (a) StatusShield shape-morph: animate corner radius CALM(pill 99)⇄ALERT(20dp) via animateDpAsState spring + reduced-motion kill; (b) skeleton first-load: `SkeletonCard` shimmer composable (alpha pulse), FeedSheet + History + Insights Loading states use it (kill spinners); (c) FeedSheet empty copy ("Quiet right now — no quakes in the last 24 h"); (d) map desaturation when offline (saturation matrix on fallback pane; android maplibre: skip if API awkward — document); (e) unify LIVE/staleness vocabulary: banner only when stale/failed, LIVE row owns connection — one sentence rule doc'd in code; (f) tabular figures: FontFeatureSettings "tnum" on magnitude text styles; (g) a11y: contentDescription/semantics on pill (dynamic per state), badges ("Magnitude 6.1"), LiveDot ("live"/"offline"), nav items; pill min-height 48dp; TalkBack phrasing per spec §4.5.

Instrumented: semantics assertions (Task 13 extends). Device: morph screencap burst, skeleton screenshot, TalkBack spot-check (manual, describe honestly).

### Task 11: Cluster labels + tap-to-zoom

**Files:** `QuakeMap.android.kt`. Retry SymbolLayer count labels with the reviewer's confirmed-compiling shape (recorded in Plan 2 task-10 report): `SymbolLayer(id, source, textField = feature["point_count"].convertToString())` + white text/halo per tokens; cluster circle tap → camera zoom+1.5 centered (cluster click API from spike). If labels STILL fail to compile: capture exact error verbatim, implement tap-to-zoom only, file honest kdoc.

Device: zoomed-out cluster with count label screenshot; tap → zoom screencap pair.

### Task 12: Release hygiene subset

**Files:** confirm Task 2 killed location logging (audit repo-wide for Log.d/println leaking data — grep, remove strays outside debug paths); buildTypes: add `release { isMinifyEnabled = true; proguardFiles(...) }` + `proguard-rules.pro` (keep rules: kotlinx-serialization, maplibre, sqldelight — standard sets); `assembleRelease` builds + installs on device (self-signed debug-keystore-signed release variant? add `signingConfig = debug` for local install ONLY w/ comment) + smoke: launches, map renders, no crash (proguard keep-rule verification — THE point). Debug hook confirmed absent: dumpsys/apk-analyzer check that release APK lacks inject path (R8 should strip; verify + document; if not stripped, gate compiles via BuildConfig now that buildTypes exist).

Device: release-variant smoke screenshots.

### Task 13: Device matrix v2 + instrumented extension

**Files:** ComponentsTest extends (charts render w/ seeded values, skeleton, semantics assertions, onboarding step text); new HistoryFlowTest/InsightsFlowTest (fake-DI pattern from HomeFlowTest); full manual matrix on 98bc1cd8 → docs/qa/plan-3-device-matrix/: tabs ×3, history-deep-scroll, history-filters, history-offline, insights-7d/30d, settings-theme ×3, onboarding ×3 (emulator), cluster-label, morph-burst, skeleton, release-smoke, web-browser-screenshot. connectedDebugAndroidTest both devices green.

### Task 14: CI/docs close-out

CI: nothing new needed (jvmTest covers new tests; wasm build already gated) — verify; README: screenshots refresh (tabs), feature list update, web section; plan-4-entry-conditions.md written from final review; push, CI green.

---

## Self-Review (write time)

1. **Coverage:** entry conditions 1→T1, 2→T2 (+F12), 3→T3, 4(release)→T12, 5(desktop/web)→T9 (desktop live map consciously NOT in Plan 3 — JDK toolchain unresolved; stays Plan 4/later, noted); design catch-up→T10; clusters→T11; etag investigation → T1 territory (poll loop exercises etag — add explicit check step in T1 verify: second poll sends If-None-Match on device logs); spec §3.4/3.5/3.6 → T5/6/8; nav → T4.
2. **Placeholders:** T9 and T11 carry explicit decision-gates with fallbacks — deliberate, bounded, documented. No TBDs.
3. **Type consistency:** HistoryFilter/PageResult/AlertRuleStore/ThemeStore named once each, consumed in T5/7/13; QuakeSelectionViewModel (T3) consumed by T4/5/6.
