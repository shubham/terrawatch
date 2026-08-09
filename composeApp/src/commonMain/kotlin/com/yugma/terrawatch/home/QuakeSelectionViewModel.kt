package com.yugma.terrawatch.home

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.yugma.terrawatch.data.QuakeRepository
import com.yugma.terrawatch.model.Quake
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

// Persisted key for the currently-selected quake's id. Snake_case to match this codebase's other
// persisted string keys (HomeLocationStore's "home_lat"/"home_lon", the later Plan 3 tasks'
// "history_cursor_<filterhash>"/"rule_minmag" etc.) rather than SavedStateHandle-idiomatic-Android
// camelCase — consistency with the rest of this codebase's own persistence keys wins over matching
// an external convention this codebase doesn't otherwise follow.
private const val SELECTED_ID_KEY = "selected_id"

/**
 * Task 3 (Plan 3): [HomeViewModel]'s selection/detail-sheet responsibility, split out on its own —
 * see `plan-3-entry-conditions.md` #3 ("it serves map+pill+sheet+detail+two-pane at the complexity
 * ceiling"). Everything here is a verbatim behavioral carry-over of HomeViewModel's former
 * `selectedQuake`/`select`/`dismissSelection`/`selectJob` (see that class's git history for the
 * original Fix Round 1/2 review findings the kdoc below still documents) plus one new capability:
 * [SavedStateHandle]-backed restore, so a selection survives Android process death instead of
 * always starting null.
 *
 * Deliberately has NO dependency on [HomeViewModel] in either direction — map/pill/sheet state
 * (still on HomeViewModel) and selection/detail state (here) don't need each other's internals,
 * only a shared [QuakeRepository]. `HomeScreen` is what wires both together at the UI layer (see
 * its own kdoc). This separation is also what Task 4's nav-graph-scoped sharing needs: History and
 * Insights will resolve the SAME [QuakeSelectionViewModel] instance (scoped to the nav graph, not
 * to Home's own back-stack entry) to drive their own detail sheets, which only works if this class
 * doesn't otherwise assume it's talking to Home.
 *
 * **Koin wiring note (verified against koin-core-viewmodel 4.1.0's actual bytecode + the current
 * Koin docs, not just assumed):** `AppModule.kt` registers this with the plain
 * `viewModel { QuakeSelectionViewModel(get(), get()) }` shape — no `parametersOf`/
 * `savedStateHandle()` DSL needed. Koin's `KoinViewModelFactory.create()` wraps the platform
 * `CreationExtras` in an `AndroidParametersHolder` (a `ParametersHolder` subclass — the "Android"
 * name is legacy; the class ships in the plain `koin-core-viewmodel` artifact, not an
 * Android-only one) whose `getOrNull(SavedStateHandle::class)` unconditionally answers
 * `SavedStateHandleSupport.createSavedStateHandle(extras)` rather than falling through to a
 * registered definition — so the second `get()` above resolves [SavedStateHandle] automatically
 * the same way the first resolves [QuakeRepository] from a `single {}`. Confirmed independently
 * against Koin's own docs ("Add SavedStateHandle to your ViewModel constructor - Koin injects it
 * automatically", insert-koin.io). `androidx.lifecycle.SavedStateHandle` itself needs no new
 * Gradle dependency either — `org.jetbrains.androidx.lifecycle:lifecycle-viewmodel-savedstate` is
 * already a transitive compile/runtime dependency of `koin-core-viewmodel` (confirmed via that
 * artifact's resolved Gradle module metadata) across the jvm/android/wasm-js variants this project
 * targets.
 */
class QuakeSelectionViewModel(
    private val repository: QuakeRepository,
    private val savedStateHandle: SavedStateHandle,
) : ViewModel() {
    // Task 11 (original): the detail sheet's data source. Holds a full Quake (not just an id) so
    // DetailSheet itself stays a dumb presentational composable with no lookup of its own — see
    // [select]. Null means "no sheet showing," doing double duty as both "nothing selected yet"
    // and "dismissed" rather than a separate Boolean visibility flag.
    private val _selectedQuake = MutableStateFlow<Quake?>(null)
    val selectedQuake: StateFlow<Quake?> = _selectedQuake

    // Fix Round 1 (original review finding, carried over verbatim): tracks select()'s own
    // in-flight launch so a second call can cancel a still-pending first one — see [select]'s
    // body. Purely a private implementation detail of that one function; nothing else in this
    // class reads or depends on it.
    private var selectJob: Job? = null

    init {
        // Task 3 (Plan 3): the new behavior this task adds. A non-null saved id means this
        // ViewModel is being recreated against a SavedStateHandle that survived process death
        // (Android) with a selection already in flight — re-running select() against it restores
        // selectedQuake from the repository exactly as if the user had just tapped that pin again,
        // rather than silently losing the open detail sheet on a system-initiated relaunch. Reuses
        // [select] rather than duplicating its body: the handle write select() performs is a
        // harmless no-op re-write of the same id it just read.
        savedStateHandle.get<String>(SELECTED_ID_KEY)?.let { select(it) }
    }

    /**
     * Task 11 (original): opens the detail sheet for [id] — called from a map pin tap, a
     * [com.yugma.terrawatch.ui.components.QuakeCard] tap, or the status pill's alert face. Reads
     * through [QuakeRepository.byId] (the DAO, not any already-collected in-memory list) so this
     * also works for a quake that isn't in the current 24h window a pin/card tap couldn't
     * otherwise have come from anyway, but mainly so this stays the one obvious source of truth —
     * no second "find it in the in-memory list" path to keep in sync with the first. An [id] that
     * doesn't resolve to any stored quake (e.g. it aged out between the tap and this lookup
     * resolving) settles on null, same as no selection at all — there is deliberately no separate
     * "not found" error state for the sheet to render.
     *
     * One-shot read; revisions arriving while the sheet is open are not reflected until
     * dismiss+reopen (accepted v1 tradeoff, unchanged by this task).
     *
     * Fix Round 1 (original review finding): cancels any still-in-flight [selectJob] before
     * launching a new one. Without this, two quick selections (e.g. pin A tapped, then pin B
     * tapped again before A's [QuakeRepository.byId] read resolves) raced as two independent
     * coroutines with no ordering guarantee between them — if A's read happened to complete after
     * B's, its stale result would silently overwrite the correct, more recent selection.
     * Cancelling the prior job first means only the most recent call to [select] can ever win.
     *
     * Task 3 (Plan 3): now also writes [id] into [savedStateHandle] under [SELECTED_ID_KEY],
     * BEFORE launching the repository read — a plain synchronous write, so it lands even if the
     * process dies mid-flight on the [QuakeRepository.byId] suspension that follows, which is
     * exactly the case this key exists to survive.
     *
     * Task 4 (Plan 3) — Task 3 ledger minor ("select() never clears handle key on null lookup —
     * dedupe-deleted id sticks and re-fails every restore"): the write above happens before the
     * lookup resolves (deliberately — see the paragraph above), so if [id] turns out not to
     * resolve to anything (aged out, or superseded by a later dedupe merge under a different id
     * since whatever tap produced this call), the handle is left holding a dead id afterward
     * unless something walks it back. Without this, [init]'s restore-on-relaunch would keep
     * re-running this exact same doomed [select] call on every future process death, forever,
     * for a selection that can never come back — graceful (no crash — see [init]'s own kdoc: an
     * unresolved id already settles on null same as no selection) but permanently, silently
     * wrong. Clearing the key on a null result converges to the exact same "nothing persisted"
     * state [dismissSelection] already produces for a live dismiss.
     */
    fun select(id: String) {
        selectJob?.cancel()
        savedStateHandle[SELECTED_ID_KEY] = id
        selectJob = viewModelScope.launch {
            val quake = repository.byId(id)
            _selectedQuake.value = quake
            if (quake == null) savedStateHandle.remove<String>(SELECTED_ID_KEY)
        }
    }

    /**
     * Called by DetailSheet's `onDismiss` (both the Dismiss button and the sheet's own
     * scrim/swipe dismissal funnel through this one callback).
     *
     * Fix Round 1 (original, adjacent to the [select] race fix above): also cancels a
     * still-in-flight [selectJob], not just [select] itself. Without this, a [select] whose
     * repository read is still in flight the instant the user dismisses would, once it resolved,
     * silently overwrite this null with the stale quake - resurrecting a sheet the user just
     * closed.
     *
     * Task 3 (Plan 3): also removes [SELECTED_ID_KEY] from [savedStateHandle] — a process death
     * after a dismiss must NOT restore the just-closed sheet on relaunch.
     */
    fun dismissSelection() {
        selectJob?.cancel()
        savedStateHandle.remove<String>(SELECTED_ID_KEY)
        _selectedQuake.value = null
    }
}
