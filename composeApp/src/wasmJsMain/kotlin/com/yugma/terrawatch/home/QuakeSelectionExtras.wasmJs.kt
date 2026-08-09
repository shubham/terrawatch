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
 * See `QuakeSelectionExtras.kt`'s kdoc for the full story. This is the wasmJs actual: a minimal,
 * self-contained [SavedStateRegistryOwner] + [ViewModelStoreOwner] whose ONLY job is satisfying
 * `androidx.lifecycle.createSavedStateHandle()`'s preconditions (decompiled+read from
 * `lifecycle-viewmodel-savedstate`'s real source — `SavedStateHandleSupport.kt` — to get this
 * sequence right, not guessed): a fresh [LifecycleRegistry] starts at [Lifecycle.State.INITIALIZED],
 * which is exactly the state both [SavedStateRegistryController.performRestore] and
 * [enableSavedStateHandles] require — no lifecycle transition needed at all.
 *
 * Deliberately NOT a faithful "real" SavedStateRegistryOwner (no actual bundle
 * serialization/restoration wired to anything persistent) — see `QuakeSelectionExtras.kt`'s kdoc
 * for why that would be pointless work: [com.yugma.terrawatch.database.InMemoryQuakeStore] has no
 * cross-reload persistence either, so there is nothing a "real" restore could ever recover on this
 * platform. `performRestore(null)` — no saved state to restore from, ever.
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

// `remember` (not a `val` at file scope, and not rebuilt per-call): this owner's ViewModelStore
// backs `createSavedStateHandle`'s own internal bookkeeping ViewModel (see
// SavedStateHandleSupport.kt's `savedStateHandlesVM`) — a NEW owner on every recomposition would
// mean a NEW backing store every time, silently discarding `QuakeSelectionViewModel`'s handle. One
// owner, created once, for this composition's whole lifetime — mirrors how a real Activity is a
// single long-lived instance, not rebuilt per recomposition either.
@Composable
actual fun rememberQuakeSelectionExtras(): CreationExtras? = remember {
    val owner = QuakeSelectionSyntheticOwner()
    MutableCreationExtras().apply {
        this[SAVED_STATE_REGISTRY_OWNER_KEY] = owner
        this[VIEW_MODEL_STORE_OWNER_KEY] = owner
    }
}
