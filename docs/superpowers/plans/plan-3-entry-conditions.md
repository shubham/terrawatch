# Plan 3 Entry Conditions

From Plan 2's final whole-branch review (branch `feat/plan-2-ui-shell`, 2026-08-09). None blocked the merge; each binds when Plan 3 touches the named area.

## Fix first (before or with the feature that touches them)

1. **Refresh loop + failure recovery** — periodic `refreshFeed()` poll (spec §4.4 "polling continues"), Retry CTA on the offline banner, wrap `refreshFeed()`'s throw path (a DB error currently kills the init coroutine and silently disables LIVE), clear `refreshFailed` on a successful no-op refresh, sliding `recentQuakes` window (frozen cutoff accumulates on long sessions).
2. **Close the location loop** — first-run grant never reaches the pill in-session (grant callback only logs; VM resolved location before the dialog answered; ASK pill tap is a no-op TODO). Grant → store → pill live update; pill tap → two-button ask per spec; give `LocationProvider` a fakeable seam.
3. **Split HomeViewModel before tabs land** — it serves map+pill+sheet+detail+two-pane at the complexity ceiling; extract selection/detail chrome; History/Insights get their own ViewModels; add SavedStateHandle for selection (+ camera restore decision).
4. **Release hygiene (before ANY release build)** — remove/gate `MainActivity` location logging (coarse coordinates hit logcat in every build type today) + the bare `CoroutineScope` around it; add buildTypes/minify + release smoke pass; explicit `HttpTimeout` on both clients; debug gesture gate (FLAG_DEBUGGABLE) verified sound, keep.
5. **Desktop/web debt** — live desktop map (JDK 25 toolchain path), wasm SqlDriver + real HomeScreen on web (FallbackMapPane already wired and waiting), in-panel master-detail for TWO_PANE (accepted Task 12 ruling).

## Design-system catch-up (spec §4.3 gaps)

- StatusShield safe⇄alert **shape-morph animation** (dropped baton T9→T10) + magnitude-badge revise micro-animation.
- Skeleton first-load (currently spinner), FeedSheet empty-state copy, offline map desaturation.
- a11y pass: explicit semantics (zero today), 48dp pill target, TalkBack phrasing, tabular figures for magnitude numerals.
- Cluster count labels + tap-to-zoom (reviewer's confirmed-compiling SymbolLayer shape recorded in task-10 report); unify LIVE/staleness double-vocabulary.

## Investigate

- Device etag/meta table empty despite successful 236-row refresh (Task 8 leftover — refresh efficiency).
- ~25 ledger minors from Plan 2 ride here — see `.superpowers` history summarized in git log and task reports; notable: non-atomic home_lat/lon metaPuts, select()-race automated test, cancelAndJoin teardown timeout, createComposeRule v1→v2 migration, emulator user-CA install for map QA, white-screen single-instance regression test.

## Rulings that stand (do not re-litigate)

- Android-only live map in v1; desktop/web = FallbackMapPane (spike: no wasm artifact; desktop lib needs JDK 25).
- DetailSheet modal reuse on desktop (in-panel replace = Plan 3 polish).
- Oscillation refire, queryArchive-throws, USGS-over-EMSC magnitude preference, absent-auto→AUTOMATIC — all Plan 1 rulings carry.
- Ad-slot Spacer deferred to Plan 4 (monetization owns it).
- Debug inject hook: `debug-` namespace + bypass path + purge-on-init; no alert evaluation for fakes.
