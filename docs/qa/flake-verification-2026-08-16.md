# HomeViewModelTest Cross-Test `Dispatchers.Main` Race — Extended Verification (2026-08-16)

Worktree: `tw-flake-verify`, `main` @ `7932c04` (includes seam fix `2cb5e8c`, one merge commit ahead
of it — no drift between the fix and this verification). Task: confirm whether `2cb5e8c` ("Give
HomeViewModel a pinnable background-dispatcher seam, killing the last structural jvmTest flake")
actually killed the flake the original brief reported (baseline `77a085f`: HomeViewModelTest failing
~1-in-7-10 runs, `IllegalStateException` from kotlinx-coroutines-test's `TestMainDispatcher`,
random victim test, attributed to `tearDown()`'s `Dispatchers.resetMain()`).

**Verdict: REPRODUCED — but not the race the brief described.** The three crossings `2cb5e8c`
targeted (HomeViewModel's own `ioDispatcher`, QuakeRepository's `ioDispatcher` threaded through the
test fakes, `QuakeDao.recent()`'s `dispatcher` param) show **zero** failures across 80 runs. One
failure occurred, and it reproduces the *exact* signature via the *exact* crossing `2cb5e8c`'s own
commit message already named and deliberately left unpinned: `QuakeDao.favoritePlaces()`. See
"Root cause of the one failure" below for the evidence chain.

## Method

- Environment: `JAVA_HOME=jbr-17.0.14`, `GIT_CONFIG_GLOBAL=/tmp/tw-gitconfig`, shared Gradle user
  home (`~/.gradle`, artifact cache only — safe to share; the worktree itself is what keeps this
  session's project state isolated from the sibling agent working in `terrawatch`). Gradle 8.14,
  project Kotlin 2.2.20, `kotlinx-coroutines` **1.10.2** (confirmed via
  `gradle/libs.versions.toml:9`, matching the brief). `--max-workers=2` on every invocation
  (machine shared with a sibling agent's parallel builds in the main `terrawatch` repo).
- All runs foreground (backgrounded loops were reported killed externally in a prior session) —
  each `./gradlew` invocation run to completion one at a time, in scripted batches of 10, from
  `/private/tmp/.../scratchpad/run_home_vm_loop.sh` (plain) and `run_home_vm_loop_debug.sh`
  (`JAVA_TOOL_OPTIONS=-Dkotlinx.coroutines.debug=on`, confirmed to actually propagate into the
  forked test JVM via the "Picked up JAVA_TOOL_OPTIONS" banner).
- Command: `./gradlew :composeApp:jvmTest --tests "com.yugma.terrawatch.home.HomeViewModelTest" --rerun-tasks --max-workers=2`.

## Results

| Batch | Iterations | Result |
|---|---|---|
| Plain, 1–10 | 10 | 10/10 PASS |
| Plain, 11–20 | 10 | 10/10 PASS |
| Plain, 21–30 | 10 | 10/10 PASS |
| Plain, 31–40 | 10 | 10/10 PASS |
| Plain, 41–50 | 10 | **9/10 PASS — iteration 41 FAILED** |
| Debug (`-Dkotlinx.coroutines.debug=on`), 1–10 | 10 | 10/10 PASS |
| Debug, 11–20 | 10 | 10/10 PASS |
| Debug, 21–30 | 10 | 10/10 PASS |
| **Total** | **80** | **79/80 PASS, 1 FAIL (1.25%)** |

For comparison, `2cb5e8c`'s own commit message reports the pre-fix/intermediate rates on the exact
same signature: baseline ~10–15%, Round 1 (VM seam alone) 3/26 (~11.5%), Round 2 (+ repository seam)
1/30 (~3.3%), Round 3 (+ DAO `recent()` seam) 30/30 twice. 1/80 (1.25%) is consistent with "Round 3
closed the dominant crossing" and inconsistent with "the original race is still at large" — this
run is not a return to the ~10-15% baseline, it's a residual an order of magnitude smaller.

The 50/50-clean gate for the planned cross-class `:composeApp:jvmTest --rerun-tasks` 10x check was
not met, so that check was **not run** — it would not have added information the scoped result
above doesn't already give more cheaply, and the machine is shared with another agent's builds.

## The one failure — full evidence chain

**Victim:** `a throw during refresh marks failed and survives, a later retry clears it` (iteration
41, plain mode). Full stack (from the JUnit XML, not just console tail):

```
java.lang.IllegalStateException: Dispatchers.Main is used concurrently with setting it
	at kotlinx.coroutines.test.internal.TestMainDispatcher$NonConcurrentlyModifiable.concurrentRW(TestMainDispatcher.kt:72)
	at kotlinx.coroutines.test.internal.TestMainDispatcher$NonConcurrentlyModifiable.setValue(TestMainDispatcher.kt:85)
	at kotlinx.coroutines.test.internal.TestMainDispatcher.resetDispatcher(TestMainDispatcher.kt:41)
	at kotlinx.coroutines.test.TestDispatchers.resetMain(TestDispatchers.kt:34)
	at com.yugma.terrawatch.home.HomeViewModelTest.tearDown(HomeViewModelTest.kt:204)
```

Exact signature match to the brief. The same run also threw a direct `OutOfMemoryError: Java heap
space` in an unrelated test (`newSinceExpand increments once per newly inserted quake`) and two more
`OutOfMemoryError`s from `DefaultDispatcher-worker-10`/`-13`'s uncaught exception handlers. System
memory at the time: `top`/`vm_stat` showed **23G used, ~150M unused, 10-11G in the compressor** —
the host was near its memory ceiling (consistent with the brief's own note that this machine runs
sibling-agent builds concurrently). I did not stop or restart the Gradle daemon or touch any process
outside this worktree to investigate this — the daemon (`--status`, PID unchanged throughout) was
left running, since it may be shared with the sibling agent's builds.

**Root cause, confirmed via source, not guessed:**

1. `HomeViewModelTest.kt:1133-1137` — `emptyFavoritePlaceStore()` builds
   `FavoritePlaceStore(QuakeDao(TerraWatchDb(driver)))` with **no `dispatcher` argument**, so it
   defaults to real `Dispatchers.Default` (`QuakeDao`'s ctor default, `core/database/.../QuakeDao.kt`).
   This is the *only* favorite-place-store builder in the file; the three `favorites`-specific tests
   also use it (just `.apply { add(...) }` afterward) — no test anywhere in this file threads a
   pinned dispatcher into it.
2. `FavoritePlaceStore.kt:24` — `val favorites: Flow<List<FavoritePlace>> = dao.favoritePlaces()`, a
   direct passthrough.
3. `QuakeDao.kt` — `favoritePlaces()` is `.mapToList(dispatcher)` — real `Dispatchers.Default` here,
   unlike `recent()` which every `HomeViewModelTest` fake now pins (that's what `2cb5e8c` fixed).
4. `HomeViewModel.kt:411` — `favoritePlaceStore.favorites.collect { places -> _favorites.value = places }`
   runs inside a plain (Main-dispatched) `viewModelScope.launch { ... }` in `init{}`, **unconditionally,
   for every HomeViewModel this suite ever constructs** — not just the `favorites`-named tests. Every
   emission requires hopping from a real `Dispatchers.Default` thread back onto `Dispatchers.Main`.
5. `HomeViewModelTest.kt:203-207` — `tearDown()` calls `Dispatchers.resetMain()` **before**
   `cancelAndJoin()` (Task-13's Fix Round 1 ordering, required reading per that class's own kdoc, to
   avoid a worse `UnconfinedTestCoroutineDispatcher` crash). This leaves a window where the
   still-alive `favoritePlaces()` collector's real background thread can touch `Dispatchers.Main` at
   the exact instant the main test thread is swapping it out — a genuine cross-thread race on the
   same global, independent of victim-test identity (matches the brief's "different victim each
   occurrence, no pattern": whichever test happens to be mid-`tearDown()` when the window lands is
   the victim).

`2cb5e8c`'s own commit message names this crossing explicitly and calls the risk correctly: *"left
deliberately unpinned, a documented residual... the 30/30 result across two full runs already
demonstrates this residual crossing is not, on its own, contributing at a measurable rate."* This
verification's 1/80 makes it measurable, but still small, and does not contradict that judgment —
it refines it from "no observed rate" to "roughly 1%".

**Why this isn't just "the OOM confused things":** checked
[Kotlin/kotlinx.coroutines#3395](https://github.com/Kotlin/kotlinx.coroutines/issues/3395) — the
`NonConcurrentlyModifiable` check is confirmed **working as designed**, an intentional detector of a
real concurrent touch of `Dispatchers.Main`, not a library defect that misfires under load. Severe
memory pressure plausibly *widened* the resetMain-before-cancelAndJoin window (slower GC/scheduling
stretches every critical section), making a rare race more likely to land in this run — consistent
with, not contradictory to, the source-level mechanism in steps 1-5 above being the actual cause.

## coroutines-test version note

Confirmed pinned version: `kotlinx-coroutines = "1.10.2"` (`gradle/libs.versions.toml:9`). Per
WebSearch, current release is **1.11.0** (paired with Kotlin 2.2.20, this project's own Kotlin
version — so the upgrade would be toolchain-compatible). Changelog between 1.10.2 and 1.11.0: the
only `kotlinx-coroutines-test`-relevant entry found was advancing deprecation levels on existing
test APIs (#4604) — **no changelog entry, PR, or issue found that changes `TestMainDispatcher`,
`setMain`/`resetMain` behavior, or the `NonConcurrentlyModifiable` concurrency check**. Combined
with #3395's "working as designed" status (open issue asking only for *better diagnostics* — richer
stack frames identifying the concurrent reader/writer — not a behavior change), an upgrade past
1.10.2 would not be expected to change whether this exception fires, only (possibly, if #3395 is
ever actioned) how easy it is to diagnose when it does. **Recommendation: do not pursue the
coroutines-test upgrade as a fix for this residual** — it targets the wrong layer.

## Seam-vs-hypothesis mapping

| Crossing | Pinned by `2cb5e8c`? | Failures observed here |
|---|---|---|
| `HomeViewModel` init location/camera-target resolution (`Dispatchers.Default`) | Yes — `ioDispatcher` ctor param | 0/80 |
| `HomeViewModel.recenterToCurrentLocation()` | Yes — same `ioDispatcher` | 0/80 |
| `HomeViewModel` state collector `.flowOn(Dispatchers.Default)` | Yes — same `ioDispatcher` | 0/80 |
| `QuakeRepository`'s own `ioDispatcher` (pre-existing) threaded through test fakes | Yes — all `fakeRepository*()` helpers now pass it | 0/80 |
| `QuakeDao.recent()`'s `.mapToList(Dispatchers.Default)` | Yes — new `dispatcher` ctor param, threaded through every dao construction that feeds a test repository | 0/80 |
| `QuakeDao.favoritePlaces()`'s `.mapToList(Dispatchers.Default)` (via `emptyFavoritePlaceStore()`) | **No — deliberately, per commit message** | **1/80** |

## Recommended next step

The seam this residual needs already exists — `QuakeDao`'s `dispatcher` ctor param (added by
`2cb5e8c` itself) already covers `favoritePlaces()`, it's just not threaded through
`emptyFavoritePlaceStore()` (and the `favoritePlaceStore` param generally) in `HomeViewModelTest`.
This is brief option (a) in miniature — a small, mechanical, same-shape follow-up to what `2cb5e8c`
already did three times, not a new design. Per the "no code fixes without 30x proof" rule, this
verification does not implement it. Recommended: thread a pinned `dispatcher` into
`emptyFavoritePlaceStore()` (and any other favorite-store test builder), then re-run this same 80x
protocol before merging. Until that lands, brief option (c) — accept the documented residual,
add a bounded CI retry (1 automatic retry) on `HomeViewModelTest` — is the reasonable interim
stance; it already matches this codebase's own established precedent (`InsightsViewModelTest`'s
kdoc documents an identical, never-eliminated DAO-level crossing on the same "not worth the added
surface" reasoning).

## What this verification did not do

- Did not run the cross-class 10x `:composeApp:jvmTest` check (gated behind a 50/50 clean scoped
  result, which this run did not reach).
- Did not implement the `favoritePlaceStore` pinning fix above — no code changes accompany this
  document, only this report.
- Did not deep-dive the `OutOfMemoryError`s beyond confirming system-wide memory pressure as a
  plausible, evidence-backed contributing condition — they hit an unrelated test and are not the
  subject of the brief.
