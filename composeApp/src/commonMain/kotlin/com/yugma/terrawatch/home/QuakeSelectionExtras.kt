package com.yugma.terrawatch.home

import androidx.compose.runtime.Composable
import androidx.lifecycle.viewmodel.CreationExtras

/**
 * Task 9 (Plan 3), web-enablement — a real bug found (not anticipated) wiring wasmJs's real
 * `App()` for the first time in this plan: `QuakeSelectionViewModel`'s `SavedStateHandle`
 * constructor param (Task 3, Plan 3 — Android process-death restore) is resolved by Koin via an
 * `AndroidParametersHolder` that UNCONDITIONALLY calls `CreationExtras.createSavedStateHandle()`
 * (see that class's own kdoc + `QuakeSelectionViewModel.kt`'s "Koin wiring note" — verified again
 * here by decompiling `koin-core-viewmodel-jvm-4.1.0.jar`'s `AndroidParametersHolder.class`), which
 * in turn requires the ambient `CreationExtras` to already carry a `SAVED_STATE_REGISTRY_OWNER_KEY`.
 *
 * On Android, `ComponentActivity` supplies that automatically (it's a real
 * `SavedStateRegistryOwner`/`HasDefaultViewModelProviderFactory`, wired in by
 * `setContent {}`) — confirmed working across 8 prior device-verified tasks. On wasmJs,
 * `ComposeViewport`'s own default `LocalViewModelStoreOwner` is NOT also a
 * `SavedStateRegistryOwner`, so the same call throws `IllegalArgumentException: "CreationExtras
 * must have a value by SAVED_STATE_REGISTRY_OWNER_KEY"` — an uncaught runtime exception that
 * crashed the ENTIRE `App()` composition (nothing rendered at all, not even Home) the first time
 * this was actually run in a browser. Real, observed browser console output, not a theoretical
 * concern (see task-9-report.md's Browser Verify section for the verbatim stack trace).
 *
 * `QuakeSelectionViewModel`'s restore-on-relaunch feature is ALSO structurally meaningless on
 * wasmJs regardless of this fix: [com.yugma.terrawatch.database.InMemoryQuakeStore] (this same
 * task's storage fallback) holds nothing across a page reload, so even a working
 * `SavedStateHandle` would restore an id that `QuakeRepository.byId` can no longer resolve,
 * settling on null exactly like no selection at all. This function's ONLY job on wasmJs is
 * therefore to satisfy Koin's unconditional precondition so `koinViewModel<QuakeSelectionViewModel>()`
 * doesn't crash — not to reproduce genuine cross-reload persistence, which nothing on this
 * platform provides yet.
 *
 * Returns `null` on every platform except wasmJs (Android/jvm keep the EXACT existing behavior,
 * unchanged, unverified-but-untouched by this task for jvm/desktop — see the jvm actual's own
 * note): `App()` below only overrides `koinViewModel`'s own `extras` argument when this returns
 * non-null, so Android's real Activity-backed extras (and whatever jvm/desktop's own default
 * already is) are never touched.
 */
@Composable
expect fun rememberQuakeSelectionExtras(): CreationExtras?
