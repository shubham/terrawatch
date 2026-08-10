package com.yugma.terrawatch.home

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.SAVED_STATE_REGISTRY_OWNER_KEY
import androidx.lifecycle.VIEW_MODEL_STORE_OWNER_KEY
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.enableSavedStateHandles
import androidx.lifecycle.viewmodel.CreationExtras
import androidx.lifecycle.viewmodel.MutableCreationExtras
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner

/**
 * Desktop hotfix (post-Task 9, Plan 3) for Concern 1 in `task-9-report.md`: this actual used to
 * return `null` unconditionally, on the documented assumption that jvm/desktop was out of Plan 3's
 * scope and its behavior here therefore "unverified-but-untouched" (see git history / that report's
 * Concern 1). That assumption turned out wrong the first time the desktop app was actually launched
 * (not just `compileKotlinJvm`'d) rather than merely compiled: Compose Multiplatform desktop's
 * `Window { }` (this project's `main.kt`) does NOT supply a [SavedStateRegistryOwner] the way
 * Android's `ComponentActivity` does, so `null` here left `koinViewModel<QuakeSelectionViewModel>()`'s
 * `extras` at its own unmodified default, and Koin's `AndroidParametersHolder` crashed the entire
 * composition on launch trying to resolve [QuakeSelectionViewModel]'s `SavedStateHandle` constructor
 * param: `IllegalArgumentException: "CreationExtras must have a value by
 * SAVED_STATE_REGISTRY_OWNER_KEY"`. Identical root cause to the one Task 9 found and fixed on
 * wasmJs (see `QuakeSelectionExtras.wasmJs.kt`'s kdoc) — desktop just hadn't actually been run
 * interactively yet for it to surface there too.
 *
 * Ported verbatim from `QuakeSelectionExtras.wasmJs.kt`'s synthetic-owner approach (see that file's
 * kdoc for the full derivation, read from `lifecycle-viewmodel-savedstate`'s real source rather than
 * guessed): a minimal, self-contained [SavedStateRegistryOwner] + [ViewModelStoreOwner] whose ONLY
 * job is satisfying `androidx.lifecycle.createSavedStateHandle()`'s preconditions — a fresh
 * [LifecycleRegistry] starts at [Lifecycle.State.INITIALIZED], exactly the state both
 * [SavedStateRegistryController.performRestore] and [enableSavedStateHandles] require, so no
 * lifecycle transition is needed here either. No new Gradle dependency: `org.jetbrains.androidx.
 * lifecycle:lifecycle-viewmodel-compose` (`libs.androidx.lifecycle.viewmodel`, declared once in
 * `commonMain.dependencies`) is a JetBrains Kotlin Multiplatform artifact whose `androidx.lifecycle`/
 * `androidx.savedstate` packages already redirect to real classes on jvm, same as on wasmJs.
 *
 * Deliberately NOT a faithful "real" SavedStateRegistryOwner here either — no bundle serialization,
 * no wiring into `main.kt`'s `Window` lifecycle. Desktop selection restore-on-relaunch is therefore
 * inert (a fresh synthetic owner, and thus a fresh empty registry, every process start) — acceptable
 * for the same reason wasmJs accepted it: nothing on desktop persists `QuakeSelectionViewModel`'s
 * selection across a process restart yet for a restored id to resolve against anyway. Genuine
 * desktop SavedState persistence (if ever wanted) is a Plan-4-or-later story; this hotfix's only job
 * is stopping the crash, not building real restore.
 */
private class QuakeSelectionSyntheticOwner : SavedStateRegistryOwner, ViewModelStoreOwner {
    private val lifecycleRegistry = LifecycleRegistry(this)
    override val lifecycle: Lifecycle get() = lifecycleRegistry

    private val savedStateRegistryController = SavedStateRegistryController.create(this)
    override val savedStateRegistry: SavedStateRegistry get() = savedStateRegistryController.savedStateRegistry

    override val viewModelStore = ViewModelStore()

    init {
        savedStateRegistryController.performRestore(null)
        enableSavedStateHandles()
    }
}

// See QuakeSelectionExtras.wasmJs.kt's matching comment: `remember` (not a file-scope `val`, not
// rebuilt per-call) because this owner's ViewModelStore backs createSavedStateHandle's own internal
// bookkeeping ViewModel (SavedStateHandleSupport.kt's `savedStateHandlesVM`) — a new owner on every
// recomposition would silently discard QuakeSelectionViewModel's handle. One owner, created once,
// for this composition's whole lifetime — mirrors how a real Window/Activity is a single long-lived
// instance, not rebuilt per recomposition either.
@Composable
actual fun rememberQuakeSelectionExtras(): CreationExtras? = remember {
    val owner = QuakeSelectionSyntheticOwner()
    MutableCreationExtras().apply {
        this[SAVED_STATE_REGISTRY_OWNER_KEY] = owner
        this[VIEW_MODEL_STORE_OWNER_KEY] = owner
    }
}
