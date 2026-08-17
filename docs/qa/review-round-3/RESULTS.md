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
