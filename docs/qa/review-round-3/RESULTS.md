# Review round 3 — detail-sheet fixes — RESULTS

Branch `feat/review-round-3` off `c6a70bd`. Samsung dogfooding report (device not available to us):
(1) quake detail bottomsheet "not fully visible," (2) Share button overlapping the navigation bar,
(3) takes 2 back presses to close when expanded. Device verification: OnePlus 9R `98bc1cd8`
(Android 14, OxygenOS, **3-button nav** — `settings get secure navigation_mode` → `0`, already the
device's own default going into this pass, not something switched on for the occasion).

## Item → verdict

| # | Item | Root cause | op9 (98bc1cd8) |
|---|------|------------|-----------------|
| 1 | Detail sheet "not fully visible" | Sheet opened resting at `PartiallyExpanded` (half-height peek), not `Expanded` | **PASS** |
| 2 | Share button overlapping the nav bar | Same root cause — half-height peek cut content off mid-flow, at the exact seam where a 3-button nav bar overlays app content | **PASS** |
| 3 | Takes 2 back presses to close | M3's own `ModalBottomSheetDialog` back handler collapses Expanded→PartiallyExpanded on press 1, only hides+dismisses on press 2 | **PASS** |

All three traced to **one** missing config (`skipPartiallyExpanded`), not three separate bugs.

## Root cause (traced against this project's actual resolved M3 1.8.2 source, not guessed)

`DetailSheet.kt` called `rememberModalBottomSheetState()` with no arguments, i.e. the library
default `skipPartiallyExpanded = false`. Unzipped and read the real, resolved
`org.jetbrains.compose.material3:material3:1.8.2` sources
(`~/.gradle/caches/.../material3-1.8.2-sources.jar`, both `commonMain` and `androidMain`) rather
than trusting API docs or memory:

- **`ModalBottomSheetContent`'s `draggableAnchors`** (`ModalBottomSheet.android.kt`): `if
  (sheetSize.height > fullHeight / 2 && !sheetState.skipPartiallyExpanded) { PartiallyExpanded at
  fullHeight / 2f }` — a `PartiallyExpanded` anchor exists whenever measured content exceeds half
  the screen. `DetailSheet`'s real content (hero + stat trio + tsunami banner + stat list +
  conditionally news/quick-share + Share button) clears that bar on essentially any phone screen.
- **`SheetState.show()`** (`SheetDefaults.kt`, called unconditionally by `ModalBottomSheet` on first
  composition): `val targetValue = when { hasPartiallyExpandedState -> PartiallyExpanded; else ->
  Expanded }` — it **prefers** `PartiallyExpanded` over `Expanded` whenever that anchor exists. So
  the sheet always opened parked at the halfway line, never full height, with no drag-affordance
  hint that there was more below. Content below that line is measured/laid out at full size
  regardless (drag only changes the sheet's Y *offset*, not its size) — the back half of it rendered
  below the display's physical bottom edge entirely. "Not fully visible" is literal, not
  approximate. Wherever that half-height cutoff happened to land (content/device-dependent) could
  bisect content right at the screen's bottom edge — exactly where a 3-button nav bar overlays app
  content in edge-to-edge mode — reading as "Share is behind the nav bar," even though the *real*
  `Expanded` state already reserves correct nav-bar clearance (M3's own default
  `contentWindowInsets = { BottomSheetDefaults.windowInsets }` = `WindowInsets.safeDrawing
  .only(Bottom)`, applied via `windowInsetsPadding(...)` on the sheet's own content column — verified
  in source; nothing wrong with it, it just never got reached before a user manually dragged past
  the halfway resting point).
- **`ModalBottomSheetDialog`'s back handler** (`ModalBottomSheet.kt`): `if (currentValue == Expanded
  && hasPartiallyExpandedState) { partialExpand() } else { hide(); onDismissRequest() }` — the FIRST
  back press from `Expanded` only collapses to `PartiallyExpanded` and never calls `DetailSheet`'s
  own `onDismiss`; only the SECOND press (now not `Expanded`) actually hides and dismisses. Exactly
  the reported "2 back presses."

## Fix

`composeApp/src/commonMain/kotlin/com/yugma/terrawatch/detail/DetailSheet.kt`:
`rememberModalBottomSheetState(skipPartiallyExpanded = true)`. Removing the `PartiallyExpanded`
anchor outright (the `!skipPartiallyExpanded` guard above then never lets it exist) fixes all three
symptoms as one config change: `show()` has nothing left to prefer over `Expanded`, so the sheet
always opens straight to full content, immediately visible; with no `PartiallyExpanded` state,
`hasPartiallyExpandedState` is always false, so *every* back press takes the `hide()` branch — one
press, from anywhere, fully closes it. The existing `verticalScroll` (added by an earlier UI-polish
fix) is untouched and still handles content taller than the display in the `Expanded` state — this
change only removes the resting state that was hiding part of it before a user ever got there.
Deliberately **not** a hand-rolled `BackHandler`/manual dismiss dance — that would reimplement (and
risk drifting from) what this one library flag already gives for free; `ModalBottomSheet.android.kt`'s
own back-callback wiring flows through the same `sheetState` unchanged. Full root-cause trace lives
in the composable's own kdoc now, so a future reader doesn't have to re-derive it from scratch.

## TDD / regression coverage

No new *pure* decision function to extract — the entire fix is a single boolean passed to a
third-party API, verified correct by direct inspection of that API's own real, resolved source (same
"no branching of our own, verified by real API inspection instead" discipline `docs/qa/post-p5-tail/
RESULTS.md`'s Fix 2 already establishes for an analogous case). Adding a hand-rolled back-intent
function here would just re-test M3's own state machine under a different name, not add real
coverage.

What *is* new: `ComponentsTest.detailSheet_singleBackPress_fullyDismissesTheSheet` (androidInstrumentedTest) —
renders the real `DetailSheet` composable and dispatches a genuine system back event via
`Espresso.pressBack()` (not a direct `SheetState`/dispatcher call — `ModalBottomSheet` renders into
its *own* Dialog window with its own `OnBackInvokedDispatcher`, separate from the host Activity's, so
only a real injected back event exercises the actual path a device back-press/gesture takes). Asserts
Share is reachable immediately (no manual drag) *and* that `onDismiss` fires exactly once after one
back press — pins THIS app's own call-site configuration, not just the library's documented
behavior, so a future accidental revert of `skipPartiallyExpanded` would be caught here.
`androidx.test.espresso:espresso-core:3.7.0` added as a new `androidInstrumentedTest`-only dependency
(`gradle/libs.versions.toml`, `composeApp/build.gradle.kts`) — not previously declared for this
source set despite the jar existing in the local Gradle cache from an unrelated transitive graph.

**Incidental find while wiring this test, fixed as a 1-line test-only side effect (not production
code):** `ComponentsTest`'s *pre-existing* `detailSheet_rendersRevisionBadgeTsunamiBannerAndShareAction`
test was **already broken** before this pass touched anything — confirmed by running it unmodified
first: `kotlin.UninitializedPropertyAccessException: lateinit property appContext has not been
initialized` at `Share.android.kt`'s `isPackageInstalled`. `DetailSheet` composes `remember {
visibleShareTargets(::isPackageInstalled) }` unconditionally, and `isPackageInstalled` reads
`Share.android.kt`'s module-level `lateinit var appContext` with no null-check — normally set by
`initShareContext`, called only from inside `ensureKoinStarted` (`KoinBootstrap.kt`), which
`ComponentsTest` deliberately never runs (its own class kdoc: "no MainActivity, no Koin, no DI
wiring"). This is the *identical class* of bug `NavRoundTripTest`'s own kdoc already documents fixing
once for `AlertDigestScheduler`'s parallel `appContext` holder — but routing through the full
`ensureKoinStarted` (that fix's approach) would be the wrong weight for a class whose entire design
is avoiding Koin. Fixed instead with a `@Before` that calls the plain, Koin-independent
`initShareContext(InstrumentationRegistry.getInstrumentation().targetContext)` directly — the
narrowest fix that satisfies the one lateinit these tests actually exercise. This was silently broken
(latent, order-dependent-safe only if never run standalone) since the "real share app icons" feature
added `appIcon`/`isPackageInstalled` calls into `DetailSheet`'s composition on `feat/feed-visit-ux`,
apparently never re-verified via `connectedDebugAndroidTest` since.

## Device verify (98bc1cd8, OnePlus 9R, 3-button nav, live app data)

1. **Live quake, fully expanded on open, no manual drag** —
   `op9-detailsheet-liveQuake-fullyExpanded-shareClearsNavBar.png`: tapped a real feed quake ("2 km N
   of The Geysers, CA"); sheet rendered hero/stat-trio/tsunami-banner/coordinates/quick-share/Share
   in one frame, immediately — no half-height peek requiring a drag-up first.
2. **Share button clears the 3-button nav bar** — same screenshot: a clear dark gap separates the
   white Share button from the OS's nav-bar glyphs at the very bottom of the display; no overlap.
3. **One back press, from `Expanded`, fully dismisses** —
   `op9-detailsheet-liveQuake-1backpress-dismissed.png`: single `KEYCODE_BACK` from the state in (1)
   returned cleanly to the Home feed sheet — no intermediate partial-height state observed.
4. **Second, independent quake (debug-injected M6.0, different content/coordinates)** — repeated (1)
   and (3) via the map's long-press debug-inject hook to rule out a fluke tied to one specific
   quake's content height: `op9-detailsheet-debugM6-fullyExpanded-shareClearsNavBar.png` (fully
   expanded immediately, Share clear of the nav bar) →
   `op9-detailsheet-debugM6-1backpress-dismissed.png` (one back press, fully dismissed). Both PASS,
   matching (1)-(3) exactly.
5. **Scroll/drag-to-dismiss cooperation intact** — a swipe-up gesture inside the (already-fitting,
   nothing-to-scroll) sheet content produced no crash and no visual change, confirming the existing
   `verticalScroll`/nested-scroll wiring still coexists cleanly with the new `skipPartiallyExpanded`
   config rather than fighting it.
6. **Crash sweep**: `adb logcat -d -b crash` and an `AndroidRuntime:E` filter for
   `com.yugma.terrawatch`, both empty across the whole session (onboarding skip, tab navigation,
   detail-sheet open/close ×2, debug-quake injection, scroll gesture, back presses).
7. **Nav mode**: `settings get secure navigation_mode` read `0` (3-button) *before* any of this
   pass's interactions — the device's own existing default, not switched for this task, so nothing
   needed restoring afterward. The `cmd overlay enable
   com.android.internal.systemui.navbar.threebutton` fallback the task anticipated was never needed.
8. **Debug quake left in local DB** — self-purges on next cold start (`HomeViewModel`'s existing,
   unconditional `DELETE ... WHERE id LIKE 'debug-%'` startup housekeeping, unrelated to this pass);
   no manual cleanup performed or required.

Screenshots: `docs/qa/review-round-3/op9-detailsheet-*.png` (4 files).

## Tests / compiles

- `./gradlew jvmTest --max-workers=4` (all modules) — **BUILD SUCCESSFUL**, 0 failures: composeApp
  255, core:model 25, core:network 50, core:database 128, core:data 177, core:ui 61,
  core:monetization 9, core:ads 12 — **717 tests total, 0 failures, 0 errors**.
- `./gradlew :composeApp:compileDebugKotlinAndroid :composeApp:compileKotlinJvm
  :composeApp:compileKotlinWasmJs --max-workers=4` — all 3 targets green (only pre-existing
  `expect`/`actual`-in-Beta warnings, unrelated to this change).
- `./gradlew :composeApp:assembleDebug --max-workers=4` — green; arm64 APK installed via `adb install
  -r` and used for the device-verify pass above.
- **Beyond the explicit gate, for real evidence the new test actually catches what it claims to** —
  `./gradlew :composeApp:connectedDebugAndroidTest` on the same device:
  - `ComponentsTest` alone: **18/18 pass**, including both the newly-fixed pre-existing
    `detailSheet_...ShareAction` test and the new `detailSheet_singleBackPress_...` test.
  - Full instrumented suite (`ComponentsTest` + `HomeFlowTest` + `NavRoundTripTest` +
    `OnboardingGateTest`, 23 tests): 21/23 pass. The 2 failures are in `HomeFlowTest`
    (`homeScreen_movesFromLoadingToContentWithTheSeededQuake`,
    `homeScreen_showsTheOfflineBannerWhenTheInitialRefreshFails`) — confirmed **pre-existing and
    unrelated** by reproducing them with `HomeFlowTest` run in complete isolation (same failure,
    same stack trace, zero interaction with anything this pass touched). Root cause identified
    (`HomeScreen`'s `detailNewsViewModel` param defaults to `koinViewModel()`, and `HomeFlowTest`
    never overrides it, so it only "passes" by accident of which sibling test class happens to start
    Koin first in the shared instrumentation process) and flagged as a separate background task
    rather than folded into this diff.

## Samsung-specific residual risk (reporting device unavailable — honest gap, not silently assumed closed)

- **Low risk — the back-press and "opens fully expanded" fixes are structural, not
  insets/OEM-dependent.** Both come from `skipPartiallyExpanded` reshaping M3's own state machine
  (`SheetState`/`ModalBottomSheetDialog`), which is identical Compose library code on every Android
  OEM — Samsung's One UI doesn't re-skin `androidx.compose.material3`. No plausible OEM-specific
  reason these two would behave differently on the reporting Samsung device than on op9.
  Not literally 100% certain: `Espresso.pressBack()`/`adb shell input keyevent 4` are OS-level input
  injection, not proof that Samsung's own predictive-back/gesture-nav overlay routes the same
  `OnBackInvokedDispatcher` path identically in every One UI version — considered low-probability,
  not verified.
- **Real, unclosed gap — the exact nav-bar clearance amount depends on Samsung's own WindowInsets
  reporting for a Dialog window**, which is genuinely untested here. `ModalBottomSheetDialogWrapper`
  uses the standard, non-deprecated `WindowCompat.setDecorFitsSystemWindows(window, false)` edge-to-edge
  API (not a One-UI-specific or legacy path), so risk is low, but One UI has historically had its own
  quirks around insets timing/values for Dialog-hosted (vs. Activity-hosted) content that op9/OxygenOS
  can't surface. If the original report's Samsung device uses full-screen gesture nav rather than
  3-button (unspecified in the original report), that's an additional untested variant — gesture nav
  typically reserves a smaller bottom inset than 3-button, which would make this fix's margin *more*
  comfortable, not less, but that's reasoning from the mechanism, not a device-verified fact.
- Recommend: closing this gap needs either the reporting Samsung device or a comparable One UI
  device/emulator in the same nav mode the original report used.

## Commits

See branch `feat/review-round-3` history for the commit(s) landing this pass (source fix + test fix +
this report), trailer `Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>`.

## Concerns

- The 2 pre-existing `HomeFlowTest` failures (see Tests/compiles above) are real, reproducible, and
  unrelated to this pass's diff — flagged as a separate background task rather than fixed here, to
  keep this branch's diff scoped to the 3 named Samsung complaints.
- Samsung-specific residual risk on the exact nav-bar clearance amount, above — structurally low risk
  but genuinely unverified without the reporting device or an equivalent One UI unit.
- `espresso-core` is a new test-only dependency addition (`gradle/libs.versions.toml`,
  `composeApp/build.gradle.kts`) — scoped to `androidInstrumentedTest` only, does not ship in the
  release APK.
- The `detailSheet_rendersRevisionBadgeTsunamiBannerAndShareAction` fix (the `@Before
  initSharePlatformContext` addition) is a test-infrastructure fix bundled into this pass because it
  directly blocked verifying the new regression test — not because it was in this task's original
  scope. Called out explicitly rather than silently folded in uncredited.

---

# Review round 3, items 3+4 — history search + magnitude filters — RESULTS

User review items 3 ("local search for earthquake in history") and 4 ("filter support in history,
quake list in dashboard list on the basis of richter scale; in the list default should be 4.0 and
above; user should be able to filter"). Device verification: OnePlus 9R `98bc1cd8` (same device as
above), verified connected before, throughout (2 rebuild/reinstall cycles), and after this pass.

## Item → verdict

| # | Item | Verdict (98bc1cd8) |
|---|------|-----|
| 3 | History search (local, case-insensitive, place text) | **PASS** |
| 4 | Shared magnitude filter, both lists, dashboard defaults 4.0+, persists | **PASS** |

## Design

**Shared vocabulary** (`composeApp/.../filter/MagnitudeFilter.kt`, new): one `MAGNITUDE_FILTER_CHIPS`
list ("All"/"4.0+"/"5.0+"/"6.0+") consumed by both `HistoryScreen`'s chip row (replacing that screen's
former, independent "All/M4.5+/M6+" set) and the feed sheet's new filter control, plus one shared pure
predicate `quakeMatchesMagFilter(mag, minMag)` used wherever the feed list needs client-side filtering.
History's own chip selection was confirmed session-only, never persisted (`HistoryViewModel._filter`
is a plain `MutableStateFlow`, grepped for any meta-table write — none exists), so there was no stored
user selection to migrate; the only carry-over is `HistoryPager`'s per-filter-value paging-cursor meta
key, where the retired "M4.5+" (4.5) combination's cursor row is now simply orphaned (harmless — never
looked up again) and the retained "M6+"→"6.0+" (6.0) combination's cursor carries over unchanged.

**History search** (`HistoryViewModel`/`QuakeRepository.pageBetween`/`Quake.sq`): a new optional
`placeQuery` predicate on the existing `pageBetween` SQL query (`AND (:placeQuery IS NULL OR place
LIKE '%' || :placeQuery || '%')` — SQLite's own default ASCII case-insensitive `LIKE`, no `COLLATE`
needed), threaded through `QuakeStore`/`QuakeDao`/`InMemoryQuakeStore`/`QuakeRepository`. Deliberately
LOCAL: `HistoryViewModel.setSearchQuery` never touches `HistoryPager`/the network — a new
`visibleAndUnsearched()` helper computes the archive-walk's own "did this page add anything real"
decision SEARCH-OBLIVIOUS (always `placeQuery = null`) so a search matching zero cached rows can never
be mistaken for "nothing here, keep paging the network," while the actually-displayed rows apply the
live search text; the two reads collapse into one when no search is active (zero added cost for the
common case). A search-caused `Content(sections = emptyList())` is a new, legitimate, durable
published shape (documented on `HistoryUiState.Empty`'s own kdoc) — `HistoryScreen` folds it and the
true archive-wide `Empty` into one "effectively empty" check, then `searchQuery.isNotBlank()` alone
decides which of two copies renders ("No quakes match 'x'" + Clear search, vs. the pre-existing "no
quakes match these filters" + Widen filters) — so clearing a search that had been applied against a
genuinely-Empty state still shows the original widen-filters treatment, not a bare list.

**Feed filter, `FeedFilterStore` (new, `core:data`)**: same `AlertRuleStore`-shaped persisted
single-value store (meta-table row, `Flow<Double?>`, `updates` SharedFlow, `distinctUntilChanged`) —
`minMag: Double?` (null = "All"), unset-default **4.0** per the user's explicit binding instruction, a
dedicated `"all"` sentinel string tells a genuine stored "All" apart from "never configured." Wired
into `HomeViewModel` as an 11th defaulted constructor param (`feedFilterStore`), mirroring
`visitStore`'s own precedent exactly.

## Coherence design (the tricky part)

Filtering the dashboard list happens ONLY at the `HomeScreen` Compose boundary — a new
`feedFilteredQuakes` local `val` (`state.quakes.filter { quakeMatchesMagFilter(it.mag,
feedFilterMinMag) }`), passed as `FeedSheet`'s/`FeedList`'s own `quakes` param. `HomeViewModel.state`
itself, `content.pins` (map), and `pillStatus(s.quakes, ...)` (the safety pill) all keep reading the
ORIGINAL, unfiltered list — there is no single "filtered state" a future caller could accidentally
wire into the map or the pill, because the filtered list never exists as a StateFlow at all, only as
this one Compose-local value. **This is also what makes `FeedSheet.kt`'s existing reveal/topId-change
wiring filter-coherent for free, with zero edits inside that file's `LaunchedEffect`s**: since a
below-filter arrival is excluded from the list `FeedSheet` receives, `quakes.firstOrNull()`'s id never
changes for it, so the pre-existing T3b "did the top change" logic never fires a reveal/auto-scroll for
something the user can't even see. The one signal that DOES need explicit gating is
`HomeViewModel._newSinceExpand` (the "N NEW" badge/count fed into `feedRevealAction`/
`feedExpandRevealAction`) — it's an accumulating ViewModel-level counter, independent of any
particular list snapshot, so its own `insertedQuakeIds` collector now does one extra `repository.byId
(id)` lookup per genuine arrival and only increments when `quakeMatchesMagFilter(quake?.mag,
feedFilterMinMag)` holds; every OTHER effect that same collector drives (`refreshFailed` clearing,
`refreshGeneration` bumping) stays ungated, since those answer "is data flowing at all," not "does the
list have something new to show." Net effect: an M2.2 arrival while scoped to 4.0+ gets a map pin (pins
unaffected, per the user's own "in the list" scoping) but bumps neither the counter, nor the reveal
chip, nor an auto-scroll, nor the peeking "N NEW" badge — verified on-device (see below), not just in
jvmTest. Changing the filter does NOT retroactively re-derive the already-accumulated
`newSinceExpand` count (accepted, documented scope boundary — it's an arrival-time-gated tally, not a
live re-derivable set) — a raised filter can leave a stale-but-honest "N NEW" badge showing until the
next arrival or `markSheetExpanded()`. The visit-summary banner's own fixed M4.0+ threshold
(`VISIT_SUMMARY_MIN_MAG`) is completely independent of this new, user-adjustable filter (which merely
happens to share the same 4.0 DEFAULT) — cross-referenced in both constants' own kdoc so a future
reader doesn't conflate the two.

## TDD

New/changed test files, all new logic red→green verified (not just written and assumed):
- `core/database/.../QuakeDaoTest.kt` + `InMemoryQuakeStoreTest.kt`: 5 new `pageBetween` `placeQuery`
  cases each (case-insensitive match, uppercase input, AND-composition with `minMag`, null-query
  no-op, no-match-returns-empty).
- `core/data/.../FeedFilterStoreTest.kt` (new, 10 tests): default-when-unset, round-trip (including
  the nullable "All" case), live-update-in-order, corrupt-value-degrades-to-default, `updates`
  emission, cross-instance persistence, the synchronous escape hatch.
- `composeApp/.../filter/MagnitudeFilterTest.kt` (new, 5 tests): the shared chip list's exact
  contents/order, `quakeMatchesMagFilter`'s truth table (null filter matches everything including
  unknown mag; at/above floor matches; below floor and unknown-mag-under-a-real-floor don't).
- `composeApp/.../HistoryViewModelTest.kt` (+6): live substring filter, case-insensitivity, AND-compose
  with magnitude filter, clearing restores the full list, a zero-match search shows an empty `Content`
  **without** an extra network fetch (asserted via `MockEngine` call-count), a page loaded via
  `loadMore()` while a search is active is itself search-filtered.
- `composeApp/.../HomeViewModelTest.kt` (+7): `feedFilterMinMag` default/live-update/write-through,
  the core coherence proof (a sub-threshold arrival never bumps `newSinceExpand`; an at-threshold one
  still does; widening the filter to "All" lets a previously-gated arrival through; the SAME gate
  holds at a user-raised 6.0+ floor, not just the default), and confirmation that a gated arrival still
  clears `refreshFailed` (the gate is scoped to the one counter, not the whole collector).
- Two tests initially had real bugs, caught by actually running them (not just writing them): one
  relied on two StateFlow emissions of an identical value (`StateFlow` conflates — fixed to expect
  one), one nested `withTimeoutOrNull { awaitItem() }` around Turbine's own `awaitItem()` — this exact
  file's own pre-existing "a slow-failed poll..." test already documents why that combination breaks
  (Turbine's internal timeout exception isn't recognized by an enclosing `withTimeoutOrNull`); fixed to
  the same `try { awaitItem() } catch (e: AssertionError)` idiom that test already established.

## Device verify (98bc1cd8, live app data, 2026-08-17)

1. **Fresh-install default 4.0+** — `op9-feed-freshinstall-default4.0plus.png`: uninstalled, reinstalled,
   onboarding skipped; feed sheet header reads "4.0+ ▾" on the very first cold start, with real M4.9
   Indonesia quakes already showing.
2. **Feed filter menu + change** — `op9-feed-filter-changed-to-6.0plus.png`: tapped the chip, the
   shared "All/4.0+/5.0+/6.0+" `DropdownMenu` opened; picked 6.0+; list immediately re-filtered to
   empty ("Quiet right now" — no M6+ globally in the last 24h at verification time).
3. **Persists across restart** — `op9-feed-filter-persists-after-restart.png`: `am force-stop` +
   relaunch; chip still reads "6.0+" with no further interaction — a real SQLite meta-table
   round-trip, not in-memory session state.
4. **Coherence: sub-threshold arrival, filter 4.0+** — `op9-feed-M2.2-inject-no-NEW-badge.png` +
   `op9-feed-M2.2-inject-detail-confirms-mag.png`: reset filter to 4.0+, long-pressed the map to
   debug-inject (temporarily re-pointed at `mag = 2.2` for this one verification pass only — see
   Concerns — reverted before commit). A pin appeared on the map (pins unaffected, confirmed) but the
   header's "NEW" badge never appeared and the list never changed; tapping the new pin confirmed
   "[DEBUG] Injected M2.2" — the actual injected magnitude, not a guess.
5. **History search, live** — `op9-history-search-indo-live-filter.png`: typed "INDO" (uppercase);
   list narrowed to exactly the Indonesia-place rows (Flores Region ×3, Ruteng) live, no submit needed.
6. **History search, empty state** — `op9-history-search-empty-state.png`: extended the query to a
   non-existent place; "No quakes match "indonesiazzznomatch"" + Clear search, verbatim per spec.
7. **Clear search restores list** — `op9-history-search-cleared-restores-list.png`: tapped Clear
   search; full list (incl. the still-present `[DEBUG] Injected M2.2` row — History has no
   origin-based exclusion, pre-existing/unrelated behavior) reappeared.
8. **History chips** — `op9-history-chips-5.0plus.png`: tapped "5.0+"; list re-paged and settled on
   exactly the M5.0+ rows.
9. **Crash sweep**: `adb logcat -d -b crash` and an `AndroidRuntime: E` filter, both empty across the
   entire session (uninstall/reinstall, onboarding skip, 2 app rebuild+reinstall cycles, filter
   changes, force-stop/relaunch, debug injection, History search/chip taps).
10. **Debug quake left in local DB** — same existing, unconditional `purgeDebugQuakes()` startup
    housekeeping as every prior round; no manual cleanup performed or required.

Screenshots: `docs/qa/review-round-3/op9-feed-*.png` (5 files), `op9-history-*.png` (4 files).

## Tests / compiles

- `./gradlew jvmTest --max-workers=4` (all modules) — **BUILD SUCCESSFUL**: composeApp 273, core:model
  25, core:network 50, core:database 138, core:data 187, core:ui 61, core:monetization 9, core:ads 12
  — **755 tests total, 0 failures, 0 errors** (up from round 3's own 717 baseline; +10 database, +10
  data, +18 composeApp).
- `./gradlew allTests --max-workers=4` (every KMP target incl. `wasmJsBrowserTest`) — **BUILD
  SUCCESSFUL**.
- `./gradlew :composeApp:compileDebugKotlinAndroid :composeApp:compileKotlinJvm
  :composeApp:compileKotlinWasmJs --max-workers=4` — all 3 targets green.
- `./gradlew :composeApp:assembleDebug --max-workers=4` — green; arm64 APK installed via `adb install
  -r` for the device-verify pass above (3 install cycles total: clean uninstall+install for the
  fresh-default check, one reinstall for the temporary low-mag debug hook, one final reinstall of the
  reverted/committed code).

## Concerns

- **Temporary debug-hook magnitude, reverted before commit**: `HomeViewModel.injectDebugQuake` gained
  a defaulted `mag: Double = 6.0` param (permanent, zero behavior change for the real long-press
  gesture) specifically so this pass could device-verify the sub-4.0 coherence gate without touching
  `QuakeMap.android.kt`'s own carefully-tuned, already-once-device-debugged long-press gesture-timing
  code (judged disproportionate risk for a one-off QA need). `HomeScreen.kt`'s own call site was
  temporarily edited to `mag = 2.2` for exactly the one verification screenshot, then reverted — `git
  diff`/the commit itself carries no trace of the temporary value, only the permanent, defaulted
  parameter.
- **Feed sheet's empty-state copy is generic, not filter-aware**: a strict filter (e.g. 6.0+) that
  happens to leave zero visible rows shows the SAME "Quiet right now — no quakes in the last 24h" copy
  a genuinely quiet unfiltered 24h window would — not filter-labeled the way History's own search-empty
  state is. Not explicitly requested for the feed sheet (unlike History's own verbatim-specified "No
  quakes match 'x'"); accepted as a minor, honestly-flagged UX imprecision rather than adding a fourth
  empty-state variant beyond this task's actual scope.
- **`newSinceExpand` is not retroactively re-scoped on a filter change** — see Coherence design above;
  a deliberate, documented simplification, not an oversight.
- **TwoPaneLayout (desktop/tablet) gets the filtered DATA, not a filter UI control** — consistent with
  this project's own Android-only device-verified scope; the panel reflects whatever the phone sheet
  (or a future desktop control) last set. Untested beyond a jvm/wasmJs compile check, same as every
  other TwoPaneLayout-only path in this codebase.
- **History's `place LIKE` predicate does not escape literal `%`/`_`** in user-typed search text — an
  accepted v1 gap (single-user local search box, not adversarial input; no real USGS/EMSC place string
  contains either character).
- All 755 jvmTest cases and both `allTests`/3-target-compile/`assembleDebug` gates are green; no
  pre-existing failures were newly introduced by this pass (spot-checked `HistoryViewModelTest`/
  `HomeViewModelTest`/`QuakeDaoTest`/`InMemoryQuakeStoreTest` test counts before/after this diff).

---

# Review round 3 — Inter font rollout — RESULTS

Implements `docs/superpowers/plans/2026-08-17-font-selection.md`'s decision (Inter, weights
400/500/600, repo-wide Bold→SemiBold sweep) — the doc itself was research/decision-only, no app
code. Device verification: OnePlus 9R `98bc1cd8` (same device as both sections above).

## Bundling decision: static TTFs, not the variable file — and not where the task said

**Obtained**: `rsms/inter` v4.1 (`gh api repos/rsms/inter/releases/latest` — real repo, "The Inter
font family," 19,813 stars; single release asset `Inter-4.1.zip`, GitHub-reported size 33,707,794 B,
downloaded size byte-identical, sha256 `9883fdd4a4…54935a`). Doc's own note that `rsms/inter` "only
ships a full multi-format release ZIP… rather than individually-addressable static files" confirmed
firsthand.

**Doc's static-size estimate did not hold, measured directly**: doc's Part 5 guessed
~100–300 KB/static-instance; the release zip's real `extras/ttf/Inter-{Regular,Medium,SemiBold}.ttf`
measure 411,640 / 417,300 / 419,744 B (~400 KB each — full multi-script glyph set, unsubsetted,
matching upstream as shipped) — **~1.22 MB combined, larger than the single `InterVariable.ttf`
(879,708 B)**. So on raw bytes alone the variable file wins. Chose static anyway, on the task's own
tie-breaker ("pick whichever path is smaller/simpler given Compose Multiplatform Font() support"),
because CMP's variable-`wght`-axis path is independently verified shaky, not just per the task's
prior — two **open** JetBrains issues on exactly this app's non-Android compile targets:
`JetBrains/compose-multiplatform#3127` (no `FontVariation` axis application on iOS/desktop) and
`#4635` (`wasmJs` renders variable fonts as garbled glyphs) — and JetBrains' own official
resources-usage doc (`kotlinlang.org/docs/multiplatform/compose-multiplatform-resources-usage.html`,
fetched directly) bundles Inter itself as two separate static files (`Inter_24pt_Regular`/
`Inter_24pt_SemiBold`), never a variable-axis family. `tnum`/`pnum` presence is not a differentiator
either way — verified by direct `fontTools` GSUB dump (not secondary marketing text) against **all
four** candidate files (3 statics + the variable): all four carry `tnum=True pnum=True`, identical.
Static wins on "proven path, non-Android targets this app actually compiles for," not on size.

**Location deviates from the task text** (`composeApp/…/composeResources/font/`) **to
`core/ui/src/commonMain/composeResources/font/`** — architecturally required, not a judgment call:
`TerraTheme.kt` (the sole consumer, wiring step below) lives in `core:ui`; compose-resources'
generated `Res` accessor is scoped to whichever module owns the `composeResources` directory, and
`composeApp` depends on `core:ui`, never the reverse, so a font placed under `composeApp` would be
unreachable from `TerraTheme.kt` by construction. Confirmed via `./gradlew :core:ui:dependencies
--configuration jvmCompileClasspath` (module graph) and by actually compiling (below).

`OFL.txt` → `core/ui/src/commonMain/composeResources/files/OFL.txt`, **not** inside `font/` alongside
the TTFs — JetBrains' own directory convention reserves `font/` for font assets and `files/` for
"other files with any folder hierarchy"; a stray `.txt` inside `font/` risks the resource generator
treating it as a font asset. Attribution note added to `SettingsScreen.kt`'s existing `AboutContent`
(matches its established one-line-per-credit pattern): `"Inter font © The Inter Project Authors, SIL
OFL 1.1"` — confirmed rendering, single line, no clipping (`font-04b-settings-about.png`).

## Wiring (`TerraTheme.kt`)

`terraFontFamily()` (new, `@Composable`, deliberately **unmemoized** — matches JetBrains' own
canonical sample verbatim: on `wasmJs`, font-resource loading is async and recomposition is how real
glyphs replace the fallback once bytes arrive, so wrapping in `remember` risks freezing every target
on the fallback face) builds one `FontFamily` from `Font(Res.font.inter_regular, FontWeight.Normal)` /
`inter_medium, FontWeight.Medium` / `inter_semibold, FontWeight.SemiBold`. Generated accessor package
turned out to be `terrawatch.core.ui.generated.resources` (Gradle-module-path-derived, not the
`com.yugma.terrawatch.ui` Android namespace guessed first) — found by actually compiling and reading
the generated `Res.kt`, not assumed.

`terraTypography()` (new, `@Composable`) applies that family to **all 15** M3 type-scale roles via
`base.<role>.copy(fontFamily = family)`, sizes/line-heights untouched. **Not** via
`Typography(defaultFontFamily = …)`: `javap`'d the actual resolved
`org.jetbrains.compose.material3:material3:1.8.2` jar (confirmed as this project's real resolution,
not assumed, via `./gradlew :core:ui:dependencies`) — its `Typography` constructor takes exactly 15
`TextStyle` params, no `FontFamily`-typed 16th. AndroidX's own `defaultFontFamily` convenience landed
in `androidx.compose.material3` `1.5.0-alpha19` (confirmed via search); the JetBrains CMP fork's
material3 hasn't caught up to that revision line at this pin. Explicit per-role wiring is the correct
fix, not a workaround.

`titleLarge`/`headlineMedium` (the theme's pre-existing magnitude-numeral emphasis) move from Bold to
**SemiBold** in this same change — see sweep decision below for why this theme-level pair, not just
the "27 scattered" call sites, was in scope.

## Weight sweep: Bold(700) → SemiBold(600) — 28 sites, not 27, and why

Doc's Part 5 concern #1: bundling only 400/500/600 means any `FontWeight.Bold` synthesizes faux-bold.
Decision (per this task): sweep to SemiBold, no faux bold anywhere. Grepped fresh rather than trusting
the doc's headline count (its own instruction: "grep exact list"): **26** real call sites outside
`TerraTheme.kt` (doc said 27; actual `FeedSheet.kt` has **4** — `reveal chip`, `"N NEW"` badge,
`FeedFilterControl` label, `LiveStatusRow`'s conditional — not the doc's "×3, one conditional") across
12 files — `DetailSheet.kt` (1), `HistoryScreen.kt` (2), `FeedSheet.kt` (4), `InsightsScreen.kt` (1),
`OnboardingScreen.kt` (4), `PaywallScreen.kt` (3), `SettingsScreen.kt` (4), `MagnitudeBadge.kt` (1),
`QuakeCard.kt` (1), `StatRow.kt` (1), `StatusShield.kt` (3), `TsunamiBanner.kt` (1) — **plus
`TerraTheme.kt`'s own 2** (`titleLarge`/`headlineMedium`), swept in the same pass: the doc's own
Part 5 item 4 warns that leaving this undecided "silently ships faux-bold on **every magnitude badge**
and headline" — magnitude-hero display styling flows through `titleLarge` (`DetailSheet.kt:263`,
comment-tagged "TerraTypography already bolds this role"), so the theme-level pair is squarely inside
the same faux-bold problem the doc names, not a separate concern. **28 sites total**, verified
zero-remaining via `grep -rn "FontWeight\.Bold"` after the sweep (only hits: `TabularFiguresTest.kt`'s
2 unit-test fixture values — a `TextStyle.Default.copy(fontWeight = …Bold)` used purely to assert
`.tabularFigures()` preserves an arbitrary pre-existing weight, not a rendered UI call site — left
untouched, and this file's own kdoc prose mentioning the historical call sites by name).

**Medium-demotion considered, not applied anywhere** (task: "only where obviously redundant"):
examined every Bold-adjacent-to-Bold candidate found while reading each site's context —
`FeedSheetHeader`'s 3 small badges in one `Row` (`LIVE` label / reveal-or-`NEW`-badge / filter-control
label) and the `MagnitudeBadge`-next-to-bold-text pattern (`QuakeCard`, `StatusShield.AlertContent`).
Neither judged "obviously redundant": the first is 3 parallel sibling badges answering different
questions, not a title/subtitle hierarchy pair; the second pairs a colored/boxed numeral (already
visually self-differentiated by container, not just weight) with plain text. Mechanical 700→600
everywhere; `FontWeight.Medium` sites untouched. **Incidental, out-of-scope finding**: 3 of
`OnboardingScreen.kt`'s 4 swept sites (`headlineMedium` + explicit weight override) were already
100%-redundant with the theme's own `headlineMedium` default even before this task — not fixed here
(not what was asked; net visual result is identical either way now that both sides say SemiBold).

## Tabular numerals

GSUB `tnum` presence confirmed above (all 4 candidate files). No subsetting/instancing was performed
(official release files used as-shipped), so the doc's Concerns-section worry ("doesn't guarantee it
survived any subsetting step") doesn't apply here. Device sanity: Insights "BY MAGNITUDE" counts
(488/63/88/5, mixed 1–3 digit values) render with visually uniform per-digit advance width —
`font-05-insights.png`.

## Cross-target

`./gradlew jvmTest allTests --continue` — **BUILD SUCCESSFUL**, 755 tests / 0 failures / 0 errors
(identical total to the pre-existing round-3 baseline above — this pass changes weight *values* and
adds font wiring, no test cases added or removed). Same invocation compiled all 3 targets clean as a
side effect: `compileKotlinWasmJs`, `compileKotlinJvm`, `compileDebugKotlinAndroid` +
`compileReleaseKotlinAndroid`, for both `composeApp` and `core:ui` (only pre-existing
`expect`/`actual`-in-Beta warnings, unrelated). `wasmJsBrowserTest` SKIPPED (no browser test runner
configured in this environment — pre-existing, not a new gap). `./gradlew :composeApp:assembleDebug`
— green.

## APK size delta (measured, not estimated)

Baseline: a same-commit (`63866d8`), pre-change `assembleDebug` output already sitting in
`composeApp/build/outputs/apk/debug/` (preserved to scratch before rebuilding). Per-ABI debug splits
(fonts are non-native assets, so the delta is ABI-agnostic — identical on both):

| APK | Before | After | Delta |
|---|---|---|---|
| `arm64-v8a-debug` | 38,938,878 B | 39,576,285 B | **+637,407 B (+1.64%)** |
| `armeabi-v7a-debug` | 34,762,922 B | 35,400,329 B | **+637,407 B** |

`unzip -l` on the built APK confirms the exact 3 font files + `OFL.txt` are actually packaged at
`assets/composeResources/terrawatch.core.ui.generated.resources/{font,files}/` — raw font bytes
(~1.22 MB + 4,380 B license) compress down to a 637 KB zip-entry delta, in line with typical TTF
deflate ratios.

## Device verify (98bc1cd8, OnePlus 9R, live app data, 2026-08-17)

**Objective on-device A/B, not eyeballing**: reinstalled the preserved pre-change baseline APK,
screenshotted the Home feed's "FLORES SEA" card (fixed string, fixed style/size), then reinstalled the
post-change build and repeated. Pixel bounding-box measurement of the identical title string: **227 px
wide (pre-change system-default font) vs 216 px wide (post-change, Inter)** — same device, same
string, same role/size, only the bundled `FontFamily` differs between installs. Confirms the font
genuinely changed at render time, not just in source.

Required screens, all captured post-change, final build reinstalled and left on-device:
`docs/qa/review-round-3/font-01-home-feed.png` (Home/feed, magnitude badges + region names),
`font-02-detail-sheet.png` (hero magnitude, stat trio, tsunami banner, coordinates/source `StatRow`s),
`font-03-history.png` (title, filter chips, sticky month header, list), `font-04-settings.png` +
`font-04b-settings-about.png` (sliders, rows, THEME radios, new Inter attribution line),
`font-05-insights.png` (bar chart + `BY MAGNITUDE` tabular counts + `STRONGEST` hero). No clipped
text, no tofu/missing-glyph boxes, SemiBold titles read as emphasized without shouting next to Regular
body text. Crash sweep not re-run this pass (no new crash-prone code paths — pure typography/resource
wiring); no `AndroidRuntime: E`/crash-tagged output observed incidentally across the whole session.

**Honesty note (mid-session hiccup, disclosed rather than omitted)**: partway through screen capture
the device transiently surfaced a lock-screen / `EmergencyDialer` / `NotificationShade` focus sequence
— root-caused to a screen-timeout auto-lock (not any credential prompt bypassed or attempted;
`mCallState=0` confirmed idle throughout, no call placed), resolved with `KEYCODE_WAKEUP` + one `BACK`
press, zero PIN/pattern entry. Also observed other agents' own `uiautomator` dump files already
present on `/sdcard` (`window_dump{3,4,5,6}.xml`, not created by this pass) — this device is shared
across concurrently-running agent sessions on this branch, a latent cross-talk risk worth naming for
future device-verification passes on `98bc1cd8`, not something this pass could control or fully rule
out as the cause of that transient hiccup.

## Concerns

- **Static bundle (~1.22 MB) is larger than the variable-file alternative (~860 KB) in raw bytes** —
  chosen anyway for proven-path/cross-platform-safety reasons (two open JetBrains CMP issues on this
  app's own non-Android targets), an explicit size-vs-robustness tradeoff, not a strict win on both
  axes. Documented in `TerraTheme.kt`'s own kdoc, not just here.
- **Font location deviates from the task's literal `composeApp/…` path** — moved to `core/ui/…`
  because the task's own step 2 (wire into `TerraTheme.kt`) is only achievable from there; see above.
- **wasmJs/jvm targets compile clean but are not visually verified** — matches this project's standing
  Android-only device-verification scope (memory: desktop/web deferred). Choosing static specifically
  sidesteps the known `wasmJs` variable-font mojibake bug preemptively; this pass did not additionally
  confirm `wasmJs` renders Inter correctly pixel-for-pixel, since that's out of the declared scope
  either way.
- **Device (`98bc1cd8`) is shared with other concurrently-running agent sessions on this branch** —
  observed leftover artifacts from at least one other session; a real, undismissed source of possible
  interference for any device-verification pass, this one included.
- **3 of `OnboardingScreen.kt`'s 4 swept call sites were already redundant with the theme default**
  before this task (both now say SemiBold, so behavior is unchanged either way) — noted, not fixed;
  out of this task's scope.
- Crash sweep (`adb logcat -d -b crash`) was not explicitly re-run this pass, unlike the two sections
  above — this change touches no lifecycle/async/permission code, only typography, so judged
  lower-risk; no crash-tagged logcat output was observed incidentally during the session either way.
